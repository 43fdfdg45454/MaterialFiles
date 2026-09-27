package me.zhanghai.android.files.provider.nfs.client

import io.github.libnfsandroid.Nfs
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.util.ArrayDeque
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import me.zhanghai.android.files.provider.common.AbstractFileByteChannel

/**
 * A file opened on one [Context], read and written in blocks of [BLOCK_SIZE] over several
 * connections at once.
 *
 * Why: over a VPN with latency and some loss, one TCP connection gets a small fraction of the link
 * (roughly MSS / RTT x 1.22 / sqrt(loss): about 0.3 MB/s at 100 ms and 0.3 %), and only many
 * connections transferring at the same time, without pauses, fill it. So:
 *
 * - Connections pull work: each [Worker] takes the next block that is needed as soon as it is free.
 *   Fast connections do more, and none sits idle waiting for a slow one. (A fixed queue per
 *   connection, refilled only when the reader advanced, left 31 connections idle whenever the
 *   reader waited on the 32nd, which had just lost a packet.)
 * - Reads: after [STREAM_AFTER_BYTES] read forward, up to [MAX_AHEAD_BLOCKS] blocks past the
 *   reader are fetched; extra connections join as described in [Profile]. A block the reader waits
 *   for and that is late gets fetched again by an idle connection ([hedgeMillis]); the first copy
 *   wins. The file's own connection only takes such urgent blocks once extra connections are up, so
 *   it is always free for a seek.
 * - Reads landing anywhere inside the fetched range count as forward: players read through
 *   Android's FUSE proxy, whose read-ahead skips around. Only reads outside it are seeks. The last
 *   few blocks dropped are kept ([recentBlocks]): players read the start, jump to the index at the
 *   end (MP4 moov, MKV cues) and come back.
 * - Writes: the data goes out in blocks, written by all connections in parallel (any order: each
 *   block has its own offset), with at most [MAX_PENDING_WRITE_BYTES] pending. A failed block
 *   fails the next write or the close; nothing is resent, since its state on the server is
 *   unknown. Closing waits for every block and commits the file (COMMIT covers what every
 *   connection wrote) before reporting success.
 * - Read-only files are also served from and stored in [NfsReadCache].
 * - Reads wait up to [READ_TIMEOUT_MILLIS], longer than a reconnect, so that playback survives a
 *   network switch instead of failing at [AbstractFileByteChannel]'s 15 s default.
 *
 * Source buffers may be direct (libarchive passes native memory), so data is always copied with
 * [ByteBuffer.get], never through [ByteBuffer.array].
 */
internal class FileByteChannel(
    private val context: Context,
    private val file: Long,
    isAppend: Boolean,
    private val authority: Authority,
    /** The file's path on the server, for opening it on extra connections. */
    private val path: ByteArray,
    /** Opened read-only: extra connections read, and [NfsReadCache] may be used. */
    private val isReadOnly: Boolean,
    /** For [NfsLog]. */
    private val logName: String,
    /** What the connections are for; a read-only file may be upgraded to [Profile.STREAM]. */
    @Volatile var profile: Profile,
    private val onReleased: () -> Unit
) : AbstractFileByteChannel(isAppend) {
    /**
     * How a file uses connections:
     * - [STREAM]: a player or a copy reading it. [RESERVED_CONNECTIONS] connections (the file's
     *   own among them) serve only what a reader waits for right now (the start, a seek: in
     *   parallel pieces) and fetch late blocks again; the others read ahead. Extra connections
     *   grow with what is read forward ([streamConnectionTarget]), so jumping around does not open
     *   any, and reads ahead at a new position start once the reader stays there
     *   ([SETTLE_MILLIS]). Shared by all descriptors of the file (a player opens several), and
     *   kept a few seconds after the last one closes (players reopen), then its connections go
     *   back to the pool.
     * - [THUMBNAIL]: a few MB for a preview: the file's own connection only.
     * - [WRITE]: an upload: writes over up to 32 connections from early on (no seeks to serve).
     */
    enum class Profile { STREAM, THUMBNAIL, WRITE }

    private val openedMillis = NfsClock.elapsedRealtime()
    private var firstBytesMillis = -1L
    private var bytesRead = 0L
    /** Loaded from the disk cache by readers (blocks read ahead from it are not counted). */
    private var diskBytesRead = 0L
    /** Where this file's reads came from, for tests (see [readStats]). */
    private val fileStats = if (isStatsEnabled) readStats(logName) else null

    init {
        if (isStatsEnabled) {
            synchronized(liveChannels) { liveChannels += this }
        }
        if (isReadOnly) {
            openStreamFiles.incrementAndGet()
        }
    }

    private var isCountedOpen = isReadOnly

    /** Its connections and what they do, while any is left (for tests). */
    private fun describeConnections(): String? =
        lock.withLock {
            if (workers.isEmpty()) {
                return null
            }
            val now = NfsClock.elapsedRealtime()
            "$logName (${profile.name.lowercase()}${if (isClosing) ", closed" else ""}): " +
                workers.joinToString(", ") { worker ->
                    (if (worker.isExtra) "extra" else "own") + (if (worker.isReserved) "*" else "") +
                        (if (worker.file == 0L) " opening" else "") + ":" +
                        (worker.job?.let { "$it for ${now - worker.jobStartedMillis} ms" }
                            ?: "idle")
                }
        }
    private var longestWaitMillis = 0L
    private var isStreamingChannel = false
    private val lock = ReentrantLock()
    /** Signalled whenever a block or a write finishes, work appears, or the channel closes. */
    private val changed = lock.newCondition()

    private val workers = mutableListOf<Worker>()
    private var mainWorker: Worker? = null
    private var extraConnectionsRequested = 0
    private var lastReconnectMillis = 0L
    private var reconnectDelayMillis = 1_000L
    /** No new connection before this time (after one failed). */
    private var nextConnectMillis = 0L
    private var isClosing = false
    /** An extra connection could not open the file: it is gone (deleted or renamed). */
    private var isFileGone = false
    /** Why the last connection that ended failed, for the error of writes left undone. */
    private var lastWorkerError: String? = null
    /** Reads of this file waiting for the network now (see readsWaitingInAllFiles). */
    private var waitingReads = 0

    /**
     * A reader of another file waits for the network: this one's connections then only fetch
     * what its own readers need soon ([YIELD_AHEAD_BLOCKS]) and write little, so that the link
     * carries what someone is waiting for (a stalled picture, a seek) before buffers.
     */
    private val isOtherFileWaitingLocked: Boolean
        get() = readsWaitingInAllFiles.get() > waitingReads

    // Reading.

    private class Block(val index: Long, val generation: Int) {
        /** The data: complete once [isDone], otherwise the [pieces] fetched so far. */
        var data: ByteArray? = null
        /** Valid once [isDone]; shorter than [BLOCK_SIZE] at the end of the file. */
        var length = 0
        var isDone = false
        /**
         * Fetched in pieces (the reader waited for it): each piece is a job of its own, taken by
         * any idle connection, so that they arrive in parallel.
         */
        var isPieceMode = false
        /** Bit i set: piece i (of [PIECE_SIZE]) is in [data], for a block fetched in pieces. */
        var pieces = 0
        /** Bit i set: piece i is being fetched. */
        var fetchingPieces = 0
        /** A second, whole fetch was started because the first was late. */
        var isHedged = false
        /** The read cache was looked up for it (by the first piece fetched). */
        var isCacheChecked = false
        /** Its whole data came from the network (not the disk cache); for statistics. */
        var isFromNetwork = false
        /** Bit i set: piece i came from the network; for statistics. */
        var networkPieces = 0
        /** Where the file ends inside the block, once a piece came back short; else BLOCK_SIZE. */
        var pieceEnd = BLOCK_SIZE
        /** Workers fetching it right now (2 when hedged). */
        var fetchers = 0
        var startedMillis = 0L
        var failures = 0
        /** After a failure, not retried before this time. */
        var retryAtMillis = 0L
        var error: IOException? = null

        val position: Long
            get() = index * BLOCK_SIZE

        /** Whether every piece up to the end of file is in. */
        val hasAllPieces: Boolean
            get() {
                for (piece in 0 until PIECES_PER_BLOCK) {
                    if (piece * PIECE_SIZE < pieceEnd && pieces and (1 shl piece) == 0) {
                        return false
                    }
                }
                return true
            }

        /** The next piece to fetch, from [first] on and wrapping around, or -1. */
        fun nextPiece(first: Int): Int {
            for (k in 0 until PIECES_PER_BLOCK) {
                val piece = (first + k) % PIECES_PER_BLOCK
                val bit = 1 shl piece
                if (piece * PIECE_SIZE < pieceEnd && pieces and bit == 0 &&
                    fetchingPieces and bit == 0) {
                    return piece
                }
            }
            return -1
        }

        /**
         * How far the data is available from [offset], or 0: the whole block once done, else
         * the run of fetched pieces starting at the one holding [offset].
         */
        fun availableEnd(offset: Int): Int {
            if (isDone) {
                return length
            }
            var piece = offset / PIECE_SIZE
            var end = 0
            while (piece < PIECES_PER_BLOCK && pieces and (1 shl piece) != 0) {
                end = ((piece + 1) * PIECE_SIZE).coerceAtMost(pieceEnd)
                ++piece
            }
            return if (end > offset) end else 0
        }
    }

    /** Blocks some reader may still need: fetched, being fetched, or failed. */
    private val blocks = HashMap<Long, Block>()
    /** Done blocks no reader is at any more, most recent last: rereads come from memory. */
    private val recentBlocks = ArrayDeque<Block>()

    /** One descriptor reading the file, with where it is. */
    internal class Reader {
        /** The block it is at. */
        var readBase = 0L
        /** Bytes read forward since its last seek, to tell streaming from probing. */
        var forwardBytes = 0L
        /** When it got to [readBase] by a seek. */
        var seekMillis = 0L
    }

    private val readers = ArrayList<Reader>()
    /** The reader of this channel used directly (a writable file read back). */
    private val ownReader = Reader()
    /**
     * Reads waiting for data, most recent last. Usually one, but a read cancelled by a seek may
     * still be leaving while the next one starts: each removes only its own entry.
     */
    private class Wait(val index: Long, val offset: Int)
    private val waits = ArrayList<Wait>()
    /** Blocks fetched before a write or truncation are stale: dropped by generation. */
    private var generation = 0
    /** Known end of file (from a short read); Long.MAX_VALUE until known. */
    private var knownEnd = Long.MAX_VALUE
    private var sizeAtOpen = -1L
    /** Average milliseconds per block fetched from the network, 0 until measured. */
    private var blockMillis = 0.0

    @Volatile
    private var cacheKey: String? = null
    @Volatile
    private var isCacheKeyResolved = false
    /** The version of the file when it was opened (see NfsReadCache.version), once known. */
    @Volatile
    var version: String? = null
        private set
    private val cacheKeyLock = Any()

    // Writing.

    private class WriteJob(val position: Long, val data: ByteArray, val length: Int) {
        var failures = 0
        var retryAtMillis = 0L
    }

    /** A block to fetch whole ([piece] -1), or one piece of it. */
    private class FetchJob(val block: Block, val piece: Int)

    /** A block to fetch into the disk cache only (see takeDiskAheadJobLocked). */
    private class DiskJob(val index: Long)

    /** Blocks fetched (or being fetched) into the disk cache ahead of the reader. */
    private val diskBlocks = HashSet<Long>()

    private var writeBuffer: ByteArray? = null
    private var writeBufferPosition = 0L
    private var writeBufferLength = 0
    private val writeQueue = ArrayDeque<WriteJob>()
    /** Queued plus being written. */
    private var pendingWriteBytes = 0L
    private var writesInFlight = 0
    private var writeError: IOException? = null
    private var sequentialWrittenBytes = 0L
    private var extraConnectionsWrote = false

    // Reading.

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer {
        lock.withLock {
            if (ownReader !in readers) {
                readers += ownReader
            }
        }
        return readShared(ownReader, position, size)
    }

    /** A new descriptor reading this (read-only) file. */
    internal fun attach(): Reader = Reader().also { lock.withLock { readers += it } }

    internal fun detach(reader: Reader) {
        lock.withLock {
            readers -= reader
            evictLocked()
            changed.signalAll()
        }
    }

    @Throws(IOException::class)
    internal fun readShared(reader: Reader, position: Long, size: Int): ByteBuffer =
        try {
            readBlock(reader, position, size)
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: Throwable) {
            lastReadError = e
            throw e
        }

    @Throws(IOException::class)
    internal fun sizeShared(): Long = onSize()

    /**
     * The file was deleted or renamed by Material Files while open: reading goes on (the server
     * keeps an open file's data), but nothing more is stored in the read cache for its path.
     */
    internal fun stopCaching() {
        cacheKey = null
        isCacheDropped = true
    }

    /** See [stopCaching]; what a fetch still running stored meanwhile is dropped at close. */
    @Volatile
    private var isCacheDropped = false

    /** Closes the file and its connections (for a shared file, once no descriptor is left). */
    @Throws(IOException::class)
    internal fun closeShared() {
        onClose()
    }

    @Throws(IOException::class)
    private fun readBlock(reader: Reader, position: Long, size: Int): ByteBuffer {
        resolveCacheKey()
        lock.withLock {
            drainWritesLocked()
            if (position >= knownEnd) {
                return EMPTY_BUFFER
            }
            val index = position / BLOCK_SIZE
            val offset = (position - index * BLOCK_SIZE).toInt()
            val (block, end) = takeBlockLocked(reader, index, offset)
            val length = size.coerceAtMost(end - offset).coerceAtLeast(0)
            // No copy: fetched bytes never change, and the caller copies them out right away.
            return ByteBuffer.wrap(block.data!!, offset, length).slice()
        }
    }

    override fun onReadAsync(position: Long, size: Int, timeoutMillis: Long): Future<ByteBuffer> =
        super.onReadAsync(position, size, timeoutMillis.coerceAtLeast(READ_TIMEOUT_MILLIS))

    /**
     * Moves the reader to block [index] and waits until the data at [offset] in it is fetched;
     * returns the block and how far its data is available.
     */
    @Throws(IOException::class)
    private fun takeBlockLocked(reader: Reader, index: Long, offset: Int): Pair<Block, Int> {
        val now = NfsClock.elapsedRealtime()
        if (index in reader.readBase until reader.readBase + aheadBlocks(reader)) {
            // Forward, possibly skipping some blocks (FUSE read-ahead).
            if (index > reader.readBase) {
                reader.forwardBytes += (index - reader.readBase) * BLOCK_SIZE
                reader.readBase = index
                evictLocked()
            }
        } else {
            // A seek, or the first read.
            reader.readBase = index
            reader.forwardBytes = 0
            reader.seekMillis = now
            evictLocked()
        }
        // What was read before is served from memory, else from the disk cache right here: a
        // cached block must not wait for a connection that is busy with something else.
        recentBlocks.firstOrNull { it.index == index }?.let { recent ->
            if (blocks[index]?.isDone != true) {
                recentBlocks.remove(recent)
                putBlockLocked(recent)
            }
        }
        readFromCacheLocked(index)
        if (reader.forwardBytes < NEAR_BLOCKS * BLOCK_SIZE) {
            // Just jumped: what a player reads next comes from the disk cache right away if it is
            // there, without waiting for the reader to settle or for a connection.
            val fileEnd = if (sizeAtOpen >= 0) minOf(knownEnd, sizeAtOpen) else knownEnd
            var next = index + 1
            while (next < index + NEAR_BLOCKS && next * BLOCK_SIZE < fileEnd) {
                readFromCacheLocked(next)
                ++next
            }
        }
        // Whether this read waits for data (counted once it arrives, if from the network), and
        // whether every connection was busy then (more connections would have helped).
        val isReadyNow = blocks[index]?.let { it.isDone || it.availableEnd(offset) > 0 } == true
        val allBusyNow = workers.none { it.file != 0L && it.job == null }
        ensureWorkersLocked()
        if (isReadOnly) {
            requestExtraConnectionsLocked()
        }
        val startMillis = NfsClock.elapsedRealtime()
        val deadline = startMillis + READ_TIMEOUT_MILLIS
        val wait = Wait(index, offset)
        waits += wait
        // Readers of other files yield to this one while it waits (see isOtherFileWaiting).
        if (!isReadyNow) {
            ++waitingReads
            readsWaitingInAllFiles.incrementAndGet()
        }
        try {
            changed.signalAll()
            while (true) {
                val block = blocks[index]
                if (block != null) {
                    val end = block.availableEnd(offset)
                    if (end > 0 || block.isDone) {
                        if (!isReadyNow && (block.isFromNetwork ||
                                block.networkPieces and (1 shl (offset / PIECE_SIZE)) != 0)) {
                            onNetworkWaitLocked(index * BLOCK_SIZE + offset, allBusyNow)
                        }
                        onReadDoneLocked(block.position + offset, end - offset, startMillis)
                        return block to end
                    }
                }
                val error = block?.error
                if (error != null) {
                    removeBlockLocked(index)
                    NfsLog.log("$logName: read at ${index * BLOCK_SIZE + offset} failed: $error")
                    throw error
                }
                val remaining = deadline - NfsClock.elapsedRealtime()
                if (remaining <= 0) {
                    val error = IOException(
                        "NFS read timed out at ${index * BLOCK_SIZE + offset}: " +
                            describeLocked(index)
                    )
                    NfsLog.log("$logName: ${error.message}")
                    throw error
                }
                try {
                    changed.await(remaining.coerceAtMost(HEDGE_CHECK_MILLIS), TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    // Cancelled (a seek in AbstractFileByteChannel): the caller no longer wants it.
                    throw InterruptedIOException().apply { initCause(e) }
                }
                // Every connection broke (a network drop): connect new ones, at most once a
                // second, or this read would wait with none until it times out.
                if (isReadOnly && workers.isEmpty() &&
                    NfsClock.elapsedRealtime() - lastReconnectMillis >= reconnectDelayMillis) {
                    // One connection at a time, with a delay that doubles up to 8 s: against a
                    // server that is dropping connections (too many for its threads) or not
                    // reachable, a burst of new ones only makes it drop more.
                    lastReconnectMillis = NfsClock.elapsedRealtime()
                    reconnectDelayMillis = (reconnectDelayMillis * 2).coerceAtMost(8_000)
                    NfsLog.log("$logName: no connections left, connecting one")
                    val extra = try {
                        Client.acquireExtraContext(
                            authority, listOf(context), isReserved = true, "$logName reconnect"
                        )
                    } catch (e: ClientException) {
                        null
                    }
                    if (extra != null) {
                        ++extraConnectionsRequested
                        Worker(extra, 0, isExtra = true, isReserved = true,
                            owner = "$logName reconnect").also {
                            workers += it
                            it.start()
                        }
                    }
                }
                // Lets an idle worker hedge a late block.
                changed.signalAll()
            }
        } finally {
            waits.remove(wait)
            if (!isReadyNow) {
                --waitingReads
                readsWaitingInAllFiles.decrementAndGet()
            }
            if (isReadOnly) {
                requestExtraConnectionsLocked()
            }
            changed.signalAll()
        }
    }

    private fun putBlockLocked(block: Block) {
        if (blocks.put(block.index, block) == null) {
            globalBlocks.incrementAndGet()
        }
    }

    private fun removeBlockLocked(index: Long) {
        if (blocks.remove(index) != null) {
            globalBlocks.decrementAndGet()
        }
    }

    private fun clearBlocksLocked() {
        globalBlocks.addAndGet(-blocks.size)
        blocks.clear()
    }

    /** The state of block [index] and of the connections, for a read that timed out. */
    private fun describeLocked(index: Long): String {
        val now = NfsClock.elapsedRealtime()
        val block = blocks[index]
        val blockState = if (block == null) {
            "block missing"
        } else {
            "block piece mode ${block.isPieceMode}, pieces ${Integer.toBinaryString(block.pieces)}" +
                " fetching ${Integer.toBinaryString(block.fetchingPieces)}, fetchers " +
                "${block.fetchers}, hedged ${block.isHedged}, failures ${block.failures}, " +
                "retry in ${block.retryAtMillis - now} ms, age ${now - block.startedMillis} ms"
        }
        val workerStates = workers.joinToString(", ") { worker ->
            (if (worker.isExtra) "extra" else "own") + (if (worker.isReserved) "*" else "") +
                (if (worker.file == 0L) " opening" else "") + ":" +
                (worker.job?.let { "$it for ${now - worker.jobStartedMillis} ms" } ?: "idle")
        }
        return "$blockState; readers at ${readers.joinToString { it.readBase.toString() }}, " +
            "${blocks.size} blocks, " +
            "${globalBlocks.get()}/$MAX_GLOBAL_BLOCKS in all files, ${waits.size} waits; " +
            "connections: $workerStates"
    }

    /** A read that waited for data from the network: a miss of memory and disk cache. */
    private fun onNetworkWaitLocked(position: Long, allBusy: Boolean) {
        networkWaits.incrementAndGet()
        fileStats?.let {
            it.networkWaits.incrementAndGet()
            it.waitedAt += position
        }
        if (allBusy) {
            ConnectionStats.waitsAllBusy.incrementAndGet()
        } else {
            ConnectionStats.waitsWithIdle.incrementAndGet()
        }
    }

    private fun onReadDoneLocked(position: Long, length: Int, startMillis: Long) {
        val now = NfsClock.elapsedRealtime()
        val waited = now - startMillis
        if (firstBytesMillis < 0) {
            firstBytesMillis = now - openedMillis
            if (isReadOnly) {
                NfsLog.log("$logName: first bytes at $position after $firstBytesMillis ms")
            }
        } else if (waited >= SLOW_READ_LOG_MILLIS) {
            NfsLog.log(
                "$logName: read at $position waited $waited ms (${workers.size} connections)"
            )
        }
        longestWaitMillis = maxOf(longestWaitMillis, waited)
        bytesRead += length.coerceAtLeast(0)
    }

    /**
     * Looks up block [index] in the disk cache from the reader's thread (a millisecond), with the
     * lock released meanwhile: the whole block, else the pieces of it stored earlier (a reader
     * that jumped away before the block was complete). What is missing is left for the
     * connections, in pieces.
     */
    private fun readFromCacheLocked(index: Long) {
        val cacheKey = cacheKey ?: return
        val existing = blocks[index]
        if (existing != null && (existing.isDone || existing.isCacheChecked)) {
            return
        }
        val position = index * BLOCK_SIZE
        val hasBlock = NfsReadCache.contains(cacheKey, position)
        if (existing == null && !hasBlock && !NfsReadCache.hasPieces(cacheKey, index)) {
            // Not cached: the connections fetch it (a new block would only wait for them).
            return
        }
        val block = existing?.also { it.isCacheChecked = true } ?: Block(index, generation).also {
            it.fetchers = 1
            it.isCacheChecked = true
            it.startedMillis = NfsClock.elapsedRealtime()
            putBlockLocked(it)
        }
        val isNew = existing == null
        // Pieces already in memory or on their way are not read again.
        val skip = if (block.isPieceMode) block.pieces or block.fetchingPieces else 0
        val data = ByteArray(BLOCK_SIZE)
        lock.unlock()
        var cached = -1
        var pieces: NfsReadCache.Pieces? = null
        try {
            if (hasBlock) {
                cached = NfsReadCache.read(cacheKey, position, data, BLOCK_SIZE)
            }
            if (cached < 0) {
                pieces = NfsReadCache.readPieces(cacheKey, index, data, skip)
            }
        } finally {
            lock.lock()
        }
        if (isNew) {
            --block.fetchers
        }
        if (block.generation != generation || block.isDone) {
            return
        }
        if (cached >= 0) {
            block.data = data
            block.length = cached
            block.isDone = true
            block.networkPieces = 0
            if (cached < BLOCK_SIZE) {
                knownEnd = minOf(knownEnd, block.position + cached)
            }
            diskBytes.addAndGet(cached.toLong())
            fileStats?.diskBytes?.addAndGet(cached.toLong())
            diskBytesRead += cached
            changed.signalAll()
            return
        }
        val mask = pieces?.mask ?: 0
        if (!block.isPieceMode) {
            if (mask == 0 && block.fetchers > 0) {
                // Fetched whole ahead of the reader: nothing to add.
                return
            }
            // New, or being fetched whole: the pieces found are used right away, and any
            // connection fetches the rest (a whole fetch still running completes it if first).
            block.isPieceMode = true
            block.data = data
            block.pieces = 0
            block.fetchingPieces = 0
            block.startedMillis = NfsClock.elapsedRealtime()
        }
        val target = block.data!!
        // Pieces fetched meanwhile stay as they are.
        val added = mask and (block.pieces or block.fetchingPieces).inv()
        if (added != 0) {
            for (piece in 0 until PIECES_PER_BLOCK) {
                if (added and (1 shl piece) != 0 && target !== data) {
                    System.arraycopy(
                        data, piece * PIECE_SIZE, target, piece * PIECE_SIZE, PIECE_SIZE
                    )
                }
            }
            block.pieces = block.pieces or added
            Integer.bitCount(added).toLong().times(PIECE_SIZE).let {
                diskBytes.addAndGet(it)
                fileStats?.diskBytes?.addAndGet(it)
                diskBytesRead += it
            }
            val end = pieces!!.end
            if (end < BLOCK_SIZE) {
                block.pieceEnd = minOf(block.pieceEnd, end)
                knownEnd = minOf(knownEnd, block.position + end)
            }
            if (block.hasAllPieces) {
                block.length = block.pieceEnd
                block.isDone = true
            }
        }
        changed.signalAll()
    }

    /**
     * Drops the blocks no reader is at or ahead of; done ones go to [recentBlocks] (at most
     * [recentBlockCount]).
     */
    private fun evictLocked() {
        val iterator = blocks.values.iterator()
        while (iterator.hasNext()) {
            val block = iterator.next()
            val needed = readers.any {
                block.index in it.readBase until it.readBase + aheadBlocks(it)
            } || waits.any { it.index == block.index }
            if (needed) {
                continue
            }
            iterator.remove()
            globalBlocks.decrementAndGet()
            if (block.isDone && block.length > 0) {
                recentBlocks.removeAll { it.index == block.index }
                recentBlocks.addLast(block)
                while (recentBlocks.size > recentBlockCount) {
                    recentBlocks.removeFirst()
                }
            }
        }
    }

    private val recentBlockCount: Int
        get() = when (profile) {
            Profile.STREAM -> STREAM_RECENT_BLOCKS
            Profile.THUMBNAIL -> THUMBNAIL_RECENT_BLOCKS
            Profile.WRITE -> WRITE_RECENT_BLOCKS
        }

    /** How far ahead of [reader] blocks are fetched. */
    private fun aheadBlocks(reader: Reader): Long =
        when {
            reader.forwardBytes < STREAM_AFTER_BYTES -> PROBE_AHEAD_BLOCKS
            profile == Profile.THUMBNAIL -> THUMBNAIL_AHEAD_BLOCKS
            else -> MAX_AHEAD_BLOCKS
        }

    /** The reader streaming the most: its reads ahead come first, and set the connections. */
    private val primaryReader: Reader?
        get() = readers.maxByOrNull { it.forwardBytes }

    /**
     * Looks up the file's version (and read cache key) now, at open: a descriptor opening it
     * later shares this channel only while the file on the server is still that version.
     */
    internal fun resolveVersion(): String? {
        resolveCacheKey()
        return version
    }

    private fun resolveCacheKey() {
        if (!isReadOnly || isCacheKeyResolved) {
            return
        }
        // Other descriptors wait for the first one's lookup rather than read without the cache.
        // Outside [lock]: a network round trip.
        synchronized(cacheKeyLock) {
            if (isCacheKeyResolved) {
                return
            }
            val stat = try {
                fetchCall(context) { Nfs.fstat(it, file) }
            } catch (e: IOException) {
                null
            }
            lock.withLock {
                if (stat != null) {
                    version = NfsReadCache.version(stat)
                    sizeAtOpen = stat.size
                    if (context.options.useReadCache && NfsReadCache.isEnabled) {
                        val key = NfsReadCache.fileKey(authority, path, stat)
                        cacheKey = key
                        // A new version (changed on the server): the old ones are useless.
                        NfsReadCache.dropOtherVersions(key)
                    }
                }
            }
            isCacheKeyResolved = true
        }
    }

    // Writing.

    @Throws(IOException::class)
    override fun onWrite(position: Long, source: ByteBuffer) {
        lock.withLock {
            throwWriteErrorLocked()
            invalidateReadsLocked()
            if (writeBufferLength > 0 && position != writeBufferPosition + writeBufferLength) {
                submitWriteBufferLocked()
                sequentialWrittenBytes = 0
            }
            if (writeBufferLength == 0) {
                writeBufferPosition = position
            }
            while (source.hasRemaining()) {
                val buffer = writeBuffer ?: ByteArray(WRITE_BLOCK_SIZE).also { writeBuffer = it }
                val length = source.remaining().coerceAtMost(WRITE_BLOCK_SIZE - writeBufferLength)
                source.get(buffer, writeBufferLength, length)
                writeBufferLength += length
                if (writeBufferLength == WRITE_BLOCK_SIZE) {
                    submitWriteBufferLocked()
                }
            }
        }
    }

    /** Queues the write buffer, waiting while too much is pending. */
    @Throws(IOException::class)
    private fun submitWriteBufferLocked() {
        val data = writeBuffer ?: return
        if (writeBufferLength == 0) {
            return
        }
        val job = WriteJob(writeBufferPosition, data, writeBufferLength)
        writeBuffer = null
        writeBufferPosition += writeBufferLength
        sequentialWrittenBytes += writeBufferLength
        writeBufferLength = 0
        ensureWorkersLocked()
        if (sequentialWrittenBytes >= STREAM_AFTER_BYTES) {
            requestExtraConnectionsLocked()
        }
        while (pendingWriteBytes + job.length > MAX_PENDING_WRITE_BYTES && writeError == null) {
            awaitChangeLocked()
        }
        throwWriteErrorLocked()
        writeQueue.addLast(job)
        pendingWriteBytes += job.length
        changed.signalAll()
    }

    /** Sends everything written so far and waits until the server has it (unstable). */
    @Throws(IOException::class)
    private fun drainWritesLocked() {
        submitWriteBufferLocked()
        while (pendingWriteBytes > 0 && writeError == null) {
            if (mainWorker != null && workers.isEmpty()) {
                // Every connection that could write is gone (broken): nothing will drain.
                writeError = IOException(
                    "NFS connection lost with $pendingWriteBytes bytes not written" +
                        (lastWorkerError?.let { ": $it" } ?: "")
                )
                break
            }
            awaitChangeLocked()
        }
        throwWriteErrorLocked()
    }

    @Throws(IOException::class)
    private fun throwWriteErrorLocked() {
        writeError?.let { throw it }
    }

    @Throws(InterruptedIOException::class)
    private fun awaitChangeLocked() {
        try {
            changed.await(HEDGE_CHECK_MILLIS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            throw InterruptedIOException().apply { initCause(e) }
        }
    }

    /** Drops fetched data; fetches still running are discarded when they finish. */
    private fun invalidateReadsLocked() {
        ++generation
        clearBlocksLocked()
        recentBlocks.clear()
        diskBlocks.clear()
        knownEnd = Long.MAX_VALUE
        for (reader in readers) {
            reader.forwardBytes = 0
        }
    }

    @Throws(IOException::class)
    override fun onTruncate(size: Long) {
        lock.withLock {
            drainWritesLocked()
            invalidateReadsLocked()
        }
        call { Nfs.ftruncate(it, file, size) }
    }

    @Throws(IOException::class)
    override fun onSize(): Long {
        lock.withLock {
            drainWritesLocked()
            if (isReadOnly && context.isBroken && sizeAtOpen >= 0) {
                // Reading goes on over the other connections.
                return sizeAtOpen
            }
        }
        return call { Nfs.fstat(it, file) }.size
    }

    @Throws(IOException::class)
    override fun onForce(metaData: Boolean) {
        lock.withLock { drainWritesLocked() }
        call { Nfs.fsync(it, file) }
    }

    @Throws(IOException::class)
    override fun onClose() {
        var error: IOException? = null
        var main: Worker? = null
        var commit = false
        lock.withLock {
            try {
                drainWritesLocked()
            } catch (e: IOException) {
                error = e
            }
            isClosing = true
            main = mainWorker
            commit = extraConnectionsWrote && error == null
            writeBuffer = null
            clearBlocksLocked()
            if (isStreamingChannel) {
                streamingChannels.decrementAndGet()
                isStreamingChannel = false
            }
            if (isReadOnly && bytesRead > 0) {
                val seconds = (NfsClock.elapsedRealtime() - openedMillis) / 1000.0
                NfsLog.log(
                    ("$logName: closed after %.1f s, read %.1f MB (%.1f MB from the disk " +
                        "cache%s), first bytes after %d ms, longest wait %d ms, %d extra " +
                        "connections").format(
                            seconds, bytesRead / 1e6, diskBytesRead / 1e6,
                            if (cacheKey == null) ", off" else "", firstBytesMillis,
                            longestWaitMillis, extraConnectionsRequested
                        )
                )
            }
            recentBlocks.clear()
            changed.signalAll()
        }
        if (isCacheDropped) {
            NfsReadCache.invalidate(authority, path)
        }
        if (isCountedOpen) {
            isCountedOpen = false
            openStreamFiles.decrementAndGet()
        }
        try {
            // Extra connections close their own files in the background (each may be finishing a
            // block, and closing takes a round trip); only the file's own handle must be idle.
            main?.thread?.join(READ_TIMEOUT_MILLIS)
            // A COMMIT covers what every connection wrote. The CLOSE of a file written through
            // its own handle commits too (libnfs sends them together), and a failed COMMIT fails
            // the close, so a close only succeeds once the data is on stable storage.
            if (commit) {
                call { Nfs.fsync(it, file) }
            }
            call { Nfs.close(it, file) }
        } catch (e: IOException) {
            // A lost connection already dropped the server-side state; nothing to close.
            if (error == null && !context.isBroken) {
                error = e
            }
        } finally {
            onReleased()
        }
        error?.let { throw it }
    }

    // Connections.

    private fun ensureWorkersLocked() {
        if (mainWorker == null && !isClosing) {
            mainWorker = Worker(
                context, file, isExtra = false, isReserved = profile == Profile.STREAM
            ).also {
                workers += it
                it.start()
            }
        }
    }

    /**
     * Adds extra connections in steps as streaming goes on, so that a probe or a small file does
     * not open 32 of them. Idle ones are reused from the pool; each extra only opens the file
     * (one round trip), in the background.
     */
    private fun requestExtraConnectionsLocked() {
        val primary = primaryReader
        var target = when (profile) {
            Profile.THUMBNAIL -> return
            Profile.STREAM -> streamConnectionTarget(
                primary?.forwardBytes ?: 0, context.options,
                NfsClock.elapsedRealtime() - openedMillis
            )
            Profile.WRITE -> writeConnectionTarget(
                sequentialWrittenBytes, context.options.maxConnections
            )
        }
        if (target > RESERVED_CONNECTIONS && !isStreamingChannel) {
            isStreamingChannel = true
            streamingChannels.incrementAndGet()
        }
        // Shared with the other files streaming at the same time; a file holding more than its
        // share gives back the surplus (connections reading ahead, never the reserved ones),
        // so that a file opened later is not left with one or two.
        val share = (context.options.maxConnections / streamingChannels.get().coerceAtLeast(1))
            .coerceAtLeast(MIN_SHARED_EXTRA_CONNECTIONS)
        if (target > RESERVED_CONNECTIONS && target > share) {
            if (extraConnectionsRequested >= share) {
                ConnectionStats.shareLimited.incrementAndGet()
            }
            target = share
        }
        retireSurplusLocked(share)
        if (isReadOnly && sizeAtOpen >= 0 && primary != null) {
            // No more connections than blocks left to read (the reserved ones always).
            val left = (sizeAtOpen - primary.readBase * BLOCK_SIZE + BLOCK_SIZE - 1) / BLOCK_SIZE
            target = target.coerceAtMost(
                left.coerceAtLeast(RESERVED_CONNECTIONS - 1L).toInt()
            )
        }
        val now = NfsClock.elapsedRealtime()
        // Never a burst: a few connecting at a time, and a pause after one failed (a server
        // refusing them, a network drop) instead of retrying in a loop.
        val reserved = reservedConnections()
        retireSurplusReservedLocked(reserved)
        while (extraConnectionsRequested < target && !isClosing && !isFileGone &&
            now >= nextConnectMillis &&
            workers.count { it.isExtra && it.file == 0L } < MAX_CONNECTING) {
            val isReserved = profile == Profile.STREAM &&
                workers.count { it.isReserved && !it.isRetiring } < reserved
            val extra = try {
                Client.acquireExtraContext(
                    authority, listOf(context), isReserved,
                    "$logName ${if (isReserved) "reserved" else "extra"}"
                )
            } catch (e: ClientException) {
                null
            } ?: break
            ++extraConnectionsRequested
            Worker(extra, 0, isExtra = true, isReserved = isReserved,
                owner = "$logName ${if (isReserved) "reserved" else "extra"}").also {
                workers += it
                it.start()
            }
        }
    }

    /**
     * Connections kept for seeks (the file's own among them): [RESERVED_CONNECTIONS] for one file,
     * fewer when many are open on the export, so that each still gets its share of the export's
     * connections instead of being refused (measured: 6 files seeking at once, 193 refused).
     */
    private fun reservedConnections(): Int {
        val files = openStreamFiles.get().coerceAtLeast(1)
        val exportLimit = context.options.maxConnections + 1 + Client.CONTEXTS_BEYOND_FILE
        return (exportLimit / files - 1).coerceIn(1, RESERVED_CONNECTIONS)
    }

    /** Gives back idle reserved connections beyond [reserved] (more files were opened). */
    private fun retireSurplusReservedLocked(reserved: Int) {
        var surplus = workers.count { it.isReserved && !it.isRetiring } - reserved
        for (worker in workers.filter { it.isExtra && it.isReserved && !it.isRetiring }
            .sortedBy { it.job != null }) {
            if (surplus <= 0) {
                break
            }
            worker.isRetiring = true
            --surplus
        }
    }

    private fun retireSurplusLocked(share: Int) {
        var surplus = workers.count { it.isExtra && !it.isRetiring } - share
        if (surplus <= 0) {
            return
        }
        for (worker in workers.filter { it.isExtra && !it.isReserved && !it.isRetiring }
            .sortedBy { it.job != null }) {
            if (surplus <= 0) {
                break
            }
            worker.isRetiring = true
            --surplus
        }
        changed.signalAll()
    }

    /**
     * Gives the extra connections back to the pool (the file has no descriptor left, but may be
     * reopened in a moment: its own connection and fetched blocks stay).
     */
    internal fun releaseExtraConnections() {
        lock.withLock {
            for (worker in workers) {
                if (worker.isExtra) {
                    worker.isRetiring = true
                }
            }
            // No longer takes a share from files streaming meanwhile.
            if (isStreamingChannel) {
                streamingChannels.decrementAndGet()
                isStreamingChannel = false
            }
            changed.signalAll()
        }
    }

    /**
     * One connection and the thread that uses it. Loops taking the most useful job: an urgent
     * block (the one the reader waits for, or a late one to fetch again), a write, then the next
     * block ahead.
     */
    private inner class Worker(
        val context: Context,
        var file: Long,
        val isExtra: Boolean,
        /** Serves only what readers wait for (and late blocks) while others read ahead. */
        val isReserved: Boolean,
        /** Who took [context] from the pool (an extra connection), for diagnostics. */
        val owner: String = logName
    ) {
        /** Leaves after its current job, giving its connection back to the pool. */
        @Volatile
        var isRetiring = false
        /** Why it failed, for the log. */
        @Volatile
        var lastError: String? = null
        val thread = Thread({ run() }, if (isExtra) "NfsExtraConnection" else "NfsConnection")
            .apply { isDaemon = true }
        private var isBroken = false
        /** Could not open the file: it no longer exists at its path. */
        private var isGone = false
        /** What the worker does, for diagnostics; read under the lock. */
        var job: String? = null
        var jobStartedMillis = 0L

        fun start() {
            thread.start()
        }

        private fun run() {
            isWorkerThread.set(true)
            try {
                if (isExtra) {
                    val opened = openWithRetries() ?: return
                    // Read by other workers under the lock.
                    lock.withLock {
                        file = opened
                        reconnectDelayMillis = 1_000L
                    }
                    extraConnectionsOpened.incrementAndGet()
                }
                loop()
            } finally {
                lock.withLock {
                    workers -= this
                    lastError?.let { lastWorkerError = it }
                    if (isRetiring && !isBroken && isExtra) {
                        extraConnectionsRequested = (extraConnectionsRequested - 1)
                            .coerceAtLeast(0)
                    }
                    val failed = isBroken || isExtra && file == 0L && !isRetiring
                    if (isBroken) {
                        ConnectionStats.broke.incrementAndGet()
                        ConnectionStats.recordFailure("$logName broke: $lastError")
                    } else if (isGone) {
                        ConnectionStats.openGone.incrementAndGet()
                    } else if (isExtra && file == 0L && !isRetiring && !isClosing) {
                        ConnectionStats.openFailed.incrementAndGet()
                        ConnectionStats.recordFailure("$logName could not open: $lastError")
                    }
                    if (failed && isReadOnly && !isClosing) {
                        // Replaced on a later read, after a pause.
                        if (isExtra) {
                            extraConnectionsRequested = (extraConnectionsRequested - 1)
                                .coerceAtLeast(0)
                        }
                        nextConnectMillis = NfsClock.elapsedRealtime() + CONNECT_PAUSE_MILLIS
                        NfsLog.log(
                            "$logName: ${if (isExtra) "an extra" else "its own"} connection " +
                                (if (isBroken) "broke" else "could not open the file") +
                                "; ${workers.size} left (${lastError ?: "no error"})"
                        )
                    }
                    changed.signalAll()
                }
                if (isExtra) {
                    if (file != 0L) {
                        try {
                            context.use { Nfs.close(it, file) }
                        } catch (e: Exception) {
                            // A broken connection dropped the open state already.
                        }
                    }
                    try {
                        Client.releaseExtraContext(authority, context, owner)
                    } catch (e: ClientException) {
                        // The pool is gone (server edited); the pump destroys the context.
                    }
                }
            }
        }

        private fun openWithRetries(): Long? {
            var attempt = 0
            while (true) {
                try {
                    return context.use {
                        Nfs.open(it, path, if (isReadOnly) Nfs.O_RDONLY else Nfs.O_WRONLY, 0)
                    }
                } catch (e: ClientException) {
                    lastError = e.message
                    if (e.errno == android.system.OsConstants.ENOENT ||
                        e.errno == android.system.OsConstants.ESTALE) {
                        // Deleted or renamed since it was opened: the connections that have it
                        // open keep reading it; no new one can.
                        isGone = true
                        lock.withLock { isFileGone = true }
                        return null
                    }
                    if (e.isServerBusy) {
                        ConnectionStats.serverBusy.incrementAndGet()
                    }
                    // A busy server (NFS4ERR_DELAY, NFS4ERR_GRACE) gets more patience.
                    val attempts = if (e.isServerBusy) BUSY_OPEN_ATTEMPTS else OPEN_ATTEMPTS
                    if (context.isBroken || ++attempt >= attempts) {
                        return null
                    }
                }
                try {
                    Thread.sleep((RETRY_BASE_MILLIS shl attempt).coerceAtMost(BUSY_RETRY_MILLIS))
                } catch (e: InterruptedException) {
                    return null
                }
                if (lock.withLock { isClosing } || isRetiring) {
                    return null
                }
            }
            return null
        }

        private fun loop() {
            while (true) {
                val job = lock.withLock {
                    // Idle until it takes the next one (read by diagnostics and statistics).
                    this.job = null
                    var job: Any? = null
                    while (!isClosing && !isBroken && !isRetiring) {
                        job = takeJobLocked()
                        if (job != null) {
                            break
                        }
                        try {
                            changed.await(HEDGE_CHECK_MILLIS, TimeUnit.MILLISECONDS)
                        } catch (e: InterruptedException) {
                            return
                        }
                    }
                    job
                } ?: return
                lock.withLock {
                    this.job = when (job) {
                        is FetchJob -> "block ${job.block.index}" +
                            (if (job.piece >= 0) " piece ${job.piece}" else "")
                        is DiskJob -> "block ${job.index} to disk"
                        else -> "write"
                    }
                    jobStartedMillis = NfsClock.elapsedRealtime()
                }
                when (job) {
                    is FetchJob -> if (job.piece >= 0) {
                        fetchPiece(job.block, job.piece)
                    } else {
                        fetch(job.block)
                    }
                    is WriteJob -> write(job)
                    is DiskJob -> fetchToDisk(job)
                }
            }
        }

        private fun takeJobLocked(): Any? {
            val now = NfsClock.elapsedRealtime()
            for (wait in waits.asReversed()) {
                takeUrgentJobLocked(wait, now)?.let { return it }
            }
            if (isReadOnly) {
                takeNearJobLocked(now)?.let { return it }
            }
            val hasExtras = workers.any { it.isExtra && it.file != 0L }
            if (!isExtra &&
                (hasExtras || profile == Profile.STREAM && extraConnectionsRequested > 0)) {
                // Kept free for the reader's next urgent block (a seek), also while the extra
                // connections open: a whole block fetched ahead on a slow connection took seconds,
                // and a seek meanwhile waited for it (measured: 7.6 s).
                return null
            }
            val isOtherFileWaiting = isOtherFileWaitingLocked
            if (!isOtherFileWaiting || writesInFlight < YIELD_WRITES_IN_FLIGHT) {
                writeQueue.firstOrNull { now >= it.retryAtMillis }?.let {
                    writeQueue.remove(it)
                    ++writesInFlight
                    return it
                }
            }
            if (!isReadOnly && isExtra) {
                return null
            }
            // Reserved connections read ahead too while the main reader is catching up after a
            // jump (a player reads several MB before it shows the picture); once it streams
            // steadily they stay free for the next jump.
            val steady = (primaryReader?.forwardBytes ?: 0) >= DISK_AHEAD_AFTER_BYTES
            if (isReserved && steady && workers.any { !it.isReserved && it.file != 0L }) {
                return null
            }
            // Not past the end of file: the known one, or the size at open (reads past it still
            // work, as urgent blocks, if the file grew).
            val fileEnd = if (sizeAtOpen >= 0) minOf(knownEnd, sizeAtOpen) else knownEnd
            // The next block ahead of a reader that nobody fetches yet: the most streaming reader
            // first, and none for a reader that just jumped (it may jump again).
            for (reader in readers.sortedByDescending { it.forwardBytes }) {
                if (reader.forwardBytes < STREAM_AFTER_BYTES &&
                    now - reader.seekMillis < SETTLE_MILLIS) {
                    continue
                }
                val end = reader.readBase + if (isOtherFileWaiting) {
                    minOf(aheadBlocks(reader), YIELD_AHEAD_BLOCKS)
                } else {
                    aheadBlocks(reader)
                }
                var index = reader.readBase
                while (index < end && index * BLOCK_SIZE < fileEnd) {
                    val block = blocks[index]
                    val recent = if (block == null) {
                        recentBlocks.firstOrNull { it.index == index }
                    } else {
                        null
                    }
                    if (recent != null) {
                        // Read before: back from memory, nothing to fetch.
                        recentBlocks.remove(recent)
                        putBlockLocked(recent)
                        ++index
                        continue
                    }
                    if (block == null) {
                        // Memory shared by all open files.
                        if (globalBlocks.get() >= MAX_GLOBAL_BLOCKS) {
                            return null
                        }
                        return FetchJob(newBlockLocked(index, now), -1)
                    }
                    if (block.error == null && !block.isDone && block.fetchers == 0 &&
                        now >= block.retryAtMillis) {
                        ++block.fetchers
                        block.startedMillis = now
                        return FetchJob(block, -1)
                    }
                    ++index
                }
            }
            return if (isOtherFileWaiting) null else takeDiskAheadJobLocked()
        }

        /**
         * The first blocks after where a reader stopped (a seek, the start): what a player reads
         * right away to fill its buffer. Fetched in parallel pieces by any connection, reserved
         * ones included, once the reader stayed there [NEAR_SETTLE_MILLIS]: whole, each would
         * take one slow connection's time (seconds on a lossy VPN).
         */
        private fun takeNearJobLocked(now: Long): FetchJob? {
            val fileEnd = if (sizeAtOpen >= 0) minOf(knownEnd, sizeAtOpen) else knownEnd
            for (reader in readers) {
                if (reader.forwardBytes >= NEAR_BLOCKS * BLOCK_SIZE ||
                    now - reader.seekMillis < NEAR_SETTLE_MILLIS) {
                    continue
                }
                var index = reader.readBase
                while (index < reader.readBase + NEAR_BLOCKS && index * BLOCK_SIZE < fileEnd) {
                    var block = blocks[index]
                    if (block == null && recentBlocks.none { it.index == index }) {
                        block = Block(index, generation).also {
                            it.isPieceMode = true
                            it.data = ByteArray(BLOCK_SIZE)
                            it.startedMillis = now
                            putBlockLocked(it)
                        }
                    }
                    if (block != null && block.isPieceMode && !block.isDone &&
                        block.error == null && now >= block.retryAtMillis) {
                        val piece = block.nextPiece(0)
                        if (piece >= 0) {
                            block.fetchingPieces = block.fetchingPieces or (1 shl piece)
                            ++block.fetchers
                            return FetchJob(block, piece)
                        }
                    }
                    ++index
                }
            }
            return null
        }

        /**
         * A block far ahead of a reader that has been streaming for a while, fetched to the disk
         * cache only: a buffer of up to [DISK_AHEAD_BLOCKS] that survives network stalls without
         * holding memory (reading it back takes a millisecond).
         */
        private fun takeDiskAheadJobLocked(): DiskJob? {
            if (profile != Profile.STREAM || cacheKey == null) {
                return null
            }
            val reader = primaryReader ?: return null
            if (reader.forwardBytes < DISK_AHEAD_AFTER_BYTES) {
                return null
            }
            val fileEnd = if (sizeAtOpen >= 0) minOf(knownEnd, sizeAtOpen) else knownEnd
            var index = reader.readBase + aheadBlocks(reader)
            val end = reader.readBase + DISK_AHEAD_BLOCKS
            while (index < end && index * BLOCK_SIZE < fileEnd) {
                if (index !in diskBlocks && blocks[index] == null) {
                    diskBlocks += index
                    return DiskJob(index)
                }
                ++index
            }
            return null
        }

        private fun fetchToDisk(job: DiskJob) {
            val cacheKey = cacheKey ?: return
            val position = job.index * BLOCK_SIZE
            try {
                if (NfsReadCache.contains(cacheKey, position)) {
                    return
                }
                val data = ByteArray(BLOCK_SIZE)
                var length = 0
                while (length < BLOCK_SIZE) {
                    val count = fetchCall(context) {
                        Nfs.read(it, file, position + length, data, length, BLOCK_SIZE - length)
                    }
                    if (count == 0) {
                        break
                    }
                    length += count
                }
                if (length > 0) {
                    NfsReadCache.writeBlock(cacheKey, position, data, length, length < BLOCK_SIZE)
                }
            } catch (e: IOException) {
                if (context.isBroken) {
                    isBroken = true
                }
                // Not buffered: fetched normally when the reader gets there.
                lock.withLock { diskBlocks -= job.index }
            }
        }

        private fun takeUrgentJobLocked(wait: Wait, now: Long): FetchJob? {
            // Extra connections of a writable file have it open for writing only.
            val waiting = wait.index
            if (waiting * BLOCK_SIZE < knownEnd && (isReadOnly || !isExtra)) {
                var block = blocks[waiting]
                if (block == null) {
                    // The reader waits for it: fetched in pieces, in parallel, the one it needs
                    // first.
                    block = Block(waiting, generation).also {
                        it.isPieceMode = true
                        it.data = ByteArray(BLOCK_SIZE)
                        it.startedMillis = now
                        putBlockLocked(it)
                    }
                }
                if (!block.isDone && block.error == null && now >= block.retryAtMillis) {
                    if (block.isPieceMode) {
                        val piece = block.nextPiece(wait.offset / PIECE_SIZE)
                        if (piece >= 0) {
                            block.fetchingPieces = block.fetchingPieces or (1 shl piece)
                            ++block.fetchers
                            return FetchJob(block, piece)
                        }
                    } else if (block.fetchers == 0) {
                        // Queued ahead but not started yet, or to retry.
                        ++block.fetchers
                        block.startedMillis = now
                        return FetchJob(block, -1)
                    } else if (now - block.startedMillis > SPLIT_MILLIS) {
                        // Fetched whole by one connection (ahead of the reader), and the reader
                        // is waiting: the other connections fetch it in parallel pieces too; the
                        // whole fetch or the last piece, whichever first, completes it.
                        block.isPieceMode = true
                        block.data = ByteArray(BLOCK_SIZE)
                        block.isCacheChecked = true
                        val piece = block.nextPiece(wait.offset / PIECE_SIZE)
                        if (piece >= 0) {
                            block.fetchingPieces = block.fetchingPieces or (1 shl piece)
                            ++block.fetchers
                            return FetchJob(block, piece)
                        }
                    }
                    // Late (a connection that lost packets or stalled): fetched again, whole, on
                    // another connection, and again if that one is late too; the first copy to
                    // arrive wins.
                    if (block.fetchers in 1 until MAX_FETCHERS &&
                        now - block.startedMillis > hedgeMillis()) {
                        block.isHedged = true
                        block.startedMillis = now
                        hedgedBlocks.incrementAndGet()
                        ++block.fetchers
                        return FetchJob(block, -1)
                    }
                }
            }
            return null
        }

        private fun newBlockLocked(index: Long, now: Long): Block =
            Block(index, generation).also {
                it.fetchers = 1
                it.startedMillis = now
                putBlockLocked(it)
            }

        private fun fetch(block: Block) {
            val position = block.index * BLOCK_SIZE
            val data = ByteArray(BLOCK_SIZE)
            var length = 0
            var fromNetwork = false
            var error: IOException? = null
            val startMillis = NfsClock.elapsedRealtime()
            try {
                val cacheKey = cacheKey
                val cached = if (cacheKey != null) {
                    NfsReadCache.read(cacheKey, position, data, BLOCK_SIZE)
                } else {
                    -1
                }
                if (cached >= 0) {
                    length = cached
                } else {
                    fromNetwork = true
                    while (length < BLOCK_SIZE) {
                        val count = fetchCall(context) {
                            Nfs.read(it, file, position + length, data, length, BLOCK_SIZE - length)
                        }
                        if (count == 0) {
                            break
                        }
                        length += count
                    }
                }
            } catch (e: IOException) {
                error = e
                lastError = e.message
                if (context.isBroken) {
                    isBroken = true
                }
            }
            lock.withLock {
                --block.fetchers
                if (block.generation != generation) {
                    // Stale: the file changed.
                    return
                }
                if (!block.isDone) {
                    val failure = error
                    if (failure != null) {
                        onFetchFailedLocked(block, failure)
                    } else {
                        block.data = data
                        block.length = length
                        block.isDone = true
                        block.isFromNetwork = fromNetwork
                        if (length < BLOCK_SIZE) {
                            knownEnd = minOf(knownEnd, position + length)
                        }
                        if (fromNetwork && length == BLOCK_SIZE) {
                            val millis = (NfsClock.elapsedRealtime() - startMillis).toDouble()
                            blockMillis = if (blockMillis == 0.0) millis else blockMillis * 0.8 +
                                millis * 0.2
                        }
                    }
                    changed.signalAll()
                }
            }
            // Stored even when the other copy of a hedged block won (it may have been pieces).
            val cacheKey = cacheKey
            if (error == null && fromNetwork && cacheKey != null) {
                NfsReadCache.writeBlock(cacheKey, position, data, length, length < BLOCK_SIZE)
            }
        }

        /**
         * Fetches one piece of a block the reader waits for. The pieces go to different
         * connections at once, so a block arrives in a fraction of the time one connection takes,
         * and the reader gets the piece it needs first (a seek, the start of playback).
         */
        private fun fetchPiece(block: Block, piece: Int) {
            val bit = 1 shl piece
            val data = lock.withLock {
                if (block.isDone || block.generation != generation) {
                    // Completed meanwhile (a hedged fetch), or stale.
                    block.fetchingPieces = block.fetchingPieces and bit.inv()
                    --block.fetchers
                    return
                }
                block.data!!
            }
            val start = piece * PIECE_SIZE
            var length = 0
            var error: IOException? = null
            var cached = -1
            try {
                val cacheKey = cacheKey
                val checkCache = lock.withLock {
                    (!block.isCacheChecked).also { block.isCacheChecked = true }
                }
                if (cacheKey != null) {
                    // This piece from the cache, if it is there (the reader looked up the
                    // block it waits for, but not blocks fetched ahead of it).
                    val stored = NfsReadCache.readPiece(cacheKey, block.index, piece, data)
                    if (stored >= 0) {
                        var complete = -1
                        lock.withLock {
                            block.fetchingPieces = block.fetchingPieces and bit.inv()
                            --block.fetchers
                            if (block.generation == generation && !block.isDone) {
                                if (stored < PIECE_SIZE) {
                                    block.pieceEnd = minOf(block.pieceEnd, start + stored)
                                    knownEnd = minOf(knownEnd, block.position + start + stored)
                                }
                                block.pieces = block.pieces or bit
                                if (block.hasAllPieces) {
                                    block.length = block.pieceEnd
                                    block.isDone = true
                                    complete = block.length
                                }
                            }
                            changed.signalAll()
                        }
                        if (complete > 0) {
                            // Stored whole in place of its pieces.
                            NfsReadCache.writeBlock(
                                cacheKey, block.position, data, complete, complete < BLOCK_SIZE
                            )
                        }
                        return
                    }
                }
                if (cacheKey != null && checkCache) {
                    // A whole block from the cache, if it is there.
                    val whole = ByteArray(BLOCK_SIZE)
                    cached = NfsReadCache.read(cacheKey, block.position, whole, BLOCK_SIZE)
                    if (cached >= 0) {
                        lock.withLock {
                            block.fetchingPieces = block.fetchingPieces and bit.inv()
                            --block.fetchers
                            if (block.generation == generation && !block.isDone) {
                                block.data = whole
                                block.networkPieces = 0
                                block.length = cached
                                block.isDone = true
                                if (cached < BLOCK_SIZE) {
                                    knownEnd = minOf(knownEnd, block.position + cached)
                                }
                            }
                            changed.signalAll()
                        }
                        return
                    }
                }
                while (length < PIECE_SIZE) {
                    val count = fetchCall(context) {
                        Nfs.read(
                            it, file, block.position + start + length, data, start + length,
                            PIECE_SIZE - length
                        )
                    }
                    if (count == 0) {
                        break
                    }
                    length += count
                }
            } catch (e: IOException) {
                error = e
                lastError = e.message
                if (context.isBroken) {
                    isBroken = true
                }
            }
            var complete = -1
            var isStale = false
            lock.withLock {
                block.fetchingPieces = block.fetchingPieces and bit.inv()
                --block.fetchers
                if (block.generation != generation) {
                    return
                }
                if (block.isDone) {
                    // Completed by a whole fetch, which stored it.
                    isStale = true
                    return@withLock
                }
                val failure = error
                if (failure != null) {
                    onFetchFailedLocked(block, failure)
                } else {
                    if (length < PIECE_SIZE) {
                        block.pieceEnd = minOf(block.pieceEnd, start + length)
                        knownEnd = minOf(knownEnd, block.position + start + length)
                    }
                    block.pieces = block.pieces or bit
                    block.networkPieces = block.networkPieces or bit
                    if (block.hasAllPieces) {
                        block.length = block.pieceEnd
                        block.isDone = true
                        complete = block.length
                    }
                }
                changed.signalAll()
            }
            // Every piece is stored as it arrives: a reader that jumps away before the block is
            // complete finds it when it comes back. The whole block replaces the pieces.
            val cacheKey = cacheKey
            if (cacheKey != null && error == null && !isStale) {
                if (complete > 0) {
                    NfsReadCache.writeBlock(
                        cacheKey, block.position, data, complete, complete < BLOCK_SIZE
                    )
                } else if (length > 0) {
                    NfsReadCache.writePiece(cacheKey, block.index, piece, data, start, length)
                }
            }
        }

        private fun onFetchFailedLocked(block: Block, error: IOException) {
            if (ClientException.isServerBusyMessage(error.message)) {
                ConnectionStats.serverBusy.incrementAndGet()
                // "Not now" from the server: asked again after a pause, for as long as the reader
                // is willing to wait, without counting it as a failure of the file.
                block.retryAtMillis = NfsClock.elapsedRealtime() + BUSY_RETRY_MILLIS
                return
            }
            if (isBroken && isReadOnly) {
                // The connection failed, not the file: another one fetches the block right away
                // (the reader's own timeout still bounds the wait if all of them fail).
                block.retryAtMillis = NfsClock.elapsedRealtime() + RETRY_BASE_MILLIS
                return
            }
            ++block.failures
            // Retried after a growing delay (a server answering NFS4ERR_DELAY), by any
            // connection; the reader gets the error once retries are exhausted, or at once if
            // the own connection of a writable file broke (the file is closed then).
            block.retryAtMillis = NfsClock.elapsedRealtime() +
                (RETRY_BASE_MILLIS shl (block.failures - 1).coerceAtMost(4))
            if (block.fetchers == 0 &&
                (block.failures >= MAX_FAILURES || !isExtra && isBroken)) {
                block.error = error
            }
        }

        private fun write(job: WriteJob) {
            var error: IOException? = null
            try {
                var written = 0
                while (written < job.length) {
                    val count = call(context) {
                        Nfs.write(
                            it, file, job.position + written, job.data, written,
                            job.length - written
                        )
                    }
                    if (count <= 0) {
                        throw IOException("NFS write made no progress")
                    }
                    written += count
                }
            } catch (e: IOException) {
                error = e
                lastError = e.message
                if (context.isBroken) {
                    isBroken = true
                }
            }
            lock.withLock {
                --writesInFlight
                if (error != null) {
                    ++job.failures
                    // WRITE of the same data at the same offset is idempotent: retry after a
                    // growing delay, on any connection, unless the file's own connection broke
                    // (the file is closed then).
                    if (job.failures < MAX_FAILURES && (isExtra || !isBroken)) {
                        job.retryAtMillis = NfsClock.elapsedRealtime() +
                            (RETRY_BASE_MILLIS shl (job.failures - 1).coerceAtMost(4))
                        writeQueue.addFirst(job)
                    } else {
                        pendingWriteBytes -= job.length
                        if (writeError == null) {
                            writeError = error
                        }
                    }
                } else {
                    pendingWriteBytes -= job.length
                    if (isExtra) {
                        extraConnectionsWrote = true
                    }
                }
                changed.signalAll()
            }
        }
    }

    /** When to fetch a late block the reader waits for again, on another connection. */
    private fun hedgeMillis(): Long =
        if (blockMillis == 0.0) {
            MIN_HEDGE_MILLIS
        } else {
            (blockMillis * HEDGE_FACTOR).toLong().coerceAtLeast(MIN_HEDGE_MILLIS)
        }

    /**
     * A read by a worker. For a read-only file, a broken connection (its own included) does not
     * close the file: the other connections go on, each with the file open.
     */
    @Throws(IOException::class)
    private inline fun <T> fetchCall(context: Context, crossinline block: (Long) -> T): T =
        if (isReadOnly) {
            try {
                context.use { block(it) }
            } catch (e: ClientException) {
                throw IOException(e.message, e)
            }
        } else {
            call(context, block)
        }

    @Throws(IOException::class)
    private inline fun <T> call(
        context: Context = this.context,
        crossinline block: (Long) -> T
    ): T =
        try {
            context.use { block(it) }
        } catch (e: ClientException) {
            if (context === this.context && context.isBroken && e.isTransportError) {
                // Never from a connection's thread: close() holds the channel's close lock while
                // it waits for the writes to drain, so a failed write marking it closed there
                // waited for close() forever (measured: an upload hung when its connection broke).
                // The write's error reaches the writer or close() anyway.
                if (!isWorkerThread.get()) {
                    setClosed()
                }
                throw AsynchronousCloseException().apply { initCause(e) }
            }
            throw IOException(e.message, e)
        }

    companion object {
        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocate(0)

        /** The unit of transfer: one READ or WRITE of the negotiated maximum, one cache block. */
        private const val BLOCK_SIZE = NfsReadCache.BLOCK_SIZE

        /** Before streaming is established: the next block only, not to waste a probe. */
        private const val PROBE_AHEAD_BLOCKS = 2L

        /** Forward reading or sequential writing this far counts as streaming. */
        private const val STREAM_AFTER_BYTES = 1L * 1024 * 1024

        /**
         * Blocks fetched ahead of the reader while streaming: 48 MiB (at most an eighth of the
         * heap). Enough for each of the 32 connections to have a block in flight and some done,
         * about 5 s of the link at 10 MB/s. More only costs memory: blocks dropped by a seek stay
         * allocated until their fetch ends, and several files may stream at once.
         */
        private val MAX_AHEAD_BLOCKS =
            minOf(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8) / BLOCK_SIZE

        /** Written data not yet acknowledged by the server. */
        private const val MAX_PENDING_WRITE_BYTES = 64L * 1024 * 1024

        /**
         * Extra connections once streaming. On a lossy, high-latency link the total grows with
         * their number (host tests at 100 ms and 0.3 % loss: 16 read 5.4 MB/s, 24 read 6.0 MB/s,
         * 32 read 6.5 MB/s). A read-only file gets them all at once (the caller caps them at the
         * blocks left); a written one, whose size is unknown, in steps: a few for a photo, all for
         * a video. Idle connections come from the pool, so each costs one OPEN.
         */
        private fun writeConnectionTarget(writtenBytes: Long, max: Int): Int =
            when {
                writtenBytes < STREAM_AFTER_BYTES -> 0
                writtenBytes < 2L * 1024 * 1024 -> 8
                writtenBytes < 4L * 1024 * 1024 -> 16
                else -> max
            }.coerceAtMost(max)

        /**
         * Extra connections of a streamed file, by how far its main reader went forward since its
         * last seek: from the first read, the reserved ones for seeks plus one reading ahead;
         * once playing, a third of them (a probe or a short clip needs no more); once clearly
         * streaming (a video playing, a copy), all: on a lossy VPN each connection adds about
         * 0.3 MB/s. Seeks reset the count, so jumping around never opens more.
         */
        private fun streamConnectionTarget(
            forwardBytes: Long,
            options: ConnectionOptions,
            openMillis: Long
        ): Int {
            val max = options.maxConnections
            return when (options.connectionGrowth) {
                ConnectionOptions.ConnectionGrowth.AT_OPEN -> max
                ConnectionOptions.ConnectionGrowth.BY_PLAYBACK -> when {
                    forwardBytes < STREAM_AFTER_BYTES -> RESERVED_CONNECTIONS
                    forwardBytes < 8L * 1024 * 1024 -> (max * 3 / 4).coerceAtLeast(
                        RESERVED_CONNECTIONS
                    )
                    else -> max
                }
                ConnectionOptions.ConnectionGrowth.GRADUAL ->
                    RESERVED_CONNECTIONS + (openMillis / GRADUAL_STEP_MILLIS * GRADUAL_STEP).toInt()
            }.coerceIn(RESERVED_CONNECTIONS.coerceAtMost(max), max)
        }

        /** [ConnectionOptions.ConnectionGrowth.GRADUAL]: this many more each step. */
        private const val GRADUAL_STEP = 2
        private const val GRADUAL_STEP_MILLIS = 1_000L

        // Why 16 and not 32: every connection is an NFSv4 client and session on the server, and
        // sessions share a fixed pool of server memory (with other phones and computers). On the
        // lossy link measured, 16 connections give 5.4 MB/s against 6.5 with 32, far above a
        // video's bitrate; more connections created in the middle of playback (to go from 16 to
        // 32) is what made the server refuse new ones and the playback stall.

        /** Connections of a streamed file kept for what readers wait for, its own included. */
        private const val RESERVED_CONNECTIONS = 4

        /** A reader that jumped gets reads ahead only once it stays there this long. */
        private const val SETTLE_MILLIS = 300L

        /**
         * After a jump, the first blocks (what a player buffers before it shows the picture) are
         * fetched in parallel pieces once the reader stays this long.
         */
        private const val NEAR_BLOCKS = 4L
        private const val NEAR_SETTLE_MILLIS = 150L

        /** A block a reader waits for, fetched whole and late by this much, goes to pieces. */
        private const val SPLIT_MILLIS = 300L

        /**
         * Disk buffer ahead of a reader streaming for [DISK_AHEAD_AFTER_BYTES]: 256 MiB, beyond the
         * [MAX_AHEAD_BLOCKS] held in memory. In memory it would take half the heap of most phones
         * (and blocks dropped by a seek stay allocated until their fetch ends); on disk it costs
         * nothing but a millisecond per block read back.
         */
        private const val DISK_AHEAD_BLOCKS = 256L * 1024 * 1024 / BLOCK_SIZE
        private const val DISK_AHEAD_AFTER_BYTES = 8L * 1024 * 1024

        /** Extra connections connecting at once, and the pause after one failed. */
        private const val MAX_CONNECTING = 8
        private const val CONNECT_PAUSE_MILLIS = 1_000L

        private const val STREAM_RECENT_BLOCKS = 24
        private const val THUMBNAIL_RECENT_BLOCKS = 4
        private const val WRITE_RECENT_BLOCKS = 8
        private const val THUMBNAIL_AHEAD_BLOCKS = 4L


        /** The least a streaming file gets when sharing the extra connections with others. */
        private const val MIN_SHARED_EXTRA_CONNECTIONS = 4

        /** Files currently using extra connections for streaming. */
        private val streamingChannels = AtomicInteger()

        /** Blocks held by all open files, and the most there may be (a sixth of the heap). */
        private val globalBlocks = AtomicInteger()
        private val MAX_GLOBAL_BLOCKS =
            (minOf(96L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6) / BLOCK_SIZE).toInt()

        /** Reads that waited this long are logged. */
        private const val SLOW_READ_LOG_MILLIS = 2_000L

        /**
         * Writes go out in blocks this big: an upload of a few MB then spreads over many
         * connections (each moves little on a lossy link), instead of waiting on a few 1 MB ones.
         */
        private const val WRITE_BLOCK_SIZE = 256 * 1024


        /** A block is fetched again when it takes this many times the average. */
        private const val HEDGE_FACTOR = 2.0
        private const val MIN_HEDGE_MILLIS = 1_500L
        private const val HEDGE_CHECK_MILLIS = 250L

        /** Set on the connections' threads (see call). */
        private val isWorkerThread = ThreadLocal.withInitial { false }

        /** Reads waiting for the network in all open files (see isOtherFileWaitingLocked). */
        private val readsWaitingInAllFiles = AtomicInteger()

        /** Files open read-only, for the reserved connections of each (reservedConnections). */
        private val openStreamFiles = AtomicInteger()

        /** Blocks ahead of its reader a file still fetches while another file's reader waits. */
        private const val YIELD_AHEAD_BLOCKS = 4L

        /** Writes in flight an upload keeps while another file's reader waits. */
        private const val YIELD_WRITES_IN_FLIGHT = 2
        /** Copies of one block fetched at once, the first one included. */
        private const val MAX_FETCHERS = 3

        /** Attempts per block (read or write), with delays from [RETRY_BASE_MILLIS] doubling. */
        private const val MAX_FAILURES = 6
        private const val RETRY_BASE_MILLIS = 100L
        private const val OPEN_ATTEMPTS = 3
        private const val BUSY_OPEN_ATTEMPTS = 10
        private const val BUSY_RETRY_MILLIS = 1_000L

        /** Blocks the reader waits for are fetched in pieces this big, in parallel. */
        private const val PIECE_SIZE = NfsReadCache.PIECE_SIZE
        private const val PIECES_PER_BLOCK = NfsReadCache.PIECES_PER_BLOCK

        /**
         * Longer than a reconnect: the NFS timeout, plus reconnecting and a TLS handshake over a
         * slow link. A player waits instead of seeing an error when the network switches.
         */
        const val READ_TIMEOUT_MILLIS = Context.TIMEOUT_MILLIS * 2L + 15_000L

        /** Extra connections that opened the file; for tests. */
        val extraConnectionsOpened = AtomicInteger()

        /** Blocks fetched a second time because the first fetch was late; for tests. */
        val hedgedBlocks = AtomicInteger()

        /** Bytes loaded from the disk cache; for tests. */
        val diskBytes = AtomicLong()

        /** Reads that had to wait for the network (not in memory or the disk cache); for tests. */
        val networkWaits = AtomicInteger()

        /** Where the reads of one file came from; for tests. */
        class ReadStats {
            /** Reads that had to wait for the network: cache misses. */
            val networkWaits = AtomicInteger()
            /** Bytes readers got from the disk cache. */
            val diskBytes = AtomicLong()
            /**
             * Where those reads were, in order: a test tells a place it read from the reads
             * ahead of it (Android's file proxy and AbstractFileByteChannel read ahead).
             */
            val waitedAt: MutableList<Long> = java.util.Collections.synchronizedList(ArrayList())
        }

        /** Set by tests: keeps [ReadStats] per file path (never cleared otherwise). */
        @Volatile
        var isStatsEnabled = false

        private val readStatsByPath = java.util.concurrent.ConcurrentHashMap<String, ReadStats>()

        private val liveChannels =
            java.util.Collections.newSetFromMap(java.util.WeakHashMap<FileByteChannel, Boolean>())

        /**
         * Files that still have connections, and what each connection does (for tests: a file
         * closed a while ago must have none left).
         */
        fun describeFilesWithConnections(): String =
            synchronized(liveChannels) { liveChannels.toList() }
                .mapNotNull { it.describeConnections() }
                .joinToString("; ")
                .ifEmpty { "none" }

        /** The stats of the file at [path] on its server (as it appears in nfs-log.txt). */
        fun readStats(path: String): ReadStats = readStatsByPath.getOrPut(path) { ReadStats() }

        /** Why the last read failed; for tests (the file provider reports only EIO). */
        @Volatile
        var lastReadError: Throwable? = null
    }
}

package me.zhanghai.android.files.provider.nfs.client

import android.os.SystemClock
import io.github.libnfsandroid.Nfs
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.util.ArrayDeque
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
 * - Reads: after [STREAM_AFTER_BYTES] read forward, up to [aheadBlocks] blocks past the reader are
 *   fetched; extra connections join in steps ([extraConnectionTarget]). A block the reader waits
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
    private val onReleased: () -> Unit
) : AbstractFileByteChannel(isAppend) {
    private val lock = ReentrantLock()
    /** Signalled whenever a block or a write finishes, work appears, or the channel closes. */
    private val changed = lock.newCondition()

    private val workers = mutableListOf<Worker>()
    private var mainWorker: Worker? = null
    private var extraConnectionsRequested = 0
    private var isClosing = false

    // Reading.

    private class Block(val index: Long, val generation: Int) {
        /** The data: complete once [isDone], otherwise the [pieces] fetched so far. */
        var data: ByteArray? = null
        /** Valid once [isDone]; shorter than [BLOCK_SIZE] at the end of the file. */
        var length = 0
        var isDone = false
        /** Bit i set: piece i (of [PIECE_SIZE]) is in [data], for a block fetched in pieces. */
        var pieces = 0
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

    /** Blocks from [readBase] on: fetched, being fetched, or failed. */
    private val blocks = HashMap<Long, Block>()
    /** Done blocks dropped behind the reader or by a seek, most recent last. */
    private val recentBlocks = ArrayDeque<Block>()
    /** The block the reader is at; blocks before it are dropped. */
    private var readBase = 0L
    /** Bytes read forward since the last seek, to tell streaming from probing. */
    private var forwardBytes = 0L
    /** The block the reader waits for, or -1, and where in it. */
    private var waitingIndex = -1L
    private var waitingOffset = 0
    /** Blocks fetched before a write or truncation are stale: dropped by generation. */
    private var generation = 0
    /** Known end of file (from a short read); Long.MAX_VALUE until known. */
    private var knownEnd = Long.MAX_VALUE
    private var sizeAtOpen = -1L
    /** Average milliseconds per block fetched from the network, 0 until measured. */
    private var blockMillis = 0.0

    @Volatile
    private var cacheKey: String? = null
    private var isCacheKeyResolved = false

    // Writing.

    private class WriteJob(val position: Long, val data: ByteArray, val length: Int) {
        var failures = 0
        var retryAtMillis = 0L
    }

    /** A block to fetch; in pieces starting with [firstPiece], or whole if it is -1. */
    private class FetchJob(val block: Block, val firstPiece: Int)

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
    override fun onRead(position: Long, size: Int): ByteBuffer =
        try {
            readBlock(position, size)
        } catch (e: InterruptedIOException) {
            throw e
        } catch (e: Throwable) {
            lastReadError = e
            throw e
        }

    @Throws(IOException::class)
    private fun readBlock(position: Long, size: Int): ByteBuffer {
        resolveCacheKey()
        lock.withLock {
            drainWritesLocked()
            if (position >= knownEnd) {
                return EMPTY_BUFFER
            }
            val index = position / BLOCK_SIZE
            val offset = (position - index * BLOCK_SIZE).toInt()
            val (block, end) = takeBlockLocked(index, offset)
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
    private fun takeBlockLocked(index: Long, offset: Int): Pair<Block, Int> {
        recentBlocks.firstOrNull { it.index == index }?.let { recent ->
            if (blocks.isEmpty() || index !in readBase until readBase + aheadBlocks) {
                // A reread of a header or index: no need to move the reader.
                return recent to recent.length
            }
        }
        if (index in readBase until readBase + aheadBlocks) {
            // Forward, possibly skipping some blocks (FUSE read-ahead).
            if (index > readBase) {
                forwardBytes += (index - readBase) * BLOCK_SIZE
                dropBlocksBeforeLocked(index)
                readBase = index
            }
        } else {
            // A seek, or the first read.
            dropBlocksBeforeLocked(Long.MAX_VALUE)
            readBase = index
            forwardBytes = 0
        }
        ensureWorkersLocked()
        val deadline = SystemClock.elapsedRealtime() + READ_TIMEOUT_MILLIS
        waitingIndex = index
        waitingOffset = offset
        try {
            changed.signalAll()
            while (true) {
                val block = blocks[index]
                if (block != null) {
                    val end = block.availableEnd(offset)
                    if (end > 0 || block.isDone) {
                        return block to end
                    }
                }
                val error = block?.error
                if (error != null) {
                    blocks.remove(index)
                    throw error
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) {
                    throw IOException("NFS read timed out")
                }
                try {
                    changed.await(remaining.coerceAtMost(HEDGE_CHECK_MILLIS), TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    // Cancelled (a seek in AbstractFileByteChannel): the caller no longer wants it.
                    throw InterruptedIOException().apply { initCause(e) }
                }
                // Lets an idle worker hedge a late block.
                changed.signalAll()
            }
        } finally {
            waitingIndex = -1
            if (isReadOnly && forwardBytes >= STREAM_AFTER_BYTES) {
                requestExtraConnectionsLocked()
            }
            changed.signalAll()
        }
    }

    private fun dropBlocksBeforeLocked(index: Long) {
        val iterator = blocks.values.iterator()
        while (iterator.hasNext()) {
            val block = iterator.next()
            if (block.index < index) {
                iterator.remove()
                if (block.isDone && block.length > 0) {
                    recentBlocks.removeAll { it.index == block.index }
                    recentBlocks.addLast(block)
                    while (recentBlocks.size > RECENT_BLOCK_COUNT) {
                        recentBlocks.removeFirst()
                    }
                }
            }
        }
    }

    /** How far ahead of the reader blocks are fetched. */
    private val aheadBlocks: Long
        get() = if (forwardBytes >= STREAM_AFTER_BYTES) MAX_AHEAD_BLOCKS else PROBE_AHEAD_BLOCKS

    private fun resolveCacheKey() {
        if (!isReadOnly) {
            return
        }
        lock.withLock {
            if (isCacheKeyResolved) {
                return
            }
            isCacheKeyResolved = true
        }
        // Outside the lock: a network round trip.
        val stat = try {
            call { Nfs.fstat(it, file) }
        } catch (e: IOException) {
            return
        }
        lock.withLock {
            sizeAtOpen = stat.size
            cacheKey = NfsReadCache.fileKey(authority, path, stat)
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
        blocks.clear()
        recentBlocks.clear()
        knownEnd = Long.MAX_VALUE
        forwardBytes = 0
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
        lock.withLock { drainWritesLocked() }
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
            blocks.clear()
            recentBlocks.clear()
            changed.signalAll()
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
            mainWorker = Worker(context, file, isExtra = false).also {
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
        val streamed = if (isReadOnly) forwardBytes else sequentialWrittenBytes
        var target = extraConnectionTarget(streamed, isReadOnly)
        if (isReadOnly && sizeAtOpen >= 0) {
            // No more connections than blocks left to read.
            val left = (sizeAtOpen - readBase * BLOCK_SIZE + BLOCK_SIZE - 1) / BLOCK_SIZE
            target = target.coerceAtMost(left.coerceAtLeast(0).toInt())
        }
        while (extraConnectionsRequested < target && !isClosing) {
            val extra = try {
                Client.acquireExtraContext(authority, listOf(context))
            } catch (e: ClientException) {
                null
            } ?: break
            ++extraConnectionsRequested
            Worker(extra, 0, isExtra = true).also {
                workers += it
                it.start()
            }
        }
    }

    /**
     * One connection and the thread that uses it. Loops taking the most useful job: an urgent
     * block (the one the reader waits for, or a late one to fetch again), a write, then the next
     * block ahead.
     */
    private inner class Worker(val context: Context, var file: Long, val isExtra: Boolean) {
        val thread = Thread({ run() }, if (isExtra) "NfsExtraConnection" else "NfsConnection")
            .apply { isDaemon = true }
        private var isBroken = false

        fun start() {
            thread.start()
        }

        private fun run() {
            try {
                if (isExtra) {
                    val opened = openWithRetries() ?: return
                    // Read by other workers under the lock.
                    lock.withLock { file = opened }
                    extraConnectionsOpened.incrementAndGet()
                }
                loop()
            } finally {
                lock.withLock {
                    workers -= this
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
                        Client.releaseExtraContext(authority, context)
                    } catch (e: ClientException) {
                        // The pool is gone (server edited); the pump destroys the context.
                    }
                }
            }
        }

        private fun openWithRetries(): Long? {
            for (attempt in 0 until OPEN_ATTEMPTS) {
                try {
                    return context.use {
                        Nfs.open(it, path, if (isReadOnly) Nfs.O_RDONLY else Nfs.O_WRONLY, 0)
                    }
                } catch (e: ClientException) {
                    if (context.isBroken || attempt == OPEN_ATTEMPTS - 1) {
                        return null
                    }
                }
                try {
                    Thread.sleep(RETRY_BASE_MILLIS shl (attempt + 1))
                } catch (e: InterruptedException) {
                    return null
                }
                if (lock.withLock { isClosing }) {
                    return null
                }
            }
            return null
        }

        private fun loop() {
            while (true) {
                val job = lock.withLock {
                    var job: Any? = null
                    while (!isClosing && !isBroken) {
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
                when (job) {
                    is FetchJob -> if (job.firstPiece >= 0) {
                        fetchInPieces(job.block, job.firstPiece)
                    } else {
                        fetch(job.block)
                    }
                    is WriteJob -> write(job)
                }
            }
        }

        private fun takeJobLocked(): Any? {
            val now = SystemClock.elapsedRealtime()
            val waiting = waitingIndex
            // Extra connections of a writable file have it open for writing only.
            if (waiting >= 0 && waiting * BLOCK_SIZE < knownEnd && (isReadOnly || !isExtra)) {
                val block = blocks[waiting]
                if (block == null) {
                    // The reader waits for it: fetched in pieces, the one it needs first.
                    return FetchJob(newBlockLocked(waiting, now), waitingOffset / PIECE_SIZE)
                }
                if (!block.isDone && block.error == null && now >= block.retryAtMillis &&
                    (block.fetchers == 0 ||
                        block.fetchers == 1 && now - block.startedMillis > hedgeMillis())) {
                    if (block.fetchers == 1) {
                        hedgedBlocks.incrementAndGet()
                    }
                    ++block.fetchers
                    block.startedMillis = now
                    return FetchJob(block, -1)
                }
            }
            val hasExtras = workers.any { it.isExtra && it.file != 0L }
            if (!isExtra && hasExtras) {
                // Kept free for the reader's next urgent block (a seek).
                return null
            }
            writeQueue.firstOrNull { now >= it.retryAtMillis }?.let {
                writeQueue.remove(it)
                ++writesInFlight
                return it
            }
            if (!isReadOnly && isExtra) {
                return null
            }
            // The next block ahead of the reader that nobody fetches yet.
            val end = readBase + aheadBlocks
            // Not past the end of file: the known one, or the size at open (reads past it still
            // work, as urgent blocks, if the file grew).
            val fileEnd = if (sizeAtOpen >= 0) minOf(knownEnd, sizeAtOpen) else knownEnd
            var index = readBase
            while (index < end && index * BLOCK_SIZE < fileEnd) {
                val block = blocks[index]
                if (block == null) {
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
            return null
        }

        private fun newBlockLocked(index: Long, now: Long): Block =
            Block(index, generation).also {
                it.fetchers = 1
                it.startedMillis = now
                blocks[index] = it
            }

        private fun fetch(block: Block) {
            val position = block.index * BLOCK_SIZE
            val data = ByteArray(BLOCK_SIZE)
            var length = 0
            var fromNetwork = false
            var error: IOException? = null
            val startMillis = SystemClock.elapsedRealtime()
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
                        val count = call(context) {
                            Nfs.read(it, file, position + length, data, length, BLOCK_SIZE - length)
                        }
                        if (count == 0) {
                            break
                        }
                        length += count
                    }
                    if (cacheKey != null && length > 0) {
                        NfsReadCache.write(cacheKey, position, data, length, length < BLOCK_SIZE)
                    }
                }
            } catch (e: IOException) {
                error = e
                if (context.isBroken) {
                    isBroken = true
                }
            }
            lock.withLock {
                --block.fetchers
                if (block.generation != generation || block.isDone) {
                    // Stale (the file changed), or the other copy of a hedged block won.
                    return
                }
                val failure = error
                if (failure != null) {
                    onFetchFailedLocked(block, failure)
                } else {
                    block.data = data
                    block.length = length
                    block.isDone = true
                    if (length < BLOCK_SIZE) {
                        knownEnd = minOf(knownEnd, position + length)
                    }
                    if (fromNetwork && length == BLOCK_SIZE) {
                        val millis = (SystemClock.elapsedRealtime() - startMillis).toDouble()
                        blockMillis = if (blockMillis == 0.0) millis else blockMillis * 0.8 +
                            millis * 0.2
                    }
                }
                changed.signalAll()
            }
        }

        /**
         * Fetches a block the reader waits for in [PIECE_SIZE] pieces, one after the other,
         * starting with [firstPiece]: over a slow connection the reader gets the part it needs
         * after a quarter of the time (a seek, or the start of playback).
         */
        private fun fetchInPieces(block: Block, firstPiece: Int) {
            val data = ByteArray(BLOCK_SIZE)
            val cacheKey = cacheKey
            if (cacheKey != null) {
                val cached = NfsReadCache.read(cacheKey, block.position, data, BLOCK_SIZE)
                if (cached >= 0) {
                    lock.withLock {
                        --block.fetchers
                        if (block.generation == generation && !block.isDone) {
                            block.data = data
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
            lock.withLock { block.data = data }
            val order = (firstPiece until PIECES_PER_BLOCK) + (0 until firstPiece)
            var error: IOException? = null
            try {
                for (piece in order) {
                    val start = piece * PIECE_SIZE
                    val skip = lock.withLock {
                        block.isDone || block.generation != generation || start >= block.pieceEnd
                    }
                    if (skip) {
                        continue
                    }
                    var length = 0
                    while (length < PIECE_SIZE) {
                        val count = call(context) {
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
                    lock.withLock {
                        if (length < PIECE_SIZE) {
                            block.pieceEnd = minOf(block.pieceEnd, start + length)
                            knownEnd = minOf(knownEnd, block.position + start + length)
                        }
                        block.pieces = block.pieces or (1 shl piece)
                        changed.signalAll()
                    }
                }
            } catch (e: IOException) {
                error = e
                if (context.isBroken) {
                    isBroken = true
                }
            }
            var complete: Int = -1
            lock.withLock {
                --block.fetchers
                if (block.generation != generation || block.isDone) {
                    return
                }
                val failure = error
                if (failure != null) {
                    // What arrived stays usable; the rest is fetched again, whole.
                    onFetchFailedLocked(block, failure)
                } else {
                    block.length = block.pieceEnd
                    block.isDone = true
                    complete = block.length
                }
                changed.signalAll()
            }
            if (complete > 0 && cacheKey != null) {
                NfsReadCache.write(
                    cacheKey, block.position, data, complete, complete < BLOCK_SIZE
                )
            }
        }

        private fun onFetchFailedLocked(block: Block, error: IOException) {
            ++block.failures
            // Retried after a growing delay (a server answering NFS4ERR_DELAY, a connection
            // being replaced), by any connection; the reader gets the error once retries are
            // exhausted, or at once if the file's own connection broke.
            block.retryAtMillis = SystemClock.elapsedRealtime() +
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
                        job.retryAtMillis = SystemClock.elapsedRealtime() +
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

    @Throws(IOException::class)
    private inline fun <T> call(
        context: Context = this.context,
        crossinline block: (Long) -> T
    ): T =
        try {
            context.use { block(it) }
        } catch (e: ClientException) {
            if (context === this.context && context.isBroken && e.isTransportError) {
                setClosed()
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
         * Blocks fetched ahead of the reader while streaming: 128 MiB, at most a sixth of the
         * heap. Several seconds of the link, so that connections never run out of work while the
         * reader waits for a slow one.
         */
        private val MAX_AHEAD_BLOCKS =
            minOf(128L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 6) / BLOCK_SIZE

        /** Written data not yet acknowledged by the server. */
        private const val MAX_PENDING_WRITE_BYTES = 64L * 1024 * 1024

        /**
         * Extra connections once streaming. On a lossy, high-latency link the total grows with
         * their number (host tests at 100 ms and 0.3 % loss: 16 read 5.4 MB/s, 24 read 6.0 MB/s,
         * 32 read 6.5 MB/s). A read-only file gets them all at once (the caller caps them at the
         * blocks left); a written one, whose size is unknown, in steps: a few for a photo, all for
         * a video. Idle connections come from the pool, so each costs one OPEN.
         */
        private fun extraConnectionTarget(streamedBytes: Long, isReadOnly: Boolean): Int =
            when {
                streamedBytes < STREAM_AFTER_BYTES -> 0
                isReadOnly -> MAX_EXTRA_CONNECTIONS
                streamedBytes < 2L * 1024 * 1024 -> 8
                streamedBytes < 4L * 1024 * 1024 -> 16
                else -> MAX_EXTRA_CONNECTIONS
            }

        private const val MAX_EXTRA_CONNECTIONS = 32

        /**
         * Writes go out in blocks this big: an upload of a few MB then spreads over many
         * connections (each moves little on a lossy link), instead of waiting on a few 1 MB ones.
         */
        private const val WRITE_BLOCK_SIZE = 256 * 1024

        private const val RECENT_BLOCK_COUNT = 8

        /** A block is fetched again when it takes this many times the average. */
        private const val HEDGE_FACTOR = 2.0
        private const val MIN_HEDGE_MILLIS = 1_500L
        private const val HEDGE_CHECK_MILLIS = 250L

        /** Attempts per block (read or write), with delays from [RETRY_BASE_MILLIS] doubling. */
        private const val MAX_FAILURES = 6
        private const val RETRY_BASE_MILLIS = 100L
        private const val OPEN_ATTEMPTS = 3

        /** Blocks the reader waits for are fetched in pieces this big. */
        private const val PIECE_SIZE = 256 * 1024
        private const val PIECES_PER_BLOCK = BLOCK_SIZE / PIECE_SIZE

        /**
         * Longer than a reconnect: the NFS timeout, plus reconnecting and a TLS handshake over a
         * slow link. A player waits instead of seeing an error when the network switches.
         */
        private const val READ_TIMEOUT_MILLIS = Context.TIMEOUT_MILLIS * 2L + 15_000L

        /** Extra connections that opened the file; for tests. */
        val extraConnectionsOpened = AtomicInteger()

        /** Blocks fetched a second time because the first fetch was late; for tests. */
        val hedgedBlocks = AtomicInteger()

        /** Why the last read failed; for tests (the file provider reports only EIO). */
        @Volatile
        var lastReadError: Throwable? = null
    }
}

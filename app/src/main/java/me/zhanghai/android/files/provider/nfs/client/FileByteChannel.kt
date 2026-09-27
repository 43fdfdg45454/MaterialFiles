package me.zhanghai.android.files.provider.nfs.client

import android.os.SystemClock
import io.github.libnfsandroid.Nfs
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.ArrayDeque
import java.util.concurrent.Future
import me.zhanghai.android.files.provider.common.AbstractFileByteChannel

/**
 * A file opened on one [Context].
 *
 * Throughput over a network depends on keeping requests in flight, not on the size of a single
 * one, and on never waiting for the reader to ask. So this channel:
 * - Coalesces the small writes callers make (Material Files copies in 8 KiB pieces) into one
 *   buffer, sent with a single [Nfs.write] that the native side splits into parallel WRITEs.
 * - Streams reads: once reading goes forward, a queue of windows ([AHEAD_COUNT] deep) is fetched
 *   in order on the channel's own thread, each as up to 16 parallel READs of 1 MiB, and topped up
 *   as soon as one is consumed. Windows start at 1 MiB after a seek (fast start) and double up to
 *   what the link moves in [TARGET_FETCH_MILLIS], measured on large fetches only: small fetches
 *   mostly measure latency and would keep the windows small forever.
 * - Treats any read landing inside the current window or the queued ones as "forward": players
 *   read through Android's FUSE proxy, whose read-ahead skips ahead and does not always ask for
 *   the next byte exactly. Only reads outside of all of that are seeks; they drop the queue and
 *   restart it from the new position.
 * - Keeps the last small windows: players read the start, jump to the index at the end (MP4
 *   moov, MKV cues) and come back.
 * - Lets reads wait [READ_TIMEOUT_MILLIS], longer than an NFS reconnect, so that playback survives
 *   a network switch instead of failing at [AbstractFileByteChannel]'s 15 s default.
 *
 * Source buffers may be direct (libarchive passes native memory), so data is always copied with
 * [ByteBuffer.get], never through [ByteBuffer.array].
 *
 * Buffered writes are sent before any read, size query, truncation, sync or close, and a close is
 * only reported successful once the data is committed to stable storage.
 */
internal class FileByteChannel(
    private val context: Context,
    private val file: Long,
    isAppend: Boolean,
    /** Server and path, for files that may use [NfsReadCache]; null for writable files. */
    private val cacheIdentity: Pair<Authority, ByteArray>?,
    private val onReleased: () -> Unit
) : AbstractFileByteChannel(isAppend) {
    // onRead() runs on coroutine threads, concurrently with calls that AbstractFileByteChannel
    // makes under its own lock; this guards the state below.
    private val bufferLock = Any()

    private var writeBuffer = ByteArray(0)
    private var writeBatchSize = MIN_WINDOW_SIZE
    private var writeBufferPosition = 0L
    private var writeBufferLength = 0
    private var hasWritten = false

    private var window = EMPTY_WINDOW
    private val recentWindows = ArrayDeque<Window>(RECENT_WINDOW_COUNT)

    /** Windows being fetched ahead of the reader, contiguous and in order. */
    private val aheads = ArrayDeque<Ahead>()
    /** Where the next window ahead starts. */
    private var aheadEnd = 0L
    /** Size of the next window ahead. */
    private var nextAheadSize = MIN_WINDOW_SIZE
    /** Known end of file, once a fetch came back short. */
    @Volatile
    private var knownEnd = Long.MAX_VALUE

    /** Bytes per millisecond, measured on large fetches; 0 until known. */
    @Volatile
    private var bandwidth = 0.0

    private var readExecutor: ExecutorService? = null

    /** This version of the file in [NfsReadCache]; resolved on the first read. */
    @Volatile
    private var cacheKey: String? = null
    private var isCacheKeyResolved = false

    private class Window(val data: ByteArray, val position: Long, val length: Int) {
        val end: Long
            get() = position + length

        val isFull: Boolean
            get() = length == data.size

        operator fun contains(position: Long): Boolean =
            position >= this.position && position < end
    }

    private class Ahead(val position: Long, val size: Int, val future: Future<Window>) {
        val end: Long
            get() = position + size
    }

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer =
        synchronized(bufferLock) {
            flushWritesLocked()
            resolveCacheKeyLocked()
            if (position !in window) {
                val next = takeAheadLocked(position)
                // Cancelled (a seek): the caller no longer wants this position.
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException()
                }
                rememberWindowLocked(window)
                val recent = if (next == null) recentWindows.firstOrNull { position in it } else null
                window = when {
                    next != null -> next
                    recent != null -> {
                        recentWindows.remove(recent)
                        recent
                    }
                    else -> {
                        // A seek, or the first read: a small window to start fast, block aligned
                        // so that it can come from and go to the read cache.
                        cancelAheadsLocked()
                        val start = position - position % BLOCK_SIZE
                        val length = (position - start + size)
                            .coerceAtLeast(MIN_WINDOW_SIZE.toLong())
                        fetchWindow(start, roundUpToBlock(length))
                    }
                }
                if (next == null) {
                    // The read-ahead restarts from here, small again.
                    cancelAheadsLocked()
                    nextAheadSize = 2 * MIN_WINDOW_SIZE
                    aheadEnd = window.end
                }
            }
            if (window.isFull && window.end < knownEnd) {
                topUpAheadsLocked()
            }
            val offset = (position - window.position).toInt()
            val length = size.coerceAtMost(window.length - offset).coerceAtLeast(0)
            ByteBuffer.wrap(window.data.copyOfRange(offset, offset + length))
        }

    override fun onReadAsync(position: Long, size: Int, timeoutMillis: Long): Future<ByteBuffer> =
        super.onReadAsync(position, size, timeoutMillis.coerceAtLeast(READ_TIMEOUT_MILLIS))

    /**
     * Returns the queued window holding [position], dropping the ones before it (the reader
     * skipped them), or null if [position] is outside the queue.
     */
    private fun takeAheadLocked(position: Long): Window? {
        val first = aheads.peekFirst() ?: return null
        if (position < first.position || position >= aheadEnd) {
            return null
        }
        while (true) {
            val ahead = aheads.pollFirst() ?: return null
            if (position >= ahead.end) {
                ahead.future.cancel(false)
                continue
            }
            val fetched = try {
                ahead.future.get()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            } catch (e: Exception) {
                // Failed or cancelled: the synchronous fetch that follows reports a real error.
                null
            }
            return fetched?.takeIf { position in it }
        }
    }

    private fun topUpAheadsLocked() {
        if (aheadEnd < window.end) {
            aheadEnd = window.end
        }
        val executor = readExecutor ?: Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "NfsReadAhead").apply { isDaemon = true }
        }.also { readExecutor = it }
        while (aheads.size < AHEAD_COUNT && aheadEnd < knownEnd) {
            val size = nextAheadSize.coerceAtMost(maxWindowSize())
            val position = aheadEnd
            aheads.addLast(Ahead(position, size, executor.submit<Window> {
                fetchWindow(position, size)
            }))
            aheadEnd += size
            nextAheadSize = (nextAheadSize * 2).coerceAtMost(MAX_WINDOW_SIZE)
        }
    }

    /** Drops the read-ahead queue; a fetch already running finishes on its own. */
    private fun cancelAheadsLocked() {
        while (true) {
            val ahead = aheads.pollFirst() ?: break
            ahead.future.cancel(false)
        }
    }

    /** Waits until no fetch uses the file handle any more. */
    private fun awaitReadsIdleLocked() {
        val executor = readExecutor ?: return
        try {
            executor.submit {}.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            // Shut down already.
        }
    }

    /** What the link moves in [TARGET_FETCH_MILLIS]; the maximum until measured. */
    private fun maxWindowSize(): Int {
        val bandwidth = bandwidth
        if (bandwidth <= 0.0) {
            return MAX_WINDOW_SIZE
        }
        val size = (bandwidth * TARGET_FETCH_MILLIS).toLong()
            .coerceIn(MIN_WINDOW_SIZE.toLong(), MAX_WINDOW_SIZE.toLong())
        // Whole blocks, so that windows stay aligned for the read cache.
        return (size - size % BLOCK_SIZE).toInt()
    }

    private fun resolveCacheKeyLocked() {
        if (isCacheKeyResolved) {
            return
        }
        isCacheKeyResolved = true
        val (authority, path) = cacheIdentity ?: return
        cacheKey = try {
            NfsReadCache.fileKey(authority, path, call { Nfs.fstat(it, file) })
        } catch (e: IOException) {
            null
        }
    }

    /** Keeps small windows (headers, indexes) that a player is likely to read again. */
    private fun rememberWindowLocked(window: Window) {
        if (window.length == 0 || window.length > MAX_RECENT_WINDOW_SIZE) {
            return
        }
        recentWindows.removeAll { it.position == window.position }
        if (recentWindows.size == RECENT_WINDOW_COUNT) {
            recentWindows.removeLast()
        }
        recentWindows.addFirst(window)
    }

    /** Runs on the reader's thread or the read-ahead thread; never touches the queue. */
    @Throws(IOException::class)
    private fun fetchWindow(position: Long, size: Int): Window {
        val data = ByteArray(size)
        val cacheKey = cacheKey?.takeIf { position % BLOCK_SIZE == 0L }
        if (cacheKey != null) {
            val cached = NfsReadCache.read(cacheKey, position, data, size)
            if (cached >= 0) {
                if (cached < size) {
                    knownEnd = minOf(knownEnd, position + cached)
                }
                return Window(data, position, cached)
            }
        }
        val startMillis = SystemClock.elapsedRealtime()
        var length = 0
        while (length < size) {
            val count = call { Nfs.read(it, file, position + length, data, length, size - length) }
            if (count == 0) {
                break
            }
            length += count
        }
        if (length < size) {
            knownEnd = minOf(knownEnd, position + length)
        }
        if (cacheKey != null && length > 0) {
            // The window's array is never modified once returned, so it can be written as is.
            NfsReadCache.write(cacheKey, position, data, length, length < size)
        }
        if (length >= BANDWIDTH_SAMPLE_SIZE) {
            val elapsedMillis = (SystemClock.elapsedRealtime() - startMillis).coerceAtLeast(1)
            val sample = length.toDouble() / elapsedMillis
            val previous = bandwidth
            bandwidth = if (previous <= 0.0) sample else previous * 0.5 + sample * 0.5
        }
        return Window(data, position, length)
    }

    @Throws(IOException::class)
    override fun onWrite(position: Long, source: ByteBuffer) {
        synchronized(bufferLock) {
            invalidateReadsLocked()
            if (writeBufferLength > 0 && position != writeBufferPosition + writeBufferLength) {
                flushWritesLocked()
            }
            if (writeBufferLength == 0) {
                writeBufferPosition = position
            }
            while (source.hasRemaining()) {
                if (writeBufferLength >= writeBatchSize) {
                    // Advances writeBufferPosition past the flushed bytes.
                    flushWritesLocked()
                } else if (writeBufferLength == writeBuffer.size) {
                    writeBuffer = writeBuffer.copyOf(writeBatchSize)
                }
                val length = source.remaining()
                    .coerceAtMost(writeBuffer.size - writeBufferLength)
                    .coerceAtMost(writeBatchSize - writeBufferLength)
                source.get(writeBuffer, writeBufferLength, length)
                writeBufferLength += length
            }
        }
    }

    @Throws(IOException::class)
    private fun flushWritesLocked() {
        var written = 0
        val startMillis = SystemClock.elapsedRealtime()
        val isFullBatch = writeBufferLength >= writeBatchSize
        try {
            while (written < writeBufferLength) {
                val count = call {
                    Nfs.write(
                        it, file, writeBufferPosition + written, writeBuffer, written,
                        writeBufferLength - written
                    )
                }
                if (count <= 0) {
                    throw IOException("NFS write made no progress")
                }
                written += count
                hasWritten = true
            }
            if (isFullBatch) {
                writeBatchSize = nextBatchSize(writeBatchSize, written, startMillis)
            }
        } finally {
            // On failure the unsent bytes are dropped: the error reaches the caller, and a later
            // write must not silently resend data whose state on the server is unknown.
            writeBufferPosition += writeBufferLength
            writeBufferLength = 0
        }
    }

    /** Drops read data, waiting for a fetch still using the file handle. */
    private fun invalidateReadsLocked() {
        cancelAheadsLocked()
        awaitReadsIdleLocked()
        window = EMPTY_WINDOW
        recentWindows.clear()
        aheadEnd = 0
        nextAheadSize = MIN_WINDOW_SIZE
        knownEnd = Long.MAX_VALUE
    }

    @Throws(IOException::class)
    override fun onTruncate(size: Long) {
        synchronized(bufferLock) {
            flushWritesLocked()
            invalidateReadsLocked()
            call { Nfs.ftruncate(it, file, size) }
        }
    }

    @Throws(IOException::class)
    override fun onSize(): Long =
        synchronized(bufferLock) {
            flushWritesLocked()
            call { Nfs.fstat(it, file) }.size
        }

    @Throws(IOException::class)
    override fun onForce(metaData: Boolean) {
        synchronized(bufferLock) {
            flushWritesLocked()
            call { Nfs.fsync(it, file) }
        }
    }

    @Throws(IOException::class)
    override fun onClose() {
        try {
            synchronized(bufferLock) {
                invalidateReadsLocked()
                flushWritesLocked()
                // NFS writes are UNSTABLE until committed; do not report a close as successful
                // before the data is on stable storage.
                if (hasWritten) {
                    call { Nfs.fsync(it, file) }
                }
            }
        } finally {
            try {
                call { Nfs.close(it, file) }
            } catch (e: IOException) {
                // A lost connection already dropped the server-side state; nothing to close.
                if (!context.isBroken) {
                    throw e
                }
            } finally {
                synchronized(bufferLock) {
                    writeBuffer = ByteArray(0)
                    window = EMPTY_WINDOW
                    recentWindows.clear()
                    readExecutor?.shutdown()
                    readExecutor = null
                }
                onReleased()
            }
        }
    }

    @Throws(IOException::class)
    private inline fun <T> call(crossinline block: (Long) -> T): T =
        try {
            context.use { block(it) }
        } catch (e: ClientException) {
            if (context.isBroken && e.isTransportError) {
                setClosed()
                throw AsynchronousCloseException().apply { initCause(e) }
            }
            throw IOException(e.message, e)
        }

    companion object {
        private val EMPTY_WINDOW = Window(ByteArray(0), 0, 0)

        private const val MIN_WINDOW_SIZE = 1024 * 1024
        private const val MAX_WINDOW_SIZE = 16 * 1024 * 1024

        /**
         * How long one read-ahead window may take. Short enough that a seek never waits long for
         * a stale fetch, long enough that the round trip between windows costs little.
         */
        private const val TARGET_FETCH_MILLIS = 2_000L

        /** Windows fetched ahead of the reader (up to 48 MiB of memory). */
        private const val AHEAD_COUNT = 3

        private const val BLOCK_SIZE = NfsReadCache.BLOCK_SIZE

        private fun roundUpToBlock(length: Long): Int =
            ((length + BLOCK_SIZE - 1) / BLOCK_SIZE * BLOCK_SIZE).toInt()

        /** Smaller fetches mostly measure latency, not bandwidth. */
        private const val BANDWIDTH_SAMPLE_SIZE = 4 * 1024 * 1024

        /** Write batches: sized to take about this long each. */
        private const val TARGET_WRITE_MILLIS = 1_000L
        private const val MAX_WRITE_BATCH_SIZE = 8 * 1024 * 1024

        /**
         * Longer than a reconnect: the NFS timeout, plus reconnecting and a TLS handshake over a
         * slow link. A player waits instead of seeing an error when the network switches.
         */
        private const val READ_TIMEOUT_MILLIS = Context.TIMEOUT_MILLIS * 2L + 15_000L

        private const val RECENT_WINDOW_COUNT = 2
        private const val MAX_RECENT_WINDOW_SIZE = 2 * 1024 * 1024

        /**
         * Doubles a write batch while it transfers within the target time, and shrinks it toward
         * what the link moves in that time otherwise.
         */
        private fun nextBatchSize(size: Int, transferred: Int, startMillis: Long): Int {
            val elapsedMillis = (SystemClock.elapsedRealtime() - startMillis).coerceAtLeast(1)
            val bytesInTarget = transferred.toLong() * TARGET_WRITE_MILLIS / elapsedMillis
            val next = if (bytesInTarget >= size * 2L) size * 2L else bytesInTarget
            return next.coerceIn(MIN_WINDOW_SIZE.toLong(), MAX_WRITE_BATCH_SIZE.toLong()).toInt()
        }
    }
}

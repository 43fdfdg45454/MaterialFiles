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
 * one, so this channel:
 * - Coalesces the small writes callers make (Material Files copies in 8 KiB pieces) into one
 *   buffer, sent with a single [Nfs.write] that the native side splits into parallel WRITEs. The
 *   buffer is sized like the read window: each flush should take about [TARGET_FETCH_MILLIS], so
 *   on a slow link (a VPN over mobile data) a batch never sits in flight long enough to approach
 *   the RPC timeout.
 * - Reads in windows fetched the same way. While reading is sequential the window doubles, and
 *   the next window is fetched in the background while the current one is consumed. Each fetch
 *   is sized to take about [TARGET_FETCH_MILLIS], keeping it well below the read timeout of
 *   [AbstractFileByteChannel] on slow links.
 *
 * For streaming (a video player reading through Material Files' file provider), three more
 * things matter:
 * - Reads may wait [READ_TIMEOUT_MILLIS], longer than an NFS reconnect takes, so that playback
 *   survives a network switch instead of failing at [AbstractFileByteChannel]'s 15 s default.
 * - A read cancelled by a seek does not go on to fetch data for the old position.
 * - The last few small windows are kept: players read the start, jump to the index at the end
 *   (MP4 moov, MKV cues) and come back, and those windows should not cross the network twice.
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
    private val onReleased: () -> Unit
) : AbstractFileByteChannel(isAppend) {
    // onRead() runs on a background thread for read-ahead, concurrently with calls that
    // AbstractFileByteChannel makes under its own lock; this guards the state below.
    private val bufferLock = Any()

    private var writeBuffer = ByteArray(0)
    private var writeBatchSize = MIN_WINDOW_SIZE
    private var writeBufferPosition = 0L
    private var writeBufferLength = 0
    private var hasWritten = false

    private var window = Window(ByteArray(0), 0, 0)
    private var nextWindowSize = MIN_WINDOW_SIZE
    private var prefetch: Future<Window>? = null
    private val recentWindows = ArrayDeque<Window>(RECENT_WINDOW_COUNT)

    private class Window(val data: ByteArray, val position: Long, val length: Int) {
        val end: Long
            get() = position + length

        val isFull: Boolean
            get() = length == data.size

        operator fun contains(position: Long): Boolean =
            position >= this.position && position < end
    }

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer =
        synchronized(bufferLock) {
            flushWritesLocked()
            if (position !in window) {
                val isSequential = window.length > 0 && position == window.end
                val prefetched = takePrefetchLocked()
                // Cancelled (a seek): the caller no longer wants this position.
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException()
                }
                rememberWindowLocked(window)
                val recent = recentWindows.firstOrNull { position in it }
                window = if (prefetched != null && position in prefetched) {
                    prefetched
                } else if (recent != null) {
                    recentWindows.remove(recent)
                    recent
                } else {
                    if (!isSequential) {
                        nextWindowSize = MIN_WINDOW_SIZE
                    }
                    fetchWindow(position, nextWindowSize.coerceAtLeast(size))
                }
                // Keep the pipe busy: fetch what comes next while this window is consumed.
                if (isSequential && window.isFull) {
                    startPrefetchLocked(window.end)
                }
            }
            val offset = (position - window.position).toInt()
            val length = size.coerceAtMost(window.length - offset).coerceAtLeast(0)
            // A copy: windows are reused and refilled by later fetches.
            ByteBuffer.wrap(window.data.copyOfRange(offset, offset + length))
        }

    override fun onReadAsync(position: Long, size: Int, timeoutMillis: Long): Future<ByteBuffer> =
        super.onReadAsync(position, size, timeoutMillis.coerceAtLeast(READ_TIMEOUT_MILLIS))

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

    @Throws(IOException::class)
    private fun fetchWindow(position: Long, size: Int): Window {
        val data = ByteArray(size)
        val startMillis = SystemClock.elapsedRealtime()
        var length = 0
        while (length < size) {
            val count = call { Nfs.read(it, file, position + length, data, length, size - length) }
            if (count == 0) {
                break
            }
            length += count
        }
        if (length == size) {
            // Written from the prefetch thread too; only a sizing hint.
            nextWindowSize = nextBatchSize(size, length, startMillis)
        }
        return Window(data, position, length)
    }

    private fun startPrefetchLocked(position: Long) {
        val size = nextWindowSize
        prefetch = prefetchExecutor.submit<Window> { fetchWindow(position, size) }
    }

    /** Waits for an in-flight prefetch; returns its window, or null if none or it failed. */
    private fun takePrefetchLocked(): Window? {
        val future = prefetch ?: return null
        prefetch = null
        return try {
            future.get()
        } catch (e: InterruptedException) {
            // Keep the interrupt visible to the caller (see onRead()).
            Thread.currentThread().interrupt()
            null
        } catch (e: Exception) {
            // The synchronous fetch that follows reports the real error, if it persists.
            null
        }
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

    /** Drops read-ahead data, waiting for an in-flight prefetch that uses the file handle. */
    private fun invalidateReadsLocked() {
        takePrefetchLocked()
        window = Window(window.data, 0, 0)
        recentWindows.clear()
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
                    window = Window(ByteArray(0), 0, 0)
                    recentWindows.clear()
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
        private const val MIN_WINDOW_SIZE = 1024 * 1024
        private const val MAX_WINDOW_SIZE = 8 * 1024 * 1024
        private const val TARGET_FETCH_MILLIS = 1_000L

        /**
         * Longer than a reconnect: the NFS timeout, plus reconnecting and a TLS handshake over a
         * slow link. A player waits instead of seeing an error when the network switches.
         */
        private const val READ_TIMEOUT_MILLIS = Context.TIMEOUT_MILLIS * 2L + 15_000L

        private const val RECENT_WINDOW_COUNT = 2
        private const val MAX_RECENT_WINDOW_SIZE = 2 * 1024 * 1024

        /**
         * Doubles a batch while it transfers within the target time, and shrinks it toward what
         * the link moves in that time otherwise.
         */
        private fun nextBatchSize(size: Int, transferred: Int, startMillis: Long): Int {
            val elapsedMillis = (SystemClock.elapsedRealtime() - startMillis).coerceAtLeast(1)
            val bytesInTarget = transferred.toLong() * TARGET_FETCH_MILLIS / elapsedMillis
            val next = if (bytesInTarget >= size * 2L) size * 2L else bytesInTarget
            return next.coerceIn(MIN_WINDOW_SIZE.toLong(), MAX_WINDOW_SIZE.toLong()).toInt()
        }

        private val prefetchExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "NfsReadAhead").apply { isDaemon = true }
        }
    }
}

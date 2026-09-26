package me.zhanghai.android.files.provider.nfs.client

import android.os.SystemClock
import io.github.libnfsandroid.Nfs
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import me.zhanghai.android.files.provider.common.AbstractFileByteChannel

/**
 * A file opened on one [Context].
 *
 * Throughput over a network depends on keeping several requests in flight, not on the size of a
 * single one, so this channel:
 * - Coalesces the small writes callers make (Material Files copies in 8 KiB pieces) into one
 *   buffer, and sends it with a single [Nfs.write] call that the native side splits into
 *   parallel WRITEs.
 * - Reads ahead in a window that grows while reading is sequential, fetched the same way. The
 *   window is sized so one fetch takes about [TARGET_FETCH_MILLIS], which keeps it well below
 *   the read timeout of [AbstractFileByteChannel] on slow links.
 *
 * Buffered writes are sent before any read, size query, truncation, sync or close, and a close
 * is only reported successful once the data is committed to stable storage.
 */
internal class FileByteChannel(
    private val context: Context,
    private val file: Long,
    isAppend: Boolean,
    private val onReleased: () -> Unit
) : AbstractFileByteChannel(isAppend) {
    // onRead() runs on a background thread for read-ahead, concurrently with calls that
    // AbstractFileByteChannel makes under its own lock; this guards the buffers below.
    private val bufferLock = Any()

    private var writeBuffer = ByteArray(0)
    private var writeBufferPosition = 0L
    private var writeBufferLength = 0
    private var hasWritten = false

    private var readWindow = ByteArray(0)
    private var readWindowPosition = 0L
    private var readWindowLength = 0
    private var nextReadWindowSize = MIN_WINDOW_SIZE

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer =
        synchronized(bufferLock) {
            flushWritesLocked()
            val windowEnd = readWindowPosition + readWindowLength
            if (position < readWindowPosition || position >= windowEnd) {
                val isSequential = readWindowLength > 0 && position == windowEnd
                fillReadWindowLocked(position, size, isSequential)
            }
            val offset = (position - readWindowPosition).toInt()
            val length = size.coerceAtMost(readWindowLength - offset).coerceAtLeast(0)
            // A copy: the window is refilled by the next read-ahead.
            ByteBuffer.wrap(readWindow.copyOfRange(offset, offset + length))
        }

    @Throws(IOException::class)
    private fun fillReadWindowLocked(position: Long, size: Int, isSequential: Boolean) {
        if (!isSequential) {
            nextReadWindowSize = MIN_WINDOW_SIZE
        }
        val windowSize = nextReadWindowSize.coerceAtLeast(size)
        if (readWindow.size < windowSize) {
            readWindow = ByteArray(windowSize)
        }
        val startMillis = SystemClock.elapsedRealtime()
        var length = 0
        while (length < windowSize) {
            val count = call {
                Nfs.read(it, file, position + length, readWindow, length, windowSize - length)
            }
            if (count == 0) {
                break
            }
            length += count
        }
        readWindowPosition = position
        readWindowLength = length
        if (length == windowSize) {
            nextReadWindowSize = nextWindowSize(windowSize, length, startMillis)
        }
    }

    @Throws(IOException::class)
    override fun onWrite(position: Long, source: ByteBuffer) {
        synchronized(bufferLock) {
            invalidateReadWindowLocked()
            if (writeBufferLength > 0 && position != writeBufferPosition + writeBufferLength) {
                flushWritesLocked()
            }
            if (writeBufferLength == 0) {
                writeBufferPosition = position
            }
            while (source.hasRemaining()) {
                if (writeBufferLength == writeBuffer.size) {
                    if (writeBuffer.size < MAX_WINDOW_SIZE) {
                        writeBuffer = writeBuffer.copyOf(
                            (writeBuffer.size * 2).coerceIn(MIN_WINDOW_SIZE, MAX_WINDOW_SIZE)
                        )
                    } else {
                        // Advances writeBufferPosition past the flushed bytes.
                        flushWritesLocked()
                    }
                }
                val length = source.remaining().coerceAtMost(writeBuffer.size - writeBufferLength)
                source.get(writeBuffer, writeBufferLength, length)
                writeBufferLength += length
            }
        }
    }

    @Throws(IOException::class)
    private fun flushWritesLocked() {
        var written = 0
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
        } finally {
            // On failure the unsent bytes are dropped: the error reaches the caller, and a later
            // write must not silently resend data whose state on the server is unknown.
            writeBufferPosition += writeBufferLength
            writeBufferLength = 0
        }
    }

    private fun invalidateReadWindowLocked() {
        readWindowLength = 0
    }

    @Throws(IOException::class)
    override fun onTruncate(size: Long) {
        synchronized(bufferLock) {
            flushWritesLocked()
            invalidateReadWindowLocked()
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
                    readWindow = ByteArray(0)
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
         * Doubles the window while a fetch stays under the target time, and shrinks it toward
         * what the link moves in that time otherwise.
         */
        private fun nextWindowSize(windowSize: Int, fetched: Int, startMillis: Long): Int {
            val elapsedMillis = (SystemClock.elapsedRealtime() - startMillis).coerceAtLeast(1)
            val bytesInTarget = fetched.toLong() * TARGET_FETCH_MILLIS / elapsedMillis
            val size = if (bytesInTarget >= windowSize * 2L) windowSize * 2 else bytesInTarget.toInt()
            return size.coerceIn(MIN_WINDOW_SIZE, MAX_WINDOW_SIZE)
        }
    }
}

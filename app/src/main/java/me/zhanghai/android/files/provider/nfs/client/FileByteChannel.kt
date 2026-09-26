package me.zhanghai.android.files.provider.nfs.client

import io.github.libnfsandroid.Nfs
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousCloseException
import me.zhanghai.android.files.provider.common.AbstractFileByteChannel

/**
 * A file opened on one [Context]. Every READ/WRITE is a separate RPC under the context lock, so
 * other operations on the same context interleave between them.
 */
internal class FileByteChannel(
    private val context: Context,
    private val file: Long,
    isAppend: Boolean,
    private val onReleased: () -> Unit
) : AbstractFileByteChannel(isAppend) {
    private var hasWritten = false

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer {
        val buffer = ByteArray(size)
        var length = 0
        while (length < size) {
            val count = call {
                Nfs.read(it, file, position + length, buffer, length, size - length)
            }
            if (count == 0) {
                break
            }
            length += count
        }
        return ByteBuffer.wrap(buffer, 0, length)
    }

    @Throws(IOException::class)
    override fun onWrite(position: Long, source: ByteBuffer) {
        // Material Files only uses heap buffers here.
        val array = source.array()
        val offset = source.arrayOffset() + source.position()
        val length = source.remaining()
        var written = 0
        while (written < length) {
            val count = call {
                Nfs.write(it, file, position + written, array, offset + written, length - written)
            }
            if (count <= 0) {
                throw IOException("NFS write made no progress")
            }
            written += count
            hasWritten = true
        }
        source.position(source.limit())
    }

    @Throws(IOException::class)
    override fun onTruncate(size: Long) {
        call { Nfs.ftruncate(it, file, size) }
    }

    @Throws(IOException::class)
    override fun onSize(): Long = call { Nfs.fstat(it, file) }.size

    @Throws(IOException::class)
    override fun onForce(metaData: Boolean) {
        call { Nfs.fsync(it, file) }
    }

    @Throws(IOException::class)
    override fun onClose() {
        try {
            // NFS writes may be UNSTABLE until committed; do not report a close as successful
            // before the data is on stable storage.
            if (hasWritten) {
                call { Nfs.fsync(it, file) }
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
}

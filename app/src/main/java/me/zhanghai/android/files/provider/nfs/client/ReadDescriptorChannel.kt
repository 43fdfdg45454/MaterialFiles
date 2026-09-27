package me.zhanghai.android.files.provider.nfs.client

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.util.concurrent.Future
import me.zhanghai.android.files.provider.common.AbstractFileByteChannel

/**
 * One descriptor of a file opened read-only: its own position, reading through the
 * [FileByteChannel] shared by all descriptors of that file (their connections and fetched blocks),
 * so that a player opening the file several times streams it once.
 */
internal class ReadDescriptorChannel(
    private val file: FileByteChannel,
    private val onClosed: () -> Unit
) : AbstractFileByteChannel(false) {
    private val reader = file.attach()

    @Throws(IOException::class)
    override fun onRead(position: Long, size: Int): ByteBuffer =
        file.readShared(reader, position, size)

    override fun onReadAsync(position: Long, size: Int, timeoutMillis: Long): Future<ByteBuffer> =
        super.onReadAsync(
            position, size, timeoutMillis.coerceAtLeast(FileByteChannel.READ_TIMEOUT_MILLIS)
        )

    override fun onWrite(position: Long, source: ByteBuffer) {
        throw NonWritableChannelException()
    }

    override fun onTruncate(size: Long) {
        throw NonWritableChannelException()
    }

    @Throws(IOException::class)
    override fun onSize(): Long = file.sizeShared()

    override fun onClose() {
        file.detach(reader)
        onClosed()
    }
}

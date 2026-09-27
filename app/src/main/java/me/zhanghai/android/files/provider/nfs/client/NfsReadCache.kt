package me.zhanghai.android.files.provider.nfs.client

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import me.zhanghai.android.files.app.application

/**
 * Blocks of NFS files already read, kept on local storage so that reading them again (seeking back
 * in a video, playing it again, reopening a file) does not cross the network.
 *
 * - A file is identified by its server, path, size and modification time: once the file changes,
 *   its old blocks are never used again (and get evicted like any other).
 * - Blocks are [BLOCK_SIZE] bytes, aligned in the file; the last one may be shorter.
 * - The cache lives in the app's cache directory, so Android may clear it when storage runs low. It
 *   is also kept below [maxSize]: the least recently used blocks go first.
 */
internal object NfsReadCache {
    const val BLOCK_SIZE = 1024 * 1024

    private const val MAX_CACHE_SIZE = 4L * 1024 * 1024 * 1024
    private const val DIRECTORY_NAME = "nfs-read-cache"

    private val directory: File by lazy {
        File(application.cacheDir, DIRECTORY_NAME).apply { mkdirs() }
    }

    /** At most 4 GiB, and never more than a quarter of the free space. */
    private val maxSize: Long
        get() = minOf(MAX_CACHE_SIZE, (directory.usableSpace + totalSize.get()) / 4)

    private val totalSize = AtomicLong(-1)

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NfsReadCache").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /**
     * The key for one version of one file: a hash of the file (server and path) followed by a hash
     * of its version (size, modification and change times, inode), so that all versions of a file
     * can be dropped together (see [invalidate]).
     */
    fun fileKey(authority: Authority, path: ByteArray, stat: io.github.libnfsandroid.NfsStat):
        String =
        pathHash(authority, path) + hash(
            ("${stat.size}:${stat.mtimeSeconds}:${stat.mtimeNanoseconds}:${stat.ctimeSeconds}:" +
                "${stat.ctimeNanoseconds}:${stat.ino}").toByteArray()
        )

    /**
     * Drops every cached version of a file, when Material Files itself is about to change it:
     * a rewrite within the server's timestamp granularity could otherwise keep the same key.
     */
    fun invalidate(authority: Authority, path: ByteArray) {
        val prefix = pathHash(authority, path)
        writer.execute {
            directory.listFiles { _, name -> name.startsWith(prefix) }?.forEach {
                val length = it.length()
                if (it.delete()) {
                    addSize(-length)
                }
            }
        }
    }

    private fun pathHash(authority: Authority, path: ByteArray): String =
        hash(authority.toString().toByteArray() + 0.toByte() + path)

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }.substring(0, 20)

    private fun blockFile(key: String, index: Long) = File(directory, "$key-$index")

    /**
     * Reads the blocks covering `[position, position + length)` into [data]; returns the number
     * of bytes available from the start (shorter at end of file), or -1 if a block is missing.
     * [position] must be block aligned.
     */
    fun read(key: String, position: Long, data: ByteArray, length: Int): Int {
        var done = 0
        var index = position / BLOCK_SIZE
        while (done < length) {
            val file = blockFile(key, index)
            val bytes = try {
                file.readBytes()
            } catch (e: IOException) {
                return -1
            }
            file.setLastModified(System.currentTimeMillis())
            val count = minOf(bytes.size, length - done)
            System.arraycopy(bytes, 0, data, done, count)
            done += count
            if (bytes.size < BLOCK_SIZE) {
                // The last block of the file.
                break
            }
            ++index
        }
        return done
    }

    /** Whether the block at block-aligned [position] is cached. */
    fun contains(key: String, position: Long): Boolean =
        blockFile(key, position / BLOCK_SIZE).exists()

    /**
     * Stores one block read from block-aligned [position] right away, on the calling thread (for
     * blocks fetched only to be cached: they must be there when the reader arrives).
     */
    fun writeNow(key: String, position: Long, data: ByteArray, length: Int, isEndOfFile: Boolean) {
        if (length < BLOCK_SIZE && !isEndOfFile) {
            return
        }
        val file = blockFile(key, position / BLOCK_SIZE)
        if (file.exists()) {
            return
        }
        try {
            val temporary = File(directory, "${file.name}.${Thread.currentThread().id}.tmp")
            temporary.outputStream().use { it.write(data, 0, length.coerceAtMost(BLOCK_SIZE)) }
            if (temporary.renameTo(file)) {
                addSize(length.toLong())
            } else {
                temporary.delete()
            }
            writer.execute { evictIfNeeded() }
        } catch (e: IOException) {
            // A cache: storage full or cleared by the system.
        }
    }

    /**
     * Stores the complete blocks in `data[0, length)` read from block-aligned [position], in the
     * background. [isEndOfFile] says that the data ends at the end of the file, so its last,
     * shorter block is complete too.
     */
    fun write(key: String, position: Long, data: ByteArray, length: Int, isEndOfFile: Boolean) {
        // The queue holds the data: when storage is slower than the network, skip caching rather
        // than let it grow without bound.
        if (pendingWriteBytes.addAndGet(length.toLong()) > MAX_PENDING_WRITE_BYTES) {
            pendingWriteBytes.addAndGet(-length.toLong())
            return
        }
        writer.execute {
            try {
                var offset = 0
                var index = position / BLOCK_SIZE
                while (offset < length) {
                    val count = minOf(BLOCK_SIZE, length - offset)
                    if (count < BLOCK_SIZE && !isEndOfFile) {
                        break
                    }
                    val file = blockFile(key, index)
                    if (!file.exists()) {
                        val temporary = File(directory, "${file.name}.tmp")
                        temporary.outputStream().use { it.write(data, offset, count) }
                        if (temporary.renameTo(file)) {
                            addSize(count.toLong())
                        } else {
                            temporary.delete()
                        }
                    }
                    offset += count
                    ++index
                }
                evictIfNeeded()
            } catch (e: IOException) {
                // A cache: storage full or cleared by the system; nothing to do.
            } finally {
                pendingWriteBytes.addAndGet(-length.toLong())
            }
        }
    }

    private const val MAX_PENDING_WRITE_BYTES = 64L * 1024 * 1024
    private val pendingWriteBytes = AtomicLong()

    private fun addSize(delta: Long) {
        if (totalSize.get() < 0) {
            totalSize.set(directory.listFiles()?.sumOf { it.length() } ?: 0)
        } else {
            totalSize.addAndGet(delta)
        }
    }

    private fun evictIfNeeded() {
        if (totalSize.get() <= maxSize) {
            return
        }
        val files = directory.listFiles() ?: return
        var size = files.sumOf { it.length() }
        val limit = maxSize * 9 / 10
        for (file in files.sortedBy { it.lastModified() }) {
            if (size <= limit) {
                break
            }
            val length = file.length()
            if (file.delete()) {
                size -= length
            }
        }
        totalSize.set(size)
    }
}

package me.zhanghai.android.files.provider.nfs.client

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.defaultSharedPreferences

/**
 * What was read from NFS files, kept on local storage so that reading it again (seeking back in a
 * video, playing it again, reopening a file) does not cross the network.
 *
 * - A file is identified by its server, path, size and modification time: once the file changes,
 *   its old data is never used again (and gets evicted like any other).
 * - Data is kept in blocks of [BLOCK_SIZE] bytes, aligned in the file (the last one may be
 *   shorter), and, for blocks not read completely (the reader jumped elsewhere while their pieces
 *   were arriving), in pieces of [PIECE_SIZE]: everything that came from the network is kept.
 *   Pieces are dropped once their whole block is stored.
 * - Written by the thread that fetched the data, before it is used: a millisecond, against the
 *   network's hundreds; nothing is skipped.
 * - The size is set in the settings (in GB, 0 turns the cache off), never leaving less than
 *   [MIN_FREE_BYTES] free; the least recently used data goes first. It lives in the app's cache
 *   directory, so Android may also clear it when storage runs low.
 */
internal object NfsReadCache {
    const val BLOCK_SIZE = 1024 * 1024
    const val PIECE_SIZE = 128 * 1024
    const val PIECES_PER_BLOCK = BLOCK_SIZE / PIECE_SIZE

    private const val GIB = 1024L * 1024 * 1024
    private const val MIN_FREE_BYTES = GIB
    private const val DIRECTORY_NAME = "nfs-read-cache"

    private val directory: File by lazy {
        File(application.cacheDir, DIRECTORY_NAME).apply { mkdirs() }
    }

    /** The size set in the settings, in GB (0: off). */
    val configuredSizeGb: Int
        get() = try {
            defaultSharedPreferences.getInt(
                application.getString(R.string.pref_key_nfs_read_cache_size_gb),
                application.resources.getInteger(R.integer.pref_default_value_nfs_read_cache_size_gb)
            )
        } catch (e: ClassCastException) {
            application.resources.getInteger(R.integer.pref_default_value_nfs_read_cache_size_gb)
        }

    val isEnabled: Boolean
        get() = configuredSizeGb > 0

    /** The size set, and never so much that less than [MIN_FREE_BYTES] would be left free. */
    private val maxSize: Long
        get() = minOf(
            configuredSizeGb * GIB, directory.usableSpace + totalSize() - MIN_FREE_BYTES
        ).coerceAtLeast(0)

    private val size = AtomicLong(-1)

    /** Evictions and invalidations. */
    private val maintenance = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NfsReadCache").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val isEvictionQueued = AtomicBoolean()

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
        maintenance.execute {
            directory.listFiles { _, name -> name.startsWith(prefix) }?.forEach { deleteFile(it) }
        }
    }

    /** How much is stored, in bytes. */
    fun totalSize(): Long {
        if (size.get() < 0) {
            size.compareAndSet(-1, directory.listFiles()?.sumOf { it.length() } ?: 0)
        }
        return size.get()
    }

    /** Applies a new size from the settings (0 drops everything). */
    fun trim() {
        maintenance.submit { evictIfNeeded() }.get()
    }

    /** Drops everything (from the settings). */
    fun clear() {
        maintenance.submit {
            directory.listFiles()?.forEach { deleteFile(it) }
        }.get()
    }

    private fun pathHash(authority: Authority, path: ByteArray): String =
        hash(authority.toString().toByteArray() + 0.toByte() + path)

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }.substring(0, 20)

    private fun blockFile(key: String, index: Long) = File(directory, "$key-$index")

    private fun pieceFile(key: String, index: Long, piece: Int) =
        File(directory, "$key-$index.$piece")

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

    /** Whether the block at block-aligned [position] is stored whole. */
    fun contains(key: String, position: Long): Boolean =
        blockFile(key, position / BLOCK_SIZE).exists()

    /** Whether some piece of block [index] is stored (see [readPieces]). */
    fun hasPieces(key: String, index: Long): Boolean =
        (0 until PIECES_PER_BLOCK).any { pieceFile(key, index, it).exists() }

    /** The pieces read by [readPieces]: bit i set for piece i, and where the file ends. */
    class Pieces(val mask: Int, val end: Int)

    /**
     * Reads the stored pieces of block [index] into their place in [data], except those in
     * [skip]; the end is [BLOCK_SIZE] unless a piece came back short (the end of the file).
     */
    fun readPieces(key: String, index: Long, data: ByteArray, skip: Int): Pieces {
        var mask = 0
        var end = BLOCK_SIZE
        val now = System.currentTimeMillis()
        for (piece in 0 until PIECES_PER_BLOCK) {
            if (skip and (1 shl piece) != 0) {
                continue
            }
            val file = pieceFile(key, index, piece)
            val bytes = try {
                file.readBytes()
            } catch (e: IOException) {
                continue
            }
            file.setLastModified(now)
            System.arraycopy(bytes, 0, data, piece * PIECE_SIZE, minOf(bytes.size, PIECE_SIZE))
            mask = mask or (1 shl piece)
            if (bytes.size < PIECE_SIZE) {
                end = minOf(end, piece * PIECE_SIZE + bytes.size)
            }
        }
        return Pieces(mask, end)
    }

    /** Reads piece [piece] of block [index] into its place in [data]; its length, or -1. */
    fun readPiece(key: String, index: Long, piece: Int, data: ByteArray): Int {
        val file = pieceFile(key, index, piece)
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            return -1
        }
        file.setLastModified(System.currentTimeMillis())
        val length = minOf(bytes.size, PIECE_SIZE)
        System.arraycopy(bytes, 0, data, piece * PIECE_SIZE, length)
        return length
    }

    /**
     * Stores the block read from block-aligned [position] (`data[0, length)`), on the calling
     * thread. [isEndOfFile] says that the file ends there, so a shorter block is complete.
     */
    fun writeBlock(key: String, position: Long, data: ByteArray, length: Int, isEndOfFile: Boolean) {
        if (length <= 0 || length < BLOCK_SIZE && !isEndOfFile || !isEnabled) {
            return
        }
        val index = position / BLOCK_SIZE
        val file = blockFile(key, index)
        if (!file.exists()) {
            writeFile(file, data, 0, length.coerceAtMost(BLOCK_SIZE))
        }
        // Its pieces are not needed any more.
        for (piece in 0 until PIECES_PER_BLOCK) {
            val pieceFile = pieceFile(key, index, piece)
            if (pieceFile.exists()) {
                deleteFile(pieceFile)
            }
        }
    }

    /**
     * Stores piece [piece] of block [index] (`data[offset, offset + length)`), on the calling
     * thread; a piece shorter than [PIECE_SIZE] must end at the end of the file.
     */
    fun writePiece(key: String, index: Long, piece: Int, data: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || !isEnabled || blockFile(key, index).exists()) {
            return
        }
        val file = pieceFile(key, index, piece)
        if (!file.exists()) {
            writeFile(file, data, offset, length)
        }
    }

    private fun writeFile(file: File, data: ByteArray, offset: Int, length: Int) {
        try {
            // Complete or absent: readers never see half a file.
            val temporary = File(directory, "${file.name}.${Thread.currentThread().id}.tmp")
            temporary.outputStream().use { it.write(data, offset, length) }
            if (temporary.renameTo(file)) {
                addSize(length.toLong())
            } else {
                temporary.delete()
            }
        } catch (e: IOException) {
            // A cache: storage full or cleared by the system.
            return
        }
        if (totalSize() > maxSize && isEvictionQueued.compareAndSet(false, true)) {
            maintenance.execute {
                isEvictionQueued.set(false)
                evictIfNeeded()
            }
        }
    }

    private fun deleteFile(file: File) {
        val length = file.length()
        if (file.delete()) {
            addSize(-length)
        }
    }

    private fun addSize(delta: Long) {
        if (size.get() < 0) {
            totalSize()
        } else {
            size.addAndGet(delta)
        }
    }

    private fun evictIfNeeded() {
        val maxSize = maxSize
        if (totalSize() <= maxSize) {
            return
        }
        val files = directory.listFiles() ?: return
        var total = files.sumOf { it.length() }
        val limit = maxSize * 9 / 10
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= limit) {
                break
            }
            val length = file.length()
            if (file.delete()) {
                total -= length
            }
        }
        size.set(total)
    }
}

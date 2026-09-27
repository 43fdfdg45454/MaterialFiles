package me.zhanghai.android.files.provider.nfs

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java8.nio.file.DirectoryNotEmptyException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.Files
import java8.nio.file.LinkOption
import java8.nio.file.NoSuchFileException
import java8.nio.file.Path
import java8.nio.file.StandardCopyOption
import java8.nio.file.StandardOpenOption
import java8.nio.file.attribute.FileTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import me.zhanghai.android.files.provider.archive.archiver.ArchiveWriter
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.createDirectory
import me.zhanghai.android.files.provider.common.createSymbolicLink
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.provider.common.getLastModifiedTime
import me.zhanghai.android.files.provider.common.getMode
import me.zhanghai.android.files.provider.common.isDirectory
import me.zhanghai.android.files.provider.common.isRegularFile
import me.zhanghai.android.files.provider.common.moveTo
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.provider.common.readAllBytes
import me.zhanghai.android.files.provider.common.readSymbolicLinkByteString
import me.zhanghai.android.files.provider.common.setLastModifiedTime
import me.zhanghai.android.files.provider.common.setMode
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.provider.common.toByteString
import me.zhanghai.android.files.provider.nfs.client.Authority
import me.zhanghai.android.files.provider.nfs.client.Client
import me.zhanghai.android.files.provider.nfs.client.ConnectionOptions
import me.zhanghai.android.files.provider.nfs.client.ConnectionStats
import me.zhanghai.android.files.provider.nfs.client.NfsTls
import me.zhanghai.android.files.storage.NfsServer
import me.zhanghai.android.files.storage.NfsServerAuthenticator
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.github.libnfsandroid.Nfs
import io.github.libnfsandroid.NfsTlsTransport
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import me.zhanghai.android.libarchive.Archive
import android.provider.OpenableColumns
import java.io.FileInputStream
import java.nio.ByteBuffer
import me.zhanghai.android.files.file.fileProviderUri
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * Runs the real provider against the NFS server given by instrumentation arguments:
 * `nfsHost` (default 10.0.2.2, the emulator's host) and `nfsExport`, over NFSv4.2.
 * Everything happens in a fresh `.mf-nfs-test-*` directory, removed at the end.
 */
@RunWith(AndroidJUnit4::class)
class NfsProviderTest {
    private lateinit var server: NfsServer

    /** Reports how long each test took, to balance the CI shards. */
    @get:org.junit.Rule
    val timing = object : org.junit.rules.TestWatcher() {
        private var start = 0L
        private var isSkipped = false

        override fun starting(description: org.junit.runner.Description) {
            start = System.nanoTime()
            isSkipped = false
        }

        override fun skipped(
            e: org.junit.AssumptionViolatedException,
            description: org.junit.runner.Description
        ) {
            isSkipped = true
        }

        override fun finished(description: org.junit.runner.Description) {
            if (isSkipped) {
                return
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(
                0, android.os.Bundle().apply {
                    putString(
                        "timing", String.format(
                            "%s %.0f", description.methodName, (System.nanoTime() - start) / 1e9
                        )
                    )
                }
            )
        }
    }
    private var security = ConnectionOptions.Security.NONE
    private lateinit var connectionsAtStart: ConnectionStats.Snapshot
    private lateinit var root: Path
    private var idleMillis = 0L
    private var dataSize = 12 * 1024 * 1024

    @get:org.junit.Rule
    val testName = org.junit.rules.TestName()

    @Before
    fun setUp() {
        val arguments = InstrumentationRegistry.getArguments()
        // CI runs the tests in parallel groups ("shard" argument), see SHARDS.
        arguments.getString("shard")?.let { shard ->
            assumeTrue(testName.methodName in SHARDS.getValue(shard))
        }
        val host = arguments.getString("nfsHost") ?: "10.0.2.2"
        val export = arguments.getString("nfsExport") ?: "/"
        idleMillis = arguments.getString("idleMillis")?.toLong() ?: 0L
        // Test data size: smaller on slow links, where every MiB costs seconds.
        dataSize = (arguments.getString("dataMiB")?.toInt() ?: 12) * 1024 * 1024
        security = when (arguments.getString("nfsSecurity")) {
            "tls" -> ConnectionOptions.Security.TLS
            "mtls" -> ConnectionOptions.Security.MUTUAL_TLS
            else -> ConnectionOptions.Security.NONE
        }
        if (security != ConnectionOptions.Security.NONE) {
            // The key chain is interactive; the test brings its CA and client certificate.
            NfsTls.sslContextFactory = { options -> testSslContext(options.security) }
        }
        server = NfsServer(
            null, null, Authority(host, Authority.DEFAULT_PORT, export),
            ConnectionOptions(
                0, 0, emptyList(), false, security,
                "test".takeIf { security == ConnectionOptions.Security.MUTUAL_TLS }
            ), ""
        )
        NfsServerAuthenticator.addTransientServer(server)
        me.zhanghai.android.files.provider.nfs.client.FileByteChannel.isStatsEnabled = true
        connectionsAtStart = ConnectionStats.snapshot()
        ConnectionStats.resetPeaks()
        root = server.path.resolve(".mf-nfs-test-" + java.lang.Long.toHexString(Random().nextLong()))
        root.createDirectory()
    }

    @After
    fun tearDown() {
        if (::root.isInitialized && root.exists(LinkOption.NOFOLLOW_LINKS)) {
            deleteRecursively(root)
        }
        // Not set up for tests skipped by the shard assumption.
        if (::server.isInitialized) {
            NfsServerAuthenticator.removeTransientServer(server)
        }
        NfsTls.sslContextFactory = null
    }

    @Test
    fun writeAndReadBack() {
        val data = ByteArray(dataSize / 2 + 7).also { Random(1).nextBytes(it) }
        val file = root.resolve("data.bin")
        file.newOutputStream().use { it.write(data) }
        assertEquals(data.size.toLong(), file.size())
        assertArrayEquals(data, file.readAllBytes())
    }

    /** New files and directories get the current time (exclusive creates used to get garbage). */
    @Test
    fun createdFilesHaveCurrentTime() {
        val now = System.currentTimeMillis()
        val exclusive = root.resolve("exclusive.txt")
        exclusive.newOutputStream(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            .use { it.write(1) }
        val plain = root.resolve("plain.txt")
        plain.newOutputStream().use { it.write(1) }
        val directory = root.resolve("directory")
        directory.createDirectory()
        for (path in listOf(exclusive, plain, directory)) {
            val skewMillis = path.getLastModifiedTime().toMillis() - now
            // The emulator's clock follows the host's, which runs the server.
            assertTrue("$path is ${skewMillis / 1000} s off", Math.abs(skewMillis) < 5 * 60_000)
        }
    }

    /**
     * The access pattern of a video player: the header, the index at the end, back to the start,
     * a seek into the middle and sequential playback from there. Every byte must be right.
     */
    @Test
    fun playerLikeReads() {
        val data = ByteArray(dataSize).also { Random(9).nextBytes(it) }
        val file = root.resolve("video.bin")
        file.newOutputStream().use { it.write(data) }
        file.newByteChannel(StandardOpenOption.READ).use { channel ->
            fun readAt(position: Long, length: Int) {
                channel.position(position)
                val buffer = ByteBuffer.allocate(length)
                while (buffer.hasRemaining() && channel.read(buffer) > 0) {}
                assertArrayEquals(
                    "bytes at $position", data.copyOfRange(position.toInt(),
                        position.toInt() + length), buffer.array()
                )
            }
            val size = data.size.toLong()
            readAt(0, 64 * 1024)
            readAt(size - 200_000L, 200_000)
            readAt(0, 512 * 1024)
            readAt(size * 5 / 12 + 123, data.size / 3)
            readAt(size / 6, 128 * 1024)
        }
    }

    /**
     * Scrubbing in a player: reads at one random position after another through the file
     * provider, without waiting for the previous position to stream. Each jump cancels the read
     * ahead of the last one, which once left the next read without priority (reads timed out
     * over the VPN). Every range is checked (the fixture holds each word's offset); a jump may
     * take at most 3 s and 1 s on average. Going back to the same places after reopening the
     * file must come from the read cache (100 ms on average).
     */
    @Test
    fun scrubbingThroughFileProvider() {
        val fixture = server.path.resolve(".mf-fixtures/scrub.bin")
        assumeTrue(fixture.exists(LinkOption.NOFOLLOW_LINKS))
        val size = fixture.size()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val random = Random(77)
        var slowest = 0L
        var totalMillis = 0L
        val jumps = 40
        // Like VLC: a second descriptor reading on its own, and a third opened and closed a few
        // times, while the first one jumps around.
        val uri = fixture.fileProviderUri
        val stop = java.util.concurrent.atomic.AtomicBoolean()
        var backgroundError: Throwable? = null
        val background = Thread {
            try {
                resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                    val chunk = ByteArray(256 * 1024)
                    var offset = 0L
                    while (!stop.get() && offset < size) {
                        val count = android.system.Os.pread(
                            pfd.fileDescriptor, chunk, 0, chunk.size, offset
                        )
                        if (count <= 0) {
                            break
                        }
                        offset += count
                    }
                }
                repeat(3) {
                    resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                        android.system.Os.pread(pfd.fileDescriptor, ByteArray(4096), 0, 4096, 0)
                    }
                }
            } catch (t: Throwable) {
                backgroundError = t
            }
        }.apply { start() }
        val buffer = ByteArray(64 * 1024)
        /** Reads [buffer] at [position] and checks it; how long it took. */
        fun readAt(pfd: android.os.ParcelFileDescriptor, position: Long, what: String): Long {
            val start = System.nanoTime()
            var done = 0
            while (done < buffer.size) {
                val count = android.system.Os.pread(
                    pfd.fileDescriptor, buffer, done, buffer.size - done, position + done
                )
                assertTrue("read at ${position + done} returned $count", count > 0)
                done += count
            }
            val millis = (System.nanoTime() - start) / 1_000_000
            val words = ByteBuffer.wrap(buffer).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until buffer.size / 8) {
                assertEquals("$what, word at ${position + i * 8}", position + i * 8,
                    words.getLong(i * 8))
            }
            return millis
        }
        val positions = ArrayList<Long>()
        resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            repeat(jumps) { jump ->
                // Word aligned, anywhere in the file.
                val position = Math.floorMod(random.nextLong(), (size - buffer.size) / 8) * 8
                positions += position
                val millis = readAt(pfd, position, "jump $jump")
                slowest = maxOf(slowest, millis)
                totalMillis += millis
                // What a user tolerates after moving the cursor (the link simulates a VPN over
                // mobile data: 100 ms round trips, 0.3 % loss).
                // The first read also opens the file (measured by the streaming test).
                assertTrue("jump $jump to $position took $millis ms", jump == 0 || millis <= 3_000)
            }
        }
        stop.set(true)
        background.join(30_000)
        assertNull("second descriptor: $backgroundError", backgroundError)
        // Back to every place visited, in another order, with the file opened again (as a player
        // reopening a video): all of it was read once, even the places left before their blocks
        // were complete, so all of it comes from the disk cache, without waiting for the network.
        val cacheDirectory = java.io.File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "nfs-read-cache"
        )
        val networkWaitsBefore = me.zhanghai.android.files.provider.nfs.client.FileByteChannel
            .networkWaits.get()
        var revisitMillis = 0L
        var slowestRevisit = 0L
        val slowRevisits = ArrayList<String>()
        resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            // Opening the file (a network round trip or two) is not what is measured.
            readAt(pfd, positions.last(), "reopen")
            positions.reversed().forEachIndexed { revisit, position ->
                val waitsBefore = me.zhanghai.android.files.provider.nfs.client.FileByteChannel
                    .networkWaits.get()
                val millis = readAt(pfd, position, "revisit $revisit")
                val waits = me.zhanghai.android.files.provider.nfs.client.FileByteChannel
                    .networkWaits.get() - waitsBefore
                if (waits > 0) {
                    slowRevisits += "$position ($waits network waits, $millis ms)"
                }
                revisitMillis += millis
                slowestRevisit = maxOf(slowestRevisit, millis)
            }
        }
        val networkWaits = me.zhanghai.android.files.provider.nfs.client.FileByteChannel
            .networkWaits.get() - networkWaitsBefore
        val cacheFiles = cacheDirectory.listFiles().orEmpty()
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0, android.os.Bundle().apply {
                putString(
                    "throughput", String.format(
                        "${security.name.lowercase()}, scrubbing: %d jumps, %d ms each on " +
                            "average, slowest %d ms; back to the same places: %d ms each on " +
                            "average, slowest %d ms, %d waited for the network (read cache: " +
                            "%d blocks, %d pieces, %d MB free)", jumps, totalMillis / jumps,
                        slowest, revisitMillis / jumps, slowestRevisit, networkWaits,
                        cacheFiles.count { !it.name.contains('.') },
                        cacheFiles.count { it.name.contains('.') },
                        cacheDirectory.usableSpace / 1_000_000
                    )
                )
            }
        )
        assertTrue("average jump ${totalMillis / jumps} ms", totalMillis / jumps <= 1_000)
        assertTrue("places read again from the network: $slowRevisits", slowRevisits.isEmpty())
        assertTrue(
            "back to the same places: ${revisitMillis / jumps} ms on average",
            revisitMillis / jumps <= 100 && slowestRevisit <= 500
        )
    }

    // Real use under load: videos of real sizes (CI fixtures on the server, see the workflow),
    // played at a real bitrate, scrubbed, switched, several at once, next to an upload. Every
    // 8-byte word of a fixture holds its offset plus the file's tag in bits 48 and up, so that
    // every byte read is checked and data of another file is caught.
    //
    // The limits are those of a good experience, not of what merely works: a video starts within
    // 1.5 s, a seek shows the picture within 1 s on average and never over 3 s, playback never
    // stalls, closing is instant, and what was seen before comes back at once from the read cache.

    /** A fixture video: its path, size and tag. */
    private class Video(val path: Path, val size: Long, val tag: Long) {
        val name: String
            get() = path.fileName.toString()
    }

    /** `movie-1.bin` (250 MB), `movie-2.bin` (500 MB), `movie-3.bin` (700 MB), episodes 1–3. */
    private fun movie(number: Int) = video("movie-$number.bin", number.toLong())

    /** `episode-1.bin` to `episode-3.bin`, 250 MB each. */
    private fun episode(number: Int) = video("episode-$number.bin", 10L + number)

    private fun video(name: String, tag: Long): Video {
        val path = server.path.resolve(".mf-fixtures/$name")
        assumeTrue("CI fixture $name", path.exists(LinkOption.NOFOLLOW_LINKS))
        return Video(path, path.size(), tag)
    }

    private val resolver
        get() = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver

    private fun open(video: Video): android.os.ParcelFileDescriptor =
        resolver.openFileDescriptor(video.path.fileProviderUri, "r")!!

    /** Reads [length] bytes of [video] at [position] and checks them; milliseconds taken. */
    private fun readVideo(
        pfd: android.os.ParcelFileDescriptor, video: Video, position: Long, length: Int,
        what: String, buffer: ByteArray = ByteArray(length)
    ): Long {
        val start = System.nanoTime()
        var done = 0
        while (done < length) {
            val count = try {
                android.system.Os.pread(
                    pfd.fileDescriptor, buffer, done, length - done, position + done
                )
            } catch (e: android.system.ErrnoException) {
                throw AssertionError(
                    "$what: read of ${video.name} at ${position + done} failed: $e; channel: " +
                        me.zhanghai.android.files.provider.nfs.client.FileByteChannel
                            .lastReadError?.toString(), e
                )
            }
            assertTrue("$what: read of ${video.name} at ${position + done} returned $count",
                count > 0)
            done += count
        }
        val millis = (System.nanoTime() - start) / 1_000_000
        val words = ByteBuffer.wrap(buffer, 0, length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val tag = video.tag shl 48
        for (i in 0 until length / 8) {
            val offset = position + i * 8
            val word = words.getLong(i * 8)
            if (word != offset + tag) {
                throw AssertionError(
                    "$what: word at $offset of ${video.name} is ${word and 0xFFFFFFFFFFFFL} of " +
                        "file tag ${word ushr 48}"
                )
            }
        }
        return millis
    }

    /** A seek: the first 64 KB at [position] (what shows the picture); milliseconds. */
    private fun seek(pfd: android.os.ParcelFileDescriptor, video: Video, position: Long,
        what: String): Long =
        readVideo(pfd, video, position / 8 * 8, SEEK_BYTES, what)

    /** How a stretch of playback went. */
    private class Playback(val stalls: Int, val stallMillis: Long)

    /**
     * Plays [seconds] of [video] from [position] as a player does at [PLAYBACK_BYTES_PER_SECOND]
     * (a 1080p movie): reads of 256 KB, never more than [PLAYER_BUFFER_MILLIS] ahead of the
     * picture. A read that arrives later than that buffer allows is a stall (the picture
     * freezes); playback then resumes from there.
     */
    private fun play(
        pfd: android.os.ParcelFileDescriptor, video: Video, position: Long, seconds: Int,
        what: String
    ): Playback {
        val chunk = 256 * 1024
        val chunkMillis = chunk * 1000L / PLAYBACK_BYTES_PER_SECOND
        val chunks = (seconds * 1000L / chunkMillis).toInt()
        val from = minOf(position, video.size - chunks.toLong() * chunk) / 8 * 8
        val buffer = ByteArray(chunk)
        var clock = System.nanoTime() / 1_000_000
        var stalls = 0
        var stallMillis = 0L
        for (k in 0 until chunks) {
            // Not further ahead than the player's buffer.
            val earliest = clock + k * chunkMillis - PLAYER_BUFFER_MILLIS
            val now = System.nanoTime() / 1_000_000
            if (now < earliest) {
                Thread.sleep(earliest - now)
            }
            readVideo(pfd, video, from + k.toLong() * chunk, chunk, what, buffer)
            val due = clock + k * chunkMillis + PLAYER_BUFFER_MILLIS
            val arrived = System.nanoTime() / 1_000_000
            if (arrived > due) {
                ++stalls
                stallMillis += arrived - due
                clock += arrived - due
            }
        }
        return Playback(stalls, stallMillis)
    }

    /** Timings of one kind of operation, checked against limits at the end. */
    private class Timings(val name: String) {
        private val values = java.util.Collections.synchronizedList(ArrayList<Long>())

        fun add(millis: Long) {
            values += millis
        }

        val average: Long
            get() = synchronized(values) { if (values.isEmpty()) 0 else values.sum() / values.size }

        val slowest: Long
            get() = synchronized(values) { values.maxOrNull() ?: 0 }

        override fun toString(): String =
            "$name ${values.size}× avg $average ms max $slowest ms"

        fun check(maxAverage: Long, maxSlowest: Long) {
            assertTrue("$this (limits: avg $maxAverage ms, max $maxSlowest ms)",
                average <= maxAverage && slowest <= maxSlowest)
        }
    }

    /** Stalls of all playbacks of a test; none allowed. */
    private class Stalls {
        private val stalls = java.util.concurrent.atomic.AtomicInteger()
        private val millis = java.util.concurrent.atomic.AtomicLong()
        private val playbacks = java.util.concurrent.atomic.AtomicInteger()

        fun add(playback: Playback) {
            playbacks.incrementAndGet()
            stalls.addAndGet(playback.stalls)
            millis.addAndGet(playback.stallMillis)
        }

        override fun toString(): String =
            "${playbacks.get()} playbacks with ${stalls.get()} stalls (${millis.get()} ms)"

        fun check() {
            assertEquals("stalls in $this", 0, stalls.get())
        }
    }

    private fun report(test: String, vararg parts: Any) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0, android.os.Bundle().apply {
                putString(
                    "throughput",
                    "${security.name.lowercase()}, $test: " + parts.joinToString("; ") +
                        "; " + connectionSummary()
                )
            }
        )
    }

    /** At most this many connections per export (the test server uses the default settings). */
    private val connectionLimit =
        ConnectionOptions.DEFAULT_MAX_CONNECTIONS + 1 + Client.CONTEXTS_BEYOND_FILE

    /**
     * How the connections fared during the test: saturation (requests refused at the export's
     * limit, files held to their share, reads that waited with every connection busy) says where
     * more connections would help; waits with idle connections say the link or the server is
     * the limit instead.
     */
    private fun connectionSummary(): String {
        val delta = ConnectionStats.snapshot() - connectionsAtStart
        return "$delta; peak ${ConnectionStats.peakTotal.get()} connections " +
            "(${ConnectionStats.peakInUse.get()} bound to files) of $connectionLimit " +
            "(+${Client.RESERVED_CONTEXTS_OVER_LIMIT} for seeks)"
    }

    /**
     * Once every file is closed, no connection stays bound to one (none leaked or reserved),
     * none exceeds the limit, and none broke or failed to open under this load.
     */
    private fun checkConnections() {
        val deadline = System.nanoTime() + 5_000_000_000L
        // Closing lets running calls finish (a call cannot be cancelled): a moment.
        while (Client.connectionCounts().second > 0 && System.nanoTime() < deadline) {
            Thread.sleep(100)
        }
        val (total, bound) = Client.connectionCounts()
        val delta = ConnectionStats.snapshot() - connectionsAtStart
        assertEquals("connections still bound to files 5 s after closing all ($total open)", 0,
            bound)
        assertTrue("peak ${ConnectionStats.peakTotal.get()} connections over the limit",
            ConnectionStats.peakTotal.get() <=
                connectionLimit + Client.RESERVED_CONTEXTS_OVER_LIMIT)
        assertEquals("connections that broke ($delta)", 0, delta.broke)
        assertEquals("connections that could not open the file ($delta)", 0, delta.openFailed)
    }

    /** Word-aligned, anywhere a seek can read [SEEK_BYTES]. */
    private fun randomPosition(random: Random, video: Video): Long =
        Math.floorMod(random.nextLong(), (video.size - SEEK_BYTES) / 8) * 8

    /** Where the reads of [path] came from (see FileByteChannel.ReadStats). */
    private fun stats(path: Path) =
        me.zhanghai.android.files.provider.nfs.client.FileByteChannel.readStats(
            (path as NfsPath).remotePath.toString()
        )

    /** Starts from an empty read cache: first visits must then come from the network. */
    private fun clearReadCache() {
        me.zhanghai.android.files.provider.nfs.client.NfsReadCache.clear()
    }

    /**
     * Cache hits and misses expected by a test, checked at the end: a place seen before must
     * come from the cache (memory or disk) without waiting for the network, and a place never
     * read (nor read ahead) must come from the network, never from data cached for something
     * else.
     */
    private class CacheExpectations {
        private val hits = java.util.concurrent.atomic.AtomicInteger()
        private val misses = java.util.concurrent.atomic.AtomicInteger()
        private val wrong = java.util.concurrent.ConcurrentLinkedQueue<String>()

        fun record(what: String, expectHit: Boolean, waitedForNetwork: Boolean, millis: Long) {
            if (expectHit) hits.incrementAndGet() else misses.incrementAndGet()
            if (expectHit == waitedForNetwork) {
                wrong += if (expectHit) {
                    "$what: seen before, but waited for the network ($millis ms)"
                } else {
                    "$what: never read, but did not wait for the network"
                }
            }
        }

        override fun toString(): String =
            "cache: ${hits.get()} expected hits, ${misses.get()} expected misses, " +
                "${wrong.size} wrong"

        fun check() {
            assertTrue("$this: ${wrong.joinToString("; ")}", wrong.isEmpty())
        }
    }

    /** A seek expected to be a cache hit ([expectHit]) or miss; milliseconds. */
    private fun seekExpecting(
        pfd: android.os.ParcelFileDescriptor, video: Video, position: Long, what: String,
        expectHit: Boolean, cache: CacheExpectations
    ): Long {
        val stats = stats(video.path)
        val before = stats.networkWaits.get()
        val millis = seek(pfd, video, position, what)
        cache.record("${video.name} $what at $position", expectHit,
            stats.networkWaits.get() > before, millis)
        return millis
    }

    /**
     * Whether nothing around [position] was read or could have been read ahead yet: no earlier
     * place within 1 MB before it or 64 MB after it (the most read ahead of a place in memory).
     */
    private fun isFresh(position: Long, seen: Collection<Long>): Boolean =
        seen.none { position in it - 1024 * 1024..it + 64L * 1024 * 1024 }

    /**
     * A whole 500 MB movie: opened as VLC does (the start, the index at the end, the start
     * again), read through as fast as the link allows, then again from the read cache.
     */
    @Test
    fun movieStreamedWhole() {
        val video = movie(2)
        clearReadCache()
        val opens = Timings("open+first 64 KB")
        val probes = Timings("index and back")
        val reads = Timings("1 MB reads")
        val cache = CacheExpectations()
        var networkMBps = 0.0
        var cacheMBps = 0.0
        var secondPassNetworkWaits = 0
        for (pass in 0..1) {
            val start = System.nanoTime()
            val waitsBefore = stats(video.path).networkWaits.get()
            open(video).use { pfd ->
                // The first pass finds nothing cached; the second, all of it.
                seekExpecting(pfd, video, 0, "open, pass $pass", pass == 1, cache)
                opens.add((System.nanoTime() - start) / 1_000_000)
                probes.add(readVideo(pfd, video, video.size - 1024 * 1024, 1024 * 1024, "index"))
                probes.add(seek(pfd, video, 0, "back to the start"))
                val buffer = ByteArray(1024 * 1024)
                val readStart = System.nanoTime()
                var position = 0L
                while (position < video.size) {
                    val length = minOf(buffer.size.toLong(), video.size - position).toInt()
                    val millis = readVideo(pfd, video, position, length, "pass $pass", buffer)
                    if (pass == 0) {
                        reads.add(millis)
                    }
                    position += length
                }
                val mbps = video.size / ((System.nanoTime() - readStart) / 1e9) / 1e6
                if (pass == 0) networkMBps = mbps else cacheMBps = mbps
            }
            if (pass == 1) {
                secondPassNetworkWaits = stats(video.path).networkWaits.get() - waitsBefore
            }
        }
        report("500 MB movie read whole", opens, probes, reads, cache,
            "second pass waited for the network $secondPassNetworkWaits times",
            String.format("%.1f MB/s from the server, %.1f MB/s again (read cache)", networkMBps,
                cacheMBps))
        opens.check(1_500, 3_000)
        probes.check(1_000, 3_000)
        // Faster than any movie plays, with no read waiting long enough to freeze the picture.
        assertTrue("from the server: $networkMBps MB/s", networkMBps >= 2.0)
        reads.check(1_000, 3_000)
        assertTrue("again from the read cache: $cacheMBps MB/s", cacheMBps >= 30.0)
        cache.check()
        // Every byte was read once: the second pass never waits for the network.
        assertEquals("second pass, reads that waited for the network", 0, secondPassNetworkWaits)
        checkConnections()
    }

    /**
     * Looking for a scene in a 700 MB movie: the start, the end, the middle, a quarter, three
     * quarters, then 30 random places, each played 3 s; then back to places seen before.
     */
    @Test
    fun sceneSearch() {
        val video = movie(3)
        clearReadCache()
        val random = Random(5005)
        val seeks = Timings("seeks")
        val again = Timings("back to places seen (cache)")
        val stalls = Stalls()
        val cache = CacheExpectations()
        val seen = ArrayList<Long>()
        open(video).use { pfd ->
            val size = video.size
            val places = listOf(0L, size - 2 * 1024 * 1024, size / 2, size / 4, size * 3 / 4) +
                List(30) { randomPosition(random, video) }
            places.forEachIndexed { i, place ->
                val position = place / 8 * 8
                val millis = if (isFresh(position, seen)) {
                    seekExpecting(pfd, video, position, "seek $i", false, cache)
                } else {
                    seek(pfd, video, position, "seek $i")
                }
                seeks.add(millis)
                stalls.add(play(pfd, video, position, 3, "play after seek $i"))
                seen += position
            }
            for ((i, position) in seen.shuffled(random).take(10).withIndex()) {
                again.add(seekExpecting(pfd, video, position, "back $i", true, cache))
            }
        }
        report("scene search in a 700 MB movie", seeks, stalls, again, cache)
        seeks.check(1_000, 3_000)
        stalls.check()
        again.check(100, 500)
        cache.check()
        checkConnections()
    }

    /**
     * Burst of seeks, then settle: dragging the cursor through the whole movie (60 seeks back to
     * back), then letting go at a random place, which must show the picture as fast as a single
     * seek and play without stalls; three times.
     */
    @Test
    fun seekBurstThenSettle() {
        val video = movie(2)
        clearReadCache()
        val random = Random(6006)
        val burst = Timings("seeks while dragging")
        val settle = Timings("seek where released")
        val again = Timings("back to them after reopening (cache)")
        val stalls = Stalls()
        val cache = CacheExpectations()
        val seen = ArrayList<Long>()
        open(video).use { pfd ->
            repeat(3) { round ->
                // Dragging: steadily forward or backward, with a little noise.
                val from = randomPosition(random, video)
                val to = randomPosition(random, video)
                for (i in 0 until 60) {
                    val position = from + (to - from) * i / 60 +
                        (random.nextInt(2 * 1024 * 1024) - 1024 * 1024)
                    val place = position.coerceIn(0, video.size - SEEK_BYTES) / 8 * 8
                    burst.add(seek(pfd, video, place, "round $round drag $i"))
                    seen += place
                }
                val release = randomPosition(random, video)
                settle.add(seek(pfd, video, release, "round $round release"))
                stalls.add(play(pfd, video, release, 10, "round $round play"))
                seen += release
            }
        }
        // Reopened (nothing left in memory): the places dragged over and released at come from
        // the disk cache, even those left before their blocks were complete.
        open(video).use { pfd ->
            for ((i, position) in seen.shuffled(random).take(20).withIndex()) {
                again.add(seekExpecting(pfd, video, position, "back $i", true, cache))
            }
        }
        report("seek bursts then settle in a 500 MB movie", burst, settle, stalls, again, cache)
        burst.check(1_000, 3_000)
        settle.check(1_000, 3_000)
        stalls.check()
        again.check(100, 500)
        cache.check()
        checkConnections()
    }

    /**
     * An evening of episodes (3 × 250 MB): each opened, played 6 s from the start, skipped to
     * the credits, played 3 s and closed; then the first one again where it was left.
     */
    @Test
    fun episodeMarathon() {
        clearReadCache()
        val opens = Timings("open+first 64 KB")
        val seeks = Timings("seek to the credits")
        val closes = Timings("close")
        val again = Timings("first episode again (cache)")
        val stalls = Stalls()
        val cache = CacheExpectations()
        for (number in 1..3) {
            val video = episode(number)
            val start = System.nanoTime()
            val pfd = open(video)
            try {
                seekExpecting(pfd, video, 0, "open", false, cache)
                opens.add((System.nanoTime() - start) / 1_000_000)
                // 6 s: under the 8 MB after which the engine buffers 256 MB ahead on disk, so
                // that the middle stays never read (a miss expected below).
                stalls.add(play(pfd, video, 0, 6, "episode $number start"))
                val credits = video.size - 20L * 1024 * 1024
                seeks.add(seekExpecting(pfd, video, credits, "credits", false, cache))
                stalls.add(play(pfd, video, credits, 3, "episode $number credits"))
            } finally {
                val closeStart = System.nanoTime()
                pfd.close()
                closes.add((System.nanoTime() - closeStart) / 1_000_000)
            }
        }
        val first = episode(1)
        val start = System.nanoTime()
        open(first).use { pfd ->
            seekExpecting(pfd, first, 0, "reopened", true, cache)
            opens.add((System.nanoTime() - start) / 1_000_000)
            // Within the 6 s played at first, and the credits: from the disk cache.
            for (position in listOf(3L * 1024 * 1024, 5L * 1024 * 1024,
                first.size - 20L * 1024 * 1024)) {
                again.add(seekExpecting(pfd, first, position, "again", true, cache))
            }
            stalls.add(play(pfd, first, 3L * 1024 * 1024, 3, "episode 1 again"))
            // Never played: from the network.
            seekExpecting(pfd, first, first.size / 2, "middle, never played", false, cache)
        }
        report("3 episodes one after another", opens, seeks, closes, stalls, again, cache)
        opens.check(1_500, 3_000)
        seeks.check(1_000, 3_000)
        closes.check(300, 1_000)
        stalls.check()
        again.check(100, 500)
        cache.check()
        checkConnections()
    }

    /** Three players at once, each scrubbing and playing its own movie. */
    @Test
    fun threePlayersAtOnce() {
        clearReadCache()
        val opens = Timings("open+first 64 KB")
        val seeks = Timings("seeks")
        val stalls = Stalls()
        val cache = CacheExpectations()
        runInParallel(3) { thread ->
            val video = movie(thread + 1)
            val random = Random(7000L + thread)
            val start = System.nanoTime()
            val seen = ArrayList<Long>()
            open(video).use { pfd ->
                seekExpecting(pfd, video, 0, "open", false, cache)
                opens.add((System.nanoTime() - start) / 1_000_000)
                stalls.add(play(pfd, video, 0, 3, "${video.name} start"))
                seen += 0L
                repeat(10) { i ->
                    val position = randomPosition(random, video)
                    seeks.add(if (isFresh(position, seen)) {
                        seekExpecting(pfd, video, position, "seek $i", false, cache)
                    } else {
                        seek(pfd, video, position, "${video.name} seek $i")
                    })
                    stalls.add(play(pfd, video, position, 3, "${video.name} play $i"))
                    seen += position
                }
                for (position in seen.shuffled(random).take(3)) {
                    seekExpecting(pfd, video, position, "back", true, cache)
                }
            }
        }
        report("3 players at once", opens, seeks, stalls, cache)
        opens.check(2_000, 4_000)
        seeks.check(1_500, 4_000)
        stalls.check()
        cache.check()
        checkConnections()
    }

    /**
     * Six files seeking at once, back to back without playing (the heaviest scrubbing): the
     * connections are shared among the files and none may starve.
     */
    @Test
    fun sixFilesSeekingAtOnce() {
        clearReadCache()
        val seeks = Timings("seeks")
        val cache = CacheExpectations()
        runInParallel(6) { thread ->
            val video = if (thread < 3) movie(thread + 1) else episode(thread - 2)
            val random = Random(8000L + thread)
            val seen = ArrayList<Long>()
            open(video).use { pfd ->
                repeat(30) { i ->
                    val position = when (i % 10) {
                        0 -> 0L
                        4 -> video.size - SEEK_BYTES
                        7 -> video.size / 2 / 8 * 8
                        else -> randomPosition(random, video)
                    }
                    // The start, the end and the middle come back every 10 seeks: hits then.
                    val millis = when {
                        position in seen -> seekExpecting(pfd, video, position, "seek $i", true,
                            cache)
                        isFresh(position, seen) -> seekExpecting(pfd, video, position,
                            "seek $i", false, cache)
                        else -> seek(pfd, video, position, "${video.name} seek $i")
                    }
                    seeks.add(millis)
                    seen += position
                }
            }
        }
        report("6 files seeking at once", seeks, cache)
        seeks.check(1_500, 4_000)
        cache.check()
        checkConnections()
    }

    /**
     * One video read by several descriptors at once, as VLC does: the player playing a minute,
     * the demuxer seeking around, and a metadata reader reopening it for the start and the end.
     * (The descriptors share the file's statistics, so cache hits are not told apart here; the
     * other tests check them.)
     */
    @Test
    fun oneVideoManyDescriptors() {
        val video = movie(2)
        clearReadCache()
        val seeks = Timings("seeks of the second descriptor")
        val probes = Timings("open+start+end of the third")
        val stalls = Stalls()
        val stop = java.util.concurrent.atomic.AtomicBoolean()
        runInParallel(3) { thread ->
            when (thread) {
                0 -> try {
                    open(video).use { pfd ->
                        seek(pfd, video, 100L * 1024 * 1024, "player")
                        stalls.add(play(pfd, video, 100L * 1024 * 1024, 60, "player"))
                    }
                } finally {
                    stop.set(true)
                }
                1 -> {
                    val random = Random(9009)
                    open(video).use { pfd ->
                        var i = 0
                        while (!stop.get() && i < 40) {
                            seeks.add(seek(pfd, video, randomPosition(random, video), "seek $i"))
                            ++i
                            Thread.sleep(500)
                        }
                    }
                }
                else -> repeat(5) { i ->
                    val start = System.nanoTime()
                    open(video).use { pfd ->
                        seek(pfd, video, 0, "metadata $i start")
                        readVideo(pfd, video, video.size - 1024 * 1024, 1024 * 1024,
                            "metadata $i end")
                    }
                    probes.add((System.nanoTime() - start) / 1_000_000)
                    Thread.sleep(5_000)
                }
            }
        }
        report("one video, 3 descriptors", stalls, seeks, probes)
        stalls.check()
        seeks.check(1_000, 3_000)
        probes.check(2_000, 4_000)
        checkConnections()
    }

    /**
     * A player switching files as fast as it can (open, read a random place, close, 30 times)
     * while another movie plays: connections released at every close go back to the pool, none
     * stay reserved or leak, and the playing movie never stalls.
     */
    @Test
    fun rapidOpenCloseWhilePlaying() {
        clearReadCache()
        val random = Random(1111)
        val cycles = Timings("open+seek+close")
        val finalOpen = Timings("open afterwards")
        val stalls = Stalls()
        val cache = CacheExpectations()
        val seen = HashMap<String, MutableSet<Long>>()
        val playing = movie(1)
        val others = listOf(movie(2), movie(3), episode(1), episode(2), episode(3))
        runInParallel(2) { thread ->
            if (thread == 0) {
                open(playing).use { pfd ->
                    seek(pfd, playing, 0, "playing movie")
                    stalls.add(play(pfd, playing, 0, 45, "playing movie"))
                }
            } else {
                repeat(30) { cycle ->
                    val video = others[random.nextInt(others.size)]
                    val start = System.nanoTime()
                    open(video).use { pfd ->
                        val position = when (cycle % 3) {
                            0 -> 0L
                            1 -> video.size - SEEK_BYTES
                            else -> randomPosition(random, video)
                        }
                        // The same place of the same file again: from the cache, although the
                        // file was closed in between. A new one: from the network.
                        val places = seen.getOrPut(video.name) { HashSet() }
                        when {
                            position in places -> seekExpecting(pfd, video, position,
                                "cycle $cycle", true, cache)
                            isFresh(position, places) -> seekExpecting(pfd, video, position,
                                "cycle $cycle", false, cache)
                            else -> seek(pfd, video, position, "cycle $cycle ${video.name}")
                        }
                        places += position
                    }
                    cycles.add((System.nanoTime() - start) / 1_000_000)
                }
            }
        }
        val video = episode(3)
        val start = System.nanoTime()
        open(video).use { pfd -> seek(pfd, video, video.size / 3, "afterwards") }
        finalOpen.add((System.nanoTime() - start) / 1_000_000)
        report("rapid open/close while a movie plays", cycles, stalls, finalOpen, cache)
        cycles.check(1_500, 3_000)
        stalls.check()
        finalOpen.check(1_500, 1_500)
        cache.check()
        checkConnections()
    }

    /**
     * Seeking and playing a movie while uploading a file to the same server: the upload's
     * connections must not starve the player, and the upload must arrive intact.
     */
    @Test
    fun seeksWhileUploading() {
        val arguments = InstrumentationRegistry.getArguments()
        val uploadBytes = (arguments.getString("uploadMiB")?.toInt() ?: 64) * 1024 * 1024
        val video = movie(3)
        clearReadCache()
        val seeks = Timings("seeks")
        val stalls = Stalls()
        val cache = CacheExpectations()
        val seen = java.util.Collections.synchronizedList(ArrayList<Long>())
        val target = root.resolve("upload.bin")
        val done = java.util.concurrent.atomic.AtomicBoolean()
        var uploadMBps = 0.0
        val chunk = ByteArray(1024 * 1024)
        runInParallel(2) { thread ->
            if (thread == 0) {
                try {
                    val start = System.nanoTime()
                    target.newOutputStream().use { output ->
                        var written = 0
                        while (written < uploadBytes) {
                            // Every word its offset: checked afterwards.
                            val words = ByteBuffer.wrap(chunk)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until chunk.size / 8) {
                                words.putLong(i * 8, written.toLong() + i * 8)
                            }
                            output.write(chunk)
                            written += chunk.size
                        }
                    }
                    uploadMBps = uploadBytes / ((System.nanoTime() - start) / 1e9) / 1e6
                } finally {
                    done.set(true)
                }
            } else {
                val random = Random(1212)
                open(video).use { pfd ->
                    var i = 0
                    // For as long as the upload lasts, and at least 10 seeks.
                    while (!done.get() || i < 10) {
                        val position = randomPosition(random, video)
                        seeks.add(seek(pfd, video, position, "seek $i"))
                        stalls.add(play(pfd, video, position, 3, "play $i"))
                        seen += position
                        ++i
                    }
                    for (position in seen.take(5)) {
                        seekExpecting(pfd, video, position, "back after the upload", true, cache)
                    }
                }
            }
        }
        assertEquals(uploadBytes.toLong(), target.size())
        // Spot checks of the upload: the start, the middle and the end. Written, never read:
        // they come from the server (a writer's data is never cached).
        val uploadWaits = stats(target).networkWaits.get()
        target.newByteChannel(StandardOpenOption.READ).use { channel ->
            for (position in listOf(0L, uploadBytes / 2L, uploadBytes - 65_536L)) {
                val buffer = ByteBuffer.allocate(65_536)
                channel.position(position)
                while (buffer.hasRemaining() && channel.read(buffer) > 0) {}
                buffer.flip()
                buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until 65_536 / 8) {
                    assertEquals("uploaded word", position + i * 8, buffer.getLong(i * 8))
                }
            }
        }
        cache.record("upload read back", false, stats(target).networkWaits.get() > uploadWaits, 0)
        report("seeks while uploading ${uploadBytes / 1024 / 1024} MiB", seeks, stalls, cache,
            String.format("upload %.1f MB/s", uploadMBps))
        seeks.check(1_000, 3_000)
        stalls.check()
        cache.check()
        checkConnections()
    }

    /**
     * A file changed on the server is never served from what was cached of its old content: read
     * (cached), rewritten with other content of the same size, read again: every read of the
     * new content comes from the network and is the new content.
     */
    @Test
    fun readCacheFollowsChanges() {
        clearReadCache()
        val size = 8 * 1024 * 1024
        val file = root.resolve("changing.bin")
        fun write(tag: Long) {
            val data = ByteArray(size)
            val words = ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until size / 8) {
                words.putLong(i * 8, (tag shl 48) + i * 8L)
            }
            file.newOutputStream().use { it.write(data) }
        }
        val cache = CacheExpectations()
        val places = listOf(0L, 3L * 1024 * 1024 + 4096, size - 65_536L)
        write(21)
        for (pass in 0..1) {
            val video = Video(file, size.toLong(), 21)
            open(video).use { pfd ->
                for (position in places) {
                    seekExpecting(pfd, video, position, "version 1 pass $pass", pass == 1, cache)
                }
            }
        }
        write(22)
        val changed = Video(file, size.toLong(), 22)
        open(changed).use { pfd ->
            for (position in places) {
                // readVideo checks the tag: old data would fail here.
                seekExpecting(pfd, changed, position, "version 2", false, cache)
            }
        }
        report("read cache after a change", cache)
        cache.check()
        checkConnections()
    }

    /** With the read cache off in the settings, nothing comes from it: every place is a miss. */
    @Test
    fun readCacheOffInSettings() {
        clearReadCache()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = context.getString(me.zhanghai.android.files.R.string.pref_key_nfs_read_cache_size_gb)
        val preferences = me.zhanghai.android.files.app.defaultSharedPreferences
        val hadValue = preferences.contains(key)
        val oldValue = preferences.getInt(key, 0)
        preferences.edit().putInt(key, 0).commit()
        val cache = CacheExpectations()
        try {
            val video = episode(2)
            val places = listOf(10L * 1024 * 1024, 100L * 1024 * 1024, video.size - SEEK_BYTES)
            for (pass in 0..1) {
                open(video).use { pfd ->
                    for (position in places) {
                        seekExpecting(pfd, video, position, "cache off, pass $pass", false, cache)
                    }
                }
            }
            val files = java.io.File(context.cacheDir, "nfs-read-cache").listFiles().orEmpty()
            assertTrue("cache off, yet ${files.size} files stored", files.isEmpty())
        } finally {
            preferences.edit().apply {
                if (hadValue) putInt(key, oldValue) else remove(key)
            }.commit()
        }
        report("read cache off", cache)
        cache.check()
        checkConnections()
    }

    /** Runs [block] on [count] threads at once; rethrows the first failure. */
    private fun runInParallel(count: Int, block: (Int) -> Unit) {
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = List(count) { index ->
            Thread {
                try {
                    block(index)
                } catch (t: Throwable) {
                    errors += t
                }
            }.apply { start() }
        }
        threads.forEach { it.join(15 * 60_000) }
        errors.peek()?.let { throw AssertionError("${errors.size} thread(s) failed: $it", it) }
    }

    /**
     * What another app (VLC, a music player) does with a file shared by Material Files: query its
     * name and size, open it through the file provider, probe it (open and close a few times),
     * read the header and the end, seek around, read the same file from two descriptors at once
     * (player plus metadata extractor) and play it through.
     */
    @Test
    fun externalAppReadsThroughFileProvider() {
        val data = ByteArray(dataSize / 2 + 11).also { Random(21).nextBytes(it) }
        val file = root.resolve("song.flac")
        file.newOutputStream().use { it.write(data) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val uri = file.fileProviderUri

        resolver.query(uri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(
                "song.flac",
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            )
            assertEquals(
                data.size.toLong(),
                cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))
            )
        }
        assertTrue(resolver.getType(uri)!!.startsWith("audio/"))
        repeat(3) { resolver.openFileDescriptor(uri, "r")!!.close() }

        fun readAt(channel: java.nio.channels.FileChannel, position: Long, length: Int) {
            val buffer = ByteBuffer.allocate(length)
            var offset = position
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer, offset)
                if (count <= 0) {
                    break
                }
                offset += count
            }
            assertArrayEquals(
                "bytes at $position",
                data.copyOfRange(position.toInt(), position.toInt() + length), buffer.array()
            )
        }
        resolver.openFileDescriptor(uri, "r")!!.use { player ->
            assertEquals(data.size.toLong(), player.statSize)
            FileInputStream(player.fileDescriptor).channel.use { channel ->
                readAt(channel, 0, 4096)
                readAt(channel, data.size - 65_536L, 65_536)
                val extractor = Thread {
                    resolver.openFileDescriptor(uri, "r")!!.use { second ->
                        FileInputStream(second.fileDescriptor).channel.use {
                            readAt(it, 0, 256 * 1024)
                            readAt(it, data.size - 8192L, 8192)
                        }
                    }
                }
                var extractorError: Throwable? = null
                extractor.setUncaughtExceptionHandler { _, e -> extractorError = e }
                extractor.start()
                readAt(channel, data.size / 2L, data.size / 6)
                var position = 0L
                while (position < data.size) {
                    val length = minOf(128 * 1024L, data.size - position).toInt()
                    readAt(channel, position, length)
                    position += length
                }
                extractor.join(60_000)
                extractorError?.let { throw AssertionError("second descriptor", it) }
            }
        }
    }

    /**
     * Playback as a video player does it: sequential reads of the file through the file provider
     * (Android's FUSE proxy, like VLC), reported in MB/s, first from the server and then again,
     * which the local read cache should serve.
     */
    @Test
    fun streamingThroughFileProvider() {
        val arguments = InstrumentationRegistry.getArguments()
        // CI puts the file on the server beforehand (.mf-fixtures), so that it does not have to
        // cross the slow link twice; otherwise it is written first.
        val fixture = server.path.resolve(".mf-fixtures/stream.bin")
        val (file, size, expected) = if (fixture.exists(LinkOption.NOFOLLOW_LINKS)) {
            Triple(
                fixture, fixture.size().toInt(),
                String(fixture.resolveSibling("stream.bin.crc32").readAllBytes()).trim().toLong()
            )
        } else {
            val size = (arguments.getString("streamMiB")?.toInt() ?: 16) * 1024 * 1024
            val data = ByteArray(size).also { Random(33).nextBytes(it) }
            val file = root.resolve("video.mkv")
            file.newOutputStream().use { it.write(data) }
            Triple(file, size, java.util.zip.CRC32().apply { update(data) }.value)
        }
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val uri = file.fileProviderUri
        // Overall MB/s, seconds to the first bytes, MB/s over the second half (steady state).
        fun play(): Triple<Double, Double, Double> {
            val start = System.nanoTime()
            var firstBytes = 0L
            var halfway = 0L
            val crc = java.util.zip.CRC32()
            var total = 0L
            resolver.openFileDescriptor(uri, "r")!!.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val count = try {
                            input.read(buffer)
                        } catch (e: java.io.IOException) {
                            // The file provider reports only EIO; say what failed underneath.
                            throw java.io.IOException(
                                "$e after $total bytes; channel: " + me.zhanghai.android.files
                                    .provider.nfs.client.FileByteChannel.lastReadError
                                    ?.stackTraceToString()?.lines()?.take(8)?.joinToString(" | "),
                                e
                            )
                        }
                        if (count < 0) {
                            break
                        }
                        if (firstBytes == 0L) {
                            firstBytes = System.nanoTime()
                        }
                        crc.update(buffer, 0, count)
                        total += count
                        if (halfway == 0L && total >= size / 2) {
                            halfway = System.nanoTime()
                        }
                    }
                }
            }
            val end = System.nanoTime()
            assertEquals(size.toLong(), total)
            assertEquals(expected, crc.value)
            return Triple(
                size / ((end - start) / 1e9) / 1e6, (firstBytes - start) / 1e9,
                (size - size / 2) / ((end - halfway).coerceAtLeast(1) / 1e9) / 1e6
            )
        }
        val connectionsBefore = me.zhanghai.android.files.provider.nfs.client.FileByteChannel
            .extraConnectionsOpened.get()
        val hedgedBefore = me.zhanghai.android.files.provider.nfs.client.FileByteChannel
            .hedgedBlocks.get()
        val first = play()
        val extraConnections = me.zhanghai.android.files.provider.nfs.client.FileByteChannel
            .extraConnectionsOpened.get() - connectionsBefore
        val hedged = me.zhanghai.android.files.provider.nfs.client.FileByteChannel
            .hedgedBlocks.get() - hedgedBefore
        val again = play()
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0, android.os.Bundle().apply {
                putString(
                    "throughput", String.format(
                        "${security.name.lowercase()}, streaming %d MiB through the file " +
                            "provider: %.1f MB/s from the server (first bytes %.1f s, second " +
                            "half %.1f MB/s, %d extra connections, %d late blocks fetched again); again " +
                            "(read cache) %.1f MB/s (first bytes %.1f s)",
                        size / 1024 / 1024, first.first, first.second, first.third,
                        extraConnections, hedged, again.first, again.second
                    )
                )
            }
        )
    }

    /**
     * Copying small files from local storage, as when copying a folder of photos: each file
     * costs round trips (create, write, close, times), which dominate over a VPN. Reports the
     * time per file.
     */
    @Test
    fun copySmallFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val local = java.io.File(context.cacheDir, "small-files").apply { mkdirs() }
        val sources = (0 until 20).map { i ->
            java.io.File(local, "photo$i.jpg").apply { writeBytes(ByteArray(20_000 + i)) }
        }
        val target = root.resolve("photos").createDirectory()
        val start = System.nanoTime()
        // What a copy from local storage does on the NFS side: create and write the file, then
        // set its modification time.
        for (source in sources) {
            val file = target.resolve(source.name)
            file.newOutputStream(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                .use { it.write(source.readBytes()) }
            file.setLastModifiedTime(FileTime.fromMillis(source.lastModified()))
        }
        val millisPerFile = (System.nanoTime() - start) / 1e6 / sources.size
        for ((i, source) in sources.withIndex()) {
            assertEquals(20_000L + i, target.resolve(source.name).size())
        }
        local.deleteRecursively()
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0, android.os.Bundle().apply {
                putString(
                    "throughput", String.format(
                        "copying 20 small files from local storage: %.0f ms per file",
                        millisPerFile
                    )
                )
            }
        )
    }

    @Test
    fun randomAccessAndTruncate() {
        val file = root.resolve("random.bin")
        file.newOutputStream().use { it.write(ByteArray(100_000)) }
        file.newByteChannel(StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.position(50_000)
            channel.write(ByteBuffer.wrap("hello".toByteArray()))
            channel.position(50_000)
            val buffer = ByteBuffer.allocate(5)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {}
            assertEquals("hello", String(buffer.array()))
            channel.truncate(10)
            assertEquals(10L, channel.size())
        }
        assertEquals(10L, file.size())
    }

    @Test
    fun append() {
        val file = root.resolve("append.txt")
        file.newOutputStream().use { it.write("a".toByteArray()) }
        file.newOutputStream(StandardOpenOption.APPEND).use { it.write("b".toByteArray()) }
        assertEquals("ab", String(file.readAllBytes()))
    }

    @Test
    fun listingWithAttributes() {
        // CI creates this directory on the server (creating 200 files costs several round trips
        // each, minutes over a VPN); otherwise it is created here.
        val fixture = server.path.resolve(".mf-fixtures/listing")
        val directory = if (fixture.exists(LinkOption.NOFOLLOW_LINKS)) {
            fixture
        } else {
            for (i in 0 until 200) {
                root.resolve("f$i").newOutputStream().use { it.write(i) }
            }
            root.resolve("dir").createDirectory()
            root
        }
        val children = directory.newDirectoryStream().use { it.toList() }
        assertEquals(201, children.size)
        assertTrue(directory.resolve("dir").isDirectory())
        assertTrue(directory.resolve("f7").isRegularFile())
        assertEquals(1L, directory.resolve("f7").size())
    }

    @Test
    fun nonUtf8AndEmojiNames() {
        val emoji = root.resolve("emoji-😀.txt")
        emoji.newOutputStream().use { it.write(1) }
        assertTrue(emoji.exists())
        val names = root.newDirectoryStream().use { stream ->
            stream.map { it.fileName.toString() }
        }
        assertTrue(names.contains("emoji-😀.txt"))
    }

    @Test
    fun renameMoveAndCopy() {
        val file = root.resolve("a.txt")
        file.newOutputStream().use { it.write("content".toByteArray()) }
        val dir = root.resolve("sub").createDirectory()
        val moved = dir.resolve("b.txt")
        file.moveTo(moved)
        assertFalse(file.exists())
        assertEquals("content", String(moved.readAllBytes()))
        val copy = root.resolve("c.txt")
        moved.copyTo(copy, StandardCopyOption.COPY_ATTRIBUTES)
        assertEquals("content", String(copy.readAllBytes()))
        // Directory rename.
        val renamedDir = root.resolve("sub2")
        dir.moveTo(renamedDir)
        assertTrue(renamedDir.resolve("b.txt").exists())
        try {
            copy.moveTo(renamedDir.resolve("b.txt"))
            fail("Expected FileAlreadyExistsException")
        } catch (e: FileAlreadyExistsException) {
            // Expected.
        }
        copy.moveTo(renamedDir.resolve("b.txt"), StandardCopyOption.REPLACE_EXISTING)
        assertFalse(copy.exists())
    }

    @Test
    fun symbolicLinks() {
        val target = root.resolve("target.txt")
        target.newOutputStream().use { it.write("x".toByteArray()) }
        val link = root.resolve("link")
        link.createSymbolicLink("target.txt".toByteString())
        assertEquals("target.txt", link.readSymbolicLinkByteString().toString())
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("x", String(link.readAllBytes()))
        link.delete()
        assertTrue(target.exists())
    }

    @Test
    fun attributes() {
        val file = root.resolve("attrs.txt")
        file.newOutputStream().use { it.write(1) }
        file.setMode(PosixFileMode.fromInt(0b110_000_000))
        assertEquals(PosixFileMode.fromInt(0b110_000_000), file.getMode())
        val time = FileTime.fromMillis(1_234_567_890_000L)
        file.setLastModifiedTime(time)
        assertEquals(time.to(TimeUnit.SECONDS), file.getLastModifiedTime().to(TimeUnit.SECONDS))
    }

    @Test
    fun errors() {
        try {
            root.resolve("missing").newInputStream().close()
            fail("Expected NoSuchFileException")
        } catch (e: NoSuchFileException) {
            // Expected.
        }
        root.resolve("full").createDirectory()
        root.resolve("full/child").newOutputStream().use { it.write(1) }
        try {
            root.resolve("full").delete()
            fail("Expected DirectoryNotEmptyException")
        } catch (e: DirectoryNotEmptyException) {
            // Expected.
        }
    }

    @Test
    fun concurrentMetadata() {
        for (i in 0 until 20) {
            root.resolve("c$i").newOutputStream().use { it.write(ByteArray(i)) }
        }
        runBlocking(Dispatchers.IO) {
            (0 until 50).map { i ->
                async {
                    val path = root.resolve("c${i % 20}")
                    assertEquals((i % 20).toLong(), path.size())
                    root.newDirectoryStream().use { it.count() }
                }
            }.awaitAll()
        }
    }

    /** Needs a short NFSv4 lease on the server; skipped when idleMillis is 0 (NFSv3). */
    @Test
    fun openFileSurvivesIdle() {
        assumeTrue(idleMillis > 0)
        val file = root.resolve("idle.bin")
        file.newOutputStream().use { it.write(ByteArray(1000) { 7 }) }
        file.newByteChannel(StandardOpenOption.READ).use { channel ->
            // Longer than the test server's lease: only the background session renewal keeps
            // the open state alive.
            Thread.sleep(idleMillis)
            val buffer = ByteBuffer.allocate(1000)
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {}
            assertEquals(1000, buffer.position())
            assertEquals(7.toByte(), buffer.get(999))
        }
    }

    /** A copy within the export runs on the server: identical content, no data through us. */
    @Test
    fun serverSideCopy() {
        val data = ByteArray(dataSize + 3).also { Random(9).nextBytes(it) }
        val source = root.resolve("original.bin")
        source.newOutputStream().use { it.write(data) }
        val copies = Client.serverSideCopyCount
        val target = root.resolve("copy.bin")
        val start = System.nanoTime()
        source.copyTo(target)
        val millis = (System.nanoTime() - start) / 1e6
        assertEquals(copies + 1, Client.serverSideCopyCount)
        assertArrayEquals(data, target.readAllBytes())
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0, android.os.Bundle().apply {
                putString(
                    "throughput", String.format(
                        "server-side copy of %d MiB: %.0f ms", dataSize / 1024 / 1024, millis
                    )
                )
            }
        )
        // Replacing an existing file goes through the server too.
        source.copyTo(target, StandardCopyOption.REPLACE_EXISTING)
        assertEquals(copies + 2, Client.serverSideCopyCount)
        assertArrayEquals(data, target.readAllBytes())
    }

    /** libarchive hands the channel direct (native) buffers; creating an archive must work. */
    @Test
    fun createZipArchive() {
        val source = root.resolve("source.txt")
        source.newOutputStream().use { it.write("archive me".toByteArray()) }
        val archive = root.resolve("archive.zip")
        archive.newByteChannel(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            ArchiveWriter(it, Archive.FORMAT_ZIP, Archive.FILTER_NONE, null).use { writer ->
                writer.write(source, source.fileName, 0, null)
            }
        }
        val bytes = archive.readAllBytes()
        assertTrue(bytes.size > 20)
        // Local file header signature "PK\u0003\u0004".
        assertArrayEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04), bytes.copyOfRange(0, 4))
    }

    @Test
    fun directBufferWrite() {
        val file = root.resolve("direct.bin")
        file.newByteChannel(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            val buffer = ByteBuffer.allocateDirect(100_000)
            while (buffer.hasRemaining()) {
                buffer.put((buffer.position() % 251).toByte())
            }
            buffer.flip()
            while (buffer.hasRemaining()) {
                it.write(buffer)
            }
        }
        val bytes = file.readAllBytes()
        assertEquals(100_000, bytes.size)
        assertEquals((99_999 % 251).toByte(), bytes[99_999])
    }

    /**
     * Copies like Material Files does (8 KiB writes and reads through the provider) and reports
     * MB/s, next to the same transfers made straight through libnfs in 8 MiB calls: the link's
     * ceiling, to tell the provider's overhead from the emulator network's.
     */
    @Test
    fun throughput() {
        val arguments = InstrumentationRegistry.getArguments()
        val size = (arguments.getString("throughputMiB")?.toInt() ?: 32) * 1024 * 1024
        val chunk = ByteArray(8 * 1024).also { Random(5).nextBytes(it) }
        val file = root.resolve("throughput.bin")
        val networkChangesBefore = Client.networkChangeCount
        var start = System.nanoTime()
        var closeStart = 0L
        file.newOutputStream().use { output ->
            for (i in 0 until size / chunk.size) {
                output.write(chunk)
            }
            closeStart = System.nanoTime()
        }
        val writeSeconds = (System.nanoTime() - start) / 1e9
        val closeSeconds = (System.nanoTime() - closeStart) / 1e9
        val buffer = ByteArray(8 * 1024)
        var read = 0L
        start = System.nanoTime()
        file.newInputStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count == -1) {
                    break
                }
                read += count
            }
        }
        val readSeconds = (System.nanoTime() - start) / 1e9
        assertEquals(size.toLong(), read)
        val networkChanges = Client.networkChangeCount - networkChangesBefore
        val nfs = Nfs.initContext()
        val rawWriteSeconds: Double
        val rawReadSeconds: Double
        try {
            Nfs.setVersion(nfs, Nfs.NFS_V4_2)
            Nfs.setUid(nfs, 0)
            Nfs.setGid(nfs, 0)
            Nfs.setTimeout(nfs, 60_000)
            if (security != ConnectionOptions.Security.NONE) {
                Nfs.setTlsTransport(
                    nfs, NfsTlsTransport(
                        testSslContext(security), server.authority.host,
                        Authority.DEFAULT_PORT, 60_000
                    )
                )
            }
            Nfs.mount(
                nfs, (arguments.getString("nfsHost") ?: "10.0.2.2").toByteArray(),
                (arguments.getString("nfsExport") ?: "/").toByteArray()
            )
            val remotePath = (file as NfsPath).remotePath.toString() + ".raw"
            val big = ByteArray(8 * 1024 * 1024).also { Random(7).nextBytes(it) }
            var handle = Nfs.open(
                nfs, remotePath.toByteArray(), Nfs.O_WRONLY or Nfs.O_CREAT or Nfs.O_TRUNC,
                0b110_100_100
            )
            var rawStart = System.nanoTime()
            var offset = 0L
            while (offset < size) {
                val length = minOf(big.size.toLong(), size - offset).toInt()
                offset += Nfs.write(nfs, handle, offset, big, 0, length)
            }
            Nfs.fsync(nfs, handle)
            rawWriteSeconds = (System.nanoTime() - rawStart) / 1e9
            Nfs.close(nfs, handle)
            handle = Nfs.open(nfs, remotePath.toByteArray(), Nfs.O_RDONLY, 0)
            rawStart = System.nanoTime()
            offset = 0L
            while (true) {
                val count = Nfs.read(nfs, handle, offset, big, 0, big.size)
                if (count == 0) {
                    break
                }
                offset += count
            }
            rawReadSeconds = (System.nanoTime() - rawStart) / 1e9
            Nfs.close(nfs, handle)
            Nfs.unlink(nfs, remotePath.toByteArray())
            Nfs.umount(nfs)
        } finally {
            Nfs.destroyContext(nfs)
        }
        val report = String.format(
            "${security.name.lowercase()}, %d MiB: provider write %.1f MB/s (close %.1f s), " +
                "read %.1f MB/s (8 KiB calls); " +
                "raw libnfs write %.1f MB/s, read %.1f MB/s; network changes %d",
            size / 1024 / 1024, size / writeSeconds / 1e6, closeSeconds, size / readSeconds / 1e6,
            size / rawWriteSeconds / 1e6, size / rawReadSeconds / 1e6, networkChanges
        )
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0, android.os.Bundle().apply { putString("throughput", report) }
        )
    }

    /** Trusts the CA in `tlsCa`; for mutual TLS presents `tlsClient` (base64 PKCS#12). */
    private fun testSslContext(security: ConnectionOptions.Security): SSLContext {
        val arguments = InstrumentationRegistry.getArguments()
        val decoder = { name: String ->
            android.util.Base64.decode(arguments.getString(name)!!, android.util.Base64.DEFAULT)
        }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry(
                "ca", CertificateFactory.getInstance("X.509")
                    .generateCertificate(decoder("tlsCa").inputStream())
            )
        }
        val trustManagers = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(trustStore) }.trustManagers
        val keyManagers = if (security == ConnectionOptions.Security.MUTUAL_TLS) {
            val password = "test".toCharArray()
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                load(decoder("tlsClient").inputStream(), password)
            }
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore, password) }.keyManagers
        } else {
            null
        }
        return SSLContext.getInstance("TLS").apply { init(keyManagers, trustManagers, null) }
    }

    private fun deleteRecursively(path: Path) {
        if (path.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
            path.newDirectoryStream().use { stream -> stream.toList() }.forEach {
                deleteRecursively(it)
            }
        }
        path.delete()
    }

    companion object {
        /** What a seek reads to show the picture. */
        private const val SEEK_BYTES = 64 * 1024

        /** A 1080p movie: 8 Mbit/s. */
        private const val PLAYBACK_BYTES_PER_SECOND = 1024 * 1024

        /** How far a player may be late before the picture freezes. */
        private const val PLAYER_BUFFER_MILLIS = 2_000L

        /**
         * Test groups of about the same duration on the slowest link, run on parallel emulators
         * in CI. Every test must be in exactly one group (CI checks it).
         */
        val SHARDS = mapOf(
            "idle" to setOf(
                "openFileSurvivesIdle", "concurrentMetadata", "append", "errors", "attributes",
                "symbolicLinks", "nonUtf8AndEmojiNames", "createdFilesHaveCurrentTime",
                "randomAccessAndTruncate", "directBufferWrite", "scrubbingThroughFileProvider"
            ),
            "movies" to setOf("movieStreamedWhole", "episodeMarathon"),
            "scenes" to setOf(
                "sceneSearch", "seekBurstThenSettle", "readCacheFollowsChanges",
                "readCacheOffInSettings"
            ),
            "load" to setOf(
                "threePlayersAtOnce", "oneVideoManyDescriptors", "rapidOpenCloseWhilePlaying"
            ),
            "jumps" to setOf("sixFilesSeekingAtOnce", "seeksWhileUploading"),
            "stream" to setOf(
                "streamingThroughFileProvider", "serverSideCopy", "playerLikeReads",
                "listingWithAttributes", "createZipArchive"
            ),
            "transfer" to setOf(
                "throughput", "externalAppReadsThroughFileProvider", "writeAndReadBack",
                "renameMoveAndCopy", "copySmallFiles"
            )
        )
    }
}

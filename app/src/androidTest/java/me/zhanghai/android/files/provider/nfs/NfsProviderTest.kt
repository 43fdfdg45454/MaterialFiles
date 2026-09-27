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

    // Load: how the app behaves under heavy, erratic use (a user scrubbing through several videos,
    // switching between them, several players at once). The files are CI fixtures of 32 MiB
    // (load-1.bin to load-4.bin) whose every 8-byte word holds its offset plus the file number
    // in bits 48 and up, so that every read is checked, and data of another file is caught.

    private val loadFileCount = 4

    private fun loadFile(number: Int): Path =
        server.path.resolve(".mf-fixtures/load-$number.bin").also {
            assumeTrue("CI fixture ${it.fileName}", it.exists(LinkOption.NOFOLLOW_LINKS))
        }

    /** Reads [length] bytes at [position] of load file [number] and checks them; milliseconds. */
    private fun readLoad(
        pfd: android.os.ParcelFileDescriptor, number: Int, position: Long, length: Int,
        what: String
    ): Long {
        val buffer = ByteArray(length)
        val start = System.nanoTime()
        var done = 0
        while (done < length) {
            val count = try {
                android.system.Os.pread(
                    pfd.fileDescriptor, buffer, done, length - done, position + done
                )
            } catch (e: android.system.ErrnoException) {
                throw AssertionError(
                    "$what: read at ${position + done} failed: $e; channel: " +
                        me.zhanghai.android.files.provider.nfs.client.FileByteChannel
                            .lastReadError?.toString(), e
                )
            }
            assertTrue("$what: read at ${position + done} returned $count", count > 0)
            done += count
        }
        val millis = (System.nanoTime() - start) / 1_000_000
        val words = ByteBuffer.wrap(buffer).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val tag = number.toLong() shl 48
        for (i in 0 until length / 8) {
            val offset = position + i * 8
            val word = words.getLong(i * 8)
            if (word != offset + tag) {
                throw AssertionError(
                    "$what: word at $offset of load-$number is ${word and 0xFFFFFFFFFFFFL} of " +
                        "load-${word ushr 48}"
                )
            }
        }
        return millis
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

    private fun reportLoad(test: String, vararg timings: Timings) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0, android.os.Bundle().apply {
                putString(
                    "throughput",
                    "${security.name.lowercase()}, $test: " + timings.joinToString("; ")
                )
            }
        )
    }

    /** Word-aligned, so that the check covers whole words. */
    private fun randomPosition(random: Random, size: Long, length: Int): Long =
        Math.floorMod(random.nextLong(), (size - length) / 8) * 8

    /**
     * One file after another, each opened (connecting all its connections), scrubbed like a
     * user looking for a scene (the start, the end, the middle, back to the start, random places,
     * a few seconds played here and there) and closed; then the first one again, whose places
     * must now come from the read cache.
     */
    @Test
    fun loadFilesOneAfterAnother() {
        val random = Random(1001)
        val opens = Timings("open+first read")
        val jumps = Timings("jumps")
        val plays = Timings("2 MB played")
        val closes = Timings("close")
        val again = Timings("first file again (cache)")
        val visited = ArrayList<Long>()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        for (number in 1..loadFileCount) {
            val file = loadFile(number)
            val size = file.size()
            val openStart = System.nanoTime()
            val pfd = resolver.openFileDescriptor(file.fileProviderUri, "r")!!
            try {
                readLoad(pfd, number, 0, 64 * 1024, "load-$number open")
                opens.add((System.nanoTime() - openStart) / 1_000_000)
                val fixed = listOf(size - 64 * 1024, size / 2, 0L, size / 2 + 8, size - 128 * 1024)
                val places = fixed + List(20) { randomPosition(random, size, 64 * 1024) } +
                    listOf(0L, size / 2, size - 64 * 1024)
                places.forEachIndexed { i, position ->
                    val millis = readLoad(pfd, number, position, 64 * 1024, "load-$number jump $i")
                    jumps.add(millis)
                    if (number == 1) {
                        visited += position
                    }
                    if (i % 5 == 4) {
                        // Stays there a little: plays 2 MB from there.
                        val from = minOf(position, size - 2 * 1024 * 1024) / 8 * 8
                        val playStart = System.nanoTime()
                        var offset = from
                        while (offset < from + 2 * 1024 * 1024) {
                            readLoad(pfd, number, offset, 256 * 1024, "load-$number play")
                            offset += 256 * 1024
                        }
                        plays.add((System.nanoTime() - playStart) / 1_000_000)
                        if (number == 1) {
                            visited += from
                        }
                    }
                }
            } finally {
                val closeStart = System.nanoTime()
                pfd.close()
                closes.add((System.nanoTime() - closeStart) / 1_000_000)
            }
        }
        resolver.openFileDescriptor(loadFile(1).fileProviderUri, "r")!!.use { pfd ->
            readLoad(pfd, 1, visited.last(), 64 * 1024, "load-1 reopen")
            for (position in visited.shuffled(random)) {
                again.add(readLoad(pfd, 1, position, 64 * 1024, "load-1 again at $position"))
            }
        }
        reportLoad("files one after another", opens, jumps, plays, closes, again)
        // Limits of a good experience, not of what merely works: a video starts within 1.5 s,
        // a seek shows the picture within a second (never over 3 s), a few seconds of a
        // ~1 MB/s video arrive faster than they play, closing is instant, and what was seen
        // before comes back at once.
        opens.check(1_500, 3_000)
        jumps.check(1_000, 3_000)
        plays.check(2_000, 4_000)
        closes.check(300, 1_000)
        again.check(100, 500)
    }

    /**
     * Several players at once, each scrubbing its own file: the connections are shared among
     * the files, and none may starve.
     */
    @Test
    fun loadFilesInParallel() {
        val opens = Timings("open+first read")
        val jumps = Timings("jumps")
        val plays = Timings("1 MB played")
        runInParallel(loadFileCount) { thread ->
            val number = thread + 1
            val random = Random(2000L + number)
            val file = loadFile(number)
            val size = file.size()
            val resolver =
                InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            val openStart = System.nanoTime()
            resolver.openFileDescriptor(file.fileProviderUri, "r")!!.use { pfd ->
                readLoad(pfd, number, 0, 64 * 1024, "load-$number open")
                opens.add((System.nanoTime() - openStart) / 1_000_000)
                repeat(20) { i ->
                    val position = when (i % 7) {
                        0 -> 0L
                        3 -> size - 64 * 1024
                        5 -> size / 2
                        else -> randomPosition(random, size, 64 * 1024)
                    }
                    jumps.add(readLoad(pfd, number, position, 64 * 1024, "load-$number jump $i"))
                    if (i % 4 == 3) {
                        val from = minOf(position, size - 1024 * 1024) / 8 * 8
                        val playStart = System.nanoTime()
                        var offset = from
                        while (offset < from + 1024 * 1024) {
                            readLoad(pfd, number, offset, 128 * 1024, "load-$number play")
                            offset += 128 * 1024
                        }
                        plays.add((System.nanoTime() - playStart) / 1_000_000)
                    }
                }
            }
        }
        reportLoad("$loadFileCount files in parallel", opens, jumps, plays)
        // Four at once share the link: a little more than one alone, still a good experience.
        opens.check(2_000, 4_000)
        jumps.check(1_500, 4_000)
        plays.check(2_000, 5_000)
    }

    /**
     * A player probing and switching files as fast as it can: open a random file, read a random
     * place, close, over and over, with another file streaming meanwhile. Connections released at
     * every close must go back to the pool (none left reserved, none leaked).
     */
    @Test
    fun loadRapidOpenClose() {
        val random = Random(3003)
        val cycles = Timings("open+read+close")
        val stream = Timings("streaming 256 KB reads")
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val stop = java.util.concurrent.atomic.AtomicBoolean()
        var streamError: Throwable? = null
        val streamer = Thread {
            try {
                val file = loadFile(4)
                resolver.openFileDescriptor(file.fileProviderUri, "r")!!.use { pfd ->
                    var offset = 0L
                    val size = file.size()
                    while (!stop.get()) {
                        stream.add(readLoad(pfd, 4, offset, 256 * 1024, "load-4 stream"))
                        offset = (offset + 256 * 1024) % (size - 256 * 1024) / 8 * 8
                    }
                }
            } catch (t: Throwable) {
                streamError = t
            }
        }.apply { start() }
        try {
            repeat(30) { cycle ->
                val number = 1 + random.nextInt(loadFileCount - 1)
                val file = loadFile(number)
                val size = file.size()
                val start = System.nanoTime()
                resolver.openFileDescriptor(file.fileProviderUri, "r")!!.use { pfd ->
                    val position = when (cycle % 3) {
                        0 -> 0L
                        1 -> size - 64 * 1024
                        else -> randomPosition(random, size, 64 * 1024)
                    }
                    readLoad(pfd, number, position, 64 * 1024, "cycle $cycle load-$number")
                }
                cycles.add((System.nanoTime() - start) / 1_000_000)
            }
        } finally {
            stop.set(true)
            streamer.join(60_000)
        }
        assertNull("streaming file: $streamError", streamError)
        reportLoad("rapid open/close with a file streaming", cycles, stream)
        cycles.check(1_500, 3_000)
        // The streaming file keeps going while the others come and go: 256 KB is a quarter of
        // a second of a ~1 MB/s video.
        stream.check(250, 3_000)
    }

    /**
     * One file, several descriptors jumping at once (a player, its demuxer and a thumbnailer
     * all reading the same video): they share the file's connections and blocks.
     */
    @Test
    fun loadOneFileManyDescriptors() {
        val file = loadFile(2)
        val size = file.size()
        val jumps = Timings("jumps")
        runInParallel(4) { thread ->
            val random = Random(4000L + thread)
            val resolver =
                InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            resolver.openFileDescriptor(file.fileProviderUri, "r")!!.use { pfd ->
                repeat(25) { i ->
                    val position = if (i % 6 == 0) {
                        listOf(0L, size / 2, size - 64 * 1024)[(i / 6 + thread) % 3]
                    } else {
                        randomPosition(random, size, 64 * 1024)
                    }
                    jumps.add(readLoad(pfd, 2, position, 64 * 1024, "descriptor $thread jump $i"))
                }
            }
        }
        reportLoad("one file, 4 descriptors jumping at once", jumps)
        jumps.check(1_000, 3_000)
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
        threads.forEach { it.join(10 * 60_000) }
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
            "load" to setOf(
                "loadFilesOneAfterAnother", "loadFilesInParallel", "loadRapidOpenClose",
                "loadOneFileManyDescriptors"
            ),
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

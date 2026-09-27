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
    private var security = ConnectionOptions.Security.NONE
    private lateinit var root: Path
    private var idleMillis = 0L

    @Before
    fun setUp() {
        val arguments = InstrumentationRegistry.getArguments()
        val host = arguments.getString("nfsHost") ?: "10.0.2.2"
        val export = arguments.getString("nfsExport") ?: "/"
        idleMillis = arguments.getString("idleMillis")?.toLong() ?: 0L
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
        NfsServerAuthenticator.removeTransientServer(server)
        NfsTls.sslContextFactory = null
    }

    @Test
    fun writeAndReadBack() {
        val data = ByteArray(5 * 1024 * 1024 + 7).also { Random(1).nextBytes(it) }
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
        val data = ByteArray(12 * 1024 * 1024).also { Random(9).nextBytes(it) }
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
            readAt(0, 64 * 1024)
            readAt(data.size - 200_000L, 200_000)
            readAt(0, 512 * 1024)
            readAt(5L * 1024 * 1024 + 123, 4 * 1024 * 1024)
            readAt(2L * 1024 * 1024, 128 * 1024)
        }
    }

    /**
     * What another app (VLC, a music player) does with a file shared by Material Files: query its
     * name and size, open it through the file provider, probe it (open and close a few times),
     * read the header and the end, seek around, read the same file from two descriptors at once
     * (player plus metadata extractor) and play it through.
     */
    @Test
    fun externalAppReadsThroughFileProvider() {
        val data = ByteArray(6 * 1024 * 1024 + 11).also { Random(21).nextBytes(it) }
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
                readAt(channel, 3L * 1024 * 1024, 1024 * 1024)
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
        for (i in 0 until 200) {
            root.resolve("f$i").newOutputStream().use { it.write(i) }
        }
        root.resolve("dir").createDirectory()
        val children = root.newDirectoryStream().use { it.toList() }
        assertEquals(201, children.size)
        assertTrue(root.resolve("dir").isDirectory())
        assertTrue(root.resolve("f7").isRegularFile())
        assertEquals(1L, root.resolve("f7").size())
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
        val data = ByteArray(16 * 1024 * 1024 + 3).also { Random(9).nextBytes(it) }
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
                putString("throughput", String.format("server-side copy of 16 MiB: %.0f ms", millis))
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
}

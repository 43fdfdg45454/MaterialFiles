package me.zhanghai.android.files.provider.nfs

import java.io.File
import java.nio.ByteBuffer
import java.util.Random
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.StandardOpenOption
import java8.nio.file.spi.FileSystemProvider
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import me.zhanghai.android.files.provider.common.createDirectory
import me.zhanghai.android.files.provider.common.delete
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.provider.common.isDirectory
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newDirectoryStream
import me.zhanghai.android.files.provider.nfs.client.Authority
import me.zhanghai.android.files.provider.nfs.client.Client
import me.zhanghai.android.files.provider.nfs.client.ConnectionOptions
import me.zhanghai.android.files.provider.nfs.client.NfsClock
import me.zhanghai.android.files.provider.nfs.client.NfsForeground
import me.zhanghai.android.files.provider.nfs.client.NfsTls
import me.zhanghai.android.files.storage.NfsServer
import me.zhanghai.android.files.storage.NfsServerAuthenticator
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.model.FrameworkMethod
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.internal.bytecode.InstrumentationConfiguration

/**
 * The load scenarios ([NfsScenarios]) run by the app's NFS code on the CI machine itself, against
 * the real NFS server over a link emulated on loopback (netem): the VPN-like one (100 ms round
 * trips, 0.3 % loss), where the emulator's own user-mode network drops connections under load,
 * and the LAN-like one with mutual TLS (tlshd), without the emulator's slow CPU. The app's code
 * runs under Robolectric; libnfs is its host build (java.library.path).
 *
 * Runs only when `nfsHost` is set (the CI's host-VPN jobs, see nfs.yml); `nfsShard` picks a group.
 */
@RunWith(NfsHostLoadTest.Runner::class)
@Config(sdk = [34], application = android.app.Application::class)
internal class NfsHostLoadTest : NfsScenarios() {
    /** Loads libnfs as it is: instrumenting it would turn its native methods into no-ops. */
    class Runner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
        override fun createClassLoaderConfig(method: FrameworkMethod): InstrumentationConfiguration =
            InstrumentationConfiguration.Builder(super.createClassLoaderConfig(method))
                .doNotInstrumentPackage("io.github.libnfsandroid")
                .build()
    }

    override lateinit var server: NfsServer
    override lateinit var root: Path
    override var security = ConnectionOptions.Security.NONE

    @get:Rule
    val testName = TestName()

    @Before
    fun setUp() {
        val host = System.getProperty("nfsHost")
        assumeTrue("host run (nfsHost) only", !host.isNullOrEmpty())
        System.getProperty("nfsShard")?.takeIf { it.isNotEmpty() }?.let { shard ->
            assumeTrue(testName.methodName in SHARDS.getValue(shard))
        }
        setUpApp()
        security = when (System.getProperty("nfsSecurity")) {
            "tls" -> ConnectionOptions.Security.TLS
            "mtls" -> ConnectionOptions.Security.MUTUAL_TLS
            else -> ConnectionOptions.Security.NONE
        }
        if (security != ConnectionOptions.Security.NONE) {
            // The test's CA and client certificate instead of Android's stores.
            NfsTls.sslContextFactory = { options -> testSslContext(options.security) }
        }
        server = NfsServer(
            null, null,
            Authority(host!!, Authority.DEFAULT_PORT, System.getProperty("nfsExport") ?: "/"),
            ConnectionOptions(
                0, 0, emptyList(), false, security,
                "test".takeIf { security == ConnectionOptions.Security.MUTUAL_TLS }
            ), ""
        )
        NfsServerAuthenticator.addTransientServer(server)
        startMeasuring()
        root = server.path.resolve(".mf-nfs-test-" + java.lang.Long.toHexString(Random().nextLong()))
        root.createDirectory()
    }

    /** What the app's initializers do for NFS (they do not run under Robolectric). */
    private fun setUpApp() {
        Class.forName("me.zhanghai.android.files.app.AppProviderKt")
            .getDeclaredField("application")
            .apply { isAccessible = true }
            .set(null, RuntimeEnvironment.getApplication())
        // Robolectric's SystemClock only moves when told to; the engine needs real time.
        NfsClock.source = { System.nanoTime() / 1_000_000 }
        // No services on the host (the foreground service keeps network access on devices).
        NfsForeground.isEnabled = false
        Client.authenticator = NfsServerAuthenticator
        if (!isProviderInstalled) {
            isProviderInstalled = true
            // java8.nio needs a default provider (the app's local files), installed first.
            FileSystemProvider.installDefaultProvider(
                me.zhanghai.android.files.provider.linux.LinuxFileSystemProvider
            )
            FileSystemProvider.installProvider(NfsFileSystemProvider)
        }
    }

    @After
    fun tearDown() {
        if (::root.isInitialized && root.exists(LinkOption.NOFOLLOW_LINKS)) {
            deleteRecursively(root)
        }
        if (::server.isInitialized) {
            NfsServerAuthenticator.removeTransientServer(server)
        }
        NfsTls.sslContextFactory = null
    }

    /** Through the app's channel, as the file provider's proxy reads it. */
    override fun openReader(path: Path): VideoReader {
        val channel = path.newByteChannel(StandardOpenOption.READ)
        return object : VideoReader {
            override val size: Long
                get() = channel.size()

            override fun read(buffer: ByteArray, offset: Int, length: Int, position: Long): Int =
                synchronized(channel) {
                    channel.position(position)
                    channel.read(ByteBuffer.wrap(buffer, offset, length))
                }

            override fun close() {
                channel.close()
            }
        }
    }

    override fun reportLine(line: String) {
        println("NFS-RESULT: $line")
        System.getProperty("nfsReport")?.takeIf { it.isNotEmpty() }?.let {
            synchronized(NfsHostLoadTest::class.java) {
                File(it).appendText("${testName.methodName}: $line\n")
            }
        }
    }

    override fun argument(name: String): String? = System.getProperty(name)

    /** Trusts the CA in `nfsTlsCa` (PEM); for mutual TLS presents `nfsTlsClient` (PKCS#12). */
    override fun testSslContext(security: ConnectionOptions.Security): SSLContext {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            File(System.getProperty("nfsTlsCa")!!).inputStream().use {
                setCertificateEntry(
                    "ca", CertificateFactory.getInstance("X.509").generateCertificate(it)
                )
            }
        }
        val trustManagers = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(trustStore) }.trustManagers
        val keyManagers = if (security == ConnectionOptions.Security.MUTUAL_TLS) {
            val password = "test".toCharArray()
            val keyStore = KeyStore.getInstance("PKCS12").apply {
                File(System.getProperty("nfsTlsClient")!!).inputStream().use { load(it, password) }
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
        private var isProviderInstalled = false

        /** Groups run by parallel CI jobs; every scenario is in exactly one (CI checks it). */
        val SHARDS = mapOf(
            "movies" to setOf("movieStreamedWhole", "episodeMarathon"),
            "scenes" to setOf(
                "sceneSearch", "seekBurstThenSettle", "readCacheFollowsChanges",
                "readCacheOffInSettings"
            ),
            "load" to setOf(
                "threePlayersAtOnce", "oneVideoManyDescriptors", "rapidOpenCloseWhilePlaying"
            ),
            "jumps" to setOf("sixFilesSeekingAtOnce", "seeksWhileUploading"),
            "changes" to setOf(
                "deletedInAppWhilePlaying", "deletedByAnotherClientThenReplaced",
                "modifiedByAnotherClientWhilePlaying", "appendedByAnotherClientWhilePlaying",
                "truncatedByAnotherClientWhilePlaying"
            )
        )
    }
}

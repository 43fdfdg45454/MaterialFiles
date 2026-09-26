package me.zhanghai.android.files.provider.nfs.client

import android.security.KeyChain
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import me.zhanghai.android.files.app.application

/**
 * TLS settings for RPC-with-TLS connections.
 *
 * - Trusted servers: the system CAs plus the CAs the user installed in Android settings (a home
 *   server's own CA goes there, "Encryption & credentials > Install a certificate > CA
 *   certificate"). The server name in Material Files must match the server certificate.
 * - Client certificate (mutual TLS): a key pair from the Android key store, picked with
 *   [KeyChain.choosePrivateKeyAlias]. The private key never leaves the key store; it is often
 *   hardware-backed, and Material Files only asks it to sign the handshake.
 */
internal object NfsTls {
    /** For tests, which cannot use the interactive key chain. */
    @Volatile
    var sslContextFactory: ((ConnectionOptions) -> SSLContext)? = null

    fun createSslContext(options: ConnectionOptions): SSLContext {
        sslContextFactory?.let { return it(options) }
        val trustStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val trustManagers = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(trustStore) }.trustManagers
        val keyManagers: Array<KeyManager>? =
            if (options.security == ConnectionOptions.Security.MUTUAL_TLS) {
                arrayOf(KeyChainKeyManager(options.clientCertificateAlias!!))
            } else {
                null
            }
        return SSLContext.getInstance("TLS").apply { init(keyManagers, trustManagers, null) }
    }

    /**
     * Presents one key chain entry. KeyChain calls block, which is fine here: handshakes run on
     * the TLS relay's own thread.
     */
    private class KeyChainKeyManager(private val alias: String) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String = alias

        override fun chooseEngineClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            engine: SSLEngine?
        ): String = alias

        override fun getClientAliases(
            keyType: String?,
            issuers: Array<out Principal>?
        ): Array<String> = arrayOf(alias)

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            KeyChain.getCertificateChain(application, this.alias)

        override fun getPrivateKey(alias: String?): PrivateKey? =
            KeyChain.getPrivateKey(application, this.alias)

        override fun getServerAliases(
            keyType: String?,
            issuers: Array<out Principal>?
        ): Array<String>? = null

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String? = null
    }
}

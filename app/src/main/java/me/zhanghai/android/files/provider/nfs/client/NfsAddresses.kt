package me.zhanghai.android.files.provider.nfs.client

import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import me.zhanghai.android.files.app.application

/**
 * The last address each server name resolved to, for when resolving fails: on a phone's mobile
 * data behind a VPN the resolver fails now and then (Android reports it as EAI_NODATA), and a
 * new connection to a server whose address is known must not fail for that. A name that resolves
 * is always used as such (its address may depend on the network: home LAN or VPN), and replaces
 * the one remembered. Kept across restarts.
 */
internal object NfsAddresses {
    private const val PREFERENCES_NAME = "nfs_addresses"

    private val addresses = ConcurrentHashMap<String, String>()

    private val preferences by lazy {
        application.getSharedPreferences(PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
    }

    /** After connecting by [host]: remembers what it resolves to now (from the resolver cache). */
    fun remember(host: String) {
        if (isLiteral(host)) {
            return
        }
        val address = try {
            InetAddress.getByName(host).hostAddress
        } catch (e: Exception) {
            return
        } ?: return
        if (addresses.put(host, address) != address) {
            runCatching { preferences.edit().putString(host, address).apply() }
        }
    }

    /** The last address [host] resolved to, if any. */
    fun last(host: String): String? =
        addresses[host] ?: runCatching { preferences.getString(host, null) }.getOrNull()
            ?.also { addresses[host] = it }

    /** Whether connecting failed because the server's name did not resolve (libnfs's message). */
    fun isResolutionFailure(e: Exception): Boolean =
        e.message?.contains("Can not resolv", ignoreCase = true) == true

    private fun isLiteral(host: String): Boolean =
        host.all { it.isDigit() || it == '.' } || host.contains(':')
}

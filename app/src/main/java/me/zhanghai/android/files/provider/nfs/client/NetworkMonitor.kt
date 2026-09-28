package me.zhanghai.android.files.provider.nfs.client

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.compat.getSystemServiceCompat

/**
 * Reports changes of the network NFS traffic goes through: switching between Wi-Fi and mobile
 * data, a VPN coming up or going down, or the device getting a new address on the same network.
 *
 * Without this, a connection whose local address disappeared looks alive until a call times out
 * (up to [Context.TIMEOUT_MILLIS]). With it, [Client] resets its connections right away: libnfs
 * reconnects over the new network, rebinds the NFSv4.2 session and resends what was in flight.
 */
internal object NetworkMonitor {
    private var isStarted = false

    /** Whether Android blocks this app's network now, as last reported (diagnostics). */
    @Volatile
    var isBlocked = false
        private set

    /** The default network's kinds (VPN, Wi-Fi, mobile data...), for the diagnostics screen. */
    fun describeDefaultNetwork(): String {
        val connectivityManager =
            application.getSystemServiceCompat(ConnectivityManager::class.java)
        val network = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork
        } else {
            null
        } ?: return "none"
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return "unknown"
        return listOf(
            NetworkCapabilities.TRANSPORT_VPN to "VPN",
            NetworkCapabilities.TRANSPORT_WIFI to "Wi-Fi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "mobile data",
            NetworkCapabilities.TRANSPORT_ETHERNET to "Ethernet"
        ).filter { capabilities.hasTransport(it.first) }.joinToString(" + ") { it.second }
            .ifEmpty { "other" }
    }

    // Guarded by this.
    private var currentNetwork: Network? = null
    private var currentAddresses: Set<String> = emptySet()

    @Synchronized
    fun start(onChanged: () -> Unit) {
        if (isStarted) {
            return
        }
        isStarted = true
        val connectivityManager =
            application.getSystemServiceCompat(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                update(network, null, onChanged)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                update(network, linkProperties, onChanged)
            }

            // Android blocking this app's network (in the background without the foreground
            // service, data saver, battery restrictions): new connections fail, name lookups
            // first. Logged, since it looks like a DNS failure.
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                isBlocked = blocked
                NfsLog.log(
                    if (blocked) {
                        "Android blocked this app's network access (background restrictions; " +
                            "foreground service running: ${NfsForeground.isRunning})"
                    } else {
                        "Android allowed this app's network access again"
                    }
                )
            }

            override fun onLost(network: Network) {
                synchronized(this@NetworkMonitor) {
                    if (network == currentNetwork) {
                        currentNetwork = null
                        currentAddresses = emptySet()
                    }
                }
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // The default network is the one new connections use, VPNs included.
                connectivityManager.registerDefaultNetworkCallback(callback)
            } else {
                connectivityManager.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    callback
                )
            }
        }
    }

    private fun update(network: Network, linkProperties: LinkProperties?, onChanged: () -> Unit) {
        val addresses = linkProperties?.linkAddresses?.map { it.address.hostAddress.orEmpty() }
            ?.toSet()
        val changed = synchronized(this) {
            val networkChanged = currentNetwork != null && network != currentNetwork
            val addressesChanged = network == currentNetwork && addresses != null
                && currentAddresses.isNotEmpty() && addresses != currentAddresses
            currentNetwork = network
            if (addresses != null) {
                currentAddresses = addresses
            }
            networkChanged || addressesChanged
        }
        if (changed) {
            onChanged()
        }
    }
}

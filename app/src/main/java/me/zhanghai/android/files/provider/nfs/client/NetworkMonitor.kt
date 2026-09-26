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

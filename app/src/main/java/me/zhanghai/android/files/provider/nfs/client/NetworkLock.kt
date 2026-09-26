package me.zhanghai.android.files.provider.nfs.client

import android.net.wifi.WifiManager
import android.os.Build
import me.zhanghai.android.files.app.wifiManager

/**
 * Keeps Wi-Fi out of power save while NFS files are open. In power save the radio sleeps between
 * beacons, which adds tens of milliseconds to every round trip and throttles transfers that
 * depend on round trips. Reference counted by open files.
 */
internal object NetworkLock {
    private const val TAG = "MaterialFiles:NFS"

    private var openCount = 0

    private val locks: List<WifiManager.WifiLock> by lazy {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Effective while the app is in the foreground with the screen on.
                add(wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, TAG))
            }
            // Covers background transfers on the versions where it still has an effect.
            @Suppress("DEPRECATION")
            add(wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG))
        }.onEach { it.setReferenceCounted(false) }
    }

    @Synchronized
    fun onFileOpened() {
        if (openCount++ == 0) {
            runCatching { locks.forEach { it.acquire() } }
        }
    }

    @Synchronized
    fun onFileClosed() {
        if (openCount > 0 && --openCount == 0) {
            runCatching { locks.forEach { if (it.isHeld) it.release() } }
        }
    }
}

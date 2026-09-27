package me.zhanghai.android.files.provider.nfs.client

import android.os.SystemClock

/**
 * Milliseconds since boot, for timing NFS work (waits, seeks settling, late blocks):
 * [SystemClock.elapsedRealtime] on Android. Tests running this code on the host JVM (Robolectric,
 * whose SystemClock only moves when told to) set [source] to real time.
 */
internal object NfsClock {
    @Volatile
    var source: () -> Long = { SystemClock.elapsedRealtime() }

    fun elapsedRealtime(): Long = source()
}

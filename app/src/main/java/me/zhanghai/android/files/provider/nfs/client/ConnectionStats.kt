package me.zhanghai.android.files.provider.nfs.client

import java.util.concurrent.atomic.AtomicInteger

/**
 * How the connections fared, for tests under load: whether files got the connections they
 * wanted, whether reads waited with every connection busy (more connections would help) or with
 * some idle (the link or the server is the limit), and whether connections broke or the server
 * pushed back. Counters only grow; tests compare [snapshot]s.
 */
internal object ConnectionStats {
    /** New connections (libnfs contexts) created. */
    val opened = AtomicInteger()
    /** A file asked for another connection and the export was at its limit. */
    val refused = AtomicInteger()
    /** A file wanted more connections than its share among the files streaming at once. */
    val shareLimited = AtomicInteger()
    /** Connections that broke while in use. */
    val broke = AtomicInteger()
    /** Extra connections that could not open the file. */
    val openFailed = AtomicInteger()
    /** Server answers "busy" (NFS4ERR_DELAY, NFS4ERR_GRACE). */
    val serverBusy = AtomicInteger()
    /** Reads that waited for the network with every connection of the file busy. */
    val waitsAllBusy = AtomicInteger()
    /** Reads that waited for the network while some connection of the file was idle. */
    val waitsWithIdle = AtomicInteger()
    /** Most connections at once (all exports), and most bound to open files. */
    val peakTotal = AtomicInteger()
    val peakInUse = AtomicInteger()

    fun updatePeak(peak: AtomicInteger, value: Int) {
        while (true) {
            val current = peak.get()
            if (value <= current || peak.compareAndSet(current, value)) {
                return
            }
        }
    }

    class Snapshot(
        val opened: Int,
        val refused: Int,
        val shareLimited: Int,
        val broke: Int,
        val openFailed: Int,
        val serverBusy: Int,
        val waitsAllBusy: Int,
        val waitsWithIdle: Int
    ) {
        operator fun minus(other: Snapshot) = Snapshot(
            opened - other.opened, refused - other.refused, shareLimited - other.shareLimited,
            broke - other.broke, openFailed - other.openFailed, serverBusy - other.serverBusy,
            waitsAllBusy - other.waitsAllBusy, waitsWithIdle - other.waitsWithIdle
        )

        override fun toString(): String =
            "connections: $opened opened, $refused refused at the export's limit, " +
                "$shareLimited times held to the per-file share, $broke broke, $openFailed " +
                "failed to open, $serverBusy server busy; network waits: $waitsAllBusy with " +
                "all connections busy, $waitsWithIdle with some idle"
    }

    fun snapshot() = Snapshot(
        opened.get(), refused.get(), shareLimited.get(), broke.get(), openFailed.get(),
        serverBusy.get(), waitsAllBusy.get(), waitsWithIdle.get()
    )

    /** Starts measuring peaks from now. */
    fun resetPeaks() {
        peakTotal.set(0)
        peakInUse.set(0)
    }
}

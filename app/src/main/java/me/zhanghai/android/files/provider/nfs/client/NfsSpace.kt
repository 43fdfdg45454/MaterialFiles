package me.zhanghai.android.files.provider.nfs.client

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Space on each server's export, as the server reports it (NFSv4 space_total, space_free and
 * space_avail, one GETATTR on the export's root).
 *
 * - [Space.available] is what this client may still write: the server subtracts what the
 *   filesystem reserves (ext4 keeps 5% for root) and, where the filesystem reports it that way, the
 *   quota of the export's directory (XFS or ext4 project quotas, a ZFS dataset quota). Linux nfsd
 *   does not report per-user quotas as NFSv4 attributes (those go through rquotad, a separate
 *   unencrypted service); exceeding one fails writes with NFS4ERR_DQUOT, reported as such.
 * - Kept for [MAX_AGE_MILLIS]: the drawer shows the last known value and refreshes it in the
 *   background, only for servers that already have connections (showing the drawer must not
 *   connect to every server). Copies check a fresh value before starting.
 */
internal object NfsSpace {
    class Space(val total: Long, val free: Long, val available: Long, val millis: Long) {
        /** Less available than free: reserved blocks or a quota. */
        val isLimited: Boolean
            get() = available < free
    }

    private const val MAX_AGE_MILLIS = 30_000L

    private val spaces = ConcurrentHashMap<Authority, Space>()
    private val refreshing: MutableSet<Authority> = ConcurrentHashMap.newKeySet()

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NfsSpace").apply { isDaemon = true }
    }

    private val _changes = MutableLiveData<Unit>()
    /** Posted when a server's space changed (the drawer shows it). */
    val changes: LiveData<Unit>
        get() = _changes

    /** The last known space of [authority], or null. */
    fun cached(authority: Authority): Space? = spaces[authority]

    /**
     * Refreshes [authority]'s space in the background if the last value is older than
     * [MAX_AGE_MILLIS] and the server is connected already (or [evenIfIdle]).
     */
    fun refreshSoon(authority: Authority, evenIfIdle: Boolean = false) {
        val space = spaces[authority]
        if (space != null && NfsClock.elapsedRealtime() - space.millis < MAX_AGE_MILLIS) {
            return
        }
        if (!evenIfIdle && !Client.isConnected(authority)) {
            return
        }
        if (!refreshing.add(authority)) {
            return
        }
        executor.execute {
            try {
                fetch(authority)
            } catch (e: ClientException) {
                // Shown again when a later refresh works.
            } finally {
                refreshing -= authority
            }
        }
    }

    /** Asks the server now (a round trip). */
    @Throws(ClientException::class)
    fun fetch(authority: Authority): Space {
        val statVfs = Client.statVfs(authority)
        val blockSize = if (statVfs.frsize > 0) statVfs.frsize else statVfs.bsize
        val space = Space(
            statVfs.blocks * blockSize, statVfs.bfree * blockSize, statVfs.bavail * blockSize,
            NfsClock.elapsedRealtime()
        )
        val previous = spaces.put(authority, space)
        if (previous == null || previous.total != space.total ||
            previous.available != space.available) {
            _changes.postValue(Unit)
        }
        return space
    }
}

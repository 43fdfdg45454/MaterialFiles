package me.zhanghai.android.files.provider.nfs.client

import io.github.libnfsandroid.Nfs
import io.github.libnfsandroid.NfsException
import io.github.libnfsandroid.NfsTlsTransport
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.withLock
import kotlin.concurrent.write

/**
 * One libnfs context: one TCP connection and one mount of an export.
 *
 * libnfs contexts are not thread-safe, so every call goes through [use], which holds [lock] for
 * its whole duration. The background pump in [Client] takes the same lock (with tryLock) to
 * service the socket while the context is idle.
 *
 * Dropped connections are handled inside libnfs: it reconnects, rebinds the NFSv4.2 session and
 * resends what was in flight, and the server answers replays of state-changing calls from its
 * reply cache, so nothing runs twice. A context whose call still failed with a transport error
 * (the server stayed unreachable past the timeout, or the session expired) is marked broken and
 * never used again.
 */
internal class Context(
    private val authority: Authority,
    val options: ConnectionOptions
) {
    val lock = ReentrantLock()

    // Written under lock; read without it by resetConnection().
    @Volatile
    private var handle = 0L
    @Volatile
    private var isDestroyed = false

    /** Lets resetConnection() run concurrently with calls, but never with destroy(). */
    private val destroyLock = ReentrantReadWriteLock()
    var lastUsedMillis = NfsClock.elapsedRealtime()
        private set

    @Volatile
    var isBroken = false
        private set

    /** Set when the connection uses RPC-with-TLS; says why a connection attempt failed. */
    private var tlsTransport: NfsTlsTransport? = null

    /** Who holds [lock] and since when, for diagnostics. */
    @Volatile
    var holder: String? = null
        private set
    @Volatile
    var heldSinceMillis = 0L
        private set

    /** Who bound it to a file (and in which role), for diagnostics; guarded by the pool. */
    val owners = ArrayList<String>()

    /** Number of open files bound to this context; guarded by the owning pool. */
    var openFileCount = 0

    val isMounted: Boolean
        get() = lock.withLock { handle != 0L }

    @Throws(ClientException::class)
    fun <T> use(block: (Long) -> T): T =
        lock.withLock {
            if (isBroken || isDestroyed) {
                throw ClientException(
                    android.system.OsConstants.EIO, "Connection to $authority was lost"
                )
            }
            // Connecting counts as in use (for the notification and diagnostics).
            holder = Thread.currentThread().name
            heldSinceMillis = NfsClock.elapsedRealtime()
            if (handle == 0L) {
                try {
                    mountLocked()
                } catch (e: ClientException) {
                    holder = null
                    throw e
                }
            }
            lastUsedMillis = NfsClock.elapsedRealtime()
            try {
                try {
                    block(handle)
                } catch (e: NfsException) {
                    // A replay after a reconnect that the server did not cache. Only uncached,
                    // i.e. idempotent, calls can get this (libnfs-android caches every
                    // state-changing one), so running the block again is safe.
                    if (e.errno != android.system.OsConstants.EALREADY) {
                        throw e
                    }
                    block(handle)
                }
            } catch (e: NfsException) {
                val exception = toClientException(e)
                if (exception.isTransportError) {
                    isBroken = true
                }
                throw exception
            } finally {
                lastUsedMillis = NfsClock.elapsedRealtime()
                holder = null
            }
        }

    @Throws(ClientException::class)
    private fun mountLocked() {
        val nfs = try {
            Nfs.initContext()
        } catch (e: NfsException) {
            isBroken = true
            throw ClientException(e)
        }
        try {
            Nfs.setVersion(nfs, Nfs.NFS_V4_2)
            Nfs.setUid(nfs, options.uid)
            Nfs.setGid(nfs, options.gid)
            Nfs.setAuxiliaryGids(nfs, options.auxiliaryGids.toIntArray())
            if (authority.port != Authority.DEFAULT_PORT) {
                Nfs.setNfsPort(nfs, authority.port)
            }
            Nfs.setTimeout(nfs, TIMEOUT_MILLIS)
            Nfs.setPollTimeout(nfs, POLL_TIMEOUT_MILLIS)
            // Reconnect after a dropped connection. Replays are safe: libnfs-android makes the
            // server cache replies of state-changing calls. A call that times out still fails
            // (no retransmission after the timeout), so an unreachable server does not hang.
            Nfs.setAutoReconnect(nfs, RECONNECT_ATTEMPTS)
            Nfs.setResolveOnReconnect(nfs, true)
            Nfs.setRetrans(nfs, 0)
            // Material Files has its own listing cache and must see other clients' changes.
            Nfs.setDirCache(nfs, false)
            Nfs.setAutoTraverseMounts(nfs, false)
            Nfs.setReadonly(nfs, options.isReadOnly)
            Nfs.setReadMax(nfs, TRANSFER_SIZE)
            Nfs.setWriteMax(nfs, TRANSFER_SIZE)
            if (options.security != ConnectionOptions.Security.NONE) {
                // A new TLS session for every connection libnfs makes, reconnects included.
                val transport = NfsTlsTransport(
                    NfsTls.createSslContext(options), authority.host, authority.port,
                    TIMEOUT_MILLIS
                )
                Nfs.setTlsTransport(nfs, transport)
                tlsTransport = transport
            }
            Nfs.mount(nfs, authority.host.toByteArray(), authority.exportPath.toByteArray())
        } catch (e: NfsException) {
            Nfs.destroyContext(nfs)
            isBroken = true
            throw toClientException(e)
        } catch (e: Exception) {
            // Loading the trust store or the client certificate failed.
            Nfs.destroyContext(nfs)
            isBroken = true
            throw ClientException(android.system.OsConstants.EACCES, "TLS setup failed: $e")
        }
        handle = nfs
    }

    /** Adds why the TLS connection failed, when that is what made the call fail. */
    private fun toClientException(e: NfsException): ClientException {
        val tlsTransport = tlsTransport ?: return ClientException(e)
        val detail = tlsTransport.lastError
            ?: if (e.message?.contains("timed out") == true) tlsRelayStates() else null
        return if (detail != null) ClientException(e, detail) else ClientException(e)
    }

    /**
     * Where the TLS relay threads are, for a timeout over TLS: tells a stalled server from a
     * relay stuck on its own.
     */
    private fun tlsRelayStates(): String? =
        Thread.getAllStackTraces().entries
            .filter { it.key.name.startsWith("NfsTls") }
            .joinToString("; ") { (thread, stack) ->
                "${thread.name} ${thread.state} at " +
                    stack.take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}." +
                        "${it.methodName}:${it.lineNumber}" }
            }
            .ifEmpty { null }

    /** Services the socket once if nobody is using the context. Called from the pump thread. */
    fun serviceIfIdle() {
        if (!lock.tryLock()) {
            return
        }
        try {
            if (handle == 0L || isBroken || isDestroyed) {
                return
            }
            try {
                Nfs.service(handle)
            } catch (e: NfsException) {
                isBroken = true
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Drops the connection so that it is reestablished over the current network (see
     * [Nfs.resetConnection]). Does not wait for a running call: that call is what resumes.
     */
    fun resetConnection() {
        destroyLock.read {
            val handle = handle
            if (handle != 0L && !isDestroyed && !isBroken) {
                Nfs.resetConnection(handle)
            }
        }
    }

    /** Unmounts and frees the context. Waits for a running call to finish. */
    fun destroy() {
        lock.withLock {
            if (isDestroyed) {
                return
            }
            isDestroyed = true
            if (handle != 0L) {
                if (!isBroken) {
                    try {
                        Nfs.umount(handle)
                    } catch (e: NfsException) {
                        // Best effort; the server times out the mount state anyway.
                    }
                }
                destroyLock.write {
                    Nfs.destroyContext(handle)
                    handle = 0L
                }
            }
        }
    }

    companion object {
        const val TIMEOUT_MILLIS = 30_000
        private const val POLL_TIMEOUT_MILLIS = 100
        const val TRANSFER_SIZE = 1024 * 1024
        private const val RECONNECT_ATTEMPTS = 10
    }
}

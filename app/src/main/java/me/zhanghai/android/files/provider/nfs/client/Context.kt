package me.zhanghai.android.files.provider.nfs.client

import android.os.SystemClock
import io.github.libnfsandroid.Nfs
import io.github.libnfsandroid.NfsException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One libnfs context: one TCP connection and one mount of an export.
 *
 * libnfs contexts are not thread-safe, so every call goes through [use], which holds [lock] for
 * its whole duration. The background pump in [Client] takes the same lock (with tryLock) to
 * service the socket while the context is idle.
 *
 * A context that saw a transport error is marked broken and never used again: we cannot know
 * whether an in-flight mutation reached the server, so nothing is silently replayed on it.
 */
internal class Context(
    private val authority: Authority,
    private val options: ConnectionOptions
) {
    val lock = ReentrantLock()

    // All below guarded by lock.
    private var handle = 0L
    private var isDestroyed = false
    var lastUsedMillis = SystemClock.elapsedRealtime()
        private set

    @Volatile
    var isBroken = false
        private set

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
            if (handle == 0L) {
                mountLocked()
            }
            lastUsedMillis = SystemClock.elapsedRealtime()
            try {
                block(handle)
            } catch (e: NfsException) {
                val exception = ClientException(e)
                if (exception.isTransportError) {
                    isBroken = true
                }
                throw exception
            } finally {
                lastUsedMillis = SystemClock.elapsedRealtime()
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
            Nfs.setVersion(nfs, options.version.nfsVersion)
            Nfs.setUid(nfs, options.uid)
            Nfs.setGid(nfs, options.gid)
            Nfs.setAuxiliaryGids(nfs, options.auxiliaryGids.toIntArray())
            if (authority.port != Authority.DEFAULT_PORT) {
                Nfs.setNfsPort(nfs, authority.port)
            }
            Nfs.setTimeout(nfs, TIMEOUT_MILLIS)
            Nfs.setPollTimeout(nfs, POLL_TIMEOUT_MILLIS)
            // Never replay requests behind our back: a retried WRITE or RENAME whose first attempt
            // reached the server could corrupt data. Callers decide what is safe to retry.
            Nfs.setAutoReconnect(nfs, 0)
            Nfs.setRetrans(nfs, 0)
            // Material Files has its own listing cache and must see other clients' changes.
            Nfs.setDirCache(nfs, false)
            Nfs.setAutoTraverseMounts(nfs, false)
            Nfs.setReadonly(nfs, options.isReadOnly)
            Nfs.setReadMax(nfs, TRANSFER_SIZE)
            Nfs.setWriteMax(nfs, TRANSFER_SIZE)
            Nfs.mount(nfs, authority.host.toByteArray(), authority.exportPath.toByteArray())
        } catch (e: NfsException) {
            Nfs.destroyContext(nfs)
            isBroken = true
            throw ClientException(e)
        }
        handle = nfs
    }

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
                Nfs.destroyContext(handle)
                handle = 0L
            }
        }
    }

    companion object {
        const val TIMEOUT_MILLIS = 15_000
        private const val POLL_TIMEOUT_MILLIS = 100
        const val TRANSFER_SIZE = 1024 * 1024
    }
}

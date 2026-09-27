package me.zhanghai.android.files.provider.nfs.client

import android.os.SystemClock
import io.github.libnfsandroid.Nfs
import io.github.libnfsandroid.NfsStat
import io.github.libnfsandroid.NfsStatVfs
import java8.nio.channels.SeekableByteChannel
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.LocalWatchService
import me.zhanghai.android.files.provider.common.NotifyEntryModifiedSeekableByteChannel
import me.zhanghai.android.files.provider.common.toByteString
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java8.nio.file.Path as Java8Path

/**
 * NFS operations used by the file system provider.
 *
 * Each export gets a small pool of [Context]s (connections). A context runs one call at a time,
 * so the pool gives concurrency between, say, a directory listing, thumbnail loading and a copy.
 * An open file stays bound to the context that opened it, because NFSv4 open state belongs to
 * that client session.
 *
 * Reliability: libnfs reconnects dropped connections and resends what was in flight, with the
 * server answering replays of state-changing calls from its reply cache. [NetworkMonitor] makes
 * that happen as soon as the device changes networks instead of after a timeout. A call that
 * still fails with a transport error breaks its context; read-only metadata calls are then
 * retried once on a fresh one. Mutations and file IO are not, since the first attempt may have
 * reached the server.
 *
 * Copies within one export run on the server ([serverSideCopy]).
 */
object Client {
    @Volatile
    lateinit var authenticator: Authenticator

    private const val MAX_CONTEXTS_PER_EXPORT = 36
    /**
     * Connected ahead of need when a file is opened for reading (see [Pool.warmUp]): the file's
     * own plus a few for parallel pieces. Streaming connects the rest; connecting all 33 at once
     * competed with the first reads (TLS handshakes) and slowed them down.
     */
    private const val WARM_CONNECTIONS = 8
    private const val PUMP_INTERVAL_MILLIS = 250L
    /**
     * Idle connections stay up this long: a file streamed over a VPN uses up to 32, and the next
     * one reuses them instead of connecting again (TCP, TLS and the session take several round
     * trips each).
     */
    private const val IDLE_TIMEOUT_MILLIS = 5 * 60_000L
    private const val LAST_CONTEXT_IDLE_TIMEOUT_MILLIS = 5 * 60_000L

    /** Per COPY call, so that progress is reported and cancellation noticed. */
    private const val COPY_CHUNK_SIZE = 64L * 1024 * 1024

    private val pools = mutableMapOf<Authority, Pool>()

    /** Pools replaced after an edit, kept until their open files are closed. */
    private val retiredPools = mutableListOf<Pool>()

    private val directoryFileAttributesCache =
        Collections.synchronizedMap(WeakHashMap<Path, NfsStat>())

    /** Connects spare contexts ahead of need. */
    private val warmUpExecutor by lazy {
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "NfsWarmUp").apply { isDaemon = true }
        }
    }

    /** Started on first use, so apps that never touch NFS pay nothing. */
    private val pump by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "NfsClientPump").apply { isDaemon = true }
        }.apply {
            scheduleWithFixedDelay(
                { runPump() }, PUMP_INTERVAL_MILLIS, PUMP_INTERVAL_MILLIS, TimeUnit.MILLISECONDS
            )
        }
    }

    // Metadata.

    @Throws(ClientException::class)
    fun stat(path: Path): NfsStat {
        synchronized(directoryFileAttributesCache) {
            directoryFileAttributesCache[path]?.let {
                if (!it.isSymbolicLink) {
                    return it.also { directoryFileAttributesCache -= path }
                }
            }
        }
        return readMetadata(path) { Nfs.stat(it, path.remotePathBytes) }
    }

    @Throws(ClientException::class)
    fun lstat(path: Path): NfsStat {
        synchronized(directoryFileAttributesCache) {
            directoryFileAttributesCache[path]?.let {
                return it.also { directoryFileAttributesCache -= path }
            }
        }
        return readMetadata(path) { Nfs.lstat(it, path.remotePathBytes) }
    }

    /** Lists a directory; the attributes of each child (from lstat) are cached for one use. */
    @Throws(ClientException::class)
    fun readDir(path: Path): List<Path> {
        val entries = readMetadata(path) { Nfs.readDir(it, path.remotePathBytes) }
        return entries.map { entry ->
            path.resolveChild(entry.name.toByteString()).also {
                directoryFileAttributesCache[it] = entry.stat
            }
        }
    }

    @Throws(ClientException::class)
    fun readLink(path: Path): ByteString =
        readMetadata(path) { Nfs.readLink(it, path.remotePathBytes) }.toByteString()

    @Throws(ClientException::class)
    fun statVfs(path: Path): NfsStatVfs = readMetadata(path) { Nfs.statVfs(it, path.remotePathBytes) }

    @Throws(ClientException::class)
    fun access(path: Path, mode: Int) {
        readMetadata(path) { Nfs.access(it, path.remotePathBytes, mode) }
    }

    // Mutations.

    @Throws(ClientException::class)
    fun mkdir(path: Path, mode: Int) {
        mutate(path) { Nfs.mkdir(it, path.remotePathBytes, mode) }
        LocalWatchService.onEntryCreated(path as Java8Path)
    }

    @Throws(ClientException::class)
    fun rmdir(path: Path) {
        mutate(path) { Nfs.rmdir(it, path.remotePathBytes) }
        directoryFileAttributesCache -= path
        LocalWatchService.onEntryDeleted(path as Java8Path)
    }

    @Throws(ClientException::class)
    fun unlink(path: Path) {
        mutate(path) { Nfs.unlink(it, path.remotePathBytes) }
        directoryFileAttributesCache -= path
        LocalWatchService.onEntryDeleted(path as Java8Path)
    }

    /** Removes a file, symbolic link or empty directory. */
    @Throws(ClientException::class)
    fun remove(path: Path) {
        if (lstat(path).isDirectory) rmdir(path) else unlink(path)
    }

    /** NFS RENAME replaces an existing target; callers check for existence first. */
    @Throws(ClientException::class)
    fun rename(path: Path, newPath: Path) {
        requireSameExport(path, newPath)
        mutate(path) { Nfs.rename(it, path.remotePathBytes, newPath.remotePathBytes) }
        directoryFileAttributesCache -= path
        directoryFileAttributesCache -= newPath
        LocalWatchService.onEntryDeleted(path as Java8Path)
        LocalWatchService.onEntryCreated(newPath as Java8Path)
    }

    @Throws(ClientException::class)
    fun link(existing: Path, link: Path) {
        requireSameExport(existing, link)
        mutate(link) { Nfs.link(it, existing.remotePathBytes, link.remotePathBytes) }
        LocalWatchService.onEntryCreated(link as Java8Path)
    }

    @Throws(ClientException::class)
    fun symlink(link: Path, target: ByteString) {
        mutate(link) { Nfs.symlink(it, target.borrowBytes(), link.remotePathBytes) }
        LocalWatchService.onEntryCreated(link as Java8Path)
    }

    @Throws(ClientException::class)
    fun chmod(path: Path, mode: Int) {
        mutate(path) { Nfs.chmod(it, path.remotePathBytes, mode) }
        onAttributesChanged(path)
    }

    @Throws(ClientException::class)
    fun chown(path: Path, uid: Int, gid: Int, noFollowLinks: Boolean) {
        mutate(path) {
            if (noFollowLinks) {
                Nfs.lchown(it, path.remotePathBytes, uid, gid)
            } else {
                Nfs.chown(it, path.remotePathBytes, uid, gid)
            }
        }
        onAttributesChanged(path)
    }

    @Throws(ClientException::class)
    fun utimes(
        path: Path,
        atimeSeconds: Long,
        atimeNanoseconds: Long,
        mtimeSeconds: Long,
        mtimeNanoseconds: Long,
        noFollowLinks: Boolean
    ) {
        mutate(path) {
            if (noFollowLinks) {
                Nfs.lutimes(
                    it, path.remotePathBytes, atimeSeconds, atimeNanoseconds, mtimeSeconds,
                    mtimeNanoseconds
                )
            } else {
                Nfs.utimes(
                    it, path.remotePathBytes, atimeSeconds, atimeNanoseconds, mtimeSeconds,
                    mtimeNanoseconds
                )
            }
        }
        onAttributesChanged(path)
    }

    private fun onAttributesChanged(path: Path) {
        directoryFileAttributesCache -= path
        LocalWatchService.onEntryModified(path as Java8Path)
    }

    // Files.

    /**
     * Opens a file. [flags] are [Nfs] open flags without `O_APPEND`: appending is done by the
     * channel at the current end of file, like the other remote providers.
     */
    @Throws(ClientException::class)
    fun openByteChannel(
        path: Path,
        flags: Int,
        mode: Int,
        isAppend: Boolean
    ): SeekableByteChannel {
        val pool = getPool(path.authority)
        val context = pool.acquire(forFile = true)
        val file = try {
            context.use { Nfs.open(it, path.remotePathBytes, flags, mode) }
        } catch (e: ClientException) {
            pool.releaseFile(context)
            throw e
        }
        if ((flags and (Nfs.O_CREAT or Nfs.O_TRUNC)) != 0) {
            directoryFileAttributesCache -= path
        }
        NetworkLock.onFileOpened()
        // Only files opened read-only may be served from the local read cache: a writer must see
        // its own and others' changes.
        val isReadOnly = (flags and (Nfs.O_WRONLY or Nfs.O_RDWR)) == 0
        if (!isReadOnly) {
            NfsReadCache.invalidate(path.authority, path.remotePathBytes)
        }
        val channel = FileByteChannel(
            context, file, isAppend, path.authority, path.remotePathBytes.copyOf(), isReadOnly,
            path.remotePath.toString()
        ) {
            pool.releaseFile(context)
            NetworkLock.onFileClosed()
        }
        if (isReadOnly) {
            // Streaming uses up to 32 more connections: have them connected (TCP, TLS, session)
            // by the time reading gets there, so that it only has to open the file on them.
            pool.warmUp(WARM_CONNECTIONS)
            // Reading changes nothing: no modification event (each one made the file list reload,
            // which restarted loading the thumbnails, whose reads made it reload again).
            return channel
        }
        return NotifyEntryModifiedSeekableByteChannel(channel, path as Java8Path)
    }

    // Server-side copy.

    /** Number of copies done on the server, for tests. */
    @Volatile
    var serverSideCopyCount = 0
        private set

    /** Network changes that reset the connections; read by tests. */
    @Volatile
    var networkChangeCount = 0
        private set

    /**
     * Copies a regular file inside the server: CLONE (instant, shares blocks) where the file
     * system supports it, otherwise COPY in chunks. The data never crosses the network.
     *
     * Returns false, having changed nothing, when the server cannot copy these files (different
     * exports or file systems, or no COPY support); the caller then copies through the client.
     * [targetFlags] are [Nfs] open flags including O_CREAT (and O_EXCL when not replacing).
     */
    @Throws(ClientException::class, java.io.InterruptedIOException::class)
    fun serverSideCopy(
        source: Path,
        target: Path,
        targetFlags: Int,
        mode: Int,
        size: Long,
        intervalMillis: Long,
        listener: ((Long) -> Unit)?
    ): Boolean {
        if (source.authority != target.authority) {
            return false
        }
        val pool = getPool(source.authority)
        val context = pool.acquire(forFile = true)
        try {
            val sourceFile = context.use { Nfs.open(it, source.remotePathBytes, Nfs.O_RDONLY, 0) }
            try {
                val targetFile = context.use {
                    Nfs.open(it, target.remotePathBytes, targetFlags, mode)
                }
                var successful = false
                var unsupported = false
                try {
                    val cloned = try {
                        context.use { Nfs.clone(it, sourceFile, 0, targetFile, 0, 0) }
                        true
                    } catch (e: ClientException) {
                        if (!e.isUnsupportedCopy) {
                            throw e
                        }
                        false
                    }
                    if (cloned) {
                        listener?.invoke(size)
                    } else {
                        var copied = 0L
                        var lastProgressMillis = SystemClock.elapsedRealtime()
                        var unreportedSize = 0L
                        while (copied < size) {
                            if (Thread.interrupted()) {
                                throw java.io.InterruptedIOException()
                            }
                            val count = try {
                                context.use {
                                    Nfs.copy(
                                        it, sourceFile, copied, targetFile, copied,
                                        (size - copied).coerceAtMost(COPY_CHUNK_SIZE)
                                    )
                                }
                            } catch (e: ClientException) {
                                if (copied == 0L && e.isUnsupportedCopy) {
                                    unsupported = true
                                    return false
                                }
                                throw e
                            }
                            if (count <= 0) {
                                throw ClientException(
                                    android.system.OsConstants.EIO, "Server-side copy stalled"
                                )
                            }
                            copied += count
                            unreportedSize += count
                            val now = SystemClock.elapsedRealtime()
                            if (listener != null && now >= lastProgressMillis + intervalMillis) {
                                listener(unreportedSize)
                                lastProgressMillis = now
                                unreportedSize = 0
                            }
                        }
                        listener?.invoke(unreportedSize)
                    }
                    // COPY results may be unstable; commit before reporting success.
                    context.use { Nfs.fsync(it, targetFile) }
                    successful = true
                } finally {
                    runCatching { context.use { Nfs.close(it, targetFile) } }
                    if (!successful) {
                        // Nothing useful was created: remove it, so that falling back to a client
                        // copy (or reporting the error) starts clean.
                        runCatching { context.use { Nfs.unlink(it, target.remotePathBytes) } }
                    }
                    if (unsupported) {
                        directoryFileAttributesCache -= target
                    }
                }
            } finally {
                runCatching { context.use { Nfs.close(it, sourceFile) } }
            }
        } finally {
            pool.releaseFile(context)
        }
        ++serverSideCopyCount
        directoryFileAttributesCache -= target
        LocalWatchService.onEntryCreated(target as Java8Path)
        return true
    }

    // Pool plumbing.

    @Throws(ClientException::class)
    private fun <T> readMetadata(path: Path, block: (Long) -> T): T {
        val pool = getPool(path.authority)
        var attempt = 0
        while (true) {
            val context = pool.acquire(forFile = false)
            try {
                return context.use(block)
            } catch (e: ClientException) {
                if (!e.isTransportError || attempt > 0) {
                    throw e
                }
                ++attempt
            } finally {
                pool.onReleased()
            }
        }
    }

    @Throws(ClientException::class)
    private fun <T> mutate(path: Path, block: (Long) -> T): T {
        val pool = getPool(path.authority)
        val context = pool.acquire(forFile = false)
        try {
            return context.use(block)
        } finally {
            pool.onReleased()
        }
    }

    @Throws(ClientException::class)
    private fun requireSameExport(path: Path, other: Path) {
        if (path.authority != other.authority) {
            throw ClientException(android.system.OsConstants.EXDEV, "Different NFS exports")
        }
    }

    @Throws(ClientException::class)
    private fun getPool(authority: Authority): Pool {
        val options = authenticator.getConnectionOptions(authority)
            ?: throw ClientException("No connection options found for $authority")
        synchronized(pools) {
            val pool = pools[authority]
            if (pool != null && pool.options == options) {
                return pool
            }
            // The server was edited: drop connections made with the old identity.
            if (pool != null) {
                pool.retire()
                retiredPools += pool
            }
            pump
            NetworkMonitor.start { onNetworkChanged() }
            return Pool(authority, options).also { pools[authority] = it }
        }
    }

    /**
     * An additional connection for a file's parallel streaming: idle and not one of [exclude],
     * or a new one while the export has room. The caller mounts it (off the reading thread) and
     * gives it back with [releaseExtraContext]; idle ones stay connected for a while, so the next
     * open of a file (players reopen it several times) reuses them.
     */
    @Throws(ClientException::class)
    internal fun acquireExtraContext(authority: Authority, exclude: Collection<Context>): Context? =
        getPool(authority).acquireExtra(exclude)

    @Throws(ClientException::class)
    internal fun releaseExtraContext(authority: Authority, context: Context) {
        getPool(authority).releaseFile(context)
    }

    /** Moves every connection to the current network right away. */
    private fun onNetworkChanged() {
        ++networkChangeCount
        val pools = synchronized(pools) { pools.values + retiredPools }
        for (pool in pools) {
            pool.resetConnections()
        }
    }

    private fun runPump() {
        val pools = synchronized(pools) {
            retiredPools.removeAll { it.isEmpty }
            pools.values + retiredPools
        }
        for (pool in pools) {
            pool.pump()
        }
    }

    private class Pool(val authority: Authority, val options: ConnectionOptions) {
        private val contexts = mutableListOf<Context>()

        private var isRetired = false

        val isEmpty: Boolean
            @Synchronized get() = contexts.isEmpty()

        @Synchronized
        fun acquire(forFile: Boolean): Context {
            removeDeadLocked()
            val healthy = contexts.filter { !it.isBroken }
            // Prefer an idle context; for files also prefer one with few open files.
            val idle = healthy.filter { !it.lock.isLocked }
            val candidate = if (forFile) {
                idle.minByOrNull { it.openFileCount }
            } else {
                idle.firstOrNull()
            }
            val context = candidate
                ?: if (healthy.size < MAX_CONTEXTS_PER_EXPORT) {
                    Context(authority, options).also { contexts += it }
                } else {
                    healthy.minByOrNull { it.lock.queueLength + it.openFileCount }!!
                }
            if (forFile) {
                ++context.openFileCount
                // A file keeps its context busy for long transfers. Have another one connected
                // (TCP, TLS, session) by the time a listing or another file needs it: players
                // and their metadata readers ask for more right after opening.
                val hasSpare = healthy.any {
                    it !== context && it.openFileCount == 0 && !it.lock.isLocked
                }
                if (!hasSpare && !isRetired && contexts.size < MAX_CONTEXTS_PER_EXPORT) {
                    val spare = Context(authority, options).also { contexts += it }
                    warmUpExecutor.execute {
                        try {
                            spare.use { }
                        } catch (e: ClientException) {
                            // It is marked broken and dropped; the next user reconnects.
                        }
                    }
                }
            }
            return context
        }

        /**
         * Starts connecting new contexts in parallel, in the background, until the export has
         * [count] (idle ones then stay up for [IDLE_TIMEOUT_MILLIS]).
         */
        @Synchronized
        fun warmUp(count: Int) {
            removeDeadLocked()
            if (isRetired) {
                return
            }
            val target = count.coerceAtMost(MAX_CONTEXTS_PER_EXPORT)
            while (contexts.count { !it.isBroken } < target) {
                val context = Context(authority, options).also { contexts += it }
                warmUpExecutor.execute {
                    try {
                        context.use { }
                    } catch (e: ClientException) {
                        // It is marked broken and dropped; the next user reconnects.
                    }
                }
            }
        }

        @Synchronized
        fun acquireExtra(exclude: Collection<Context>): Context? {
            removeDeadLocked()
            if (isRetired) {
                return null
            }
            val context = contexts.firstOrNull {
                !it.isBroken && it !in exclude && it.openFileCount == 0 && !it.lock.isLocked
            } ?: if (contexts.size < MAX_CONTEXTS_PER_EXPORT) {
                Context(authority, options).also { contexts += it }
            } else {
                return null
            }
            ++context.openFileCount
            return context
        }

        @Synchronized
        fun releaseFile(context: Context) {
            --context.openFileCount
            removeDeadLocked()
        }

        @Synchronized
        fun onReleased() {
            removeDeadLocked()
        }

        private fun removeDeadLocked() {
            val iterator = contexts.iterator()
            while (iterator.hasNext()) {
                val context = iterator.next()
                if (context.isBroken && context.openFileCount == 0) {
                    iterator.remove()
                    destroyInBackground(context)
                }
            }
        }

        fun pump() {
            val now = SystemClock.elapsedRealtime()
            val snapshot = synchronized(this) {
                removeDeadLocked()
                // Close connections nobody used for a while, but keep one warm unless retired.
                val idle = contexts.filter {
                    it.openFileCount == 0 && !it.lock.isLocked
                        && (isRetired || now - it.lastUsedMillis > IDLE_TIMEOUT_MILLIS)
                }
                // Keep the last connection a while longer, so browsing back is instant.
                val keepWarm = !isRetired && idle.size == contexts.size && idle.any {
                    now - it.lastUsedMillis <= LAST_CONTEXT_IDLE_TIMEOUT_MILLIS
                }
                for (context in idle.sortedBy { it.lastUsedMillis }
                    .dropLast(if (keepWarm) 1 else 0)) {
                    contexts -= context
                    destroyInBackground(context)
                }
                contexts.toList()
            }
            for (context in snapshot) {
                context.serviceIfIdle()
            }
        }

        fun resetConnections() {
            val snapshot = synchronized(this) { contexts.toList() }
            for (context in snapshot) {
                context.resetConnection()
            }
        }

        /** Stops handing out contexts; the pump destroys them once their files are closed. */
        @Synchronized
        fun retire() {
            isRetired = true
        }

        private fun destroyInBackground(context: Context) {
            pump.execute { context.destroy() }
        }
    }

    interface Path {
        val authority: Authority
        val remotePath: ByteString
        fun resolveChild(name: ByteString): Path

        val remotePathBytes: ByteArray
            get() = remotePath.borrowBytes()
    }
}

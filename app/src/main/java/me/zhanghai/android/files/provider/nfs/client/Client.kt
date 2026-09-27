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

    /** Connections per export beyond what one file may use (a listing, a second file). */
    internal const val CONTEXTS_BEYOND_FILE = 8
    /** More allowed for connections that serve seeks, so that a file is never left without. */
    internal const val RESERVED_CONTEXTS_OVER_LIMIT = 4
    /**
     * Connected ahead of need when a file is opened for reading (see [Pool.warmUp]): the file's
     * own plus a few for parallel pieces. Streaming connects the rest; connecting all 33 at once
     * competed with the first reads (TLS handshakes) and slowed them down.
     */
    private const val GRADUAL_WARM_CONNECTIONS = 5

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
        closeIdleSharedFile(path.authority to path.remotePath)
        mutate(path) { Nfs.unlink(it, path.remotePathBytes) }
        forgetReadCache(path)
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
        closeIdleSharedFile(path.authority to path.remotePath)
        closeIdleSharedFile(newPath.authority to newPath.remotePath)
        mutate(path) { Nfs.rename(it, path.remotePathBytes, newPath.remotePathBytes) }
        forgetReadCache(path)
        forgetReadCache(newPath)
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
    /**
     * Reads of NFS files started from the current thread (or coroutine, as a context element) are
     * for thumbnails: a few MB each, on the file's own connection only (see
     * [FileByteChannel.Profile.THUMBNAIL]).
     */
    val thumbnailReads = ThreadLocal<Boolean>()

    /** Files open read-only, shared by all their descriptors (see [ReadDescriptorChannel]). */
    private class SharedFile(val file: FileByteChannel) {
        var descriptors = 0
        var closeTask: java.util.concurrent.ScheduledFuture<*>? = null
    }

    private val sharedFiles = mutableMapOf<Pair<Authority, ByteString>, SharedFile>()

    /**
     * Closes files in parallel: closing waits for the file's own connection to finish its current
     * request (seconds over a slow link), and one close must never hold up the next, or their
     * connections would stay taken while other files wait for them.
     */
    private val sharedFileCloser by lazy {
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "NfsSharedFileCloser").apply { isDaemon = true }
        }
    }

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
        val isReadOnly = (flags and (Nfs.O_WRONLY or Nfs.O_RDWR)) == 0
        val key = path.authority to path.remotePath
        val profile = when {
            !isReadOnly -> FileByteChannel.Profile.WRITE
            thumbnailReads.get() == true -> FileByteChannel.Profile.THUMBNAIL
            else -> FileByteChannel.Profile.STREAM
        }
        if (isReadOnly) {
            attachSharedFile(path, key, profile)?.let { return it }
        } else {
            // A file about to change: no descriptor may keep reading the old data from memory.
            closeIdleSharedFile(key)
        }
        val pool = getPool(path.authority)
        val owner = "${path.remotePath} own"
        val context = pool.acquire(forFile = true, owner)
        val file = try {
            context.use { Nfs.open(it, path.remotePathBytes, flags, mode) }
        } catch (e: ClientException) {
            pool.releaseFile(context, owner)
            throw e
        }
        if ((flags and (Nfs.O_CREAT or Nfs.O_TRUNC)) != 0) {
            directoryFileAttributesCache -= path
        }
        NetworkLock.onFileOpened()
        // Only files opened read-only may be served from the local read cache: a writer must see
        // its own and others' changes.
        if (!isReadOnly) {
            NfsReadCache.invalidate(path.authority, path.remotePathBytes)
        }
        val channel = FileByteChannel(
            context, file, isAppend, path.authority, path.remotePathBytes.copyOf(), isReadOnly,
            path.remotePath.toString(), profile
        ) {
            pool.releaseFile(context, owner)
            NetworkLock.onFileClosed()
        }
        if (!isReadOnly) {
            return NotifyEntryModifiedSeekableByteChannel(channel, path as Java8Path)
        }
        if (profile == FileByteChannel.Profile.STREAM) {
            // Streaming uses more connections: have a few connected (TCP, TLS, session) by the
            // time reading gets there, so that it only has to open the file on them.
            // All the file may use, except with gradual growth (that connects them as it goes).
            pool.warmUp(
                if (pool.options.connectionGrowth ==
                    ConnectionOptions.ConnectionGrowth.GRADUAL) {
                    GRADUAL_WARM_CONNECTIONS
                } else {
                    pool.options.maxConnections + 1
                }
            )
        }
        // Its version now: descriptors opening it later share it only while it is unchanged.
        channel.resolveVersion()
        // Reading changes nothing: no modification event (each one made the file list reload,
        // which restarted loading the thumbnails, whose reads made it reload again).
        val shared = synchronized(sharedFiles) {
            sharedFiles[key]?.takeIf { it.closeTask == null || it.descriptors > 0 }
                ?.also { existing ->
                    // Opened meanwhile by another descriptor: use that one.
                    existing.descriptors++
                    existing.closeTask?.cancel(false)
                    existing.closeTask = null
                }
                ?: SharedFile(channel).also {
                    it.descriptors = 1
                    sharedFiles[key] = it
                }
        }
        if (shared.file !== channel) {
            runCatching { channel.closeShared() }
        }
        return ReadDescriptorChannel(shared.file) { releaseSharedFile(key, shared) }
    }

    /**
     * Shares the file already open at [path], if it is still the same version on the server
     * (close-to-open consistency, like the kernel's NFS client): a player closing and reopening a
     * file changed or deleted meanwhile by another client must see the change, not the old
     * memory and cache of the open one (Android closes descriptors late, so the old one may still
     * be open). Costs one GETATTR.
     */
    private fun attachSharedFile(
        path: Path,
        key: Pair<Authority, ByteString>,
        profile: FileByteChannel.Profile
    ): SeekableByteChannel? {
        val candidate = synchronized(sharedFiles) { sharedFiles[key] } ?: return null
        val isSameVersion = try {
            val current = readMetadata(path) { Nfs.stat(it, path.remotePathBytes) }
            NfsReadCache.version(current) == candidate.file.version
        } catch (e: ClientException) {
            // Gone; otherwise it could not tell (a network error), and the open one is as good
            // as a new one.
            e.errno != android.system.OsConstants.ENOENT
        }
        val shared = synchronized(sharedFiles) {
            val shared = sharedFiles[key]
            if (shared !== candidate) {
                // Closed meanwhile: opened anew.
                return null
            }
            if (!isSameVersion) {
                // Changed or gone: the descriptors that have it keep reading it; new ones open
                // the file as it is now (or fail if it is gone).
                sharedFiles -= key
                return null
            }
            shared.descriptors++
            shared.closeTask?.cancel(false)
            shared.closeTask = null
            shared
        }
        if (profile == FileByteChannel.Profile.STREAM) {
            // A player opening a file whose thumbnail was just read.
            shared.file.profile = FileByteChannel.Profile.STREAM
        }
        return ReadDescriptorChannel(shared.file) { releaseSharedFile(key, shared) }
    }

    /**
     * When the last descriptor closes, the file closes: its connections go back to the pool at
     * once (closing takes a round trip, so it runs in the background). A reopen starts a new one
     * from warm connections, and what was read comes back from the disk cache.
     */
    private fun releaseSharedFile(key: Pair<Authority, ByteString>, shared: SharedFile) {
        synchronized(sharedFiles) {
            if (--shared.descriptors > 0) {
                return
            }
            if (sharedFiles[key] === shared) {
                sharedFiles -= key
            }
        }
        shared.file.releaseExtraConnections()
        sharedFileCloser.execute { runCatching { shared.file.closeShared() } }
    }

    /**
     * Drops what the read cache holds for [path] (deleted, renamed, or replaced by a rename), and
     * stops a file still being read there from storing more.
     */
    private fun forgetReadCache(path: Path) {
        synchronized(sharedFiles) { sharedFiles[path.authority to path.remotePath] }
            ?.file?.stopCaching()
        NfsReadCache.invalidate(path.authority, path.remotePathBytes)
    }

    private fun closeIdleSharedFile(key: Pair<Authority, ByteString>) {
        val shared = synchronized(sharedFiles) {
            val shared = sharedFiles[key]?.takeIf { it.descriptors == 0 } ?: return
            shared.closeTask?.cancel(false)
            sharedFiles -= key
            shared
        }
        runCatching { shared.file.closeShared() }
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
        closeIdleSharedFile(target.authority to target.remotePath)
        val pool = getPool(source.authority)
        val context = pool.acquire(forFile = true, "copy")
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
            pool.releaseFile(context, "copy")
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
    internal fun acquireExtraContext(
        authority: Authority,
        exclude: Collection<Context>,
        isReserved: Boolean,
        owner: String
    ): Context? = getPool(authority).acquireExtra(exclude, isReserved, owner)

    @Throws(ClientException::class)
    internal fun releaseExtraContext(authority: Authority, context: Context, owner: String) {
        getPool(authority).releaseFile(context, owner)
    }

    /**
     * Connections of every export, and how many are bound to open files (for tests: none may
     * stay bound once every file is closed).
     */
    internal fun describeBoundConnections(): String {
        val pools = synchronized(pools) { pools.values + retiredPools }
        val bound = pools.flatMap { it.describeBound() }
        val threads = Thread.getAllStackTraces().entries
            .filter { it.key.name.startsWith("Nfs") }
            .joinToString("; ") { (thread, stack) ->
                "${thread.name} ${thread.state} at " + stack.take(5).joinToString(" < ") {
                    "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
                }
            }
        return "bound: ${bound.joinToString(" | ")}; threads: $threads"
    }

    internal fun connectionCounts(): Pair<Int, Int> {
        val pools = synchronized(pools) { pools.values + retiredPools }
        return pools.map { it.counts() }.fold(0 to 0) { total, counts ->
            total.first + counts.first to total.second + counts.second
        }
    }

    /** Moves every connection to the current network right away. */
    private fun onNetworkChanged() {
        ++networkChangeCount
        NfsLog.log("network changed: resetting all connections")
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

        /** A file's own connection and its extra ones, plus a few for everything else. */
        private val maxContexts = options.maxConnections + 1 + CONTEXTS_BEYOND_FILE

        private var isRetired = false

        val isEmpty: Boolean
            @Synchronized get() = contexts.isEmpty()

        private fun newContextLocked(): Context =
            Context(authority, options).also {
                contexts += it
                ConnectionStats.opened.incrementAndGet()
                ConnectionStats.updatePeak(ConnectionStats.peakTotal, contexts.size)
            }

        private fun onBoundLocked() {
            ConnectionStats.updatePeak(
                ConnectionStats.peakInUse, contexts.count { it.openFileCount > 0 }
            )
        }

        /** The connections bound to open files, and who uses them (for tests). */
        @Synchronized
        fun describeBound(): List<String> {
            val now = SystemClock.elapsedRealtime()
            return contexts.filter { it.openFileCount > 0 }.map {
                "${it.openFileCount} file(s) ${it.owners}" + (if (it.isBroken) ", broken" else "") +
                    (it.holder?.let { holder ->
                        ", in use by $holder for ${now - it.heldSinceMillis} ms"
                    } ?: ", idle for ${now - it.lastUsedMillis} ms")
            }
        }

        /** Connections of this export, and how many are bound to open files. */
        @Synchronized
        fun counts(): Pair<Int, Int> = contexts.size to contexts.count { it.openFileCount > 0 }

        @Synchronized
        fun acquire(forFile: Boolean, owner: String = ""): Context {
            removeDeadLocked()
            val healthy = contexts.filter { !it.isBroken }
            // Prefer an idle context; for files also prefer one with few open files.
            val idle = healthy.filter { !it.lock.isLocked }
            val candidate = if (forFile) {
                idle.minByOrNull { it.openFileCount }
            } else {
                idle.firstOrNull()
            }
            // A file's own connection is never shared with another file's work while the export
            // may still grow a little (measured: shared, a seek waited 10 s behind the other
            // file's reads); the excess closes with the file (releaseFile).
            val limit = if (forFile) maxContexts + RESERVED_CONTEXTS_OVER_LIMIT else maxContexts
            val context = candidate
                ?: if (healthy.size < limit) {
                    newContextLocked()
                } else {
                    healthy.minByOrNull { it.lock.queueLength + it.openFileCount }!!
                }
            if (forFile) {
                ++context.openFileCount
                context.owners += owner
                onBoundLocked()
                // A file keeps its context busy for long transfers. Have another one connected
                // (TCP, TLS, session) by the time a listing or another file needs it: players
                // and their metadata readers ask for more right after opening.
                val hasSpare = healthy.any {
                    it !== context && it.openFileCount == 0 && !it.lock.isLocked
                }
                if (!hasSpare && !isRetired && contexts.size < maxContexts) {
                    val spare = newContextLocked()
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
            val target = count.coerceAtMost(maxContexts)
            while (contexts.count { !it.isBroken } < target) {
                val context = newContextLocked()
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
        fun acquireExtra(exclude: Collection<Context>, isReserved: Boolean, owner: String):
            Context? {
            removeDeadLocked()
            if (isRetired) {
                return null
            }
            val context = contexts.firstOrNull {
                !it.isBroken && it !in exclude && it.openFileCount == 0 && !it.lock.isLocked
            } ?: if (contexts.size < maxContexts +
                (if (isReserved) RESERVED_CONTEXTS_OVER_LIMIT else 0)) {
                newContextLocked()
            } else {
                ConnectionStats.refused.incrementAndGet()
                return null
            }
            ++context.openFileCount
            context.owners += owner
            onBoundLocked()
            return context
        }

        @Synchronized
        fun releaseFile(context: Context, owner: String) {
            --context.openFileCount
            context.owners -= owner
            removeDeadLocked()
            // Connections over the limit (reserved ones for seeks may exceed it) close as soon as
            // they are free: the excess never outlives the files that needed it.
            if (context.openFileCount == 0 && contexts.size > maxContexts &&
                contexts.remove(context)) {
                destroyInBackground(context)
            }
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

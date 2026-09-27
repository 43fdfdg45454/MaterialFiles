package me.zhanghai.android.files.provider.nfs

import java.nio.ByteBuffer
import java.util.Random
import java8.nio.file.Files
import java8.nio.file.LinkOption
import java8.nio.file.Path
import java8.nio.file.StandardOpenOption
import javax.net.ssl.SSLContext
import me.zhanghai.android.files.provider.common.exists
import me.zhanghai.android.files.provider.common.newByteChannel
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.provider.common.size
import me.zhanghai.android.files.provider.nfs.client.Client
import me.zhanghai.android.files.provider.nfs.client.ConnectionOptions
import me.zhanghai.android.files.provider.nfs.client.ConnectionStats
import me.zhanghai.android.files.storage.NfsServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real use of NFS files under load, shared by two runs:
 * - [NfsProviderTest] on the emulator, reading through the file provider (Android's file proxy,
 *   as VLC does), over the LAN-like link.
 * - NfsHostLoadTest on the CI machine itself (Robolectric), reading through the app's channel,
 *   over a faithful VPN-like link (netem, without the emulator's user-mode network, which drops
 *   connections under load).
 *
 * Subclasses give the server, where to work, how a player opens a file and where results go.
 */
abstract class NfsScenarios {
    protected abstract val server: NfsServer
    protected abstract val root: Path
    protected abstract val security: ConnectionOptions.Security

    /** A file as a player reads it: its size, and reads at any position. */
    interface VideoReader : java.io.Closeable {
        val size: Long

        /** Reads up to [length] bytes at [position]; 0 or less at end of file. */
        @Throws(java.io.IOException::class)
        fun read(buffer: ByteArray, offset: Int, length: Int, position: Long): Int
    }

    /** Opens [path] as a player does. */
    protected abstract fun openReader(path: Path): VideoReader

    /** Publishes one line of results (CI annotations). */
    protected abstract fun reportLine(line: String)

    /** A test argument (instrumentation argument, or system property on the host). */
    protected abstract fun argument(name: String): String?

    /** For the other client of the change tests, with TLS. */
    protected abstract fun testSslContext(security: ConnectionOptions.Security): SSLContext

    protected lateinit var connectionsAtStart: ConnectionStats.Snapshot

    /** Called by subclasses once the server is set up: statistics start from here. */
    protected fun startMeasuring() {
        me.zhanghai.android.files.provider.nfs.client.FileByteChannel.isStatsEnabled = true
        connectionsAtStart = ConnectionStats.snapshot()
        ConnectionStats.resetPeaks()
    }

    /**
     * A test still running after [TEST_TIMEOUT_MILLIS] fails with what every thread is doing
     * (CI logs cannot be read: the dump goes out as the failure), and the next one runs.
     */
    @get:org.junit.Rule
    val watchdog = org.junit.rules.TestRule { base, description ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val error = java.util.concurrent.atomic.AtomicReference<Throwable>()
                val thread = Thread({
                    try {
                        base.evaluate()
                    } catch (t: Throwable) {
                        error.set(t)
                    }
                }, "Test-${description.methodName}")
                thread.start()
                thread.join(TEST_TIMEOUT_MILLIS)
                if (thread.isAlive) {
                    throw AssertionError(
                        "${description.methodName} still running after " +
                            "${TEST_TIMEOUT_MILLIS / 60_000} min; files with connections: " +
                            me.zhanghai.android.files.provider.nfs.client.FileByteChannel
                                .describeFilesWithConnections() + "; " +
                            Client.describeBoundConnections().substringBefore("; threads:") +
                            "; threads: " + threadDump()
                    )
                }
                error.get()?.let { throw it }
            }
        }
    }

    /** The threads that matter for a hang, with where they are. */
    protected fun threadDump(): String =
        Thread.getAllStackTraces().entries
            .filter { (thread, stack) ->
                stack.isNotEmpty() && (thread.name.startsWith("Nfs") ||
                    thread.name.startsWith("Test-") || thread.name.startsWith("Thread-") ||
                    thread.name.contains("Proxy", ignoreCase = true) ||
                    thread.name.contains("Fuse", ignoreCase = true)) &&
                    // Idle pool threads say nothing.
                    stack.none { it.methodName == "getTask" || it.methodName == "take" }
            }
            .joinToString(" || ") { (thread, stack) ->
                "${thread.name} ${thread.state}: " + stack.take(10).joinToString(" < ") {
                    "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
                }
            }
            .take(20_000)

    // Real use under load: videos of real sizes (CI fixtures on the server, see the workflow),
    // played at a real bitrate, scrubbed, switched, several at once, next to an upload. Every
    // 8-byte word of a fixture holds its offset plus the file's tag in bits 48 and up, so that
    // every byte read is checked and data of another file is caught.
    //
    // The limits are those of a good experience, not of what merely works: a video starts within
    // 1.5 s, a seek shows the picture within 1 s on average and never over 3 s, playback never
    // stalls, closing is instant, and what was seen before comes back at once from the read cache.

    /** A fixture video: its path, size and tag. */
    protected class Video(val path: Path, val size: Long, val tag: Long) {
        val name: String
            get() = path.fileName.toString()
    }

    /** `movie-1.bin` (250 MB), `movie-2.bin` (500 MB), `movie-3.bin` (700 MB), episodes 1–3. */
    protected fun movie(number: Int) = video("movie-$number.bin", number.toLong())

    /** `episode-1.bin` to `episode-3.bin`, 250 MB each. */
    protected fun episode(number: Int) = video("episode-$number.bin", 10L + number)

    protected fun video(name: String, tag: Long): Video {
        val path = server.path.resolve(".mf-fixtures/$name")
        assumeTrue("CI fixture $name", path.exists(LinkOption.NOFOLLOW_LINKS))
        return Video(path, path.size(), tag)
    }

    protected fun open(video: Video): VideoReader = openReader(video.path)

    /** Reads [length] bytes of [video] at [position] and checks them; milliseconds taken. */
    protected fun readVideo(
        pfd: VideoReader, video: Video, position: Long, length: Int,
        what: String, buffer: ByteArray = ByteArray(length)
    ): Long {
        val start = System.nanoTime()
        var done = 0
        while (done < length) {
            val count = try {
                pfd.read(buffer, done, length - done, position + done)
            } catch (e: java.io.IOException) {
                throw AssertionError(
                    "$what: read of ${video.name} at ${position + done} failed: $e; channel: " +
                        me.zhanghai.android.files.provider.nfs.client.FileByteChannel
                            .lastReadError?.toString(), e
                )
            }
            assertTrue("$what: read of ${video.name} at ${position + done} returned $count",
                count > 0)
            done += count
        }
        val millis = (System.nanoTime() - start) / 1_000_000
        val words = ByteBuffer.wrap(buffer, 0, length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val tag = video.tag shl 48
        for (i in 0 until length / 8) {
            val offset = position + i * 8
            val word = words.getLong(i * 8)
            if (word != offset + tag) {
                throw AssertionError(
                    "$what: word at $offset of ${video.name} is ${word and 0xFFFFFFFFFFFFL} of " +
                        "file tag ${word ushr 48}"
                )
            }
        }
        return millis
    }

    /** A seek: the first 64 KB at [position] (what shows the picture); milliseconds. */
    protected fun seek(pfd: VideoReader, video: Video, position: Long,
        what: String): Long =
        readVideo(pfd, video, position / 8 * 8, SEEK_BYTES, what)

    /** How a stretch of playback went. */
    protected class Playback(val stalls: Int, val stallMillis: Long)

    /**
     * Plays [seconds] of [video] from [position] as a player does at [PLAYBACK_BYTES_PER_SECOND]
     * (a 1080p movie): reads of 256 KB, never more than [PLAYER_BUFFER_MILLIS] ahead of the
     * picture. A read that arrives later than that buffer allows is a stall (the picture
     * freezes); playback then resumes from there.
     */
    protected fun play(
        pfd: VideoReader, video: Video, position: Long, seconds: Int,
        what: String
    ): Playback {
        val chunk = 256 * 1024
        val chunkMillis = chunk * 1000L / PLAYBACK_BYTES_PER_SECOND
        val chunks = (seconds * 1000L / chunkMillis).toInt()
        val from = minOf(position, video.size - chunks.toLong() * chunk) / 8 * 8
        val buffer = ByteArray(chunk)
        var clock = System.nanoTime() / 1_000_000
        var stalls = 0
        var stallMillis = 0L
        for (k in 0 until chunks) {
            // Not further ahead than the player's buffer.
            val earliest = clock + k * chunkMillis - PLAYER_BUFFER_MILLIS
            val now = System.nanoTime() / 1_000_000
            if (now < earliest) {
                Thread.sleep(earliest - now)
            }
            readVideo(pfd, video, from + k.toLong() * chunk, chunk, what, buffer)
            val due = clock + k * chunkMillis + PLAYER_BUFFER_MILLIS
            val arrived = System.nanoTime() / 1_000_000
            if (arrived > due) {
                ++stalls
                stallMillis += arrived - due
                clock += arrived - due
            }
        }
        return Playback(stalls, stallMillis)
    }

    /** Timings of one kind of operation, checked against limits at the end. */
    protected class Timings(val name: String) {
        protected val values = java.util.Collections.synchronizedList(ArrayList<Long>())

        fun add(millis: Long) {
            values += millis
        }

        val average: Long
            get() = synchronized(values) { if (values.isEmpty()) 0 else values.sum() / values.size }

        val slowest: Long
            get() = synchronized(values) { values.maxOrNull() ?: 0 }

        override fun toString(): String =
            "$name ${values.size}× avg $average ms max $slowest ms"

        fun check(maxAverage: Long, maxSlowest: Long) {
            assertTrue("$this (limits: avg $maxAverage ms, max $maxSlowest ms)",
                average <= maxAverage && slowest <= maxSlowest)
        }
    }

    /** Stalls of all playbacks of a test; none allowed. */
    protected class Stalls {
        protected val stalls = java.util.concurrent.atomic.AtomicInteger()
        protected val millis = java.util.concurrent.atomic.AtomicLong()
        protected val playbacks = java.util.concurrent.atomic.AtomicInteger()

        fun add(playback: Playback) {
            playbacks.incrementAndGet()
            stalls.addAndGet(playback.stalls)
            millis.addAndGet(playback.stallMillis)
        }

        override fun toString(): String =
            "${playbacks.get()} playbacks with ${stalls.get()} stalls (${millis.get()} ms)"

        fun check() {
            assertEquals("stalls in $this", 0, stalls.get())
        }
    }

    protected fun report(test: String, vararg parts: Any) {
        reportLine(
            "${security.name.lowercase()}, $test: " + parts.joinToString("; ") + "; " +
                connectionSummary()
        )
    }

    /** At most this many connections per export (the test server uses the default settings). */
    protected val connectionLimit =
        ConnectionOptions.DEFAULT_MAX_CONNECTIONS + 1 + Client.CONTEXTS_BEYOND_FILE

    /**
     * How the connections fared during the test: saturation (requests refused at the export's
     * limit, files held to their share, reads that waited with every connection busy) says where
     * more connections would help; waits with idle connections say the link or the server is
     * the limit instead.
     */
    protected fun connectionSummary(): String {
        val delta = ConnectionStats.snapshot() - connectionsAtStart
        return "$delta; peak ${ConnectionStats.peakTotal.get()} connections " +
            "(${ConnectionStats.peakInUse.get()} bound to files) of $connectionLimit " +
            "(+${Client.RESERVED_CONTEXTS_OVER_LIMIT} for seeks)" +
            (if (delta.broke + delta.openFailed > 0) {
                "; last failures: " + ConnectionStats.lastFailures.joinToString(" | ")
            } else "")
    }

    /**
     * Once every file is closed, no connection stays bound to one (none leaked or reserved),
     * none exceeds the limit, and none broke or failed to open under this load.
     */
    protected fun checkConnections() {
        val deadline = System.nanoTime() + 5_000_000_000L
        // Closing lets running calls finish (a call cannot be cancelled): a moment.
        while (Client.connectionCounts().second > 0 && System.nanoTime() < deadline) {
            Thread.sleep(100)
        }
        val (total, bound) = Client.connectionCounts()
        val delta = ConnectionStats.snapshot() - connectionsAtStart
        assertEquals(
            "connections still bound to files 5 s after closing all ($total open; files with " +
                "connections: " + me.zhanghai.android.files.provider.nfs.client.FileByteChannel
                .describeFilesWithConnections() + "; " + Client.describeBoundConnections() + ")",
            0, bound
        )
        assertTrue("peak ${ConnectionStats.peakTotal.get()} connections over the limit",
            ConnectionStats.peakTotal.get() <=
                connectionLimit + Client.RESERVED_CONTEXTS_OVER_LIMIT)
        assertEquals("connections that broke ($delta)", 0, delta.broke)
        assertEquals("connections that could not open the file ($delta)", 0, delta.openFailed)
    }

    /** Word-aligned, anywhere a seek can read [SEEK_BYTES]. */
    protected fun randomPosition(random: Random, video: Video): Long =
        Math.floorMod(random.nextLong(), (video.size - SEEK_BYTES) / 8) * 8

    /** Where the reads of [path] came from (see FileByteChannel.ReadStats). */
    protected fun stats(path: Path) =
        me.zhanghai.android.files.provider.nfs.client.FileByteChannel.readStats(
            (path as NfsPath).remotePath.toString()
        )

    /** Starts from an empty read cache: first visits must then come from the network. */
    protected fun clearReadCache() {
        me.zhanghai.android.files.provider.nfs.client.NfsReadCache.clear()
    }

    /**
     * Cache hits and misses expected by a test, checked at the end: a place seen before must
     * come from the cache (memory or disk) without waiting for the network, and a place never
     * read (nor read ahead) must come from the network, never from data cached for something
     * else.
     */
    protected class CacheExpectations {
        protected val hits = java.util.concurrent.atomic.AtomicInteger()
        protected val misses = java.util.concurrent.atomic.AtomicInteger()
        protected val wrong = java.util.concurrent.ConcurrentLinkedQueue<String>()

        fun record(what: String, expectHit: Boolean, waitedForNetwork: Boolean, millis: Long) {
            if (expectHit) hits.incrementAndGet() else misses.incrementAndGet()
            if (expectHit == waitedForNetwork) {
                wrong += if (expectHit) {
                    "$what: seen before, but waited for the network ($millis ms)"
                } else {
                    "$what: never read, but did not wait for the network"
                }
            }
        }

        override fun toString(): String =
            "cache: ${hits.get()} expected hits, ${misses.get()} expected misses, " +
                "${wrong.size} wrong" +
                (if (wrong.isEmpty()) "" else " (${wrong.take(4).joinToString("; ")})")

        fun check() {
            assertTrue("$this: ${wrong.joinToString("; ")}", wrong.isEmpty())
        }
    }

    /** A seek expected to be a cache hit ([expectHit]) or miss; milliseconds. */
    protected fun seekExpecting(
        pfd: VideoReader, video: Video, position: Long, what: String,
        expectHit: Boolean, cache: CacheExpectations
    ): Long {
        val stats = stats(video.path)
        val before = stats.waitedAt.size
        val millis = seek(pfd, video, position, what)
        cache.record("${video.name} $what at $position", expectHit,
            waitedWithin(stats, before, position / 8 * 8, SEEK_BYTES), millis)
        return millis
    }

    /**
     * Whether a read of `[position, position + length)` waited for the network since the stats
     * had [before] waits: only reads in that range count, not those Android's file proxy and the
     * channel's buffer make ahead of it on their own.
     */
    protected fun waitedWithin(
        stats: me.zhanghai.android.files.provider.nfs.client.FileByteChannel.Companion.ReadStats,
        before: Int, position: Long, length: Int
    ): Boolean {
        // Android's file proxy (FUSE) reads whole pages, from before the position asked for: the
        // read of this range may start up to a piece (128 KiB, its largest read) earlier.
        val from = position - position % (128 * 1024)
        return synchronized(stats.waitedAt) {
            stats.waitedAt.drop(before).any { it >= from && it < position + length }
        }
    }

    /**
     * Whether nothing around [position] was read or could have been read ahead yet: no earlier
     * place within 1 MB before it or 64 MB after it (the most read ahead of a place in memory).
     */
    protected fun isFresh(position: Long, seen: Collection<Long>): Boolean =
        seen.none { position in it - 1024 * 1024..it + 64L * 1024 * 1024 }

    /**
     * A whole 500 MB movie: opened as VLC does (the start, the index at the end, the start
     * again), read through as fast as the link allows, then again from the read cache.
     */
    @Test
    fun movieStreamedWhole() {
        val video = movie(2)
        clearReadCache()
        val opens = Timings("open+first 64 KB")
        val probes = Timings("index and back")
        val reads = Timings("1 MB reads")
        val cache = CacheExpectations()
        var networkMBps = 0.0
        var cacheMBps = 0.0
        var secondPassNetworkWaits = 0
        var secondPassWaitedAt = emptyList<Long>()
        for (pass in 0..1) {
            val start = System.nanoTime()
            val positionsBefore = stats(video.path).waitedAt.size
            open(video).use { pfd ->
                // The first pass finds nothing cached; the second, all of it.
                seekExpecting(pfd, video, 0, "open, pass $pass", pass == 1, cache)
                opens.add((System.nanoTime() - start) / 1_000_000)
                probes.add(readVideo(pfd, video, video.size - 1024 * 1024, 1024 * 1024, "index"))
                probes.add(seek(pfd, video, 0, "back to the start"))
                val buffer = ByteArray(1024 * 1024)
                val readStart = System.nanoTime()
                var position = 0L
                while (position < video.size) {
                    val length = minOf(buffer.size.toLong(), video.size - position).toInt()
                    val millis = readVideo(pfd, video, position, length, "pass $pass", buffer)
                    if (pass == 0) {
                        reads.add(millis)
                    }
                    position += length
                }
                val mbps = video.size / ((System.nanoTime() - readStart) / 1e9) / 1e6
                if (pass == 0) networkMBps = mbps else cacheMBps = mbps
            }
            if (pass == 1) {
                // A read at the end of file asks the server (the file may have grown): not a
                // cache miss.
                secondPassWaitedAt = synchronized(stats(video.path).waitedAt) {
                    stats(video.path).waitedAt.drop(positionsBefore)
                }.filter { it < video.size }
                secondPassNetworkWaits = secondPassWaitedAt.size
            }
        }
        report("500 MB movie read whole", opens, probes, reads, cache,
            "second pass waited for the network $secondPassNetworkWaits times",
            String.format("%.1f MB/s from the server, %.1f MB/s again (read cache)", networkMBps,
                cacheMBps))
        opens.check(1_500, 3_000)
        probes.check(1_000, 3_000)
        // Faster than any movie plays, with no read waiting long enough to freeze the picture.
        assertTrue("from the server: $networkMBps MB/s", networkMBps >= 2.0)
        reads.check(1_000, 3_000)
        assertTrue("again from the read cache: $cacheMBps MB/s", cacheMBps >= 30.0)
        cache.check()
        // Every byte was read once: the second pass never waits for the network.
        assertEquals("second pass, reads that waited for the network (at $secondPassWaitedAt)", 0,
            secondPassNetworkWaits)
        checkConnections()
    }

    /**
     * Looking for a scene in a 700 MB movie: the start, the end, the middle, a quarter, three
     * quarters, then 30 random places, each played 3 s; then back to places seen before.
     */
    @Test
    fun sceneSearch() {
        val video = movie(3)
        clearReadCache()
        val random = Random(5005)
        val seeks = Timings("seeks")
        val again = Timings("back to places seen (cache)")
        val stalls = Stalls()
        val cache = CacheExpectations()
        val seen = ArrayList<Long>()
        open(video).use { pfd ->
            val size = video.size
            val places = listOf(0L, size - 2 * 1024 * 1024, size / 2, size / 4, size * 3 / 4) +
                List(30) { randomPosition(random, video) }
            places.forEachIndexed { i, place ->
                val position = place / 8 * 8
                val millis = if (isFresh(position, seen)) {
                    seekExpecting(pfd, video, position, "seek $i", false, cache)
                } else {
                    seek(pfd, video, position, "seek $i")
                }
                seeks.add(millis)
                stalls.add(play(pfd, video, position, 3, "play after seek $i"))
                seen += position
            }
            for ((i, position) in seen.shuffled(random).take(10).withIndex()) {
                again.add(seekExpecting(pfd, video, position, "back $i", true, cache))
            }
        }
        report("scene search in a 700 MB movie", seeks, stalls, again, cache)
        seeks.check(1_000, 3_000)
        stalls.check()
        again.check(100, 500)
        cache.check()
        checkConnections()
    }

    /**
     * Burst of seeks, then settle: dragging the cursor through the whole movie (60 seeks back to
     * back), then letting go at a random place, which must show the picture as fast as a single
     * seek and play without stalls; three times.
     */
    @Test
    fun seekBurstThenSettle() {
        val video = movie(2)
        clearReadCache()
        val random = Random(6006)
        val burst = Timings("seeks while dragging")
        val settle = Timings("seek where released")
        val again = Timings("back to them after reopening (cache)")
        val stalls = Stalls()
        val cache = CacheExpectations()
        val seen = ArrayList<Long>()
        open(video).use { pfd ->
            repeat(3) { round ->
                // Dragging: steadily forward or backward, with a little noise.
                val from = randomPosition(random, video)
                val to = randomPosition(random, video)
                for (i in 0 until 60) {
                    val position = from + (to - from) * i / 60 +
                        (random.nextInt(2 * 1024 * 1024) - 1024 * 1024)
                    val place = position.coerceIn(0, video.size - SEEK_BYTES) / 8 * 8
                    burst.add(seek(pfd, video, place, "round $round drag $i"))
                    seen += place
                }
                val release = randomPosition(random, video)
                settle.add(seek(pfd, video, release, "round $round release"))
                stalls.add(play(pfd, video, release, 10, "round $round play"))
                seen += release
            }
        }
        // Reopened (nothing left in memory): the places dragged over and released at come from
        // the disk cache, even those left before their blocks were complete.
        open(video).use { pfd ->
            for ((i, position) in seen.shuffled(random).take(20).withIndex()) {
                again.add(seekExpecting(pfd, video, position, "back $i", true, cache))
            }
        }
        report("seek bursts then settle in a 500 MB movie", burst, settle, stalls, again, cache)
        burst.check(1_000, 3_000)
        settle.check(1_000, 3_000)
        stalls.check()
        again.check(100, 500)
        cache.check()
        checkConnections()
    }

    /**
     * An evening of episodes (3 × 250 MB): each opened, played 6 s from the start, skipped to
     * the credits, played 3 s and closed; then the first one again where it was left.
     */
    @Test
    fun episodeMarathon() {
        clearReadCache()
        val opens = Timings("open+first 64 KB")
        val seeks = Timings("seek to the credits")
        val closes = Timings("close")
        val again = Timings("first episode again (cache)")
        val stalls = Stalls()
        val cache = CacheExpectations()
        for (number in 1..3) {
            val video = episode(number)
            val start = System.nanoTime()
            val pfd = open(video)
            try {
                seekExpecting(pfd, video, 0, "open", false, cache)
                opens.add((System.nanoTime() - start) / 1_000_000)
                // 6 s: under the 8 MB after which the engine buffers 256 MB ahead on disk, so
                // that the middle stays never read (a miss expected below).
                stalls.add(play(pfd, video, 0, 6, "episode $number start"))
                val credits = video.size - 20L * 1024 * 1024
                seeks.add(seekExpecting(pfd, video, credits, "credits", false, cache))
                stalls.add(play(pfd, video, credits, 3, "episode $number credits"))
            } finally {
                val closeStart = System.nanoTime()
                pfd.close()
                closes.add((System.nanoTime() - closeStart) / 1_000_000)
            }
        }
        val first = episode(1)
        val start = System.nanoTime()
        open(first).use { pfd ->
            seekExpecting(pfd, first, 0, "reopened", true, cache)
            opens.add((System.nanoTime() - start) / 1_000_000)
            // Within the 6 s played at first, and the credits: from the disk cache.
            for (position in listOf(3L * 1024 * 1024, 5L * 1024 * 1024,
                first.size - 20L * 1024 * 1024)) {
                again.add(seekExpecting(pfd, first, position, "again", true, cache))
            }
            stalls.add(play(pfd, first, 3L * 1024 * 1024, 3, "episode 1 again"))
            // Never played: from the network.
            seekExpecting(pfd, first, first.size / 2, "middle, never played", false, cache)
        }
        report("3 episodes one after another", opens, seeks, closes, stalls, again, cache)
        opens.check(1_500, 3_000)
        seeks.check(1_000, 3_000)
        closes.check(300, 1_000)
        stalls.check()
        again.check(100, 500)
        cache.check()
        checkConnections()
    }

    /** Three players at once, each scrubbing and playing its own movie. */
    @Test
    fun threePlayersAtOnce() {
        clearReadCache()
        val opens = Timings("open+first 64 KB")
        val seeks = Timings("seeks")
        val stalls = Stalls()
        val cache = CacheExpectations()
        runInParallel(3) { thread ->
            val video = movie(thread + 1)
            val random = Random(7000L + thread)
            val start = System.nanoTime()
            val seen = ArrayList<Long>()
            open(video).use { pfd ->
                seekExpecting(pfd, video, 0, "open", false, cache)
                opens.add((System.nanoTime() - start) / 1_000_000)
                stalls.add(play(pfd, video, 0, 3, "${video.name} start"))
                seen += 0L
                repeat(10) { i ->
                    val position = randomPosition(random, video)
                    seeks.add(if (isFresh(position, seen)) {
                        seekExpecting(pfd, video, position, "seek $i", false, cache)
                    } else {
                        seek(pfd, video, position, "${video.name} seek $i")
                    })
                    stalls.add(play(pfd, video, position, 3, "${video.name} play $i"))
                    seen += position
                }
                for (position in seen.shuffled(random).take(3)) {
                    seekExpecting(pfd, video, position, "back", true, cache)
                }
            }
        }
        report("3 players at once", opens, seeks, stalls, cache)
        opens.check(2_000, 4_000)
        seeks.check(1_500, 4_000)
        stalls.check()
        cache.check()
        checkConnections()
    }

    /**
     * Six files seeking at once, back to back without playing (the heaviest scrubbing): the
     * connections are shared among the files and none may starve.
     */
    @Test
    fun sixFilesSeekingAtOnce() {
        clearReadCache()
        val seeks = Timings("seeks")
        val cache = CacheExpectations()
        runInParallel(6) { thread ->
            val video = if (thread < 3) movie(thread + 1) else episode(thread - 2)
            val random = Random(8000L + thread)
            val seen = ArrayList<Long>()
            open(video).use { pfd ->
                repeat(30) { i ->
                    val position = when (i % 10) {
                        0 -> 0L
                        4 -> video.size - SEEK_BYTES
                        7 -> video.size / 2 / 8 * 8
                        else -> randomPosition(random, video)
                    }
                    // The start, the end and the middle come back every 10 seeks: hits then.
                    val millis = when {
                        position in seen -> seekExpecting(pfd, video, position, "seek $i", true,
                            cache)
                        isFresh(position, seen) -> seekExpecting(pfd, video, position,
                            "seek $i", false, cache)
                        else -> seek(pfd, video, position, "${video.name} seek $i")
                    }
                    seeks.add(millis)
                    seen += position
                }
            }
        }
        report("6 files seeking at once", seeks, cache)
        seeks.check(1_500, 4_000)
        cache.check()
        checkConnections()
    }

    /**
     * One video read by several descriptors at once, as VLC does: the player playing a minute,
     * the demuxer seeking around, and a metadata reader reopening it for the start and the end.
     * (The descriptors share the file's statistics, so cache hits are not told apart here; the
     * other tests check them.)
     */
    @Test
    fun oneVideoManyDescriptors() {
        val video = movie(2)
        clearReadCache()
        val seeks = Timings("seeks of the second descriptor")
        val probes = Timings("open+start+end of the third")
        val stalls = Stalls()
        val stop = java.util.concurrent.atomic.AtomicBoolean()
        runInParallel(3) { thread ->
            when (thread) {
                0 -> try {
                    open(video).use { pfd ->
                        seek(pfd, video, 100L * 1024 * 1024, "player")
                        stalls.add(play(pfd, video, 100L * 1024 * 1024, 60, "player"))
                    }
                } finally {
                    stop.set(true)
                }
                1 -> {
                    val random = Random(9009)
                    open(video).use { pfd ->
                        var i = 0
                        while (!stop.get() && i < 40) {
                            seeks.add(seek(pfd, video, randomPosition(random, video), "seek $i"))
                            ++i
                            Thread.sleep(500)
                        }
                    }
                }
                else -> repeat(5) { i ->
                    val start = System.nanoTime()
                    open(video).use { pfd ->
                        seek(pfd, video, 0, "metadata $i start")
                        readVideo(pfd, video, video.size - 1024 * 1024, 1024 * 1024,
                            "metadata $i end")
                    }
                    probes.add((System.nanoTime() - start) / 1_000_000)
                    Thread.sleep(5_000)
                }
            }
        }
        report("one video, 3 descriptors", stalls, seeks, probes)
        stalls.check()
        seeks.check(1_000, 3_000)
        probes.check(2_000, 4_000)
        checkConnections()
    }

    /**
     * A player switching files as fast as it can (open, read a random place, close, 30 times)
     * while another movie plays: connections released at every close go back to the pool, none
     * stay reserved or leak, and the playing movie never stalls.
     */
    @Test
    fun rapidOpenCloseWhilePlaying() {
        clearReadCache()
        val random = Random(1111)
        val cycles = Timings("open+seek+close")
        val finalOpen = Timings("open afterwards")
        val stalls = Stalls()
        val cache = CacheExpectations()
        val seen = HashMap<String, MutableSet<Long>>()
        val playing = movie(1)
        val others = listOf(movie(2), movie(3), episode(1), episode(2), episode(3))
        runInParallel(2) { thread ->
            if (thread == 0) {
                open(playing).use { pfd ->
                    seek(pfd, playing, 0, "playing movie")
                    stalls.add(play(pfd, playing, 0, 45, "playing movie"))
                }
            } else {
                repeat(30) { cycle ->
                    val video = others[random.nextInt(others.size)]
                    val start = System.nanoTime()
                    open(video).use { pfd ->
                        val position = when (cycle % 3) {
                            0 -> 0L
                            1 -> video.size - SEEK_BYTES
                            else -> randomPosition(random, video)
                        }
                        // The same place of the same file again: from the cache, although the
                        // file was closed in between. A new one: from the network.
                        val places = seen.getOrPut(video.name) { HashSet() }
                        when {
                            position in places -> seekExpecting(pfd, video, position,
                                "cycle $cycle", true, cache)
                            isFresh(position, places) -> seekExpecting(pfd, video, position,
                                "cycle $cycle", false, cache)
                            else -> seek(pfd, video, position, "cycle $cycle ${video.name}")
                        }
                        places += position
                    }
                    cycles.add((System.nanoTime() - start) / 1_000_000)
                }
            }
        }
        val video = episode(3)
        val start = System.nanoTime()
        open(video).use { pfd -> seek(pfd, video, video.size / 3, "afterwards") }
        finalOpen.add((System.nanoTime() - start) / 1_000_000)
        report("rapid open/close while a movie plays", cycles, stalls, finalOpen, cache)
        cycles.check(1_500, 3_000)
        stalls.check()
        finalOpen.check(1_500, 1_500)
        cache.check()
        checkConnections()
    }

    /**
     * Seeking and playing a movie while uploading a file to the same server: the upload's
     * connections must not starve the player, and the upload must arrive intact.
     */
    @Test
    fun seeksWhileUploading() {
        val uploadBytes = (argument("uploadMiB")?.toInt() ?: 64) * 1024 * 1024
        val video = movie(3)
        clearReadCache()
        val seeks = Timings("seeks")
        val stalls = Stalls()
        val cache = CacheExpectations()
        val seen = java.util.Collections.synchronizedList(ArrayList<Long>())
        val target = root.resolve("upload.bin")
        val done = java.util.concurrent.atomic.AtomicBoolean()
        var uploadMBps = 0.0
        val chunk = ByteArray(1024 * 1024)
        runInParallel(2) { thread ->
            if (thread == 0) {
                try {
                    val start = System.nanoTime()
                    target.newOutputStream().use { output ->
                        var written = 0
                        while (written < uploadBytes) {
                            // Every word its offset: checked afterwards.
                            val words = ByteBuffer.wrap(chunk)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until chunk.size / 8) {
                                words.putLong(i * 8, written.toLong() + i * 8)
                            }
                            output.write(chunk)
                            written += chunk.size
                        }
                    }
                    uploadMBps = uploadBytes / ((System.nanoTime() - start) / 1e9) / 1e6
                } finally {
                    done.set(true)
                }
            } else {
                val random = Random(1212)
                open(video).use { pfd ->
                    var i = 0
                    // For as long as the upload lasts, and at least 10 seeks.
                    while (!done.get() || i < 10) {
                        val position = randomPosition(random, video)
                        seeks.add(seek(pfd, video, position, "seek $i"))
                        stalls.add(play(pfd, video, position, 3, "play $i"))
                        seen += position
                        ++i
                    }
                    for (position in seen.take(5)) {
                        seekExpecting(pfd, video, position, "back after the upload", true, cache)
                    }
                }
            }
        }
        assertEquals(uploadBytes.toLong(), target.size())
        // Spot checks of the upload: the start, the middle and the end. Written, never read:
        // they come from the server (a writer's data is never cached).
        val uploadWaits = stats(target).waitedAt.size
        target.newByteChannel(StandardOpenOption.READ).use { channel ->
            for (position in listOf(0L, uploadBytes / 2L, uploadBytes - 65_536L)) {
                val buffer = ByteBuffer.allocate(65_536)
                channel.position(position)
                while (buffer.hasRemaining() && channel.read(buffer) > 0) {}
                buffer.flip()
                buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until 65_536 / 8) {
                    assertEquals("uploaded word", position + i * 8, buffer.getLong(i * 8))
                }
            }
        }
        cache.record("upload read back", false, stats(target).waitedAt.size > uploadWaits, 0)
        report("seeks while uploading ${uploadBytes / 1024 / 1024} MiB", seeks, stalls, cache,
            String.format("upload %.1f MB/s", uploadMBps))
        seeks.check(1_000, 3_000)
        stalls.check()
        cache.check()
        checkConnections()
    }

    /**
     * A file changed on the server is never served from what was cached of its old content: read
     * (cached), rewritten with other content of the same size, read again: every read of the
     * new content comes from the network and is the new content.
     */
    @Test
    fun readCacheFollowsChanges() {
        clearReadCache()
        val size = 8 * 1024 * 1024
        val file = root.resolve("changing.bin")
        fun write(tag: Long) {
            val data = ByteArray(size)
            val words = ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until size / 8) {
                words.putLong(i * 8, (tag shl 48) + i * 8L)
            }
            file.newOutputStream().use { it.write(data) }
        }
        val cache = CacheExpectations()
        val places = listOf(0L, 3L * 1024 * 1024 + 4096, size - 65_536L)
        write(21)
        for (pass in 0..1) {
            val video = Video(file, size.toLong(), 21)
            open(video).use { pfd ->
                for (position in places) {
                    seekExpecting(pfd, video, position, "version 1 pass $pass", pass == 1, cache)
                }
            }
        }
        write(22)
        val changed = Video(file, size.toLong(), 22)
        open(changed).use { pfd ->
            for (position in places) {
                // readVideo checks the tag: old data would fail here.
                seekExpecting(pfd, changed, position, "version 2", false, cache)
            }
        }
        report("read cache after a change", cache)
        cache.check()
        checkConnections()
    }

    /** With the read cache off in the settings, nothing comes from it: every place is a miss. */
    @Test
    fun readCacheOffInSettings() {
        clearReadCache()
        val context = me.zhanghai.android.files.app.application
        val key = context.getString(me.zhanghai.android.files.R.string.pref_key_nfs_read_cache_size_gb)
        val preferences = me.zhanghai.android.files.app.defaultSharedPreferences
        val hadValue = preferences.contains(key)
        val oldValue = preferences.getInt(key, 0)
        preferences.edit().putInt(key, 0).commit()
        val cache = CacheExpectations()
        try {
            val video = episode(2)
            val places = listOf(10L * 1024 * 1024, 100L * 1024 * 1024, video.size - SEEK_BYTES)
            for (pass in 0..1) {
                open(video).use { pfd ->
                    for (position in places) {
                        seekExpecting(pfd, video, position, "cache off, pass $pass", false, cache)
                    }
                }
            }
            val files = java.io.File(context.cacheDir, "nfs-read-cache").listFiles().orEmpty()
            assertTrue("cache off, yet ${files.size} files stored", files.isEmpty())
        } finally {
            preferences.edit().apply {
                if (hadValue) putInt(key, oldValue) else remove(key)
            }.commit()
        }
        report("read cache off", cache)
        cache.check()
        checkConnections()
    }

    // Files deleted or changed while being played. What must happen (NFSv4 semantics, as with a
    // local file on Linux, and close-to-open consistency like the kernel's NFS client):
    // - Deleted: playback goes on to the end, seeks included (the server keeps an open file's
    //   data until it is closed); opening it again fails at once. Deleted by Material Files, its
    //   read cache is dropped; by another client, the app cannot know, but that data is never
    //   served for a new file at the same path. Extra connections that find it gone stop being
    //   requested.
    // - Changed by another client: while open, what was read may stay old (undefined for any NFS
    //   client); once reopened, everything is the new content, from the network (a new version),
    //   and the old version's cached data is dropped. A file that grows can be read past its old
    //   end; one that shrinks ends at its new end at once, without hanging.
    // - Connections: none breaks, none stays bound once the file is closed.

    /** `delete-app.bin` … `truncate-other.bin`: 200 MiB each, from the CI (shard "changes"). */
    protected fun changing(name: String, tag: Long) = video("$name.bin", tag)

    /**
     * Another NFS client (libnfs directly, not through Material Files): what it changes, the app
     * learns only from the server.
     */
    protected fun <T> otherClient(block: (Long) -> T): T {
        val authority = server.authority
        val nfs = io.github.libnfsandroid.Nfs.initContext()
        try {
            io.github.libnfsandroid.Nfs.setVersion(nfs, io.github.libnfsandroid.Nfs.NFS_V4_2)
            io.github.libnfsandroid.Nfs.setUid(nfs, 0)
            io.github.libnfsandroid.Nfs.setGid(nfs, 0)
            io.github.libnfsandroid.Nfs.setTimeout(nfs, 30_000)
            if (security != ConnectionOptions.Security.NONE) {
                io.github.libnfsandroid.Nfs.setTlsTransport(
                    nfs, io.github.libnfsandroid.NfsTlsTransport(
                        testSslContext(security), authority.host, authority.port, 30_000
                    )
                )
            }
            io.github.libnfsandroid.Nfs.mount(
                nfs, authority.host.toByteArray(), authority.exportPath.toByteArray()
            )
            return block(nfs)
        } finally {
            runCatching { io.github.libnfsandroid.Nfs.umount(nfs) }
            io.github.libnfsandroid.Nfs.destroyContext(nfs)
        }
    }

    /** Writes `[from, from + length)` of a tagged file (offset + tag in every word). */
    protected fun writeTagged(nfs: Long, path: Path, from: Long, length: Long, tag: Long,
        flags: Int = io.github.libnfsandroid.Nfs.O_WRONLY) {
        val file = io.github.libnfsandroid.Nfs.open(nfs, (path as NfsPath).remotePathBytes, flags,
            0b110_100_100)
        try {
            val chunk = ByteArray(1024 * 1024)
            var offset = from
            while (offset < from + length) {
                val count = minOf(chunk.size.toLong(), from + length - offset).toInt()
                val words = ByteBuffer.wrap(chunk).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count / 8) {
                    words.putLong(i * 8, (tag shl 48) + offset + i * 8)
                }
                var done = 0
                while (done < count) {
                    done += io.github.libnfsandroid.Nfs.write(nfs, file, offset + done, chunk,
                        done, count - done)
                }
                offset += count
            }
            io.github.libnfsandroid.Nfs.fsync(nfs, file)
        } finally {
            io.github.libnfsandroid.Nfs.close(nfs, file)
        }
    }

    /** Opening [video] fails (it is gone); milliseconds taken. */
    protected fun openFails(video: Video): Long {
        val start = System.nanoTime()
        try {
            open(video).close()
            throw AssertionError("${video.name} opened after it was deleted")
        } catch (e: java.io.IOException) {
            // Expected: not found (FileNotFoundException from the file provider,
            // NoSuchFileException from the app's channel).
        } catch (e: IllegalArgumentException) {
            // Some providers report a missing file this way.
        }
        return (System.nanoTime() - start) / 1_000_000
    }

    /** The versions of [path] the read cache holds data of (after pending maintenance). */
    protected fun cachedVersions(path: Path): Set<String> =
        me.zhanghai.android.files.provider.nfs.client.NfsReadCache.filesOf(
            server.authority, (path as NfsPath).remotePathBytes
        ).map { it.substring(20, 40) }.toSet()

    /** Waits up to 3 s for [condition] (cache maintenance and closes run in the background). */
    protected fun eventually(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) return false
            Thread.sleep(100)
        }
        return true
    }

    /** Deleted by Material Files while playing: playback and seeks go on; its cache is dropped. */
    @Test
    fun deletedInAppWhilePlaying() {
        val video = changing("delete-app", 31)
        clearReadCache()
        val seeks = Timings("seeks after deleting")
        val reopen = Timings("reopening fails")
        val deletes = Timings("delete")
        val stalls = Stalls()
        open(video).use { pfd ->
            seek(pfd, video, 10L * 1024 * 1024, "start")
            stalls.add(play(pfd, video, 10L * 1024 * 1024, 5, "before deleting"))
            val start = System.nanoTime()
            Files.delete(video.path)
            deletes.add((System.nanoTime() - start) / 1_000_000)
            // Where it was, then a part never read.
            stalls.add(play(pfd, video, 15L * 1024 * 1024, 5, "after deleting"))
            seeks.add(seek(pfd, video, 150L * 1024 * 1024, "never read, after deleting"))
            stalls.add(play(pfd, video, 150L * 1024 * 1024, 3, "never read, after deleting"))
        }
        reopen.add(openFails(video))
        val cacheEmpty = eventually { cachedVersions(video.path).isEmpty() }
        val delta = ConnectionStats.snapshot() - connectionsAtStart
        report("deleted in the app while playing", deletes, stalls, seeks, reopen,
            "cache of the deleted file dropped: $cacheEmpty")
        stalls.check()
        seeks.check(1_000, 3_000)
        reopen.check(1_500, 1_500)
        assertTrue("read cache still holds the deleted file", cacheEmpty)
        // Extra connections that find it gone stop being requested (at most those connecting).
        assertTrue("${delta.openGone} connections tried to open the deleted file",
            delta.openGone <= 8)
        checkConnections()
    }

    /**
     * Deleted by another client while playing, then a new file at the same path: playback goes
     * on; the new file comes whole from the network, never from the old file's cache.
     */
    @Test
    fun deletedByAnotherClientThenReplaced() {
        val video = changing("delete-other", 32)
        clearReadCache()
        val stalls = Stalls()
        val seeks = Timings("seeks after deleting")
        val reopen = Timings("reopening fails")
        val cache = CacheExpectations()
        open(video).use { pfd ->
            seek(pfd, video, 0, "start")
            stalls.add(play(pfd, video, 0, 5, "before deleting"))
            seek(pfd, video, 100L * 1024 * 1024, "middle")
            stalls.add(play(pfd, video, 100L * 1024 * 1024, 3, "before deleting"))
            otherClient { nfs ->
                io.github.libnfsandroid.Nfs.unlink(nfs, (video.path as NfsPath).remotePathBytes)
            }
            seeks.add(seek(pfd, video, 2L * 1024 * 1024, "seen, after deleting"))
            seeks.add(seek(pfd, video, 150L * 1024 * 1024, "never read, after deleting"))
            stalls.add(play(pfd, video, 150L * 1024 * 1024, 3, "never read, after deleting"))
        }
        reopen.add(openFails(video))
        // A different file at the same path.
        val size = 8L * 1024 * 1024
        otherClient { nfs ->
            writeTagged(nfs, video.path, 0, size, 36, io.github.libnfsandroid.Nfs.O_WRONLY or
                io.github.libnfsandroid.Nfs.O_CREAT or io.github.libnfsandroid.Nfs.O_TRUNC)
        }
        val replaced = Video(video.path, size, 36)
        open(replaced).use { pfd ->
            for (position in listOf(0L, 2L * 1024 * 1024, size - SEEK_BYTES)) {
                seekExpecting(pfd, replaced, position, "new file", false, cache)
            }
        }
        val oneVersion = eventually { cachedVersions(video.path).size <= 1 }
        report("deleted by another client, then replaced", stalls, seeks, reopen, cache,
            "only the new file's data cached: $oneVersion")
        stalls.check()
        seeks.check(1_000, 3_000)
        reopen.check(1_500, 1_500)
        cache.check()
        assertTrue("old file's data still cached", oneVersion)
        val delta = ConnectionStats.snapshot() - connectionsAtStart
        assertTrue("${delta.openGone} connections tried to open the deleted file",
            delta.openGone <= 8)
        checkConnections()
    }

    /**
     * Changed by another client while playing (4 MB at the start and at 150 MB): the rest keeps
     * playing; once reopened, everything is the new version, from the network, and only the new
     * version's data stays cached.
     */
    @Test
    fun modifiedByAnotherClientWhilePlaying() {
        val video = changing("modify-other", 33)
        clearReadCache()
        val stalls = Stalls()
        val cache = CacheExpectations()
        val changed = 4L * 1024 * 1024
        open(video).use { pfd ->
            seek(pfd, video, 0, "start")
            stalls.add(play(pfd, video, 0, 5, "before the change"))
            seek(pfd, video, 100L * 1024 * 1024, "middle")
            stalls.add(play(pfd, video, 100L * 1024 * 1024, 3, "before the change"))
            otherClient { nfs ->
                writeTagged(nfs, video.path, 0, changed, 37)
                writeTagged(nfs, video.path, 150L * 1024 * 1024, changed, 37)
            }
            // A part neither read nor changed plays on.
            seek(pfd, video, 60L * 1024 * 1024, "unchanged, after the change")
            stalls.add(play(pfd, video, 60L * 1024 * 1024, 3, "after the change"))
        }
        val newVersion = Video(video.path, video.size, 37)
        open(video).use { pfd ->
            // New version: nothing from the old one's cache, changed or not.
            seekExpecting(pfd, newVersion, 0, "changed start", false, cache)
            seekExpecting(pfd, newVersion, 150L * 1024 * 1024, "changed middle", false, cache)
            seekExpecting(pfd, video, 100L * 1024 * 1024 + 4096, "unchanged, cached before",
                false, cache)
        }
        val oneVersion = eventually { cachedVersions(video.path).size == 1 }
        report("modified by another client while playing", stalls, cache,
            "only the new version cached: $oneVersion")
        stalls.check()
        cache.check()
        assertTrue("old version's data still cached", oneVersion)
        checkConnections()
    }

    /** Checks that `buffer[0, length)` is [video]'s data at [position]. */
    protected fun checkWords(buffer: ByteBuffer, length: Int, position: Long, tag: Long,
        what: String) {
        for (i in 0 until length / 8) {
            val word = buffer.getLong(i * 8)
            assertEquals("$what: word at ${position + i * 8}", (tag shl 48) + position + i * 8,
                word)
        }
    }

    /** Reads up to [length] bytes at [position] through [channel]; the bytes read. */
    protected fun readChannel(channel: java8.nio.channels.SeekableByteChannel, position: Long,
        length: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        channel.position(position)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) <= 0) {
                break
            }
        }
        buffer.flip()
        return buffer
    }

    /**
     * A file growing while played (a recording): the part appended by another client after it
     * was opened is read through the same channel, not cut at the old end.
     */
    @Test
    fun appendedByAnotherClientWhilePlaying() {
        val video = changing("append-other", 34)
        clearReadCache()
        val appended = 16L * 1024 * 1024
        val reads = Timings("1 MB reads of the appended part")
        video.path.newByteChannel(StandardOpenOption.READ).use { channel ->
            val near = readChannel(channel, video.size - 5L * 1024 * 1024, 3 * 1024 * 1024)
            checkWords(near, near.limit(), video.size - 5L * 1024 * 1024, 34, "before growing")
            otherClient { nfs -> writeTagged(nfs, video.path, video.size, appended, 34) }
            var position = video.size
            while (position < video.size + appended) {
                val start = System.nanoTime()
                val buffer = readChannel(channel, position, 1024 * 1024)
                reads.add((System.nanoTime() - start) / 1_000_000)
                assertEquals("appended part at $position", 1024 * 1024, buffer.limit())
                checkWords(buffer, buffer.limit(), position, 34, "appended part")
                position += 1024 * 1024
            }
        }
        // Reopened through the file provider: the new size, and the new end.
        val grown = Video(video.path, video.size + appended, 34)
        open(grown).use { pfd ->
            assertEquals("size after growing", grown.size, pfd.size)
            seek(pfd, grown, grown.size - SEEK_BYTES, "new end")
        }
        report("appended by another client while playing", reads)
        reads.check(1_000, 3_000)
        checkConnections()
    }

    /**
     * A file truncated by another client while played: past the new end, reads end at once
     * (no hang, no error); before it, reading goes on.
     */
    @Test
    fun truncatedByAnotherClientWhilePlaying() {
        val video = changing("truncate-other", 35)
        clearReadCache()
        val newSize = 50L * 1024 * 1024
        val pastEnd = Timings("read past the new end")
        val before = Timings("read before the new end")
        video.path.newByteChannel(StandardOpenOption.READ).use { channel ->
            val start = readChannel(channel, 0, 5 * 1024 * 1024)
            checkWords(start, start.limit(), 0, 35, "before truncating")
            otherClient { nfs ->
                io.github.libnfsandroid.Nfs.truncate(
                    nfs, (video.path as NfsPath).remotePathBytes, newSize
                )
            }
            var begin = System.nanoTime()
            val past = readChannel(channel, 120L * 1024 * 1024, 1024 * 1024)
            pastEnd.add((System.nanoTime() - begin) / 1_000_000)
            assertEquals("bytes read past the new end", 0, past.limit())
            begin = System.nanoTime()
            val inside = readChannel(channel, 20L * 1024 * 1024, 1024 * 1024)
            before.add((System.nanoTime() - begin) / 1_000_000)
            assertEquals("bytes read before the new end", 1024 * 1024, inside.limit())
            checkWords(inside, inside.limit(), 20L * 1024 * 1024, 35, "before the new end")
        }
        val truncated = Video(video.path, newSize, 35)
        open(truncated).use { pfd ->
            assertEquals("size after truncating", newSize, pfd.size)
            seek(pfd, truncated, newSize - SEEK_BYTES, "new end")
        }
        report("truncated by another client while playing", pastEnd, before)
        pastEnd.check(1_500, 1_500)
        before.check(1_000, 3_000)
        checkConnections()
    }

    /** Runs [block] on [count] threads at once; rethrows the first failure. */
    protected fun runInParallel(count: Int, block: (Int) -> Unit) {
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val threads = List(count) { index ->
            Thread {
                try {
                    block(index)
                } catch (t: Throwable) {
                    errors += t
                }
            }.apply { start() }
        }
        threads.forEach { it.join(15 * 60_000) }
        errors.peek()?.let { throw AssertionError("${errors.size} thread(s) failed: $it", it) }
    }

    companion object {
        const val TEST_TIMEOUT_MILLIS = 6 * 60_000L

        /** What a seek reads to show the picture. */
        const val SEEK_BYTES = 64 * 1024

        /** A 1080p movie: 8 Mbit/s. */
        const val PLAYBACK_BYTES_PER_SECOND = 1024 * 1024

        /** How far a player may be late before the picture freezes. */
        const val PLAYER_BUFFER_MILLIS = 2_000L
    }
}

package me.zhanghai.android.files.nfs

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.inSpans
import androidx.fragment.app.Fragment
import androidx.fragment.app.add
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.io.File
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.databinding.NfsDiagnosticsFragmentBinding
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asFileSize
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.provider.nfs.client.Client
import me.zhanghai.android.files.provider.nfs.client.ClientException
import me.zhanghai.android.files.provider.nfs.client.ConnectionStats
import me.zhanghai.android.files.provider.nfs.client.FileByteChannel
import me.zhanghai.android.files.provider.nfs.client.NetworkMonitor
import me.zhanghai.android.files.provider.nfs.client.NfsClock
import me.zhanghai.android.files.provider.nfs.client.NfsForeground
import me.zhanghai.android.files.provider.nfs.client.NfsReadCache
import me.zhanghai.android.files.provider.nfs.client.NfsSpace
import me.zhanghai.android.files.util.createSendStreamIntent
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.withChooser

/**
 * What the NFS connections are doing now: the network Android gives the app (and whether it
 * blocks it), live traffic, connections per server and role, open files, recent failures and the
 * read cache; a link test and a way to share nfs-log.txt. Opened from the "Connected to NFS"
 * notification and from the settings.
 */
class NfsDiagnosticsActivity : AppActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (savedInstanceState == null) {
            supportFragmentManager.commit { add<NfsDiagnosticsFragment>(android.R.id.content) }
        }
    }
}

class NfsDiagnosticsFragment : Fragment() {
    private lateinit var binding: NfsDiagnosticsFragmentBinding

    /** Traffic counters a second apart, for the last 10 s. */
    private val samples = ArrayDeque<Sample>()

    private class Sample(val millis: Long, val read: Long, val written: Long)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        NfsDiagnosticsFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        activity.setTitle(R.string.nfs_diagnostics_title)
        binding.testLinkButton.setOnClickListener { testLink() }
        binding.shareLogButton.setOnClickListener { shareLog() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val report = withContext(Dispatchers.IO) { report() }
                    binding.statusText.text = report
                    delay(1000)
                }
            }
        }
    }

    private fun report(): CharSequence {
        val now = NfsClock.elapsedRealtime()
        samples.addLast(
            Sample(
                now, FileByteChannel.networkBytesRead.get(),
                FileByteChannel.networkBytesWritten.get()
            )
        )
        while (samples.size > 11) {
            samples.removeFirst()
        }
        val text = SpannableStringBuilder()
        fun header(resId: Int) {
            if (text.isNotEmpty()) {
                text.append("\n")
            }
            text.inSpans(StyleSpan(android.graphics.Typeface.BOLD)) { append(getString(resId)) }
            text.append("\n")
        }
        fun line(value: String) {
            text.append(value).append("\n")
        }

        header(R.string.nfs_diagnostics_status)
        line(getString(R.string.nfs_diagnostics_network, NetworkMonitor.describeDefaultNetwork()))
        line(
            getString(
                if (NetworkMonitor.isBlocked) {
                    R.string.nfs_diagnostics_network_blocked
                } else {
                    R.string.nfs_diagnostics_network_allowed
                }
            )
        )
        line(
            getString(
                if (NfsForeground.isRunning) {
                    R.string.nfs_diagnostics_service_running
                } else {
                    R.string.nfs_diagnostics_service_stopped
                }
            )
        )
        line(getString(R.string.nfs_diagnostics_network_changes, Client.networkChangeCount))

        header(R.string.nfs_diagnostics_traffic)
        val last = samples.last()
        val second = samples.getOrNull(samples.size - 2)
        val first = samples.first()
        fun rate(to: Sample, from: Sample?, bytes: (Sample) -> Long): Double =
            if (from == null || to.millis == from.millis) {
                0.0
            } else {
                (bytes(to) - bytes(from)) / 1e6 / ((to.millis - from.millis) / 1000.0)
            }
        line(
            getString(
                R.string.nfs_diagnostics_download, rate(last, second) { it.read },
                rate(last, first) { it.read }
            )
        )
        line(
            getString(
                R.string.nfs_diagnostics_upload, rate(last, second) { it.written },
                rate(last, first) { it.written }
            )
        )
        line(
            getString(
                R.string.nfs_diagnostics_totals, last.read / 1e6, last.written / 1e6,
                FileByteChannel.diskBytes.get() / 1e6
            )
        )

        header(R.string.nfs_diagnostics_connections)
        val servers = Client.serverConnections()
        if (servers.isEmpty()) {
            line(getString(R.string.nfs_diagnostics_no_connections))
        }
        for (server in servers) {
            val roles = server.roles
            line(
                getString(
                    R.string.nfs_diagnostics_server, server.authority.host, roles.total,
                    roles.totalInUse, roles.total - roles.totalInUse
                )
            )
            NfsSpace.refreshSoon(server.authority)
            NfsSpace.cached(server.authority)?.let { space ->
                val available = space.available.asFileSize().formatHumanReadable(requireContext())
                val total = space.total.asFileSize().formatHumanReadable(requireContext())
                line(
                    "  " + if (space.isLimited) {
                        getString(
                            R.string.nfs_diagnostics_space_limited, available, total,
                            space.free.asFileSize().formatHumanReadable(requireContext())
                        )
                    } else {
                        getString(R.string.nfs_diagnostics_space, available, total)
                    }
                )
            }
            for (role in Client.Role.values()) {
                val count = roles.counts[role.ordinal]
                if (count == 0) {
                    continue
                }
                val inUse = roles.inUse[role.ordinal]
                line(
                    "  " + getString(
                        R.string.nfs_connection_notification_role_format,
                        getString(NfsForeground.ROLE_NAMES.getValue(role)), count, inUse,
                        count - inUse
                    )
                )
            }
        }

        header(R.string.nfs_diagnostics_open_files)
        val files = FileByteChannel.openFiles()
        if (files.isEmpty()) {
            line(getString(R.string.nfs_diagnostics_no_open_files))
        }
        for (file in files) {
            line(
                getString(
                    R.string.nfs_diagnostics_open_file, file.name.substringAfterLast('/'),
                    file.profile.name.lowercase(), file.bytesRead / 1e6,
                    file.diskBytesRead / 1e6, file.connections, file.busyConnections,
                    file.openSeconds
                )
            )
        }

        header(R.string.nfs_diagnostics_problems)
        val stats = ConnectionStats.snapshot()
        line(
            getString(
                R.string.nfs_diagnostics_problem_counts, stats.broke, stats.openFailed,
                stats.refused, stats.serverBusy
            )
        )
        for (failure in ConnectionStats.lastFailures.toList().asReversed()) {
            line("• $failure")
        }

        header(R.string.nfs_diagnostics_cache)
        line(
            getString(
                R.string.nfs_diagnostics_cache_usage, NfsReadCache.totalSize() / 1e9,
                NfsReadCache.configuredSizeGb
            )
        )
        return text
    }

    private fun testLink() {
        val servers = Client.serverConnections().map { it.authority }
            .ifEmpty { Client.knownServers() }
        if (servers.isEmpty()) {
            showToast(R.string.nfs_diagnostics_test_no_server)
            return
        }
        binding.testLinkButton.isEnabled = false
        binding.testText.visibility = View.VISIBLE
        binding.testText.setText(R.string.nfs_diagnostics_testing)
        viewLifecycleOwner.lifecycleScope.launch {
            val lines = mutableListOf<String>()
            for (authority in servers) {
                val result = withContext(Dispatchers.IO) {
                    try {
                        Client.testLink(authority, TEST_SECONDS) {}
                    } catch (e: ClientException) {
                        e
                    }
                }
                lines += authority.host + ":"
                when (result) {
                    is ClientException -> lines += "  " + getString(
                        R.string.nfs_diagnostics_test_failed, result.message
                    )
                    is Client.LinkTest -> {
                        val latencies = result.latenciesMillis
                        lines += "  " + getString(
                            R.string.nfs_diagnostics_test_latency, latencies.min(),
                            latencies.average().toLong(), latencies.max()
                        )
                        lines += "  " + if (result.file == null) {
                            getString(R.string.nfs_diagnostics_test_no_file)
                        } else {
                            getString(
                                R.string.nfs_diagnostics_test_speed,
                                result.bytes / 1e6 / result.seconds.coerceAtLeast(0.001),
                                result.connections, result.file.substringAfterLast('/'),
                                result.seconds
                            )
                        }
                    }
                }
                binding.testText.text = lines.joinToString("\n")
            }
            binding.testLinkButton.isEnabled = true
        }
    }

    private fun shareLog() {
        val file = requireContext().getExternalFilesDir(null)?.let { File(it, "nfs-log.txt") }
        if (file == null || !file.exists()) {
            showToast(R.string.nfs_diagnostics_no_log)
            return
        }
        val uri = Paths.get(file.absolutePath).fileProviderUri
        startActivitySafe(uri.createSendStreamIntent(MimeType.TEXT_PLAIN).withChooser())
    }

    companion object {
        private const val TEST_SECONDS = 8
    }
}

package me.zhanghai.android.files.storage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.EditNfsServerFragmentBinding
import me.zhanghai.android.files.provider.nfs.client.Authority
import me.zhanghai.android.files.provider.nfs.client.ConnectionOptions
import me.zhanghai.android.files.util.ActionState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.finish
import me.zhanghai.android.files.util.hideTextInputLayoutErrorOnTextChange
import me.zhanghai.android.files.util.isReady
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.takeIfNotEmpty
import me.zhanghai.android.files.util.viewModels
import java.net.URI

class EditNfsServerFragment : Fragment() {
    private val args by args<Args>()

    private val viewModel by viewModels { { EditNfsServerViewModel() } }

    private lateinit var binding: EditNfsServerFragmentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launchWhenStarted {
            launch { viewModel.connectState.collect { onConnectStateChanged(it) } }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        EditNfsServerFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.lifecycleScope.launchWhenCreated {
            activity.setSupportActionBar(binding.toolbar)
            activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            activity.setTitle(
                if (args.server != null) {
                    R.string.storage_edit_nfs_server_title_edit
                } else {
                    R.string.storage_edit_nfs_server_title_add
                }
            )
        }

        binding.hostEdit.hideTextInputLayoutErrorOnTextChange(binding.hostLayout)
        binding.hostEdit.doAfterTextChanged { updateNamePlaceholder() }
        binding.portEdit.hideTextInputLayoutErrorOnTextChange(binding.portLayout)
        binding.portEdit.doAfterTextChanged { updateNamePlaceholder() }
        binding.exportPathEdit.hideTextInputLayoutErrorOnTextChange(binding.exportPathLayout)
        binding.exportPathEdit.doAfterTextChanged { updateNamePlaceholder() }
        binding.pathEdit.doAfterTextChanged { updateNamePlaceholder() }
        binding.uidEdit.hideTextInputLayoutErrorOnTextChange(binding.uidLayout)
        binding.gidEdit.hideTextInputLayoutErrorOnTextChange(binding.gidLayout)
        binding.auxiliaryGidsEdit.hideTextInputLayoutErrorOnTextChange(
            binding.auxiliaryGidsLayout
        )
        binding.saveOrConnectAndAddButton.setText(
            if (args.server != null) {
                R.string.save
            } else {
                R.string.storage_edit_nfs_server_connect_and_add
            }
        )
        binding.saveOrConnectAndAddButton.setOnClickListener {
            if (args.server != null) {
                saveOrAdd()
            } else {
                connectAndAdd()
            }
        }
        binding.cancelButton.setOnClickListener { finish() }
        binding.removeOrAddButton.setText(
            if (args.server != null) R.string.remove else R.string.storage_edit_nfs_server_add
        )
        binding.removeOrAddButton.setOnClickListener {
            if (args.server != null) {
                remove()
            } else {
                saveOrAdd()
            }
        }

        if (savedInstanceState == null) {
            val server = args.server
            if (server != null) {
                val authority = server.authority
                binding.hostEdit.setText(authority.host)
                if (authority.port != Authority.DEFAULT_PORT) {
                    binding.portEdit.setText(authority.port.toString())
                }
                binding.exportPathEdit.setText(authority.exportPath)
                binding.pathEdit.setText(server.relativePath)
                binding.nameEdit.setText(server.customName)
                val options = server.options
                binding.uidEdit.setText(options.uid.toString())
                binding.gidEdit.setText(options.gid.toString())
                binding.auxiliaryGidsEdit.setText(options.auxiliaryGids.joinToString(", "))
                binding.readOnlyCheck.isChecked = options.isReadOnly
            }
        }
    }

    private fun updateNamePlaceholder() {
        val host = binding.hostEdit.text.toString().takeIfNotEmpty()
        val port = binding.portEdit.text.toString().takeIfNotEmpty()?.toIntOrNull()
            ?: Authority.DEFAULT_PORT
        val exportPath = binding.exportPathEdit.text.toString().trim().takeIfNotEmpty() ?: "/"
        val path = binding.pathEdit.text.toString().trim()
        binding.nameLayout.placeholderText =
            if (host != null && exportPath.startsWith("/")) {
                val authority = Authority(host, port, exportPath)
                if (path.isNotEmpty()) "$authority/$path" else authority.toString()
            } else {
                getString(R.string.storage_edit_nfs_server_name_placeholder)
            }
    }

    private fun saveOrAdd() {
        val server = getServerOrSetError() ?: return
        Storages.addOrReplace(server)
        finish()
    }

    private fun connectAndAdd() {
        if (!viewModel.connectState.value.isReady) {
            return
        }
        val server = getServerOrSetError() ?: return
        viewModel.connect(server)
    }

    private fun onConnectStateChanged(state: ActionState<NfsServer, Unit>) {
        when (state) {
            is ActionState.Ready, is ActionState.Running -> {
                val isConnecting = state is ActionState.Running
                binding.progress.fadeToVisibilityUnsafe(isConnecting)
                binding.scrollView.fadeToVisibilityUnsafe(!isConnecting)
                binding.saveOrConnectAndAddButton.isEnabled = !isConnecting
                binding.removeOrAddButton.isEnabled = !isConnecting
            }
            is ActionState.Success -> {
                Storages.addOrReplace(state.argument)
                finish()
            }
            is ActionState.Error -> {
                val throwable = state.throwable
                throwable.printStackTrace()
                showToast(throwable.toString())
                viewModel.finishConnecting()
            }
        }
    }

    private fun remove() {
        Storages.remove(args.server!!)
        finish()
    }

    private fun getServerOrSetError(): NfsServer? {
        var errorEdit: TextInputEditText? = null
        val host = binding.hostEdit.text.toString().takeIfNotEmpty()
            ?.let { URI::class.canonicalizeHost(it) }
        if (host == null) {
            binding.hostLayout.error = getString(R.string.storage_edit_nfs_server_host_error_empty)
            if (errorEdit == null) {
                errorEdit = binding.hostEdit
            }
        } else if (!URI::class.isValidHost(host)) {
            binding.hostLayout.error =
                getString(R.string.storage_edit_nfs_server_host_error_invalid)
            if (errorEdit == null) {
                errorEdit = binding.hostEdit
            }
        }
        val port = binding.portEdit.text.toString().takeIfNotEmpty()
            .let { if (it != null) it.toIntOrNull()?.takeIf { it in 1..65535 }
                else Authority.DEFAULT_PORT }
        if (port == null) {
            binding.portLayout.error = getString(R.string.storage_edit_nfs_server_port_error_invalid)
            if (errorEdit == null) {
                errorEdit = binding.portEdit
            }
        }
        val exportPath = binding.exportPathEdit.text.toString().trim().takeIfNotEmpty()
            ?.trimEnd('/')?.ifEmpty { "/" } ?: "/"
        if (!exportPath.startsWith("/")) {
            binding.exportPathLayout.error =
                getString(R.string.storage_edit_nfs_server_export_path_error_invalid)
            if (errorEdit == null) {
                errorEdit = binding.exportPathEdit
            }
        }
        val path = binding.pathEdit.text.toString().trim().trim('/')
        val name = binding.nameEdit.text.toString().takeIfNotEmpty()
        val uid = parseId(binding.uidEdit.text.toString())
        if (uid == null) {
            binding.uidLayout.error = getString(R.string.storage_edit_nfs_server_id_error_invalid)
            if (errorEdit == null) {
                errorEdit = binding.uidEdit
            }
        }
        val gid = parseId(binding.gidEdit.text.toString())
        if (gid == null) {
            binding.gidLayout.error = getString(R.string.storage_edit_nfs_server_id_error_invalid)
            if (errorEdit == null) {
                errorEdit = binding.gidEdit
            }
        }
        val auxiliaryGids = parseAuxiliaryGids(binding.auxiliaryGidsEdit.text.toString())
        if (auxiliaryGids == null) {
            binding.auxiliaryGidsLayout.error =
                getString(R.string.storage_edit_nfs_server_auxiliary_gids_error_invalid)
            if (errorEdit == null) {
                errorEdit = binding.auxiliaryGidsEdit
            }
        }
        if (errorEdit != null) {
            errorEdit.requestFocus()
            return null
        }
        val authority = Authority(host!!, port!!, exportPath)
        val options = ConnectionOptions(
            ConnectionOptions.Version.V4_2, uid!!, gid!!, auxiliaryGids!!,
            binding.readOnlyCheck.isChecked
        )
        return NfsServer(args.server?.id, name, authority, options, path)
    }

    /** Empty means the conventional anonymous ID. */
    private fun parseId(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return ConnectionOptions.DEFAULT_ID
        }
        return trimmed.toLongOrNull()?.takeIf { it in 0..MAX_ID }?.toInt()
    }

    private fun parseAuxiliaryGids(text: String): List<Int>? {
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size > ConnectionOptions.MAX_AUXILIARY_GIDS) {
            return null
        }
        return parts.map { part ->
            part.toLongOrNull()?.takeIf { it in 0..MAX_ID }?.toInt() ?: return null
        }
    }

    @Parcelize
    class Args(val server: NfsServer? = null) : ParcelableArgs

    companion object {
        // AUTH_SYS IDs are unsigned 32-bit; IDs above 2^31 - 1 are not supported.
        private const val MAX_ID = 0x7FFFFFFFL
    }
}

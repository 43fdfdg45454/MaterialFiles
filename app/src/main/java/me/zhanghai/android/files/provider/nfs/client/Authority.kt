package me.zhanghai.android.files.provider.nfs.client

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.provider.common.UriAuthority

/**
 * Identifies one mounted NFS export: `host:exportPath`.
 *
 * In URIs the export path travels as the user info (percent-encoded), because it may contain
 * several segments and cannot be told apart from the path inside the export otherwise:
 * `nfs://%2Fsrv%2Fnfs@nas:2049/some/dir`.
 */
@Parcelize
data class Authority(
    val host: String,
    val port: Int,
    val exportPath: String
) : Parcelable {
    init {
        require(exportPath.startsWith("/")) { "Export path must be absolute: $exportPath" }
    }

    fun toUriAuthority(): UriAuthority {
        val uriPort = port.takeIf { it != DEFAULT_PORT }
        return UriAuthority(exportPath, host, uriPort)
    }

    /** The conventional NFS notation, `host:/export`. */
    override fun toString(): String =
        if (port == DEFAULT_PORT) "$host:$exportPath" else "$host:$port:$exportPath"

    companion object {
        const val DEFAULT_PORT = 2049
    }
}

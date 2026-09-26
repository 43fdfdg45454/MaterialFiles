package me.zhanghai.android.files.provider.nfs.client

import android.os.Parcelable
import io.github.libnfsandroid.Nfs
import kotlinx.parcelize.Parcelize

/**
 * How to talk to an export. NFS AUTH_SYS has no password: the server trusts the numeric identity
 * the client sends, so this plays the role that a password plays for SFTP or SMB.
 */
@Parcelize
data class ConnectionOptions(
    val version: Version,
    val uid: Int,
    val gid: Int,
    val auxiliaryGids: List<Int>,
    val isReadOnly: Boolean
) : Parcelable {
    init {
        require(auxiliaryGids.size <= MAX_AUXILIARY_GIDS) {
            "At most $MAX_AUXILIARY_GIDS auxiliary groups"
        }
    }

    enum class Version(val nfsVersion: Int) {
        V3(Nfs.NFS_V3),
        V4_2(Nfs.NFS_V4_2)
    }

    companion object {
        const val MAX_AUXILIARY_GIDS = 16

        /** Conventional "nobody" identity. */
        const val DEFAULT_ID = 65534

        val DEFAULT = ConnectionOptions(Version.V4_2, DEFAULT_ID, DEFAULT_ID, emptyList(), false)
    }
}

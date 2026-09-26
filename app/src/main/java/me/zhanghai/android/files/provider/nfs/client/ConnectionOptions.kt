package me.zhanghai.android.files.provider.nfs.client

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize

/**
 * How to talk to an export (always NFSv4.2).
 *
 * Two independent things:
 * - [security]: how the connection is protected. [Security.TLS] encrypts it and authenticates
 *   the server (RPC-with-TLS, `xprtsec=tls` on the server); [Security.MUTUAL_TLS] also presents
 *   the client certificate [clientCertificateAlias] from the Android key store (`xprtsec=mtls`),
 *   so that the server can tell this device from others without trusting its address.
 * - The identity sent with every call (AUTH_SYS [uid], [gid], [auxiliaryGids]). NFS has no
 *   password: the server trusts these numbers, unless it maps them all to one user (all_squash).
 */
@Parcelize
data class ConnectionOptions(
    val uid: Int,
    val gid: Int,
    val auxiliaryGids: List<Int>,
    val isReadOnly: Boolean,
    val security: Security = Security.NONE,
    val clientCertificateAlias: String? = null
) : Parcelable {
    init {
        require(auxiliaryGids.size <= MAX_AUXILIARY_GIDS) {
            "At most $MAX_AUXILIARY_GIDS auxiliary groups"
        }
        require(security != Security.MUTUAL_TLS || clientCertificateAlias != null) {
            "Mutual TLS needs a client certificate"
        }
    }

    enum class Security {
        NONE,
        TLS,
        MUTUAL_TLS
    }

    /**
     * Stored servers are kept as parcels inside the storage list, which has no per-item length,
     * so the layout must stay readable. Earlier builds wrote the NFS version name first ("V3" or
     * "V4_2") followed by the identity; this layout starts with a marker instead and appends the
     * security settings.
     */
    companion object : Parceler<ConnectionOptions> {
        const val MAX_AUXILIARY_GIDS = 16

        /** Conventional "nobody" identity. */
        const val DEFAULT_ID = 65534

        val DEFAULT = ConnectionOptions(DEFAULT_ID, DEFAULT_ID, emptyList(), false)

        private const val LAYOUT_2 = "options-v2"

        override fun ConnectionOptions.write(parcel: Parcel, flags: Int) {
            parcel.writeString(LAYOUT_2)
            parcel.writeInt(uid)
            parcel.writeInt(gid)
            parcel.writeInt(auxiliaryGids.size)
            auxiliaryGids.forEach { parcel.writeInt(it) }
            parcel.writeInt(if (isReadOnly) 1 else 0)
            parcel.writeString(security.name)
            parcel.writeString(clientCertificateAlias)
        }

        override fun create(parcel: Parcel): ConnectionOptions {
            val layout = parcel.readString()
            val uid = parcel.readInt()
            val gid = parcel.readInt()
            val auxiliaryGids = List(parcel.readInt()) { parcel.readInt() }
            val isReadOnly = parcel.readInt() != 0
            if (layout != LAYOUT_2) {
                // The first layout: the version name was the marker, and nothing follows.
                return ConnectionOptions(uid, gid, auxiliaryGids, isReadOnly)
            }
            val security = parcel.readString()
                ?.let { name -> Security.entries.firstOrNull { it.name == name } }
                ?: Security.NONE
            val alias = parcel.readString()
            return ConnectionOptions(
                uid, gid, auxiliaryGids, isReadOnly,
                if (security == Security.MUTUAL_TLS && alias == null) Security.TLS else security,
                alias
            )
        }
    }
}

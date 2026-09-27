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
    val clientCertificateAlias: String? = null,
    /** Extra connections a file may use for streaming and transfers (see FileByteChannel). */
    val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
    /** When a streamed file gets them. */
    val connectionGrowth: ConnectionGrowth = ConnectionGrowth.BY_PLAYBACK
) : Parcelable {
    init {
        require(auxiliaryGids.size <= MAX_AUXILIARY_GIDS) {
            "At most $MAX_AUXILIARY_GIDS auxiliary groups"
        }
        require(security != Security.MUTUAL_TLS || clientCertificateAlias != null) {
            "Mutual TLS needs a client certificate"
        }
        require(maxConnections in MIN_MAX_CONNECTIONS..MAX_MAX_CONNECTIONS) {
            "Between $MIN_MAX_CONNECTIONS and $MAX_MAX_CONNECTIONS connections"
        }
    }

    enum class Security {
        NONE,
        TLS,
        MUTUAL_TLS
    }

    /**
     * When a streamed file gets its extra connections. All of them are connected (TCP, TLS,
     * session) when the file opens, except with [GRADUAL].
     * - [AT_OPEN]: all used from the first read.
     * - [BY_PLAYBACK]: by how far the player went forward since its last seek: 4, a bit more after
     *   1 MB, all after 8 MB.
     * - [GRADUAL]: from 4, two more every second since the file opened, each connected when it is
     *   added (to find how many the server takes).
     */
    enum class ConnectionGrowth {
        AT_OPEN,
        BY_PLAYBACK,
        GRADUAL
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

        const val MIN_MAX_CONNECTIONS = 4
        const val MAX_MAX_CONNECTIONS = 32
        const val DEFAULT_MAX_CONNECTIONS = 16

        private const val LAYOUT_2 = "options-v2"
        private const val LAYOUT_3 = "options-v3"

        override fun ConnectionOptions.write(parcel: Parcel, flags: Int) {
            parcel.writeString(LAYOUT_3)
            parcel.writeInt(uid)
            parcel.writeInt(gid)
            parcel.writeInt(auxiliaryGids.size)
            auxiliaryGids.forEach { parcel.writeInt(it) }
            parcel.writeInt(if (isReadOnly) 1 else 0)
            parcel.writeString(security.name)
            parcel.writeString(clientCertificateAlias)
            parcel.writeInt(maxConnections)
            parcel.writeString(connectionGrowth.name)
        }

        override fun create(parcel: Parcel): ConnectionOptions {
            val layout = parcel.readString()
            val uid = parcel.readInt()
            val gid = parcel.readInt()
            val auxiliaryGids = List(parcel.readInt()) { parcel.readInt() }
            val isReadOnly = parcel.readInt() != 0
            if (layout != LAYOUT_2 && layout != LAYOUT_3) {
                // The first layout: the version name was the marker, and nothing follows.
                return ConnectionOptions(uid, gid, auxiliaryGids, isReadOnly)
            }
            val security = parcel.readString()
                ?.let { name -> Security.entries.firstOrNull { it.name == name } }
                ?: Security.NONE
            val alias = parcel.readString()
            var maxConnections = DEFAULT_MAX_CONNECTIONS
            var growth = ConnectionGrowth.BY_PLAYBACK
            if (layout == LAYOUT_3) {
                maxConnections = parcel.readInt()
                    .coerceIn(MIN_MAX_CONNECTIONS, MAX_MAX_CONNECTIONS)
                growth = parcel.readString()
                    ?.let { name -> ConnectionGrowth.entries.firstOrNull { it.name == name } }
                    ?: ConnectionGrowth.BY_PLAYBACK
            }
            return ConnectionOptions(
                uid, gid, auxiliaryGids, isReadOnly,
                if (security == Security.MUTUAL_TLS && alias == null) Security.TLS else security,
                alias, maxConnections, growth
            )
        }
    }
}

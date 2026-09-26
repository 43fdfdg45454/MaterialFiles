package me.zhanghai.android.files.provider.nfs

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.provider.nfs.client.Authority

/** Identity of a file on an export, stable across renames and hard links. */
@Parcelize
internal data class NfsFileKey(
    val authority: Authority,
    val dev: Long,
    val ino: Long
) : Parcelable

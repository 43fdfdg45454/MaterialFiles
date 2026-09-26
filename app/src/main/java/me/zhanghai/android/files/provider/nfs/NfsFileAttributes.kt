/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs

import android.os.Parcelable
import io.github.libnfsandroid.NfsStat
import java.time.Instant
import java8.nio.file.attribute.FileTime
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.provider.common.AbstractPosixFileAttributes
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.FileTimeParceler
import me.zhanghai.android.files.provider.common.PosixFileMode
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixFileType
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.PosixUser

@Parcelize
internal data class NfsFileAttributes(
    override val lastModifiedTime: @WriteWith<FileTimeParceler> FileTime,
    override val lastAccessTime: @WriteWith<FileTimeParceler> FileTime,
    override val creationTime: @WriteWith<FileTimeParceler> FileTime,
    override val type: PosixFileType,
    override val size: Long,
    override val fileKey: Parcelable,
    override val owner: PosixUser?,
    override val group: PosixGroup?,
    override val mode: Set<PosixFileModeBit>?,
    override val seLinuxContext: ByteString?
) : AbstractPosixFileAttributes() {
    companion object {
        fun from(stat: NfsStat, path: NfsPath): NfsFileAttributes {
            val lastModifiedTime = FileTime.from(
                Instant.ofEpochSecond(stat.mtimeSeconds, stat.mtimeNanoseconds)
            )
            val lastAccessTime = FileTime.from(
                Instant.ofEpochSecond(stat.atimeSeconds, stat.atimeNanoseconds)
            )
            // NFS has no creation time; ctime is the last status change.
            val creationTime = lastModifiedTime
            val type = PosixFileType.fromMode(stat.mode)
            val size = stat.size
            val fileKey = NfsFileKey(path.authority, stat.dev, stat.ino)
            val owner = PosixUser(stat.uid, null)
            val group = PosixGroup(stat.gid, null)
            val mode = PosixFileMode.fromInt(stat.mode)
            val seLinuxContext = null
            return NfsFileAttributes(
                lastModifiedTime, lastAccessTime, creationTime, type, size, fileKey, owner, group,
                mode, seLinuxContext
            )
        }
    }
}

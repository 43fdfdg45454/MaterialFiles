/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs

import java8.nio.file.attribute.FileTime
import me.zhanghai.android.files.provider.common.ByteString
import me.zhanghai.android.files.provider.common.PosixFileAttributeView
import me.zhanghai.android.files.provider.common.PosixFileModeBit
import me.zhanghai.android.files.provider.common.PosixGroup
import me.zhanghai.android.files.provider.common.PosixUser
import me.zhanghai.android.files.provider.common.toInt
import io.github.libnfsandroid.NfsStat
import me.zhanghai.android.files.provider.nfs.client.Client
import me.zhanghai.android.files.provider.nfs.client.ClientException
import java.io.IOException

internal class NfsFileAttributeView(
    private val path: NfsPath,
    private val noFollowLinks: Boolean
) : PosixFileAttributeView {
    override fun name(): String = NAME

    @Throws(IOException::class)
    override fun readAttributes(): NfsFileAttributes {
        val stat = getStat()
        return NfsFileAttributes.from(stat, path)
    }

    override fun setTimes(
        lastModifiedTime: FileTime?,
        lastAccessTime: FileTime?,
        createTime: FileTime?
    ) {
        if (lastAccessTime == null && lastModifiedTime == null) {
            // Only throw if caller is trying to set only create time, so that foreign copy move can
            // still set other times.
            if (createTime != null) {
                throw UnsupportedOperationException("createTime")
            }
            return
        }
        val currentStat = if (lastAccessTime == null || lastModifiedTime == null) {
            getStat()
        } else {
            null
        }
        val atime = lastAccessTime?.toInstant()
        val mtime = lastModifiedTime?.toInstant()
        try {
            Client.utimes(
                path,
                atime?.epochSecond ?: currentStat!!.atimeSeconds,
                atime?.nano?.toLong() ?: currentStat!!.atimeNanoseconds,
                mtime?.epochSecond ?: currentStat!!.mtimeSeconds,
                mtime?.nano?.toLong() ?: currentStat!!.mtimeNanoseconds,
                noFollowLinks
            )
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
    }

    @Throws(IOException::class)
    override fun setOwner(owner: PosixUser) {
        val currentStat = getStat()
        try {
            Client.chown(path, owner.id, currentStat.gid, noFollowLinks)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
    }

    @Throws(IOException::class)
    override fun setGroup(group: PosixGroup) {
        val currentStat = getStat()
        try {
            Client.chown(path, currentStat.uid, group.id, noFollowLinks)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
    }

    @Throws(IOException::class)
    override fun setMode(mode: Set<PosixFileModeBit>) {
        if (noFollowLinks) {
            throw UnsupportedOperationException("Cannot set mode for symbolic links")
        }
        try {
            Client.chmod(path, mode.toInt())
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }
    }

    @Throws(IOException::class)
    private fun getStat(): NfsStat =
        try {
            if (noFollowLinks) Client.lstat(path) else Client.stat(path)
        } catch (e: ClientException) {
            throw e.toFileSystemException(path.toString())
        }

    @Throws(IOException::class)
    override fun setSeLinuxContext(context: ByteString) {
        throw UnsupportedOperationException()
    }

    @Throws(IOException::class)
    override fun restoreSeLinuxContext() {
        throw UnsupportedOperationException()
    }

    companion object {
        private val NAME = NfsFileSystemProvider.scheme

        val SUPPORTED_NAMES = setOf("basic", "posix", NAME)
    }
}

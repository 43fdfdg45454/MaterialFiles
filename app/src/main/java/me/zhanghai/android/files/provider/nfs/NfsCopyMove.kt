/*
 * Copyright (c) 2021 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.provider.nfs

import io.github.libnfsandroid.Nfs
import io.github.libnfsandroid.NfsStat
import java.io.IOException
import java.time.Instant
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.NoSuchFileException
import java8.nio.file.StandardCopyOption
import me.zhanghai.android.files.provider.common.CopyOptions
import me.zhanghai.android.files.provider.common.copyTo
import me.zhanghai.android.files.provider.common.newInputStream
import me.zhanghai.android.files.provider.common.newOutputStream
import me.zhanghai.android.files.provider.nfs.client.Client
import me.zhanghai.android.files.provider.nfs.client.ClientException

internal object NfsCopyMove {
    private const val PERMISSION_MASK = 0b111_111_111_111

    @Throws(IOException::class)
    fun copy(source: NfsPath, target: NfsPath, copyOptions: CopyOptions) {
        if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        val sourceStat = try {
            if (copyOptions.noFollowLinks) Client.lstat(source) else Client.stat(source)
        } catch (e: ClientException) {
            throw e.toFileSystemException(source.toString())
        }
        val targetStat = lstatOrNull(target)
        if (targetStat != null) {
            if (source == target) {
                copyOptions.progressListener?.invoke(sourceStat.size)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
        }
        val sourceMode = sourceStat.mode and PERMISSION_MASK
        when {
            sourceStat.isRegularFile -> {
                if (targetStat != null) {
                    removeIfExists(target)
                }
                var targetFlags = Nfs.O_WRONLY or Nfs.O_TRUNC or Nfs.O_CREAT
                if (!copyOptions.replaceExisting) {
                    targetFlags = targetFlags or Nfs.O_EXCL
                }
                // Within one export the server copies (or clones) by itself: nothing crosses the
                // network, which matters most over a VPN.
                val copiedOnServer = try {
                    Client.serverSideCopy(
                        source, target, targetFlags, sourceMode, sourceStat.size,
                        copyOptions.progressIntervalMillis, copyOptions.progressListener
                    )
                } catch (e: ClientException) {
                    throw e.toFileSystemException(source.toString(), target.toString())
                }
                if (!copiedOnServer) {
                    copyThroughClient(source, target, targetFlags, sourceMode, copyOptions)
                }
            }
            sourceStat.isDirectory -> {
                if (targetStat != null) {
                    removeIfExists(target)
                }
                try {
                    Client.mkdir(target, sourceMode)
                } catch (e: ClientException) {
                    throw e.toFileSystemException(target.toString())
                }
                copyOptions.progressListener?.invoke(sourceStat.size)
            }
            sourceStat.isSymbolicLink -> {
                val sourceTarget = try {
                    Client.readLink(source)
                } catch (e: ClientException) {
                    throw e.toFileSystemException(source.toString())
                }
                if (targetStat != null) {
                    removeIfExists(target)
                }
                try {
                    Client.symlink(target, sourceTarget)
                } catch (e: ClientException) {
                    throw e.toFileSystemException(target.toString())
                }
                copyOptions.progressListener?.invoke(sourceStat.size)
            }
            else -> throw FileSystemException(
                source.toString(), null, "type 0${Integer.toOctalString(sourceStat.getType())}"
            )
        }
        // We don't take error when copying attribute fatal, so errors will only be logged from now
        // on.
        if (!sourceStat.isSymbolicLink) {
            try {
                Client.utimes(
                    target,
                    if (copyOptions.copyAttributes) {
                        sourceStat.atimeSeconds
                    } else {
                        Instant.now().epochSecond
                    },
                    if (copyOptions.copyAttributes) sourceStat.atimeNanoseconds else 0,
                    sourceStat.mtimeSeconds, sourceStat.mtimeNanoseconds, false
                )
            } catch (e: ClientException) {
                e.printStackTrace()
            }
            if (copyOptions.copyAttributes) {
                try {
                    Client.chown(target, sourceStat.uid, sourceStat.gid, false)
                } catch (e: ClientException) {
                    e.printStackTrace()
                }
            }
        }
    }

    @Throws(IOException::class)
    fun move(source: NfsPath, target: NfsPath, copyOptions: CopyOptions) {
        val sourceStat = try {
            Client.lstat(source)
        } catch (e: ClientException) {
            throw e.toFileSystemException(source.toString())
        }
        val targetStat = lstatOrNull(target)
        if (targetStat != null) {
            if (source == target) {
                copyOptions.progressListener?.invoke(sourceStat.size)
                return
            }
            if (!copyOptions.replaceExisting) {
                throw FileAlreadyExistsException(source.toString(), target.toString(), null)
            }
            // Removing first keeps NFS RENAME from replacing a non-empty directory differently
            // from other providers, and makes the replacement explicit.
            try {
                Client.remove(target)
            } catch (e: ClientException) {
                throw e.toFileSystemException(target.toString())
            }
        }
        var renameSuccessful = false
        if (source.authority == target.authority) {
            try {
                Client.rename(source, target)
                renameSuccessful = true
            } catch (e: ClientException) {
                if (copyOptions.atomicMove) {
                    throw e.toFileSystemException(source.toString(), target.toString())
                }
                // Ignored.
            }
        } else if (copyOptions.atomicMove) {
            throw UnsupportedOperationException(StandardCopyOption.ATOMIC_MOVE.toString())
        }
        if (renameSuccessful) {
            copyOptions.progressListener?.invoke(sourceStat.size)
            return
        }
        var copyOptions = copyOptions
        if (!copyOptions.copyAttributes || !copyOptions.noFollowLinks) {
            copyOptions = CopyOptions(
                copyOptions.replaceExisting, true, false, true, copyOptions.progressIntervalMillis,
                copyOptions.progressListener
            )
        }
        copy(source, target, copyOptions)
        try {
            Client.remove(source)
        } catch (e: ClientException) {
            if (e.toFileSystemException(source.toString()) !is NoSuchFileException) {
                try {
                    Client.remove(target)
                } catch (e2: ClientException) {
                    e.addSuppressed(e2.toFileSystemException(target.toString()))
                }
            }
            throw e.toFileSystemException(source.toString())
        }
    }

    @Throws(IOException::class)
    private fun copyThroughClient(
        source: NfsPath,
        target: NfsPath,
        targetFlags: Int,
        sourceMode: Int,
        copyOptions: CopyOptions
    ) {
        val sourceInputStream = try {
            Client.openByteChannel(source, Nfs.O_RDONLY, 0, false)
        } catch (e: ClientException) {
            throw e.toFileSystemException(source.toString())
        }.newInputStream()
        try {
            val targetOutputStream = try {
                Client.openByteChannel(target, targetFlags, sourceMode, false)
            } catch (e: ClientException) {
                throw e.toFileSystemException(target.toString())
            }.newOutputStream()
            var successful = false
            try {
                sourceInputStream.copyTo(
                    targetOutputStream, copyOptions.progressIntervalMillis,
                    copyOptions.progressListener
                )
                successful = true
            } finally {
                try {
                    // Closing commits the written data to stable storage.
                    targetOutputStream.close()
                } catch (e: IOException) {
                    successful = false
                    throw FileSystemException(target.toString(), null, e.message)
                        .apply { initCause(e) }
                } finally {
                    if (!successful) {
                        try {
                            Client.unlink(target)
                        } catch (e: ClientException) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } finally {
            sourceInputStream.close()
        }
    }

    @Throws(IOException::class)
    private fun lstatOrNull(path: NfsPath): NfsStat? =
        try {
            Client.lstat(path)
        } catch (e: ClientException) {
            val exception = e.toFileSystemException(path.toString())
            if (exception !is NoSuchFileException) {
                throw exception
            }
            null
        }

    @Throws(IOException::class)
    private fun removeIfExists(path: NfsPath) {
        try {
            Client.remove(path)
        } catch (e: ClientException) {
            val exception = e.toFileSystemException(path.toString())
            if (exception !is NoSuchFileException) {
                throw exception
            }
        }
    }
}

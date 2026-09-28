package me.zhanghai.android.files.provider.nfs.client

import android.system.OsConstants
import io.github.libnfsandroid.NfsException
import java8.nio.file.AccessDeniedException
import java8.nio.file.DirectoryNotEmptyException
import java8.nio.file.FileAlreadyExistsException
import java8.nio.file.FileSystemException
import java8.nio.file.FileSystemLoopException
import java8.nio.file.NoSuchFileException
import java8.nio.file.NotDirectoryException
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.provider.common.ReadOnlyFileSystemException
import me.zhanghai.android.files.provider.common.InvalidFileNameException
import me.zhanghai.android.files.provider.common.IsDirectoryException

class ClientException : Exception {
    /** POSIX errno, or 0 when not an NFS error. */
    val errno: Int

    constructor(message: String?) : super(message) {
        errno = 0
    }

    constructor(cause: NfsException) : super(describe(cause), cause) {
        errno = cause.errno
    }

    constructor(errno: Int, message: String?) : super(message) {
        this.errno = errno
    }

    /** [cause] with more detail, such as why a TLS connection failed. */
    constructor(cause: NfsException, detail: String) : super("${describe(cause)} ($detail)", cause) {
        errno = cause.errno
    }

    /**
     * Whether the connection itself is in doubt (as opposed to the server answering with an
     * error). The context that produced it must not be reused.
     */
    val isTransportError: Boolean
        get() = isTransportErrno(errno) && !isServerBusy

    /**
     * The server answered "not now" (NFS4ERR_DELAY, or NFS4ERR_GRACE while it recovers after a
     * restart). libnfs reports these as EIO, like a broken connection, but the connection works:
     * asking again later is right, and dropping the connection (then opening new ones, which get
     * the same answer) only makes things worse.
     */
    val isServerBusy: Boolean
        get() = isServerBusyMessage(message)

    /**
     * The server could not answer a replayed call from its reply cache after a reconnect. Only
     * returned for calls that are not cached, which are the idempotent ones: asking again is safe.
     */
    val isRetryableReplay: Boolean
        get() = errno == OsConstants.EALREADY

    /** The server cannot copy or clone these files itself; copying through the client works. */
    val isUnsupportedCopy: Boolean
        get() = when (errno) {
            OsConstants.ENOTSUP, OsConstants.EOPNOTSUPP, OsConstants.EXDEV, OsConstants.EINVAL,
            OsConstants.ENOSYS -> true
            else -> false
        }

    fun toFileSystemException(file: String?, other: String? = null): FileSystemException =
        when (errno) {
            OsConstants.EACCES, OsConstants.EPERM -> AccessDeniedException(file, other, message)
            OsConstants.EEXIST -> FileAlreadyExistsException(file, other, message)
            OsConstants.EISDIR -> IsDirectoryException(file, other, message)
            OsConstants.ELOOP -> FileSystemLoopException(file)
            OsConstants.ENOTDIR -> NotDirectoryException(file)
            OsConstants.ENOTEMPTY -> DirectoryNotEmptyException(file)
            OsConstants.ENOENT -> NoSuchFileException(file, other, message)
            OsConstants.EROFS -> ReadOnlyFileSystemException(file, other, message)
            OsConstants.ENAMETOOLONG -> InvalidFileNameException(file, other, message)
            else -> FileSystemException(file, other, message)
        }.apply { initCause(this@ClientException) }

    companion object {
        /**
         * The server's error, prefixed with what it means for the two a user can act on: the
         * server is full, or this user's quota is used up (libnfs reports NFS4ERR_DQUOT as ERANGE,
         * so it is told by its name).
         */
        private fun describe(cause: NfsException): String? {
            val message = cause.message
            val meaning = when {
                message?.contains("NFS4ERR_DQUOT") == true -> R.string.nfs_error_quota
                cause.errno == OsConstants.ENOSPC || message?.contains("NFS4ERR_NOSPC") == true ->
                    R.string.nfs_error_no_space
                else -> return message
            }
            return try {
                "${application.getString(meaning)} ($message)"
            } catch (e: RuntimeException) {
                // No application (a test outside Android).
                message
            }
        }

        fun isServerBusyMessage(message: String?): Boolean =
            message != null && (message.contains("NFS4ERR_DELAY") ||
                message.contains("NFS4ERR_GRACE"))

        fun isTransportErrno(errno: Int): Boolean =
            when (errno) {
                OsConstants.EIO, OsConstants.ETIMEDOUT, OsConstants.ECONNRESET,
                OsConstants.ECONNREFUSED, OsConstants.ECONNABORTED, OsConstants.EPIPE,
                OsConstants.ENOTCONN, OsConstants.ENETUNREACH, OsConstants.EHOSTUNREACH,
                OsConstants.EINTR, OsConstants.ENOTCONN -> true
                else -> false
            }
    }
}

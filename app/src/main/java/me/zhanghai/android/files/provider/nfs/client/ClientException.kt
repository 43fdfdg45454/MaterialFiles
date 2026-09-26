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
import me.zhanghai.android.files.provider.common.ReadOnlyFileSystemException
import me.zhanghai.android.files.provider.common.InvalidFileNameException
import me.zhanghai.android.files.provider.common.IsDirectoryException

class ClientException : Exception {
    /** POSIX errno, or 0 when not an NFS error. */
    val errno: Int

    constructor(message: String?) : super(message) {
        errno = 0
    }

    constructor(cause: NfsException) : super(cause.message, cause) {
        errno = cause.errno
    }

    constructor(errno: Int, message: String?) : super(message) {
        this.errno = errno
    }

    /** [cause] with more detail, such as why a TLS connection failed. */
    constructor(cause: NfsException, detail: String) : super("${cause.message} ($detail)", cause) {
        errno = cause.errno
    }

    /**
     * Whether the connection itself is in doubt (as opposed to the server answering with an
     * error). The context that produced it must not be reused.
     */
    val isTransportError: Boolean
        get() = isTransportErrno(errno)

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

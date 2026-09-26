package me.zhanghai.android.files.provider.nfs

import io.github.libnfsandroid.Nfs
import java8.nio.file.StandardOpenOption
import me.zhanghai.android.files.provider.common.OpenOptions

/** Appending is done by the channel, so O_APPEND is never passed to the server. */
internal fun OpenOptions.toNfsFlags(): Int {
    var flags = if (read && write) {
        Nfs.O_RDWR
    } else if (write || append) {
        Nfs.O_WRONLY
    } else {
        Nfs.O_RDONLY
    }
    if (truncateExisting) {
        flags = flags or Nfs.O_TRUNC
    }
    if (createNew) {
        flags = flags or Nfs.O_CREAT or Nfs.O_EXCL
    } else if (create) {
        flags = flags or Nfs.O_CREAT
    }
    if (noFollowLinks) {
        flags = flags or Nfs.O_NOFOLLOW
    }
    if (sync || dsync) {
        flags = flags or Nfs.O_SYNC
    }
    if (deleteOnClose) {
        throw UnsupportedOperationException(StandardOpenOption.DELETE_ON_CLOSE.toString())
    }
    return flags
}

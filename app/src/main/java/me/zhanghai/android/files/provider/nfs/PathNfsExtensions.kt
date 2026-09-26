package me.zhanghai.android.files.provider.nfs

import java8.nio.file.Path
import me.zhanghai.android.files.provider.nfs.client.Authority

fun Authority.createNfsRootPath(): Path =
    NfsFileSystemProvider.getOrNewFileSystem(this).rootDirectory

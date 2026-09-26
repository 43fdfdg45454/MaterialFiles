package me.zhanghai.android.files.provider.nfs.client

interface Authenticator {
    fun getConnectionOptions(authority: Authority): ConnectionOptions?
}

package me.zhanghai.android.files.storage

import me.zhanghai.android.files.provider.nfs.client.Authenticator
import me.zhanghai.android.files.provider.nfs.client.Authority
import me.zhanghai.android.files.provider.nfs.client.ConnectionOptions
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

object NfsServerAuthenticator : Authenticator {
    private val transientServers = mutableSetOf<NfsServer>()

    override fun getConnectionOptions(authority: Authority): ConnectionOptions? {
        val server = synchronized(transientServers) {
            transientServers.find { it.authority == authority }
        } ?: Settings.STORAGES.valueCompat.find {
            it is NfsServer && it.authority == authority
        } as NfsServer?
        return server?.options
    }

    fun addTransientServer(server: NfsServer) {
        synchronized(transientServers) { transientServers += server }
    }

    fun removeTransientServer(server: NfsServer) {
        synchronized(transientServers) { transientServers -= server }
    }
}

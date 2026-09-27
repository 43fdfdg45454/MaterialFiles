/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.app

import android.os.AsyncTask
import android.os.Build
import android.webkit.WebView
import jcifs.context.SingletonContext
import me.zhanghai.android.files.BuildConfig
import me.zhanghai.android.files.coil.initializeCoil
import me.zhanghai.android.files.filejob.fileJobNotificationTemplate
import me.zhanghai.android.files.ftpserver.ftpServerServiceNotificationTemplate
import me.zhanghai.android.files.provider.nfs.client.nfsConnectionNotificationTemplate
import me.zhanghai.android.files.hiddenapi.HiddenApi
import me.zhanghai.android.files.provider.FileSystemProviders
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.storage.FtpServerAuthenticator
import me.zhanghai.android.files.storage.NfsServerAuthenticator
import me.zhanghai.android.files.storage.SftpServerAuthenticator
import me.zhanghai.android.files.storage.SmbServerAuthenticator
import me.zhanghai.android.files.storage.StorageVolumeListLiveData
import me.zhanghai.android.files.storage.WebDavServerAuthenticator
import me.zhanghai.android.files.theme.custom.CustomThemeHelper
import me.zhanghai.android.files.theme.night.NightModeHelper
import java.util.Properties
import me.zhanghai.android.files.provider.ftp.client.Client as FtpClient
import me.zhanghai.android.files.provider.nfs.client.Client as NfsClient
import me.zhanghai.android.files.provider.sftp.client.Client as SftpClient
import me.zhanghai.android.files.provider.smb.client.Client as SmbClient
import me.zhanghai.android.files.provider.webdav.client.Client as WebDavClient

val appInitializers = listOf(
    ::initializeCrashLog,
    ::initializeFirebase,
    ::disableHiddenApiChecks,
    ::initializeWebViewDebugging,
    ::initializeCoil,
    ::initializeFileSystemProviders,
    ::upgradeApp,
    ::initializeLiveDataObjects,
    ::initializeCustomTheme,
    ::initializeNightMode,
    ::createNotificationChannels
)

private fun initializeCrashLog() {
    CrashLog.install()
}

private fun initializeFirebase() {
//#ifdef NONFREE
    me.zhanghai.android.files.nonfree.FirebaseInitializer.initialize()
//#endif
}

private fun disableHiddenApiChecks() {
    HiddenApi.disableHiddenApiChecks()
}

private fun initializeWebViewDebugging() {
    if (BuildConfig.DEBUG) {
        WebView.setWebContentsDebuggingEnabled(true)
    }
}

private fun initializeFileSystemProviders() {
    FileSystemProviders.install()
    FileSystemProviders.overflowWatchEvents = true
    // SingletonContext.init() calls NameServiceClientImpl.initCache() which connects to network.
    AsyncTask.THREAD_POOL_EXECUTOR.execute {
        // init() copies the system properties without locking them: another thread setting one
        // at startup makes it throw (seen crashing the app). Nothing is initialized then; retry.
        for (attempt in 1..5) {
            try {
                SingletonContext.init(
                    Properties().apply {
                        setProperty("jcifs.netbios.cachePolicy", "0")
                        setProperty("jcifs.smb.client.maxVersion", "SMB1")
                    }
                )
                break
            } catch (e: ConcurrentModificationException) {
                Thread.sleep(50)
            }
        }
    }
    FtpClient.authenticator = FtpServerAuthenticator
    NfsClient.authenticator = NfsServerAuthenticator
    SftpClient.authenticator = SftpServerAuthenticator
    SmbClient.authenticator = SmbServerAuthenticator
    WebDavClient.authenticator = WebDavServerAuthenticator
}

private fun initializeLiveDataObjects() {
    // Force initialization of LiveData objects so that it won't happen on a background thread.
    StorageVolumeListLiveData.value
    Settings.FILE_LIST_DEFAULT_DIRECTORY.value
}

private fun initializeCustomTheme() {
    CustomThemeHelper.initialize(application)
}

private fun initializeNightMode() {
    NightModeHelper.initialize(application)
}

private fun createNotificationChannels() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        notificationManager.createNotificationChannels(
            listOf(
                backgroundActivityStartNotificationTemplate.channelTemplate,
                fileJobNotificationTemplate.channelTemplate,
                ftpServerServiceNotificationTemplate.channelTemplate,
                nfsConnectionNotificationTemplate.channelTemplate
            ).map { it.create(application) }
        )
    }
}

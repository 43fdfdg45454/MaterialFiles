package me.zhanghai.android.files.provider.nfs.client

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.NotificationIds
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.util.NotificationChannelTemplate
import me.zhanghai.android.files.util.NotificationTemplate

val nfsConnectionNotificationTemplate =
    NotificationTemplate(
        NotificationChannelTemplate(
            "nfs_connection",
            R.string.notification_channel_nfs_connection_name,
            NotificationManagerCompat.IMPORTANCE_LOW,
            descriptionRes = R.string.notification_channel_nfs_connection_description,
            showBadge = false
        ),
        colorRes = R.color.color_primary,
        smallIcon = R.drawable.notification_icon,
        contentTitleRes = R.string.nfs_connection_notification_title,
        ongoing = true,
        onlyAlertOnce = true,
        category = NotificationCompat.CATEGORY_SERVICE,
        priority = NotificationCompat.PRIORITY_LOW
    )

/**
 * Keeps Material Files in the foreground, from Android's point of view, while it has NFS
 * connections.
 *
 * Android (15 and later for every app, earlier ones with battery or data restrictions) blocks new
 * network requests of an app in the background: a name lookup fails at once (getaddrinfo
 * EAI_NODATA, "Can not resolv"), and so does every new connection and every reconnection. Material
 * Files is in the background exactly when it matters: while a player shows a video it serves.
 * Players that keep the file only as a descriptor do not keep Material Files' process important,
 * so a seek that needed new connections, or a reconnection after a drop, failed until Material
 * Files came back to the screen.
 *
 * A foreground service is Android's way to say that the app keeps doing something the user asked
 * for. It runs while any NFS connection is up (idle ones close after 5 minutes) and says which
 * servers it is connected to. It must be started while Material Files is on screen or has just
 * left it (Android does not allow it later), which is when the first connection is made.
 */
class NfsConnectionService : Service() {
    override fun onCreate() {
        super.onCreate()
        try {
            ServiceCompat.startForeground(
                this, NotificationIds.NFS_CONNECTION, NfsForeground.buildNotification(this),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            )
        } catch (e: RuntimeException) {
            // Not allowed any more (Material Files went to the background meanwhile).
            NfsForeground.onStartFailed(e)
            stopSelf()
            return
        }
        NfsForeground.onStarted(this)
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        super.onDestroy()
        NfsForeground.onStopped(this)
    }
}

/**
 * Starts [NfsConnectionService] when NFS connections exist and stops it once none is left
 * (see [update], called by [Client]'s pump).
 */
internal object NfsForeground {
    /** For tests on the host, which have no services. */
    @Volatile
    var isEnabled = true

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    // Guarded by this.
    private var service: NfsConnectionService? = null
    private var isStarting = false
    private var hasLoggedFailure = false
    private var lastAttemptMillis = 0L
    private var connections = 0
    private var servers = emptyList<String>()
    private var shownText: String? = null
    private var shownMillis = 0L

    /** Whether the service is running (for logs). */
    val isRunning: Boolean
        @Synchronized get() = service != null

    /** [connections] across [servers] (host names); 0 stops the service. */
    fun update(connections: Int, servers: List<String>) {
        if (!isEnabled) {
            return
        }
        val now = NfsClock.elapsedRealtime()
        var start = false
        var stop: NfsConnectionService? = null
        var refresh: NfsConnectionService? = null
        synchronized(this) {
            this.connections = connections
            this.servers = servers
            val service = service
            if (connections > 0) {
                if (service == null) {
                    if (!isStarting && now - lastAttemptMillis >= RETRY_MILLIS) {
                        isStarting = true
                        lastAttemptMillis = now
                        start = true
                    }
                } else if (text() != shownText && now - shownMillis >= REFRESH_MILLIS) {
                    refresh = service
                }
            } else if (service != null) {
                stop = service
            }
        }
        if (start) {
            start()
        }
        stop?.let { mainHandler.post { it.stopSelf() } }
        refresh?.let { service ->
            mainHandler.post {
                try {
                    NotificationManagerCompat.from(service)
                        .notify(NotificationIds.NFS_CONNECTION, buildNotification(service))
                } catch (e: SecurityException) {
                    // Notifications not allowed: the service runs anyway.
                }
            }
        }
    }

    private fun start() {
        try {
            ContextCompat.startForegroundService(
                application, Intent(application, NfsConnectionService::class.java)
            )
        } catch (e: RuntimeException) {
            // ForegroundServiceStartNotAllowedException: Material Files is in the background.
            onStartFailed(e)
        }
    }

    fun onStartFailed(e: Exception) {
        val log = synchronized(this) {
            isStarting = false
            !hasLoggedFailure.also { hasLoggedFailure = true }
        }
        if (!log) {
            return
        }
        NfsLog.log("could not keep network access in the background (foreground service not " +
            "allowed now: $e); new connections may fail until Material Files is opened")
    }

    fun onStarted(service: NfsConnectionService) {
        val stop = synchronized(this) {
            isStarting = false
            hasLoggedFailure = false
            this.service = service
            connections == 0
        }
        NfsLog.log("foreground service started: network access kept in the background")
        if (stop) {
            service.stopSelf()
        }
    }

    fun onStopped(service: NfsConnectionService) {
        synchronized(this) {
            if (this.service === service) {
                this.service = null
                shownText = null
            }
        }
    }

    @Synchronized
    private fun text(): String =
        application.resources.getQuantityString(
            R.plurals.nfs_connection_notification_text_format, connections,
            servers.joinToString(", "), connections
        )

    fun buildNotification(service: Service) =
        synchronized(this) {
            val text = text()
            shownText = text
            shownMillis = NfsClock.elapsedRealtime()
            nfsConnectionNotificationTemplate.createBuilder(service)
                .setContentText(text)
                .build()
        }

    /** After Android refused to start the service, it is tried again this much later. */
    private const val RETRY_MILLIS = 10_000L
    private const val REFRESH_MILLIS = 2_000L
}

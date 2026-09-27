package me.zhanghai.android.files.provider.nfs.client

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import me.zhanghai.android.files.app.application

/**
 * What NFS file transfers did, in `Android/data/<package>/files/nfs-log.txt`: when each file
 * opened and got its first bytes, reads that waited long, errors returned to the app reading, and
 * a summary at close. Users send it for problems seen in other apps (a player stalling or failing),
 * which report nothing useful themselves. Only the most recent entries are kept.
 */
internal object NfsLog {
    private const val FILE_NAME = "nfs-log.txt"
    private const val MAX_SIZE = 256 * 1024L
    private const val KEPT_SIZE = 128 * 1024

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NfsLog").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private val file: File?
        get() = application.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

    fun log(message: String) {
        val line = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date()) + " " +
            message + "\n"
        writer.execute {
            try {
                val file = file ?: return@execute
                file.appendText(line)
                if (file.length() > MAX_SIZE) {
                    file.writeText(file.readText().takeLast(KEPT_SIZE))
                }
            } catch (e: Exception) {
                // Diagnostics only.
            }
        }
    }
}

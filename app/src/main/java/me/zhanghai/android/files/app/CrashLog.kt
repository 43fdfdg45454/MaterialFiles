package me.zhanghai.android.files.app

import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.zhanghai.android.files.BuildConfig

/**
 * Keeps the stack traces of crashes in `Android/data/<package>/files/crash-log.txt`, readable from
 * Material Files itself, since the NFS build has no crash reporting and users cannot run adb.
 * Only the most recent crashes are kept.
 */
object CrashLog {
    private const val FILE_NAME = "crash-log.txt"
    private const val MAX_SIZE = 64 * 1024

    val file: File?
        get() = application.getExternalFilesDir(null)?.let { File(it, FILE_NAME) }

    fun install() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                write(thread, throwable)
            } catch (t: Throwable) {
                // Never get in the way of the crash itself.
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun write(thread: Thread, throwable: Throwable) {
        val file = file ?: return
        val trace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
        val entry = buildString {
            append("==== ")
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date()))
            append(" | ").append(BuildConfig.APPLICATION_ID).append(' ')
            append(BuildConfig.VERSION_NAME).append(" | Android ").append(Build.VERSION.RELEASE)
            append(" | thread ").append(thread.name).append('\n')
            append(trace).append('\n')
        }
        val previous = if (file.exists()) file.readText() else ""
        file.writeText((previous + entry).takeLast(MAX_SIZE))
    }
}

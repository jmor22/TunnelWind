package com.tunnelwind.app

import android.app.Application
import android.content.Context

/**
 * earliest-safe global context + crash persistence.
 * A global UncaughtExceptionHandler writes every crash (full stack) to
 * filesDir/crash-log.txt so the NEXT app launch surfaces it in the UI log card.
 */
class TunnelWindApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.init(applicationContext)

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                val text = buildString {
                    append("[").append(stamp).append("] crash in thread '")
                        .append(thread.name).append("'\n")
                    append(throwable.toString()).append('\n')
                    append(throwable.stackTraceToString())
                    append("\n\n")
                }
                openFileOutput("crash-log.txt", Context.MODE_APPEND).use {
                    it.write(text.toByteArray())
                }
            } catch (_: Throwable) { }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Called by MainActivity at startup: surface persisted crashes into the UI log. */
    fun drainCrashLog(): List<String> {
        return try {
            val file = java.io.File(filesDir, "crash-log.txt")
            if (!file.exists()) emptyList()
            else {
                val lines = file.readLines().filter { it.isNotBlank() }
                file.delete()
                lines
            }
        } catch (_: Throwable) { emptyList() }
    }
}

/** earliest-safe global context for non-compose code paths (VPN chips etc.) */
object AppContextHolder {
    @Volatile var context: Context? = null
        private set

    fun init(c: Context) { if (context == null) context = c }

    /** used by TunnelService only (same process) */
    fun forceInit(c: Context) { context = c }
}

package com.tunnelwind.app

import android.os.Handler
import android.os.Looper

/**
 * Shared, observable tunnel state (read by Compose screens, written by TunnelService).
 */
object TunnelState {
    enum class Phase { Stopped, Starting, Connected, Stopping }

    // observable values (Compose-safe since we're on the main thread via handler)
    @Volatile var phase: Phase = Phase.Stopped
    @Volatile var connectedRegion: String? = null
    @Volatile var activeProtocol: String? = null
    @Volatile var bytesUp: Long = 0
    @Volatile var bytesDown: Long = 0
    @Volatile var httpPort: Int = 0
    @Volatile var socksPort: Int = 0
    @Volatile var connecting: Boolean = false

    @Volatile var availableRegions: List<String> = emptyList()
    @Volatile var egressRegion: String = ""            // user selected; empty = auto
    @Volatile var bypassOwnRegion: Boolean = false
    @Volatile var vpnPrepared: Boolean = false
    @Volatile var lastError: String? = null

    @Volatile var logs: List<String> = emptyList()

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    /** thread-safe mutation hook: runs everything on the main thread and notifies UI */
    fun update(transform: TunnelState.() -> Unit) {
        main.post {
            synchronized(TunnelState) { this@TunnelState.transform() }
            listeners.forEach { it() }
        }
    }

    fun log(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        update {
            logs = (logs + "[$stamp] ${line.take(400)}").takeLast(300)
        }
    }
}

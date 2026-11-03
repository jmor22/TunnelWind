package com.tunnelwind.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Shared observable tunnel state (read by Compose screens, written by TunnelService).
 *
 * Backed by Compose snapshot state, so any composable that reads a field is
 * recomposed automatically when it changes. (The previous hand-rolled
 * listener + "tick" counter approach left stale cards on screen: the status
 * panel kept showing "Disconnected" after the tunnel was already up.)
 */
object TunnelState {
    enum class Phase { Stopped, Starting, Connected, Stopping }

    var phase by mutableStateOf(Phase.Stopped)
    var connectedRegion by mutableStateOf<String?>(null)
    var activeProtocol by mutableStateOf<String?>(null)
    var bytesUp by mutableStateOf(0L)
    var bytesDown by mutableStateOf(0L)
    var httpPort by mutableStateOf(0)
    var socksPort by mutableStateOf(0)
    var connecting by mutableStateOf(false)

    var availableRegions by mutableStateOf<List<String>>(emptyList())
    var egressRegion by mutableStateOf("")            // user selected; empty = auto
    var bypassOwnRegion by mutableStateOf(false)
    var vpnPrepared by mutableStateOf(false)
    var lastError by mutableStateOf<String?>(null)

    var logs by mutableStateOf<List<String>>(emptyList())

    /** thread-safe mutation hook; fields are snapshot state, recomposition follows */
    fun update(transform: TunnelState.() -> Unit) {
        synchronized(TunnelState) { this@TunnelState.transform() }
    }

    // ---- log batching: the engine can emit hundreds of notices per second
    // (webRTC/ICE diagnostics); appending one list entry per notice would make
    // every recomposition walk a huge list. We flush at most every 250 ms.
    private val main = Handler(Looper.getMainLooper())
    private val pendingLogs = mutableListOf<String>()
    private var flushScheduled = false

    fun log(line: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        // mirror to logcat for on-device diagnostics (adb logcat -s TunnelWind)
        try { android.util.Log.i("TunnelWind", line.take(500)) } catch (_: Throwable) { }
        synchronized(pendingLogs) { pendingLogs.add("[$stamp] ${line.take(400)}") }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        synchronized(this) {
            if (flushScheduled) return
            flushScheduled = true
            main.postDelayed({
                synchronized(TunnelState) { flushScheduled = false }
                val batch: List<String>
                synchronized(pendingLogs) {
                    if (pendingLogs.isEmpty()) return@postDelayed
                    batch = pendingLogs.toList()
                    pendingLogs.clear()
                }
                synchronized(TunnelState) {
                    logs = (logs + batch).takeLast(300)
                }
            }, 250)
        }
    }
}

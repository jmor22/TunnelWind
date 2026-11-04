package com.tunnelwind.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Auto-connect after a reboot (only when the user enabled it in Settings).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        Log.i("TunnelWind", "[boot] received " + (intent?.action ?: "null"))
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        // Some builds replay BOOT_COMPLETED to freshly updated packages; only act
        // when the device really did just boot.
        val uptimeMs = android.os.SystemClock.elapsedRealtime()
        if (uptimeMs > 10 * 60_000L) {
            Log.i("TunnelWind", "[boot] ignoring stale BOOT_COMPLETED (uptime ${uptimeMs / 1000}s)")
            return
        }
        try {
            Prefs.init(ctx)
            if (!Prefs.autoConnect) return
            ctx.startForegroundService(
                Intent(ctx, TunnelService::class.java).setAction(TunnelService.ACTION_START)
            )
        } catch (_: Throwable) {
            // Android 12+ may refuse a background FGS start; the user can open the app
        }
    }
}

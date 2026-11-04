package com.tunnelwind.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: connect / disconnect from the notification shade.
 */
class TunnelTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        when (TunnelState.phase) {
            TunnelState.Phase.Connected,
            TunnelState.Phase.Starting,
            TunnelState.Phase.Stopping ->
                startService(Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_STOP))

            else -> {
                // A tile cannot show the system VPN consent dialog, so if consent
                // is still missing open the app and let the user tap CONNECT there.
                if (VpnService.prepare(this) != null) {
                    openApp()
                } else {
                    startForegroundService(
                        Intent(this, TunnelService::class.java).setAction(TunnelService.ACTION_START)
                    )
                }
            }
        }
        updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        tile.state = when (TunnelState.phase) {
            TunnelState.Phase.Connected -> Tile.STATE_ACTIVE
            TunnelState.Phase.Starting, TunnelState.Phase.Stopping -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "Tunnel Wind"
        tile.updateTile()
    }

    companion object {
        fun requestUpdate(ctx: Context) {
            try {
                TileService.requestListeningState(
                    ctx, ComponentName(ctx, TunnelTileService::class.java)
                )
            } catch (_: Throwable) { }
        }
    }
}

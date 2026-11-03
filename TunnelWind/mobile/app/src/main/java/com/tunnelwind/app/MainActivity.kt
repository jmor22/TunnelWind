package com.tunnelwind.app

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunnelwind.app.ui.CountryName

private val Bg        = Color(0xFF101018)
private val Panel     = Color(0xFF1B1B2B)
private val PanelHi   = Color(0xFF232338)
private val Accent    = Color(0xFF6C5CE7)
private val AccentHi  = Color(0xFF8878F0)
private val Field     = Color(0xFF26263A)
private val TextCol   = Color(0xFFEAEAF2)
private val Dim       = Color(0xFF9A9AAC)
private val Ok        = Color(0xFF3FD48E)
private val Warn      = Color(0xFFE4527A)

class MainActivity : ComponentActivity() {

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) startServiceTunnel()
            else TunnelState.log("[ui] VPN permission denied")
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            TunnelState.log("[ui] notification permission: " + granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppContextHolder.init(applicationContext)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // surface persisted crashes from a previous run into the log card
        val app = application as? TunnelWindApp
        val crashes = app?.drainCrashLog() ?: emptyList()
        if (crashes.isNotEmpty()) {
            TunnelState.log("[prev-crash] " + crashes.take(12).joinToString(" | ").take(900))
        }
        // Samsung/Android aggressively freeze background apps; without this the
        // system can kill the VPN process while browsing in another app.
        requestBatteryExemptionOnce()
        setContent { TunnelWindUi(::connectPressed, ::stopPressed) }
    }

    private fun requestBatteryExemptionOnce() {
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            val prefs = getSharedPreferences("tw", MODE_PRIVATE)
            if (prefs.getBoolean("asked_battery", false)) return
            prefs.edit().putBoolean("asked_battery", true).apply()
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:" + packageName))
            )
        } catch (_: Throwable) { }
    }

    private fun connectPressed() {
        // VpnService.prepare returns null when permission is ALREADY granted -
        // launching a null intent would crash (the v1.2.1 bug). Null means go.
        val consentIntent = VpnService.prepare(this)
        if (consentIntent == null) startServiceTunnel()
        else vpnPermission.launch(consentIntent)
    }

    private fun startServiceTunnel() {
        val intent = Intent(this, TunnelService::class.java).apply { action = TunnelService.ACTION_START }
        startForegroundService(intent)
    }

    private fun stopPressed() {
        startForegroundService(Intent(this, TunnelService::class.java).apply { action = TunnelService.ACTION_STOP })
    }
}

@Composable
fun TunnelWindUi(onConnect: () -> Unit, onStop: () -> Unit) {

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Header()
            ConnectCard(onConnect, onStop)
            StatusCard()
            RegionCard()
            SplitTunnelCard()
            // pass logs as a parameter: a parameterless composable is skipped by
            // Compose on recomposition, so the log card would only refresh after
            // a full activity recreation (the "log empty until relaunch" bug)
            LogCard(TunnelState.logs)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Tunnel Wind", color = TextCol, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Surface(color = Field, shape = RoundedCornerShape(6.dp)) {
            Text("v1.8.1", color = Dim, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun ConnectCard(onConnect: () -> Unit, onStop: () -> Unit) {
    val phase = TunnelState.phase
    val connected = phase == TunnelState.Phase.Connected
    val starting  = phase == TunnelState.Phase.Starting

    val inf = rememberInfiniteTransition(label = "pulse")
    val pulse by inf.animateFloat(
        initialValue = 0.85f, targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(950, easing = LinearEasing)), label = "pulseV")

    val label = when {
        connected -> "DISCONNECT"
        starting  -> "CONNECTING..."
        else      -> "CONNECT"
    }
    val container = when {
        connected -> PanelHi
        starting  -> Field
        else      -> Accent
    }

    Button(
        onClick = { when {
            connected -> onStop()
            starting  -> { }
            else      -> onConnect()
        } },
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .then(if (starting) Modifier.alpha(0.92f) else Modifier),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = TextCol),
        shape = RoundedCornerShape(18.dp),
        border = if (starting) BorderStroke(2.dp, AccentHi.copy(alpha = pulse)) else null,
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
    }
}

@Composable
private fun StatusCard() {
    val s = TunnelState
    Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("\u25CF", color = statusDotColor(s), fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text(statusLabel(s), color = TextCol, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                if (s.phase == TunnelState.Phase.Connected)
                    "up " + fmt(s.bytesUp) + "   down " + fmt(s.bytesDown)
                else "- -",
                color = Dim, fontSize = 12.sp
            )
            if (s.httpPort > 0 || s.socksPort > 0) {
                Text("HTTP :" + s.httpPort + "   SOCKS :" + s.socksPort, color = Dim, fontSize = 12.sp)
            }
            if (s.lastError != null) {
                Text("last error: " + s.lastError, color = Warn, fontSize = 11.sp, maxLines = 3)
            }
        }
    }
}

private fun statusDotColor(s: TunnelState): Color = when (s.phase) {
    TunnelState.Phase.Connected -> Ok
    TunnelState.Phase.Starting  -> AccentHi
    TunnelState.Phase.Stopping  -> Dim
    TunnelState.Phase.Stopped   -> Warn
}

private fun statusLabel(s: TunnelState): String = when (s.phase) {
    TunnelState.Phase.Connected -> "Connected" +
            (s.activeProtocol?.let { " - " + it } ?: "") +
            (s.connectedRegion?.let { " - " + it } ?: "")
    TunnelState.Phase.Starting  -> if (s.vpnPrepared) "Starting..." else "Waiting for VPN permission..."
    TunnelState.Phase.Stopping  -> "Disconnecting..."
    TunnelState.Phase.Stopped   -> "Disconnected"
}

@Composable
private fun RegionCard() {
    Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SERVER LOCATION", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (TunnelState.egressRegion.isEmpty()) "Auto (best performance)"
                    else "Exit: " + TunnelState.egressRegion + " - " + CountryName(TunnelState.egressRegion),
                    color = TextCol, fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            if (TunnelState.availableRegions.isEmpty()) {
                Text("chips populate after first connect", color = Dim, fontSize = 12.sp)
            } else {
                FlowChips()
            }
        }
    }
}

@Composable
private fun FlowChips() {
    val ctx = LocalContextCurrent()
    val sorted = listOf("") + TunnelState.availableRegions.sorted()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sorted.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { code ->
                    val selected = code.equals(TunnelState.egressRegion, ignoreCase = true)
                    Surface(
                        color = if (selected) Accent else Field,
                        contentColor = TextCol,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                TunnelState.update { egressRegion = code }
                                if (TunnelState.phase == TunnelState.Phase.Connected ||
                                    TunnelState.phase == TunnelState.Phase.Starting) {
                                    ctx.startForegroundService(
                                        Intent(ctx, TunnelService::class.java)
                                            .apply { action = TunnelService.ACTION_REGION })
                                } else {
                                    TunnelState.log("[ui] region set: " +
                                        (if (code.isEmpty()) "auto" else code) + " (will apply on connect)")
                                }
                            }
                    ) {
                        Text(
                            if (code.isEmpty()) "AUTO" else code,
                            fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalContextCurrent(): android.content.Context = androidx.compose.ui.platform.LocalContext.current

@Composable
private fun SplitTunnelCard() {
    Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SPLIT TUNNEL", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            TunnelState.update { bypassOwnRegion = !bypassOwnRegion }
                        }
                        .padding(4.dp)
                ) {
                    Checkbox(
                        checked = TunnelState.bypassOwnRegion,
                        onCheckedChange = { TunnelState.update { bypassOwnRegion = it } }
                    )
                    Text("bypass own country", color = Dim, fontSize = 12.sp)
                }
            }
            Text("applied on next (re)connect", color = Dim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun LogCard(logs: List<String>) {
    Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier
        .fillMaxWidth().height(240.dp)) {
        SelectionContainer {
            Column(Modifier.padding(10.dp)) {
                Text("Connection Log", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                // Only the newest lines: the card has a fixed height, so rendering
                // 60 lines just clipped them out of view (the "log looks empty" bug).
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    logs.takeLast(12).forEach { line ->
                        Text(line, color = Dim, fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}


private fun fmt(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble(); var u = 0
    while (v >= 1024.0 && u < units.size - 1) { v /= 1024.0; u++ }
    return String.format("%.1f %s", v, units[u])
}

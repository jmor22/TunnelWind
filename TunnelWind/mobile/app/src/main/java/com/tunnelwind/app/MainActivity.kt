package com.tunnelwind.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunnelwind.app.ui.CountryName
import com.tunnelwind.app.ui.flagEmoji
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// ---------------------------------------------------------------- design tokens
private val BgTop     = Color(0xFF0B1020)
private val BgBottom  = Color(0xFF0E1526)
private val Card      = Color(0xFF151B2E)
private val CardBorder= Color(0xFF22304D)
private val Track     = Color(0xFF2A3448)
private val Accent    = Color(0xFF2F80ED)
private val AccentHi  = Color(0xFF5AA0FF)
private val Ok        = Color(0xFF3FE0A0)
private val Warn      = Color(0xFFE4527A)
private val TextCol   = Color(0xFFF2F5FA)
private val Dim       = Color(0xFF8A94A6)

private val TwScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = BgTop,
    onBackground = TextCol,
    surface = Card,
    onSurface = TextCol,
    surfaceVariant = Track,
    onSurfaceVariant = Dim,
    outline = CardBorder,
    error = Warn
)

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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        AppContextHolder.init(applicationContext)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // surface persisted crashes from a previous run into the log
        val app = application as? TunnelWindApp
        val crashes = app?.drainCrashLog() ?: emptyList()
        if (crashes.isNotEmpty()) {
            TunnelState.log("[prev-crash] " + crashes.take(12).joinToString(" | ").take(900))
        }
        // Samsung/Android aggressively freeze background apps; without this the
        // system can kill the VPN process while browsing in another app.
        requestBatteryExemptionOnce()
        setContent {
            MaterialTheme(colorScheme = TwScheme) {
                TunnelWindUi(::connectPressed, ::stopPressed)
            }
        }
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
                    .setData(Uri.parse("package:" + packageName))
            )
        } catch (_: Throwable) { }
    }

    private fun connectPressed() {
        // VpnService.prepare returns null when permission is ALREADY granted -
        // launching a null intent would crash. Null means go.
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

// ---------------------------------------------------------------- screen

@Composable
fun TunnelWindUi(onConnect: () -> Unit, onStop: () -> Unit) {
    var showServers by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp)
        ) {
            TopBar(onDiagnostics = { showDiagnostics = true })
            Spacer(Modifier.weight(0.5f))
            PowerButton(onConnect = onConnect, onStop = onStop)
            Spacer(Modifier.height(22.dp))
            StatusBlock()
            Spacer(Modifier.height(26.dp))
            ServerPill(onClick = { showServers = true })
            Spacer(Modifier.height(12.dp))
            StatsRow()
            Spacer(Modifier.height(12.dp))
            SplitTunnelRow()
            Spacer(Modifier.weight(1f))
        }
    }

    if (showServers) {
        ServerSheet(onDismiss = { showServers = false })
    }
    if (showDiagnostics) {
        DiagnosticsSheet(onDismiss = { showDiagnostics = false })
    }
}

@Composable
private fun TopBar(onDiagnostics: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                "Tunnel Wind", color = TextCol, fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp
            )
            Text(
                "VPN BY PSIPHON", color = Dim, fontSize = 9.sp,
                letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onDiagnostics) {
            Icon(Icons.Rounded.Tune, contentDescription = "Diagnostics", tint = Dim)
        }
    }
}

@Composable
private fun PowerButton(onConnect: () -> Unit, onStop: () -> Unit) {
    val phase = TunnelState.phase
    val connected = phase == TunnelState.Phase.Connected
    val connecting = phase == TunnelState.Phase.Starting
    val stopping = phase == TunnelState.Phase.Stopping

    val tint = when {
        connected -> Ok
        connecting -> AccentHi
        stopping -> Dim
        else -> Track
    }
    val label = when {
        connected -> "DISCONNECT"
        connecting -> "CONNECTING"
        stopping -> "STOPPING"
        else -> "CONNECT"
    }

    val pulse = rememberInfiniteTransition(label = "power")
    val spin by pulse.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "spin"
    )
    val breathe by pulse.animateFloat(
        0.70f, 1f,
        infiniteRepeatable(tween(1700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow"
    )

    Box(
        Modifier.fillMaxWidth().height(252.dp),
        contentAlignment = Alignment.Center
    ) {
        // soft halo behind the shield
        Canvas(Modifier.size(240.dp)) {
            val r = size.minDimension / 2f
            val alpha = when {
                connected -> 0.22f * breathe
                connecting -> 0.15f * breathe
                else -> 0.04f
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tint.copy(alpha = alpha), Color.Transparent),
                    center = center,
                    radius = r
                ),
                radius = r
            )
        }

        // outer ring (+ progress arc while connecting)
        Canvas(Modifier.size(222.dp)) {
            val ring = 1.5.dp.toPx()
            drawCircle(
                color = if (connected || connecting) tint.copy(alpha = 0.35f) else CardBorder,
                radius = size.minDimension / 2f - ring,
                style = Stroke(ring)
            )
            if (connecting) {
                drawArc(
                    color = AccentHi,
                    startAngle = spin,
                    sweepAngle = 78f,
                    useCenter = false,
                    topLeft = Offset(ring, ring),
                    size = Size(size.width - ring * 2, size.height - ring * 2),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // inner disc = the actual button
        Column(
            modifier = Modifier
                .size(170.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        if (connected) listOf(Color(0xFF173A31), Color(0xFF0F2620))
                        else listOf(Color(0xFF1B2438), Color(0xFF121A29))
                    )
                )
                .border(
                    1.dp,
                    if (connected) Ok.copy(alpha = 0.55f) else CardBorder,
                    CircleShape
                )
                .clickable(enabled = !connecting && !stopping) {
                    if (connected) onStop() else onConnect()
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.VerifiedUser,
                contentDescription = null,
                tint = if (connected) Ok else if (connecting) AccentHi else Dim,
                modifier = Modifier.size(46.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                label, color = TextCol, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp
            )
        }
    }
}

@Composable
private fun StatusBlock() {
    val phase = TunnelState.phase
    val title = when (phase) {
        TunnelState.Phase.Connected -> "Protected"
        TunnelState.Phase.Starting -> "Connecting\u2026"
        TunnelState.Phase.Stopping -> "Disconnecting\u2026"
        else -> "Not protected"
    }
    val subtitle = when (phase) {
        TunnelState.Phase.Connected ->
            TunnelState.connectedRegion?.let { CountryName(it) + " \u00B7 Automatic" }
                ?: "Tunnel is active"
        TunnelState.Phase.Starting ->
            when {
                TunnelState.reconnectAttempt > 0 ->
                    "Reconnecting (attempt ${TunnelState.reconnectAttempt}/3)\u2026"
                TunnelState.vpnPrepared -> "Finding the fastest server\u2026"
                else -> "Waiting for VPN permission\u2026"
            }
        TunnelState.Phase.Stopping -> "Closing the tunnel\u2026"
        else -> "Tap the shield to connect"
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            title,
            color = if (phase == TunnelState.Phase.Connected) Ok else TextCol,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = Dim, fontSize = 13.sp)
    }
}

@Composable
private fun ServerPill(onClick: () -> Unit) {
    val code = TunnelState.egressRegion
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Card)
            .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (code.isEmpty()) "\uD83C\uDF10" else flagEmoji(code), fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (code.isEmpty()) "Fastest server" else CountryName(code),
                color = TextCol, fontSize = 15.sp, fontWeight = FontWeight.Medium
            )
            Text(
                if (code.isEmpty()) "Automatic selection" else "$code \u00B7 selected",
                color = Dim, fontSize = 11.sp
            )
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Dim)
    }
}

@Composable
private fun StatsRow() {
    val phase = TunnelState.phase
    var connectedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }

    LaunchedEffect(phase) {
        if (phase == TunnelState.Phase.Connected) {
            if (connectedAt == 0L) connectedAt = System.currentTimeMillis()
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        } else {
            connectedAt = 0L
            now = 0L
        }
    }

    val seconds = if (connectedAt == 0L || now == 0L) 0L
    else ((now - connectedAt) / 1000L).coerceAtLeast(0L)
    val session = if (seconds <= 0L) "--:--"
    else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatCell("SESSION", session, Modifier.weight(1f))
        StatCell("UPLOAD", fmt(TunnelState.bytesUp), Modifier.weight(1f))
        StatCell("DOWNLOAD", fmt(TunnelState.bytesDown), Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(vertical = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value, color = TextCol, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Text(
            label, color = Dim, fontSize = 9.sp,
            letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SplitTunnelRow() {
    val ctx = LocalContext.current
    val checked = TunnelState.bypassOwnRegion

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Card)
            .border(1.dp, CardBorder, RoundedCornerShape(18.dp))
            .clickable { toggleSplitTunnel(ctx) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Split tunneling", color = TextCol,
                fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
            Text(
                if (checked) "Home-country traffic bypasses the VPN"
                else "All traffic goes through the VPN",
                color = Dim, fontSize = 11.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = { toggleSplitTunnel(ctx) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
                uncheckedThumbColor = Dim,
                uncheckedTrackColor = Track,
                uncheckedBorderColor = CardBorder
            )
        )
    }
}

private fun toggleSplitTunnel(ctx: android.content.Context) {
    val newValue = !TunnelState.bypassOwnRegion
    TunnelState.update { bypassOwnRegion = newValue }
    TunnelState.log("[ui] split tunneling (own region) = $newValue")
    if (TunnelState.phase == TunnelState.Phase.Connected ||
        TunnelState.phase == TunnelState.Phase.Starting) {
        ctx.startForegroundService(
            Intent(ctx, TunnelService::class.java).apply { action = TunnelService.ACTION_REGION }
        )
        TunnelState.log("[ui] reconnecting to apply split tunneling")
    }
}

private fun pickRegion(ctx: android.content.Context, code: String) {
    TunnelState.update { egressRegion = code }
    TunnelState.log("[ui] server location = " + code.ifEmpty { "auto" })
    if (TunnelState.phase == TunnelState.Phase.Connected ||
        TunnelState.phase == TunnelState.Phase.Starting) {
        ctx.startForegroundService(
            Intent(ctx, TunnelService::class.java).apply { action = TunnelService.ACTION_REGION }
        )
    }
}

// ---------------------------------------------------------------- sheets

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val regions = TunnelState.availableRegions
    val selected = TunnelState.egressRegion
    var query by remember { mutableStateOf("") }

    val filtered = remember(regions, query) {
        val q = query.trim()
        if (q.isEmpty()) regions.sorted()
        else regions.filter {
            CountryName(it).contains(q, ignoreCase = true) || it.equals(q, ignoreCase = true)
        }.sorted()
    }

    fun close() {
        scope.launch {
            sheetState.hide()
        }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Card,
        contentColor = TextCol,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Track) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .imePadding()
        ) {
            Text("Server location", color = TextCol, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Choose where your traffic should exit.", color = Dim, fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search country", color = Dim) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Dim) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = CardBorder,
                    focusedContainerColor = BgTop,
                    unfocusedContainerColor = BgTop,
                    cursorColor = Accent,
                    focusedTextColor = TextCol,
                    unfocusedTextColor = TextCol
                )
            )

            Spacer(Modifier.height(10.dp))

            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                item {
                    ServerRow(
                        code = "",
                        title = "Fastest server",
                        subtitle = "Automatic selection",
                        selected = selected.isEmpty()
                    ) {
                        pickRegion(ctx, "")
                        close()
                    }
                }
                items(filtered, key = { it }) { code ->
                    ServerRow(
                        code = code,
                        title = CountryName(code),
                        subtitle = code,
                        selected = code == selected
                    ) {
                        pickRegion(ctx, code)
                        close()
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ServerRow(
    code: String,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (code.isEmpty()) "\uD83C\uDF10" else flagEmoji(code), fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, color = TextCol, fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(subtitle, color = Dim, fontSize = 11.sp)
        }
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val logs = TunnelState.logs.takeLast(200)
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.size - 1)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Card,
        contentColor = TextCol,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Track) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .imePadding()
        ) {
            Text("Diagnostics", color = TextCol, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoPill("HTTP", if (TunnelState.httpPort > 0) TunnelState.httpPort.toString() else "\u2014", Modifier.weight(1f))
                InfoPill("SOCKS", if (TunnelState.socksPort > 0) TunnelState.socksPort.toString() else "\u2014", Modifier.weight(1f))
            }

            if (TunnelState.lastError != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Last error: " + TunnelState.lastError,
                    color = Warn, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis
                )
            }

            // debug builds only: exercise the unexpected-exit auto-reconnect path
            val ctx = LocalContext.current
            val debuggable = remember {
                (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            }
            if (debuggable) {
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = {
                    ctx.startForegroundService(
                        Intent(ctx, TunnelService::class.java)
                            .setAction(TunnelService.ACTION_DEBUG_DROP)
                    )
                }) {
                    Text("Simulate engine crash (debug)", color = Warn, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "CONNECTION LOG", color = Dim, fontSize = 9.sp,
                letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BgTop)
                    .padding(10.dp)
            ) {
                items(logs) { line ->
                    Text(
                        line, color = Dim, fontSize = 9.5.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Tunnel Wind " + appVersion() + "  \u00B7  psiphon-tunnel-core",
                color = Dim, fontSize = 10.sp
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InfoPill(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(BgTop)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = TextCol, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Dim, fontSize = 9.sp, letterSpacing = 1.2.sp)
    }
}

// ---------------------------------------------------------------- helpers

@Composable
private fun appVersion(): String {
    val ctx = LocalContext.current
    return remember {
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        } catch (_: Throwable) { "" }
    }
}

private fun fmt(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var u = 0
    while (v >= 1024.0 && u < units.size - 1) { v /= 1024.0; u++ }
    return String.format(Locale.US, "%.1f %s", v, units[u])
}

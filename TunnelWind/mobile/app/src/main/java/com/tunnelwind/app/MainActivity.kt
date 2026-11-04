package com.tunnelwind.app

import android.app.Activity
import android.content.Context
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.tunnelwind.app.ui.CountryName
import com.tunnelwind.app.ui.flagEmoji
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        AppContextHolder.init(applicationContext)
        Prefs.init(applicationContext)
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
        maybeAutoConnectOnLaunch()
        setContent {
            MaterialTheme(colorScheme = TwScheme, typography = TwTypography) {
                if (!Prefs.onboarded) {
                    OnboardingScreen(onDone = { Prefs.setOnboarded() })
                } else {
                    TunnelWindUi(::connectPressed, ::stopPressed)
                }
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
        val consentIntent = VpnService.prepare(this)
        if (consentIntent == null) startServiceTunnel()
        else vpnPermission.launch(consentIntent)
    }

    /**
     * Auto-connect on app launch (and after a reboot via BootReceiver), honoring
     * the "Wi-Fi only" rule. A recent manual disconnect suppresses it for a while
     * so reopening the app does not immediately undo the user's choice.
     */
    private fun maybeAutoConnectOnLaunch() {
        try {
            TunnelState.log(
                "[auto] launch check: enabled=${Prefs.autoConnect} onboarded=${Prefs.onboarded} " +
                    "phase=${TunnelState.phase} sinceStop=${(System.currentTimeMillis() - Prefs.lastStopAt) / 1000}s"
            )
            if (!Prefs.autoConnect || !Prefs.onboarded) return
            if (TunnelState.phase != TunnelState.Phase.Stopped) return
            if (System.currentTimeMillis() - Prefs.lastStopAt < 10 * 60_000L) return
            if (Prefs.autoWifiOnly && !isOnWifi()) return
            if (VpnService.prepare(this) != null) return   // consent dialog pending
            TunnelState.log("[auto] connecting on launch")
            startServiceTunnel()
        } catch (_: Throwable) { }
    }

    private fun isOnWifi(): Boolean {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            cm.allNetworks.any { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@any false
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            }
        } catch (_: Throwable) { false }
    }

    private fun startServiceTunnel() {
        val intent = Intent(this, TunnelService::class.java).apply { action = TunnelService.ACTION_START }
        startForegroundService(intent)
    }

    private fun stopPressed() {
        startForegroundService(Intent(this, TunnelService::class.java).apply { action = TunnelService.ACTION_STOP })
    }
}

// ---------------------------------------------------------------- root

private data class OnboardPage(val emoji: String, val title: String, val body: String)

@Composable
private fun OnboardingScreen(onDone: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val pages = listOf(
        OnboardPage(
            "\uD83C\uDF10",
            "Welcome to Tunnel Wind",
            "A private, uncensored connection built on the Psiphon network."
        ),
        OnboardPage(
            "\u26A1",
            "One tap to protect",
            "The engine picks the fastest server automatically. Prefer a specific country? Pick one from Servers."
        ),
        OnboardPage(
            "\uD83D\uDD12",
            "Two permissions",
            "Android will ask for VPN access (required) and notifications (so you can see the tunnel state). Allow both when prompted."
        )
    )
    val page = pages[step]

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.6f))
            Text(page.emoji, fontSize = 64.sp)
            Spacer(Modifier.height(28.dp))
            Text(
                page.title, color = TextCol, fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.height(14.dp))
            Text(
                page.body, color = Dim, fontSize = 14.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 21.sp
            )
            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pages.indices.forEach { i ->
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (i == step) Accent else Track)
                    )
                }
            }
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    if (step < pages.lastIndex) step++
                    else { Prefs.setOnboarded(); onDone() }
                },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent, contentColor = Color.White
                )
            ) {
                Text(
                    if (step < pages.lastIndex) "Continue" else "Get started",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
fun TunnelWindUi(onConnect: () -> Unit, onStop: () -> Unit) {
    // plain remember: the app should always open on Home (saved state made it
    // restore e.g. the Servers tab after an update/onboarding)
    var tab by remember { mutableIntStateOf(0) }
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
        ) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> HomeScreen(
                        onConnect = onConnect,
                        onStop = onStop,
                        onOpenServers = { tab = 1 },
                        onDiagnostics = { showDiagnostics = true }
                    )
                    1 -> ServersScreen(onPicked = { tab = 0 })
                    else -> SettingsScreen(onDiagnostics = { showDiagnostics = true })
                }
            }
            BottomBar(tab) { tab = it }
        }
    }

    if (showDiagnostics) {
        DiagnosticsSheet(onDismiss = { showDiagnostics = false })
    }
}

@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val items = listOf(
        Triple("Home", Icons.Rounded.Home, 0),
        Triple("Servers", Icons.Rounded.Public, 1),
        Triple("Settings", Icons.Rounded.Settings, 2)
    )
    NavigationBar(
        containerColor = Card,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        items.forEach { (label, icon, index) ->
            NavigationBarItem(
                selected = selected == index,
                onClick = { onSelect(index) },
                icon = { Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp)) },
                label = { Text(label, fontSize = 10.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = TextCol,
                    indicatorColor = Accent.copy(alpha = 0.28f),
                    unselectedIconColor = Dim,
                    unselectedTextColor = Dim
                )
            )
        }
    }
}

// ---------------------------------------------------------------- home

@Composable
private fun HomeScreen(
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onOpenServers: () -> Unit,
    onDiagnostics: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        TopBar(onDiagnostics = onDiagnostics)
        Spacer(Modifier.weight(0.45f))
        PowerButton(onConnect = onConnect, onStop = onStop)
        Spacer(Modifier.height(20.dp))
        StatusBlock()
        Spacer(Modifier.height(24.dp))
        ServerPill(onClick = onOpenServers)
        Spacer(Modifier.height(12.dp))
        StatsRow()
        Spacer(Modifier.weight(1f))
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
            TunnelState.connectedRegion?.let { CountryName(it) } ?: "Tunnel is active"
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
    val code = Prefs.region
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

// ---------------------------------------------------------------- servers

@Composable
private fun ServersScreen(onPicked: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val regions = TunnelState.availableRegions.ifEmpty { Prefs.cachedRegions }
    val selected = Prefs.region
    val favorites = Prefs.favorites
    val recents = Prefs.recents

    LaunchedEffect(regions) {
        if (regions.isNotEmpty() && Latency.shouldRefresh()) {
            Latency.measure(ctx, regions)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("Servers", color = TextCol, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text("Choose where your traffic exits", color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchField(
                query = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = { scope.launch { Latency.measure(ctx, regions) } },
                enabled = !Latency.measuring
            ) {
                if (Latency.measuring) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Accent,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Measure latency", tint = Dim)
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        val q = query.trim()
        val filtered = if (q.isEmpty()) regions.sorted()
        else regions.filter {
            CountryName(it).contains(q, ignoreCase = true) || it.equals(q, ignoreCase = true)
        }.sorted()

        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "auto") {
                ServerRow("", "Fastest server", "Automatic selection", selected.isEmpty()) {
                    pickServer(ctx, "", onPicked)
                }
            }

            if (q.isEmpty() && favorites.isNotEmpty()) {
                item(key = "h-fav") { SectionHeader("FAVORITES") }
                items(favorites.sorted(), key = { "fav-$it" }) { code ->
                    ServerRowItem(code, selected, onPicked, ctx)
                }
            }

            if (q.isEmpty() && recents.isNotEmpty()) {
                val showRecents = recents.filter { regions.isEmpty() || it in regions }
                if (showRecents.isNotEmpty()) {
                    item(key = "h-rec") { SectionHeader("RECENT") }
                    items(showRecents, key = { "rec-$it" }) { code ->
                        ServerRowItem(code, selected, onPicked, ctx)
                    }
                }
            }

            item(key = "h-all") {
                SectionHeader(if (q.isEmpty()) "ALL LOCATIONS" else "RESULTS")
            }

            if (filtered.isEmpty()) {
                item(key = "empty") {
                    Text(
                        if (regions.isEmpty()) "Connect once so the engine can report the available regions."
                        else "No country matches \"$q\".",
                        color = Dim, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }

            items(filtered, key = { "all-$it" }) { code ->
                ServerRowItem(code, selected, onPicked, ctx)
            }

            item(key = "tail") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ServerRowItem(code: String, selected: String, onPicked: () -> Unit, ctx: Context) {
    val favorite = code in Prefs.favorites
    val latency = Latency.ms[code]
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (code == selected) Accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable { pickServer(ctx, code, onPicked) }
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(flagEmoji(code), fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                CountryName(code), color = TextCol, fontSize = 15.sp,
                fontWeight = if (code == selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(code, color = Dim, fontSize = 11.sp)
        }
        if (code == selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
        }
        if (latency != null) {
            Spacer(Modifier.width(8.dp))
            Text("$latency ms", color = latencyColor(latency), fontSize = 11.sp)
        } else if (Latency.measuring) {
            Spacer(Modifier.width(8.dp))
            Text("\u2026", color = Dim, fontSize = 11.sp)
        }
        IconButton(onClick = { Prefs.toggleFavorite(code) }) {
            Icon(
                imageVector = if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = if (favorite) "Remove favorite" else "Add favorite",
                tint = if (favorite) Accent else Dim,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun latencyColor(ms: Int): Color = when {
    ms < 120 -> Ok
    ms < 250 -> Color(0xFFF5C451)
    else -> Warn
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

private fun pickServer(ctx: Context, code: String, onPicked: () -> Unit) {
    Prefs.saveRegion(code)
    Prefs.addRecent(code)
    TunnelState.log("[ui] server location = " + code.ifEmpty { "auto" })
    restartIfRunning(ctx)
    onPicked()
}

// ---------------------------------------------------------------- settings

@Composable
private fun SettingsScreen(onDiagnostics: () -> Unit) {
    val ctx = LocalContext.current
    var showProtocol by remember { mutableStateOf(false) }
    var showSplitMode by remember { mutableStateOf(false) }
    var showCountries by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Settings", color = TextCol, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(18.dp))

        SectionHeader("CONNECTION")
        SettingsCard {
            SettingsRow(
                title = "Protocol",
                value = Prefs.protocolLabel(),
                subtitle = "Restrict the transports the engine may use",
                onClick = { showProtocol = true }
            )
            HorizontalDivider(color = CardBorder)
            SettingsRow(
                title = "Always-on VPN",
                value = "",
                subtitle = "Recommended: enable always-on + block connections without VPN",
                onClick = { openVpnSettings(ctx) }
            )
        }

        Spacer(Modifier.height(18.dp))
        SectionHeader("SPLIT TUNNELING")
        SettingsCard {
            SettingsRow(
                title = "Mode",
                value = when (Prefs.splitMode) {
                    "own" -> "Own country"
                    "list" -> "Selected"
                    else -> "Off"
                },
                subtitle = Prefs.splitModeLabel(),
                onClick = { showSplitMode = true }
            )
            if (Prefs.splitMode == "list") {
                HorizontalDivider(color = CardBorder)
                SettingsRow(
                    title = "Countries",
                    value = "${Prefs.splitRegions.size}",
                    subtitle = "Traffic to these countries stays outside the VPN",
                    onClick = { showCountries = true }
                )
            }
            HorizontalDivider(color = CardBorder)
            SettingsRow(
                title = "Excluded apps",
                value = "${Prefs.excludedApps.size}",
                subtitle = "Apps that bypass the VPN entirely",
                onClick = { showApps = true }
            )
        }

        Spacer(Modifier.height(18.dp))
        SectionHeader("AUTO-CONNECT")
        SettingsCard {
            SwitchRow(
                title = "Auto-connect",
                subtitle = "Connect when the app opens or after a reboot",
                checked = Prefs.autoConnect,
                onCheckedChange = { Prefs.saveAutoConnect(it) }
            )
            HorizontalDivider(color = CardBorder)
            SwitchRow(
                title = "Wi-Fi only",
                subtitle = "Never auto-connect on mobile data",
                checked = Prefs.autoWifiOnly,
                enabled = Prefs.autoConnect,
                onCheckedChange = { Prefs.saveAutoWifiOnly(it) }
            )
        }

        Spacer(Modifier.height(18.dp))
        SectionHeader("DIAGNOSTICS")
        SettingsCard {
            SettingsRow(
                title = "Connection log",
                value = "",
                subtitle = "Ports, engine notices, errors",
                onClick = onDiagnostics
            )
            HorizontalDivider(color = CardBorder)
            SettingsRow(
                title = "Share diagnostics",
                value = "",
                subtitle = "Export the current log as text",
                onClick = { shareLog(ctx) }
            )
        }

        Spacer(Modifier.height(26.dp))
        Text(
            "Tunnel Wind " + appVersion() + "  \u00B7  psiphon-tunnel-core",
            color = Dim, fontSize = 10.sp
        )
        Spacer(Modifier.height(24.dp))
    }

    if (showProtocol) ProtocolDialog(onDismiss = { showProtocol = false; restartIfRunning(ctx) })
    if (showSplitMode) SplitModeDialog(onDismiss = { showSplitMode = false; restartIfRunning(ctx) })
    if (showCountries) CountryPickerDialog(onDismiss = { showCountries = false; restartIfRunning(ctx) })
    if (showApps) AppPickerSheet(onDismiss = { showApps = false; restartIfRunning(ctx) })
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Card)
            .border(1.dp, CardBorder, RoundedCornerShape(18.dp)),
        content = content
    )
}

@Composable
private fun SettingsRow(
    title: String,
    value: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextCol, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, color = Dim, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (value.isNotEmpty()) {
            Text(value, color = Dim, fontSize = 12.sp, maxLines = 1)
            Spacer(Modifier.width(4.dp))
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Dim, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) TextCol else Dim,
                fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
            Text(subtitle, color = Dim, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
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

@Composable
private fun ProtocolDialog(onDismiss: () -> Unit) {
    val options = listOf(
        "auto" to "Automatic (all transports)",
        "ssh" to "SSH",
        "ossh" to "OSSH",
        "tls" to "TLS-OSSH",
        "quic" to "QUIC-OSSH",
        "meek" to "Meek (fronted)"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        titleContentColor = TextCol,
        textContentColor = Dim,
        title = { Text("Protocol", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                options.forEach { (key, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { Prefs.saveProtocol(key); onDismiss() }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = Prefs.protocol == key,
                            onClick = { Prefs.saveProtocol(key); onDismiss() },
                            colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = Dim)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, color = TextCol, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Locking a protocol can help on restrictive networks, but may reduce the number of servers you can reach.",
                    color = Dim, fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Accent) }
        }
    )
}

@Composable
private fun SplitModeDialog(onDismiss: () -> Unit) {
    val options = listOf(
        "off" to "Off - everything goes through the VPN",
        "own" to "Own country bypasses the VPN",
        "list" to "Selected countries bypass the VPN"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        titleContentColor = TextCol,
        textContentColor = Dim,
        title = { Text("Split tunneling", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                options.forEach { (key, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { Prefs.saveSplitMode(key); onDismiss() }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = Prefs.splitMode == key,
                            onClick = { Prefs.saveSplitMode(key); onDismiss() },
                            colors = RadioButtonDefaults.colors(selectedColor = Accent, unselectedColor = Dim)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, color = TextCol, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Bypassed traffic leaves from your real IP, unencrypted by the tunnel.",
                    color = Dim, fontSize = 11.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Accent) }
        }
    )
}

@Composable
private fun CountryPickerDialog(onDismiss: () -> Unit) {
    val regions = TunnelState.availableRegions.ifEmpty { Prefs.cachedRegions }.sorted()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        titleContentColor = TextCol,
        textContentColor = Dim,
        title = { Text("Bypass countries", fontSize = 20.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            if (regions.isEmpty()) {
                Text("Connect once so the engine can report the available regions.", color = Dim, fontSize = 12.sp)
            } else {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    regions.forEach { code ->
                        val checked = code in Prefs.splitRegions
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val s = Prefs.splitRegions.toMutableSet()
                                    if (checked) s.remove(code) else s.add(code)
                                    Prefs.saveSplitRegions(s)
                                }
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = Dim)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("${flagEmoji(code)}  ${CountryName(code)}", color = TextCol, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = Accent) }
        }
    )
}

// ---------------------------------------------------------------- per-app picker

private data class AppEntry(
    val pkg: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.ImageBitmap?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(ctx) }
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
            Text("Excluded apps", color = TextCol, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Traffic from these apps stays outside the VPN (they use your normal connection).",
                color = Dim, fontSize = 12.sp
            )
            Spacer(Modifier.height(12.dp))

            SearchField(query = query, onValueChange = { query = it }, placeholder = "Search apps")
            Spacer(Modifier.height(8.dp))

            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
                }
            } else {
                val filtered = if (query.isBlank()) list
                else list.filter { it.label.contains(query, ignoreCase = true) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                    items(filtered, key = { it.pkg }) { app ->
                        val checked = app.pkg in Prefs.excludedApps
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val s = Prefs.excludedApps.toMutableSet()
                                    if (checked) s.remove(app.pkg) else s.add(app.pkg)
                                    Prefs.saveExcludedApps(s)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (app.icon != null) {
                                Image(
                                    bitmap = app.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(Track))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.label, color = TextCol, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.pkg, color = Dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(checkedColor = Accent, uncheckedColor = Dim)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onDismiss) { Text("Done", color = Accent) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun loadLaunchableApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val infos = pm.queryIntentActivities(intent, 0)
        .mapNotNull { it.activityInfo?.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != ctx.packageName }
    return infos.mapNotNull { ai ->
        try {
            AppEntry(
                pkg = ai.packageName,
                label = pm.getApplicationLabel(ai).toString(),
                icon = ai.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
            )
        } catch (_: Throwable) { null }
    }.sortedBy { it.label.lowercase() }
}

// ---------------------------------------------------------------- diagnostics

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

// ---------------------------------------------------------------- shared bits

@Composable
private fun SearchField(
    query: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "Search country",
    modifier: Modifier = Modifier.fillMaxWidth()
) {
    OutlinedTextField(
        value = query,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text(placeholder, color = Dim) },
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
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, color = Dim, fontSize = 9.sp,
        letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 8.dp)
    )
}

private fun restartIfRunning(ctx: Context) {
    if (TunnelState.phase == TunnelState.Phase.Connected ||
        TunnelState.phase == TunnelState.Phase.Starting) {
        ctx.startForegroundService(
            Intent(ctx, TunnelService::class.java).apply { action = TunnelService.ACTION_REGION }
        )
        TunnelState.log("[ui] reconnecting to apply new settings")
    }
}

private fun openVpnSettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Throwable) {
        try {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) { }
    }
}

private fun shareLog(ctx: Context) {
    try {
        val file = java.io.File(ctx.cacheDir, "tunnelwind-diagnostics.txt")
        file.writeText(TunnelState.logs.joinToString("\n"))
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share diagnostics")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {
        TunnelState.log("[ui] could not share diagnostics")
    }
}

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

package com.tunnelwind.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import psi.Psi
import psi.PsiphonProvider

import TunnelWindBootstrap.remoteServerListUrls
import TunnelWindBootstrap.obfuscatedServerListRootUrls
import TunnelWindBootstrap.remoteServerListSignaturePublicKey
import TunnelWindBootstrap.serverEntrySignaturePublicKey
import TunnelWindBootstrap.sponsorId
import TunnelWindBootstrap.propagationChannelId

/**
 * Android VPN host - DIRECT Go-binding integration.
 *
 * Instead of the ca.psiphon.PsiphonTunnel Java wrapper (whose notice/config
 * post-processing throws org.json exceptions on unexpected shapes - the root
 * cause of the "Value X cannot be converted to JSONObject" crashes), we call
 * the gomobile binding [Psi.start] ourselves and implement [PsiphonProvider]:
 *   - config: we build the exact JSON the engine wants (same contract as our
 *     Windows client - verified byte-for-byte on the desktop engine), including
 *     the VPN-mode fd via "PacketTunnelTunFileDescriptor";
 *   - notices: we parse engine lines ourselves (tolerant: data may be object,
 *     string, or missing) and route them into [TunnelState].
 */
class TunnelService : VpnService(), PsiphonProvider {

    companion object {
        // tun interface params
        const val tunAddressV4 = "10.240.1.1"
        const val tunDnsV4     = "10.240.1.1"
        const val vpnMtu       = 1500L

        const val ACTION_START  = "com.tunnelwind.app.START"
        const val ACTION_STOP   = "com.tunnelwind.app.STOP"
        const val ACTION_REGION = "com.tunnelwind.app.REGION"

        const val clientVersion = "100"
        var tunFdFd: Int = -1

        /**
         * PROCESS-GLOBAL engine state: Psi.start/stop are process-global singletons
         * (controller != nil in Go), and they outlive TunnelService instances
         * (START_STICKY restarts, activity restarts). Without this, a fresh service
         * instance calls Psi.start again and gets "already started" while the old
         * controller is still running happily in the background.
         */
        @Volatile var engineStarted: Boolean = false
        private val engineLock = Any()

        fun markStarted() { synchronized(engineLock) { engineStarted = true } }
        fun markStopped() { synchronized(engineLock) { engineStarted = false } }
        fun isEngineStarted(): Boolean = synchronized(engineLock) { engineStarted }
    }

    private var running = false
    private var tunFd: ParcelFileDescriptor? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    // DNS from the real uplink, captured BEFORE the tun hides it (see readUpstreamDns).
    @Volatile private var upstreamDns: String = ""

    // tun subnet chosen at establish time (see selectTunNet)
    @Volatile private var tunNet = TunNet("10.0.0.1", "10.0.0.0", 8, "10.0.0.2")

    override fun onCreate() {
        super.onCreate()
        AppContextHolder.forceInit(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        return when (action) {
            ACTION_START  -> { startFrontend(); startTunnel(); START_STICKY }
            ACTION_STOP   -> { stopTunnelInternal()            ; START_NOT_STICKY }
            ACTION_REGION -> { restartWithNewRegion()           ; START_STICKY }
            else          -> START_NOT_STICKY
        }
    }

    private fun startFrontend() {
        val notif = TunnelNotifications.foreground(this)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(TunnelNotifications.ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(TunnelNotifications.ID, notif)
        }
    }

    private fun startTunnel() {
        synchronized(this) {
            if (running) return
            running = true
        }

        // The engine is a process-global singleton: if it's already running
        // (service restarted, activity recreated), DON'T call Psi.start again -
        // just re-attach our state to the live controller and let its notices
        // (still routed via the old provider instance into TunnelState) drive the UI.
        if (isEngineStarted()) {
            TunnelState.log("[state] engine already running - reattaching UI state")
            TunnelState.update { phase = TunnelState.Phase.Starting; vpnPrepared = true }
            return
        }

        TunnelState.update {
            if (phase != TunnelState.Phase.Stopped) return@update
            phase = TunnelState.Phase.Starting
        }
        TunnelState.log("starting VPN service (direct Go binding)...")

        scope.launch {
            try {
                // 1) Bootstrap FIRST, while app sockets are still direct internet:
                //    once the tun is established, all traffic (including our own
                //    sockets) is captured by the VPN and the fetch would hang.
                TunnelState.log("fetching server list (direct internet)...")
                val embeddedEntries = prefetchServerList()

                // 1b) Capture the REAL DNS servers now, while the uplink is still
                //     visible; afterwards the tun (10.240.1.1) becomes active.
                upstreamDns = readUpstreamDns()
                TunnelState.log("[dns] upstream = " + (upstreamDns.ifEmpty { "<none, will use 8.8.8.8,1.1.1.1>" }))

                // 2) Only now take over traffic.
                val fd = establishTunnelDevice()
                TunnelState.update { vpnPrepared = true }
                TunnelState.log("tun device ready, fd=$fd; starting engine...")
                markStarted()

                // NOTE: Psi.start() does NOT block - it sets up the controller in
                // background goroutines and returns. A clean return here means
                // STARTED, not stopped.
                // embeddedServerEntryList (p1) = newline-separated hex entries; the
                // engine imports these directly at startup (this is the Android
                // equivalent of the Windows launcher's --serverList).
                // useDeviceBinder = TRUE is mandatory in VpnService mode: the engine
                // calls bindToDevice(fd) on each of its sockets and we respond with
                // VpnService.protect(fd), keeping its dials OUTSIDE our tun. Without
                // it the engine deadlocks dialing through its own tunnel.
                Psi.start(
                    buildConfig(fd),
                    embeddedEntries ?: "",   // embeddedServerEntryList
                    "",                      // embeddedServerEntryListFilename
                    this@TunnelService,
                    true,                    // useDeviceBinder  <-- critical
                    false,                   // useIPv6Synthesizer
                    true                     // useHasIPv6Route
                )
                TunnelState.log("engine started (controller running in background)")
                startConnectWatchdog()
            } catch (t: Throwable) {
                val msg = t.message ?: t.toString()
                if (msg.contains("already started")) {
                    // another instance started the engine - treat as reattach
                    TunnelState.log("[state] engine already running - reattaching")
                    TunnelState.update { phase = TunnelState.Phase.Starting; vpnPrepared = true }
                    return@launch
                }
                TunnelState.log("[error] " + msg)
                TunnelState.log("[stack] " + t.stackTraceToString().take(1500))
                TunnelState.update { phase = TunnelState.Phase.Stopped; lastError = t.message }
                markStopped()
                teardown()
            }
        }
    }

    private fun establishTunnelDevice(): Int {
        // Adopt the official Psiphon private-address scheme (VpnManager.java):
        // the tun IP and the DNS/"router" address MUST be different addresses in
        // the same private subnet. With DNS == tun IP (our old 10.240.1.1/10.240.1.1)
        // the kernel delivers DNS packets locally and they never reach the tunnel,
        // so every app dies with "DNS address could not be found".
        tunNet = selectTunNet()
        TunnelState.log("[tun] " + tunNet.ip + "/" + tunNet.prefix + " dns " + tunNet.dns)

        val pfd = Builder().apply {
            setSession("Tunnel Wind")
            setMtu(vpnMtu.toInt())
            addAddress(tunNet.ip, tunNet.prefix)
            addRoute("0.0.0.0", 0)
            addRoute(tunNet.subnet, tunNet.prefix)
            addDnsServer(tunNet.dns)
        }.establish() ?: throw IllegalStateException("VpnService.prepare was not granted (dialog pending)")
        tunFd = pfd
        val intFd = pfd.detachFd()
        tunFdFd = intFd
        return intFd
    }

    /** private /8-/24 subnet for the tun; avoids clashing with real uplinks */
    internal data class TunNet(
        val ip: String, val subnet: String, val prefix: Int, val dns: String
    )

    private fun selectTunNet(): TunNet {
        val candidates = linkedMapOf(
            "10"  to TunNet("10.0.0.1", "10.0.0.0", 8, "10.0.0.2"),
            "172" to TunNet("172.16.0.1", "172.16.0.0", 12, "172.16.0.2"),
            "192" to TunNet("192.168.0.1", "192.168.0.0", 16, "192.168.0.2"),
            "169" to TunNet("169.254.1.1", "169.254.1.0", 24, "169.254.1.2")
        )
        try {
            for (ni in java.net.NetworkInterface.getNetworkInterfaces()) {
                for (addr in ni.inetAddresses) {
                    if (addr !is java.net.Inet4Address) continue
                    val ip = addr.hostAddress ?: continue
                    if (ip.startsWith("10.")) {
                        candidates.remove("10")
                    } else if (ip.length >= 6 &&
                        ip.substring(0, 6) >= "172.16" && ip.substring(0, 6) <= "172.31") {
                        candidates.remove("172")
                    } else if (ip.startsWith("192.168")) {
                        candidates.remove("192")
                    }
                }
            }
        } catch (_: Throwable) { }
        return candidates.values.firstOrNull() ?: TunNet("10.0.0.1", "10.0.0.0", 8, "10.0.0.2")
    }

    /**
     * Download the signed server list (zlib payload) and return its entry list in
     * the engine's embedded-entry format: newline-separated hex strings, each
     * decoding to "<ip> <port> <secret> <cert> <server-entry JSON>"
     * (see protocol.StreamingServerEntryDecoder / ImportEmbeddedServerEntries).
     *
     * The same bytes are also cached in the engine datastore so its own fetcher
     * can use them if it ever succeeds.
     */
    private fun prefetchServerList(): String? {
        val urls = listOf(
            "https://s3.amazonaws.com/psiphon/web/mjr4-p23r-puwl/server_list_compressed",
            "https://www.blogsfmcancercitizen.com/web/mjr4-p23r-puwl/server_list_compressed",
            "https://www.raceeggxdas.com/web/mjr4-p23r-puwl/server_list_compressed",
            "https://www.sysstemafariuniversitydebt.com/web/mjr4-p23r-puwl/server_list_compressed"
        )
        val dir = java.io.File(filesDir, "ca.psiphon.PsiphonTunnel.tunnel-core")
        dir.mkdirs()
        val target = java.io.File(dir, "remote_server_list")

        for (url in urls) {
            try {
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "tunnelwind-android")
                }
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                if (bytes.size <= 4096 || bytes[0] != 0x78.toByte()) {
                    TunnelState.log("[bootstrap] bad payload from " + url)
                    continue
                }

                // zlib-inflate: JSON envelope {"data": "<hex-lines>", "signature": ...}
                val json = java.util.zip.Inflater().run {
                    setInput(bytes)
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (!finished()) {
                        val n = inflate(buf)
                        if (n == 0) break
                        out.write(buf, 0, n)
                    }
                    end()
                    out.toString("UTF-8")
                }
                val data = org.json.JSONObject(json).optString("data", "")
                if (data.isEmpty()) {
                    TunnelState.log("[bootstrap] no data field in payload")
                    continue
                }

                // sidebar: cache the compressed payload for the engine fetcher
                try { target.writeBytes(bytes) } catch (_: Throwable) { }

                val entries = data.count { it == '\n' } + 1
                TunnelState.log("[bootstrap] server list: $entries entries (" + bytes.size + " bytes)")
                return data
            } catch (t: Throwable) {
                TunnelState.log("[bootstrap] fetch failed (" + url.substring(8, minOf(40, url.length)) + "): " + (t.message ?: t.toString()))
            }
        }
        TunnelState.log("[bootstrap] all server-list fetches failed - engine will try its own")
        return null
    }

    /** If no tunnel comes up within 120s, stop and restore direct internet. */
    private fun startConnectWatchdog() {
        scope.launch {
            var waited = 0
            while (waited < 180 && TunnelState.phase != TunnelState.Phase.Connected) {
                kotlinx.coroutines.delay(5_000)
                waited += 5
            }
            if (TunnelState.phase != TunnelState.Phase.Connected && isEngineStarted()) {
                TunnelState.log("[watchdog] no tunnel after 180s - disconnecting to restore internet")
                try { Psi.stop() } catch (_: Throwable) { }
                markStopped()
                withContext(Dispatchers.Main) { teardown() }
            }
        }
    }

    private fun restartWithNewRegion() {
        stopTunnelInternal()
    }

    private fun stopTunnelInternal() {
        TunnelState.update { phase = TunnelState.Phase.Stopping }
        TunnelState.log("stopping...")
        scope.launch {
            if (isEngineStarted()) {
                try { Psi.stop() } catch (_: Throwable) { }   // blocks until fully stopped
                markStopped()
            }
            withContext(Dispatchers.Main) { teardown() }
        }
    }

    private fun teardown() {
        running = false
        tunFd = null
        TunnelState.update {
            phase = TunnelState.Phase.Stopped
            connectedRegion = null; activeProtocol = null
            httpPort = 0; socksPort = 0; bytesUp = 0; bytesDown = 0
        }
        TunnelState.log("disconnected")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        TunnelState.log("[vpn] revoked by system")
        scope.launch {
            if (isEngineStarted()) {
                try { Psi.stop() } catch (_: Throwable) { }
                markStopped()
            }
            withContext(Dispatchers.Main) { teardown() }
        }
        super.onRevoke()
    }

    override fun onDestroy() {
        tunFd = null
        super.onDestroy()
    }

    // ---------------------------------------------------------- config (full JSON)

    private fun buildConfig(fd: Int): String {
        val b = StringBuilder()
        fun esc(v: String?): String {
            if (v == null) return ""
            return v.replace("\\", "\\\\").replace("\"", "\\\"")
        }
        fun kvStr(k: String, v: String?, trailing: Boolean = true) {
            b.append("  \"").append(k).append("\" : \"").append(esc(v)).append('"')
            if (trailing) b.append(',')
            b.append('\n')
        }
        fun kvInt(k: String, v: Int, trailing: Boolean = true) {
            b.append("  \"").append(k).append("\" : ").append(v)
            if (trailing) b.append(',')
            b.append('\n')
        }
        fun kvBool(k: String, v: Boolean, trailing: Boolean = true) {
            b.append("  \"").append(k).append("\" : ").append(v)
            if (trailing) b.append(',')
            b.append('\n')
        }
        fun kvUrlList(k: String, items: List<String>, trailing: Boolean = true) {
            // single array of objects: [{...},{...}]  (NOT [[...],[...]] - the
            // double-wrap caused "cannot unmarshal array into TransferURL")
            b.append("  \"").append(k).append("\" : [")
            b.append(items.joinToString(",") { url ->
                "{ \"URL\" : \"" +
                    Base64.encodeToString(url.toByteArray(), Base64.NO_WRAP) +
                    "\", \"OnlyAfterAttempts\" : 0, \"SkipVerify\" : false }"
            })
            b.append(']')
            if (trailing) b.append(',')
            b.append('\n')
        }

        b.append('{').append('\n')
        kvStr("ClientPlatform", "Android_" + android.os.Build.VERSION.RELEASE + "_" + packageName)
        kvStr("ClientVersion", clientVersion)
        kvStr("PropagationChannelId", propagationChannelId)
        kvStr("SponsorId", sponsorId)
        kvUrlList("RemoteServerListURLs", remoteServerListUrls)
        kvUrlList("ObfuscatedServerListRootURLs", obfuscatedServerListRootUrls)
        kvStr("ServerEntrySignaturePublicKey", serverEntrySignaturePublicKey)
        kvStr("RemoteServerListSignaturePublicKey", remoteServerListSignaturePublicKey)
        kvStr("DataRootDirectory", filesDir.absolutePath)
        // diagnostics ON for bring-up (log batching keeps the UI responsive);
        // this is what reveals server-list/dial progress in logcat
        kvBool("EmitDiagnosticNotices", false)
        kvBool("EmitDiagnosticNetworkParameters", false)
        kvBool("EmitServerAlerts", true)
        kvBool("EmitBytesTransferred", true)
        kvInt("TunnelPoolSize", 1)
        if (fd > 0) {
            b.append("  \"PacketTunnelTunFileDescriptor\" : ").append(fd).append(",\n")
            b.append("  \"PacketTunnelTransparentDNSIPv4Address\" : \"").append(tunNet.dns).append("\",\n")
        }
        if (TunnelState.egressRegion.isNotEmpty()) {
            b.append("  \"EgressRegion\" : \"").append(esc(TunnelState.egressRegion)).append("\",\n")
        }
        if (TunnelState.bypassOwnRegion) {
            b.append("  \"SplitTunnelOwnRegion\" : true,\n")
        }
        b.append("  \"EstablishTunnelTimeoutSeconds\" : 0\n")
        b.append('}')
        return b.toString()
    }

    // ---------------------------------------------------------- PsiphonProvider

    /** engine notice line - WE parse it (tolerant to any data shape) */
    override fun notice(noticeJson: String?) {
        if (noticeJson == null) return
        TunnelState.log(noticeJson.take(300))
        try {
            val root = JSONObject(noticeJson)
            val noticeType = root.optString("noticeType")
            val data: JSONObject? = try { root.getJSONObject("data") } catch (_: Throwable) { null }

            when (noticeType) {
                "ListeningHttpProxyPort" -> {
                    val p = data?.optInt("port", 0) ?: 0
                    TunnelState.update { httpPort = p }
                    TunnelState.log("[ports] HTTP = " + p)
                }
                "ListeningSocksProxyPort" -> {
                    val p = data?.optInt("port", 0) ?: 0
                    TunnelState.update { socksPort = p }
                    TunnelState.log("[ports] SOCKS = " + p)
                }
                "Tunnels" -> {
                    val c = data?.optInt("count", 0) ?: 0
                    if (c > 0) {
                        TunnelState.log("[state] tunnel up")
                        TunnelState.update { phase = TunnelState.Phase.Connected }
                    } else if (TunnelState.phase == TunnelState.Phase.Connected) {
                        TunnelState.update { phase = TunnelState.Phase.Starting }
                    }
                }
                "BytesTransferred" -> {
                    // engine fields are "sent"/"received" and are DELTAS since the
                    // previous notice, so accumulate them
                    val sent = data?.optLong("sent", 0) ?: 0
                    val recv = data?.optLong("received", 0) ?: 0
                    if (sent != 0L || recv != 0L) {
                        TunnelState.update {
                            bytesUp += sent
                            bytesDown += recv
                        }
                    }
                }
                "AvailableEgressRegions" -> {
                    val arr: JSONArray? = data?.optJSONArray("regions")
                    if (arr != null) {
                        val list = ArrayList<String>()
                        for (i in 0 until arr.length()) list.add(arr.getString(i))
                        TunnelState.update { availableRegions = list }
                        TunnelState.log("[regions] " + list.size + " available")
                    }
                }
                "ConnectedServerRegion" -> {
                    val r = data?.optString("serverRegion", "") ?: ""
                    if (r.isNotEmpty()) TunnelState.update { connectedRegion = r }
                }
                "Exiting" -> {
                    // the engine controller is shutting down (self-initiated or error)
                    TunnelState.log("[state] engine exiting")
                    markStopped()
                    TunnelState.update { phase = TunnelState.Phase.Stopped }
                    teardown()
                }
                "Info", "Warning" -> {
                    val msg = data?.optString("message", "") ?: ""
                    if (msg.isNotEmpty()) TunnelState.log("[core] " + msg.take(200))
                }
                else -> { /* unhandled notice types are logged above already */ }
            }
        } catch (t: Throwable) {
            TunnelState.log("[notice-parse] " + (t.message ?: t.toString()))
        }
    }

    override fun getNetworkID(): String {
        // best-effort stable per-network id; core tolerates ""
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val net = cm.activeNetwork ?: return ""
            val caps = cm.getNetworkCapabilities(net) ?: return ""
            Integer.toHexString((caps.hashCode().toLong() and 0xffffffffL).toInt())
        } catch (_: Throwable) { "" }
    }

    override fun hasNetworkConnectivity(): Long =
        try { if ((getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager)
                .activeNetwork != null) 1 else 0 } catch (_: Throwable) { 0 }

    override fun hasIPv6Route(): Long = 0

    override fun iPv6Synthesize(ipv4Addr: String?): String = ipv4Addr ?: ""

    override fun bindToDevice(fileDescriptor: Long): String {
        // VpnService socket protection (keep engine sockets outside the tun)
        return try {
            if (protect(fileDescriptor.toInt())) "" else "protect failed"
        } catch (t: Throwable) { "protect: " + t.message }
    }

    /**
     * Real upstream DNS servers (non-VPN networks), comma-delimited.
     *
     * IMPORTANT: this must be read while the tun is NOT yet up. Our tun declares
     * 10.240.1.1 as its DNS server, so as soon as it is established it becomes
     * the "active network" - returning that address would make the engine send
     * DNS into its own dead tunnel ("read udp ...: connection refused").
     * We skip VPN transports entirely and only take DNS from real uplinks.
     */
    private fun readUpstreamDns(): String {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val found = LinkedHashSet<String>()
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue
                val lp = cm.getLinkProperties(net) ?: continue
                for (addr in lp.dnsServers) {
                    val host = addr.hostAddress ?: continue
                    if (host.isNotEmpty()) found.add(host)
                }
            }
            found.joinToString(",")
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * Comma-delimited DNS server addresses for the engine's resolver
     * (psi.go: "GetDNSServersAsString must return a comma-delimited list").
     * Returning "" causes "no DNS servers" and every dial fails.
     */
    override fun getDNSServersAsString(): String {
        if (upstreamDns.isNotEmpty()) return upstreamDns
        val fresh = readUpstreamDns()
        if (fresh.isNotEmpty()) {
            upstreamDns = fresh
            return fresh
        }
        return "8.8.8.8,1.1.1.1"
    }

    override fun onAccessToken(accessToken: String?) { /* psiCash later */ }
}

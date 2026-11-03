package com.tunnelwind.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import ca.psiphon.PsiphonTunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import TunnelWindBootstrap.remoteServerListUrls
import TunnelWindBootstrap.obfuscatedServerListRootUrls
import TunnelWindBootstrap.remoteServerListSignaturePublicKey
import TunnelWindBootstrap.serverEntrySignaturePublicKey
import TunnelWindBootstrap.sponsorId
import TunnelWindBootstrap.propagationChannelId

class TunnelService : VpnService(), PsiphonTunnel.HostService {

    companion object {
        // tun interface params
        const val tunAddressV4 = "10.240.1.1"
        const val tunDnsV4     = "10.240.1.1"
        const val vpnMtu       = 1360

        const val ACTION_START  = "com.tunnelwind.app.START"
        const val ACTION_STOP   = "com.tunnelwind.app.STOP"
        const val ACTION_REGION = "com.tunnelwind.app.REGION"

        const val clientVersion = "100"
        var tunFdFd: Int = -1
    }

    private var tunnel: PsiphonTunnel? = null
    private var tunFd: ParcelFileDescriptor? = null

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        AppContextHolder.forceInit(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        return when (action) {
            ACTION_START  -> { startFrontend(); startTunnel(); START_STICKY }
            ACTION_STOP   -> { stopTunnelInternal()          ; START_NOT_STICKY }
            ACTION_REGION -> { restartWithNewRegion()         ; START_STICKY }
            else          -> START_NOT_STICKY
        }
    }

    private fun startFrontend() {
        val notif = TunnelNotifications.foreground(this)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(TunnelNotifications.ID, notif, ServiceInfo_FGS_SPECIAL_USE())
        } else {
            startForeground(TunnelNotifications.ID, notif)
        }
    }

    private fun ServiceInfo_FGS_SPECIAL_USE(): Int =
        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

    private fun startTunnel() {
        TunnelState.update {
            if (phase != TunnelState.Phase.Stopped) return@update
            phase = TunnelState.Phase.Starting
        }
        TunnelState.log("starting VPN service...")
        scope.launch(Dispatchers.IO) {
            try {
                val fd = establishTunnelDevice()
                TunnelState.update { vpnPrepared = true }
                val t = PsiphonTunnel.newPsiphonTunnel(this@TunnelService).apply {
                    setVpnMode(true)
                }
                tunnel = t
                withContext(Dispatchers.Main) {
                    t.startTunneling("")
                }
            } catch (e: Throwable) {
                val stack = e.stackTraceToString().take(1500)
                TunnelState.log("[error] " + (e.message ?: e.toString()))
                TunnelState.log("[stack] " + stack)
                TunnelState.update { phase = TunnelState.Phase.Stopped; lastError = e.message }
                stopSelf()
            }
        }
    }

    private fun establishTunnelDevice(): Int {
        // Builder is the inner VpnService.Builder; the service's implicit
        // "this" is the receiver
        val pfd = Builder().apply {
            setSession("Tunnel Wind")
            setMtu(vpnMtu)
            addAddress(tunAddressV4, 32)
            addDnsServer(tunDnsV4)
            addRoute("0.0.0.0", 0)
        }.establish() ?: throw IllegalStateException("VpnService.prepare was not granted (dialog pending)")
        tunFd = pfd
        val intFd = pfd.detachFd()
        tunFdFd = intFd
        return intFd
    }

    private fun restartWithNewRegion() {
        stopTunnelInternal()
    }

    private fun stopTunnelInternal() {
        TunnelState.update { phase = TunnelState.Phase.Stopping }
        TunnelState.log("stopping...")
        scope.launch(Dispatchers.IO) {
            try { tunnel?.stop() } catch (_: Throwable) { }
            withContext(Dispatchers.Main) {
                tunnel = null
                tunFd = null
                TunnelState.update {
                    phase = TunnelState.Phase.Stopped
                    connectedRegion = null; activeProtocol = null
                    httpPort = 0; socksPort = 0; bytesUp = 0; bytesDown = 0
                    vpnPrepared = false
                }
                TunnelState.log("disconnected")
                stopSelf()
            }
        }
    }

    override fun onRevoke() {
        TunnelState.log("[vpn] revoked by system")
        stopTunnelInternal()
        super.onRevoke()
    }

    override fun onDestroy() {
        tunnel = null
        tunFd = null
        super.onDestroy()
    }

    // ------------------------------------------------------------- host service

    override fun getContext(): TunnelService = this

    /**
     * Full engine JSON (same container as the Windows client + VPN extras).
     * NOTE: ClientPlatform/ClientVersion are intentionally NOT emitted here -
     * the wrapper synthesizes "Android_<os>_<package>" + ClientAPILevel itself
     * in buildPsiphonConfig, and duplicate keys only add ambiguity.
     */
    override fun getPsiphonConfig(): String {
        val fd = tunFdFd
        val b = StringBuilder()
        fun esc(v: String?): String {
            if (v == null) return ""
            return v.replace("\\", "\\\\").replace("\"", "\\\"")
        }
        fun kvStr(k: String, v: String?, trailing: Boolean = true) {
            if (v == null) return
            b.append("  \"").append(k).append("\" : \"").append(esc(v)).append('"')
            if (trailing) b.append(',')
            b.append('\n')
        }
        fun kvInt(k: String, v: Int, trailing: Boolean = true) {
            if (v <= 0) return
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
            b.append("  \"").append(k).append("\" : ").append(
                items.joinToString(prefix = "[", postfix = "]", separator = ",") { url ->
                    "[ { \"URL\" : \"" +
                        java.util.Base64.getEncoder().encodeToString(url.toByteArray()) +
                        "\", \"OnlyAfterAttempts\" : 0, \"SkipVerify\" : false } ]"
                })
            if (trailing) b.append(',')
            b.append('\n')
        }

        kvStr("PropagationChannelId", propagationChannelId)
        kvStr("SponsorId", sponsorId)
        kvUrlList("RemoteServerListURLs", remoteServerListUrls)
        kvUrlList("ObfuscatedServerListRootURLs", obfuscatedServerListRootUrls)
        kvStr("ServerEntrySignaturePublicKey", serverEntrySignaturePublicKey)
        kvStr("RemoteServerListSignaturePublicKey", remoteServerListSignaturePublicKey)
        kvStr("DataRootDirectory", filesDir.absolutePath)
        kvBool("EmitDiagnosticNotices", true)
        kvBool("EmitDiagnosticNetworkParameters", true)
        kvBool("EmitServerAlerts", true)
        kvInt("TunnelPoolSize", 1)
        if (fd > 0) {
            b.append("  \"PacketTunnelTunFileDescriptor\" : ").append(fd).append(",\n")
            b.append("  \"PacketTunnelTransparentDNSIPv4Address\" : \"").append(tunDnsV4).append("\",\n")
        }
        if (TunnelState.egressRegion.isNotEmpty()) {
            b.append("  \"EgressRegion\" : \"").append(TunnelState.egressRegion).append("\",\n")
        }
        if (TunnelState.bypassOwnRegion) {
            b.append("  \"SplitTunnelOwnRegion\" : true,\n")
        }
        b.append("  \"UseNoticeFiles\" : false\n")
        b.append('}')
        return b.toString()
    }
}


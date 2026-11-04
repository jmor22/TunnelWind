package com.tunnelwind.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * User preferences, persisted in SharedPreferences and exposed as Compose state
 * so the UI reacts immediately. The service reads the same values when building
 * the engine config / VpnService builder.
 */
object Prefs {
    private lateinit var sp: SharedPreferences

    var region by mutableStateOf("")              // "" = fastest server
        private set
    var favorites by mutableStateOf<Set<String>>(emptySet())
        private set
    var recents by mutableStateOf<List<String>>(emptyList())
        private set
    var protocol by mutableStateOf("auto")        // auto | ssh | ossh | tls | quic | meek
        private set
    var splitMode by mutableStateOf("off")        // off | own | list
        private set
    var splitRegions by mutableStateOf<Set<String>>(emptySet())
        private set
    var excludedApps by mutableStateOf<Set<String>>(emptySet())
        private set

    /** last region list reported by the engine (available before the first connect) */
    var cachedRegions by mutableStateOf<List<String>>(emptyList())
        private set

    /** first-run onboarding completed */
    var onboarded by mutableStateOf(false)
        private set

    /** auto-connect: on app launch / after boot, optionally Wi-Fi only */
    var autoConnect by mutableStateOf(false)
        private set
    var autoWifiOnly by mutableStateOf(false)
        private set
    var lastStopAt by mutableStateOf(0L)
        private set

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("tw_prefs", Context.MODE_PRIVATE)
        region = sp.getString("region", "") ?: ""
        favorites = sp.getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
        recents = (sp.getString("recents", "") ?: "").split(",").filter { it.isNotBlank() }
        protocol = sp.getString("protocol", "auto") ?: "auto"
        splitMode = sp.getString("split_mode", "off") ?: "off"
        splitRegions = sp.getStringSet("split_regions", emptySet())?.toSet() ?: emptySet()
        excludedApps = sp.getStringSet("excluded_apps", emptySet())?.toSet() ?: emptySet()
        cachedRegions = (sp.getString("cached_regions", "") ?: "").split(",").filter { it.isNotBlank() }
        onboarded = sp.getBoolean("onboarded", false)
        autoConnect = sp.getBoolean("auto_connect", false)
        autoWifiOnly = sp.getBoolean("auto_wifi_only", false)
        lastStopAt = sp.getLong("last_stop_at", 0L)
    }

    // ---------------------------------------------------------------- writers
    fun saveRegion(v: String) {
        region = v
        sp.edit().putString("region", v).apply()
    }

    fun toggleFavorite(code: String) {
        if (code.isEmpty()) return
        val s = favorites.toMutableSet()
        if (!s.add(code)) s.remove(code)
        favorites = s
        sp.edit().putStringSet("favorites", s).apply()
    }

    fun addRecent(code: String) {
        if (code.isEmpty()) return
        val l = (listOf(code) + recents.filter { it != code }).take(6)
        recents = l
        sp.edit().putString("recents", l.joinToString(",")).apply()
    }

    fun saveProtocol(p: String) {
        protocol = p
        sp.edit().putString("protocol", p).apply()
    }

    fun saveSplitMode(m: String) {
        splitMode = m
        sp.edit().putString("split_mode", m).apply()
    }

    fun saveSplitRegions(s: Set<String>) {
        splitRegions = s
        sp.edit().putStringSet("split_regions", s).apply()
    }

    fun saveExcludedApps(s: Set<String>) {
        excludedApps = s
        sp.edit().putStringSet("excluded_apps", s).apply()
    }

    fun saveCachedRegions(list: List<String>) {
        cachedRegions = list
        sp.edit().putString("cached_regions", list.joinToString(",")).apply()
    }

    fun setOnboarded() {
        onboarded = true
        sp.edit().putBoolean("onboarded", true).apply()
    }

    fun saveAutoConnect(v: Boolean) {
        autoConnect = v
        sp.edit().putBoolean("auto_connect", v).apply()
    }

    fun saveAutoWifiOnly(v: Boolean) {
        autoWifiOnly = v
        sp.edit().putBoolean("auto_wifi_only", v).apply()
    }

    fun markStoppedNow() {
        lastStopAt = System.currentTimeMillis()
        sp.edit().putLong("last_stop_at", lastStopAt).apply()
    }

    // ---------------------------------------------------------------- engine view
    /** LimitTunnelProtocols value; empty list = let the engine use everything */
    fun protocolList(): List<String> = when (protocol) {
        "ssh" -> listOf("SSH")
        "ossh" -> listOf("OSSH")
        "tls" -> listOf("TLS-OSSH")
        "quic" -> listOf("QUIC-OSSH")
        "meek" -> listOf(
            "FRONTED-MEEK-OSSH", "FRONTED-MEEK-HTTP-OSSH",
            "FRONTED-MEEK-QUIC-OSSH", "UNFRONTED-MEEK-OSSH"
        )
        else -> emptyList()
    }

    fun protocolLabel(): String = when (protocol) {
        "ssh" -> "SSH only"
        "ossh" -> "OSSH only"
        "tls" -> "TLS-OSSH only"
        "quic" -> "QUIC-OSSH only"
        "meek" -> "Meek (fronted)"
        else -> "Automatic"
    }

    fun splitModeLabel(): String = when (splitMode) {
        "own" -> "Own country bypasses the VPN"
        "list" -> "Selected countries bypass the VPN"
        else -> "Everything through the VPN"
    }
}

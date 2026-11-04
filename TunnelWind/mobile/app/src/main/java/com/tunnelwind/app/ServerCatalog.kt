package com.tunnelwind.app

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Compact index of the server list: region -> a few (ip, port) candidates.
 * Parsed from the embedded server entries during bootstrap (the same hex
 * "<ip> <port> <secret> <cert> <json>" format the engine consumes) and cached
 * so latency can be measured before the next connection.
 */
object ServerCatalog {
    private const val MAX_PER_REGION = 3
    private val byRegion = mutableMapOf<String, MutableList<Pair<String, Int>>>()

    fun candidates(region: String): List<Pair<String, Int>> = synchronized(this) {
        byRegion[region]?.toList() ?: emptyList()
    }

    fun isEmpty(): Boolean = synchronized(this) { byRegion.isEmpty() }

    fun rebuild(entries: String, ctx: Context?) {
        val map = mutableMapOf<String, MutableList<Pair<String, Int>>>()
        for (raw in entries.lineSequence()) {
            val line = raw.trim()
            if (line.length < 8) continue
            try {
                val text = String(hexToBytes(line), Charsets.UTF_8)
                val parts = text.split(' ', limit = 5)
                if (parts.size < 5) continue
                val json = JSONObject(parts[4])
                val region = json.optString("region", "")
                // Old entries carry "<ip> <port>" in the prefix; newer ones use
                // "0 0 0 0 <json>" and keep the address in the JSON as ipAddress /
                // webServerPort (a string) or one of the SSH/TLS ports.
                val ip = json.optString("ipAddress", "").ifEmpty {
                    parts[0].takeIf { it != "0" } ?: ""
                }
                // webServerPort is a string in the protocol but some lists use a
                // number; prefer it (usually 443) because it is the most likely
                // port to be reachable for a latency probe.
                val webPort = when (val v = json.opt("webServerPort")) {
                    is Number -> v.toInt()
                    is String -> v.toIntOrNull() ?: 0
                    else -> 0
                }
                val port = webPort.takeIf { it > 0 }
                    ?: json.optInt("tlsOSSHPort", 0).takeIf { it > 0 }
                    ?: json.optInt("sshPort", 0).takeIf { it > 0 }
                    ?: json.optInt("sshObfuscatedPort", 0).takeIf { it > 0 }
                    ?: parts[1].toIntOrNull()?.takeIf { it > 0 }
                    ?: 0
                if (region.isEmpty() || ip.isEmpty() || port <= 0) continue
                val list = map.getOrPut(region) { mutableListOf() }
                if (list.size < MAX_PER_REGION) list.add(ip to port)
            } catch (_: Throwable) { }
        }
        if (map.isEmpty()) return
        synchronized(this) {
            byRegion.clear()
            byRegion.putAll(map)
        }
        if (ctx != null) save(ctx)
    }

    fun load(ctx: Context) {
        try {
            val f = File(ctx.filesDir, "server-catalog.txt")
            if (!f.exists()) return
            val map = mutableMapOf<String, MutableList<Pair<String, Int>>>()
            f.forEachLine { line ->
                val p = line.split(' ')
                if (p.size == 3) {
                    val port = p[2].toIntOrNull() ?: return@forEachLine
                    map.getOrPut(p[0]) { mutableListOf() }.add(p[1] to port)
                }
            }
            if (map.isNotEmpty()) {
                synchronized(this) {
                    byRegion.clear()
                    byRegion.putAll(map)
                }
            }
        } catch (_: Throwable) { }
    }

    private fun save(ctx: Context) {
        try {
            val sb = StringBuilder()
            synchronized(this) {
                for ((region, list) in byRegion) {
                    for ((ip, port) in list) {
                        sb.append(region).append(' ').append(ip).append(' ').append(port).append('\n')
                    }
                }
            }
            File(ctx.filesDir, "server-catalog.txt").writeText(sb.toString())
        } catch (_: Throwable) { }
    }

    private fun hexToBytes(s: String): ByteArray {
        val out = ByteArray(s.length / 2)
        var i = 0
        while (i + 1 < s.length) {
            val hi = Character.digit(s[i], 16)
            val lo = Character.digit(s[i + 1], 16)
            if (hi < 0 || lo < 0) break
            out[i / 2] = ((hi shl 4) + lo).toByte()
            i += 2
        }
        return out
    }
}

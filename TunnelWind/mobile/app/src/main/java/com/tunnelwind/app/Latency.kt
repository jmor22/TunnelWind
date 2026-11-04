package com.tunnelwind.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Approximate per-region latency: a TCP handshake to a couple of that region's
 * listed servers, measured over the real uplink (the socket is bound to the
 * non-VPN network so measurements work while the tunnel is up too).
 *
 * This is "time to reach the server", not the full tunnel round-trip, but it is
 * a good relative indicator for the server picker.
 */
object Latency {
    val ms = mutableStateMapOf<String, Int>()

    var measuring by mutableStateOf(false)
        private set

    private var lastRun = 0L
    private const val MAX_AGE_MS = 5 * 60_000L
    private const val TIMEOUT_MS = 1200

    fun shouldRefresh(): Boolean =
        ms.isEmpty() || System.currentTimeMillis() - lastRun > MAX_AGE_MS

    suspend fun measure(ctx: Context, regions: List<String>) {
        if (measuring || regions.isEmpty() || ServerCatalog.isEmpty()) return
        measuring = true
        lastRun = System.currentTimeMillis()
        try {
            withContext(Dispatchers.IO) {
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val net: Network = cm.allNetworks.firstOrNull { n ->
                    val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                } ?: return@withContext

                val gate = Semaphore(8)
                coroutineScope {
                    regions.forEach { region ->
                        launch {
                            gate.withPermit {
                                val best = ServerCatalog.candidates(region)
                                    .mapNotNull { (ip, port) -> probe(net, ip, port) }
                                    .minOrNull()
                                if (best != null) ms[region] = best
                            }
                        }
                    }
                }
            }
        } finally {
            measuring = false
        }
    }

    private fun probe(net: Network, ip: String, port: Int): Int? {
        val socket = Socket()
        return try {
            net.bindSocket(socket)
            val t0 = System.currentTimeMillis()
            socket.connect(InetSocketAddress(ip, port), TIMEOUT_MS)
            (System.currentTimeMillis() - t0).toInt()
        } catch (_: Throwable) {
            null
        } finally {
            try { socket.close() } catch (_: Throwable) { }
        }
    }
}

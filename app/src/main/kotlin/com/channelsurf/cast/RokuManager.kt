package com.channelsurf.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.channelsurf.core.schedule.TuneResolution
import com.channelsurf.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder

data class RokuDevice(val host: String, val port: Int, val name: String)

/**
 * Sends the live channel to a Roku TV running the Channel Surfer receiver (the sideloaded "dev" channel,
 * see /roku). Discovery is SSDP ("roku:ecp"); control is Roku's External Control Protocol on port 8060.
 *
 * ECP is plain HTTP on the local network only. It's spoken over a raw socket so the app's HTTPS-only policy
 * for the internet stays in force, and only private (LAN) addresses are ever contacted.
 */
class RokuManager(context: Context, private val scope: CoroutineScope, private val diagnostics: DiagnosticsLog) {
    companion object {
        private const val ITEMS_PER_REQUEST = 8
    }

    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    private val _devices = MutableStateFlow<List<RokuDevice>>(emptyList())
    val devices: StateFlow<List<RokuDevice>> = _devices

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching

    private var discoverJob: Job? = null

    fun discover() {
        if (discoverJob?.isActive == true) return
        discoverJob = scope.launch(Dispatchers.IO) {
            _searching.value = true
            val lock = try { wifi?.createMulticastLock("channel-surfer-ssdp")?.apply { setReferenceCounted(false); acquire() } } catch (e: Exception) { null }
            try {
                val found = LinkedHashMap<String, RokuDevice>()
                repeat(2) { round ->
                    ssdpSearch(timeoutMs = 2_500).forEach { (host, port) ->
                        if (host !in found) {
                            val name = deviceName(host, port) ?: "Roku TV ($host)"
                            found[host] = RokuDevice(host, port, name)
                            _devices.value = found.values.toList()
                        }
                    }
                    if (round == 0 && found.isNotEmpty()) return@repeat
                }
                _devices.value = found.values.toList()
                diagnostics.info("roku", "Found ${found.size} Roku device(s)")
            } catch (e: Exception) {
                diagnostics.warn("roku", "Discovery failed: ${e.javaClass.simpleName}")
            } finally {
                runCatching { lock?.release() }
                _searching.value = false
            }
        }
    }

    private fun ssdpSearch(timeoutMs: Int): List<Pair<String, Int>> {
        val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nST: roku:ecp\r\nMX: 2\r\n\r\n".toByteArray()
        val out = ArrayList<Pair<String, Int>>()
        DatagramSocket().use { sock ->
            sock.soTimeout = 400
            val group = InetAddress.getByName("239.255.255.250")
            repeat(2) { sock.send(DatagramPacket(msg, msg.size, group, 1900)) }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val pkt = DatagramPacket(buf, buf.size)
                try { sock.receive(pkt) } catch (e: SocketTimeoutException) { continue }
                val text = String(pkt.data, 0, pkt.length)
                val location = text.lines().firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim() ?: continue
                val uri = try { URI(location) } catch (e: Exception) { continue }
                val host = uri.host ?: continue
                if (isPrivateLan(host)) out += host to (if (uri.port > 0) uri.port else 8060)
            }
        }
        return out.distinct()
    }

    private fun deviceName(host: String, port: Int): String? {
        val xml = http("GET", host, port, "/query/device-info") ?: return null
        fun tag(t: String) = Regex("<$t>([^<]{1,80})</$t>").find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        return tag("user-device-name") ?: tag("friendly-device-name") ?: tag("friendly-model-name")
    }

    /** Sends what's on now. Launches the receiver if needed, otherwise updates the running one. */
    suspend fun send(device: RokuDevice, res: TuneResolution, nowUtcMs: Long): Boolean = withContext(Dispatchers.IO) {
        val params = LinkedHashMap<String, String>()
        params["num"] = res.channel.displayNumber
        params["ch"] = res.channel.displayName
        params["title"] = res.block.headline.take(120)
        when (res) {
            is TuneResolution.Play -> {
                params["url"] = res.mediaUrl
                params["pos"] = "%.1f".format(java.util.Locale.US, res.expectedFilePositionMs(nowUtcMs).coerceAtLeast(0) / 1000.0)
                params["fmt"] = when (res.asset.container) { "hls" -> "hls"; else -> "mp4" }
            }
            is TuneResolution.Slate -> {
                params["slate"] = when (res.kind) {
                    com.channelsurf.core.schedule.SlateKind.TECHNICAL_DIFFICULTIES -> "TECHNICAL DIFFICULTIES"
                    com.channelsurf.core.schedule.SlateKind.STATION_BREAK -> res.channel.displayName.uppercase()
                    com.channelsurf.core.schedule.SlateKind.NO_PROGRAMMING -> "OFF AIR"
                }
                params["detail"] = if (res.kind == com.channelsurf.core.schedule.SlateKind.STATION_BREAK) "We'll be right back" else "No recordings for this block yet"
            }
        }
        val query = params.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
        val running = http("GET", device.host, device.port, "/query/active-app")?.contains("id=\"dev\"") == true
        val path = if (running) "/input?$query" else "/launch/dev?$query"
        val ok = http("POST", device.host, device.port, path) != null
        if (!ok) diagnostics.warn("roku", "Couldn't reach ${device.name}")
        ok
    }

    /** One scheduled block for the Roku's own queue. [url] null = a slate. */
    data class QueueItem(val startUtcMs: Long, val endUtcMs: Long, val fileOffsetMs: Long, val hls: Boolean, val title: String, val url: String?)

    /**
     * Sends the channel's upcoming schedule. The Roku plays it by the wall clock on its own, so the channel keeps
     * going show after show while the phone sleeps. The first request replaces whatever it had.
     */
    suspend fun sendQueue(
        device: RokuDevice, channelNumber: String, channelName: String, items: List<QueueItem>, clock: () -> Long,
    ): Boolean = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext false
        val base = clock() / 1000
        fun rel(ms: Long) = "%.2f".format(java.util.Locale.US, ms / 1000.0 - base)
        fun clean(t: String) = t.replace('|', '/').replace(Regex("\\s+"), " ").trim().ifEmpty { "-" }.take(100)
        val encoded = items.map { i ->
            listOf(
                rel(i.startUtcMs), rel(i.endUtcMs), "%.2f".format(java.util.Locale.US, i.fileOffsetMs.coerceAtLeast(0) / 1000.0),
                if (i.hls) "hls" else "mp4", clean(i.title), i.url?.replace("|", "%7C") ?: "-",
            ).joinToString("|")
        }
        val chunks = encoded.chunked(ITEMS_PER_REQUEST)
        var ok = true
        for ((n, chunk) in chunks.withIndex()) {
            val params = LinkedHashMap<String, String>()
            if (n == 0) {
                params["num"] = channelNumber
                params["ch"] = channelName
                params["title"] = items.first().title.take(120)
                params["qreset"] = "1"
                params["base"] = base.toString()
                params["pnow"] = rel(clock())
            } else {
                params["qadd"] = "1"
            }
            chunk.forEachIndexed { i, v -> params["i$i"] = v }
            val query = params.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }
            val path = if (n == 0) {
                val running = http("GET", device.host, device.port, "/query/active-app")?.contains("id=\"dev\"") == true
                if (running) "/input?$query" else {
                    val launched = http("POST", device.host, device.port, "/launch/dev?$query") != null
                    if (!launched) { ok = false; break }
                    // Give the receiver a moment to start before sending the rest.
                    for (attempt in 0 until 10) {
                        kotlinx.coroutines.delay(700)
                        if (http("GET", device.host, device.port, "/query/active-app")?.contains("id=\"dev\"") == true) break
                    }
                    kotlinx.coroutines.delay(1_500)
                    continue
                }
            } else "/input?$query"
            if (http("POST", device.host, device.port, path) == null) { ok = false; if (n == 0) break }
        }
        if (!ok) diagnostics.warn("roku", "Couldn't reach ${device.name}")
        ok
    }

    /** Leaves the receiver by pressing Home on the Roku. */
    suspend fun disconnect(device: RokuDevice) = withContext(Dispatchers.IO) {
        http("POST", device.host, device.port, "/keypress/Home")
    }

    enum class Readiness { READY, NO_RECEIVER, UNREACHABLE }

    /** Can we control this Roku, and is the Channel Surfer receiver installed on it? */
    suspend fun readiness(device: RokuDevice): Readiness = withContext(Dispatchers.IO) {
        val apps = http("GET", device.host, device.port, "/query/apps") ?: return@withContext Readiness.UNREACHABLE
        if (apps.contains("id=\"dev\"")) Readiness.READY else Readiness.NO_RECEIVER
    }

    /** Minimal HTTP/1.1 over a socket, LAN only. Returns the body for 2xx, else null. */
    private fun http(method: String, host: String, port: Int, path: String): String? {
        if (!isPrivateLan(host)) return null
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 2_500)
                s.soTimeout = 4_000
                val req = "$method $path HTTP/1.1\r\nHost: $host:$port\r\nContent-Length: 0\r\nConnection: close\r\nUser-Agent: ChannelSurfer\r\n\r\n"
                s.getOutputStream().apply { write(req.toByteArray()); flush() }
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val status = reader.readLine() ?: return null
                val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: return null
                val body = StringBuilder()
                var inBody = false
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (inBody) { body.append(line).append('\n'); if (body.length > 200_000) break }
                    else if (line!!.isEmpty()) inBody = true
                }
                if (code in 200..299) body.toString() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isPrivateLan(host: String): Boolean {
        val p = host.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }
}

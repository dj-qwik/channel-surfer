package com.channelsurf.server

import com.channelsurf.core.manifest.ManifestCodec
import com.channelsurf.core.manifest.ManifestResult
import com.channelsurf.core.model.Lineup
import com.channelsurf.core.model.ProgramBlock
import com.channelsurf.core.schedule.ScheduleEngine
import com.channelsurf.core.schedule.TuneResolution
import com.channelsurf.core.schedule.TuneResolver
import com.channelsurf.core.security.ManifestSignature
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.PrivateKey
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** A published manifest: the exact bytes served, their signature, and the compiled engine. */
class Publication(val bytes: ByteArray, val signature: String, val lineup: Lineup) {
    val engine = ScheduleEngine(lineup)
}

/**
 * Endpoints (see docs/BACKEND_API.md):
 *  GET /healthz
 *  GET /v1/time                                   -> {"utcMs": 1762180000000}
 *  GET /v1/manifest                               -> manifest JSON + X-Manifest-Signature / X-Manifest-Revision
 *  GET /v1/schedule?channelId=&fromUtcMs=&toUtcMs= -> gapless blocks (window <= 8 days)
 *  GET /v1/now?channelId=                         -> current block and offset
 */
class ScheduleServer(
    private val manifestFile: File,
    private val privateKey: PrivateKey,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val current = AtomicReference<Publication>()
    private var server: HttpServer? = null

    fun loadManifest(): Publication {
        val text = manifestFile.readText()
        val lineup = when (val r = ManifestCodec.decode(text)) {
            is ManifestResult.Success -> r.lineup
            is ManifestResult.Failure -> throw IllegalStateException("Manifest invalid:\n" + r.errors.joinToString("\n"))
        }
        // Serve the canonical re-encoding so the signature always covers exactly what clients parse.
        val bytes = ManifestCodec.encode(lineup).toByteArray(Charsets.UTF_8)
        return Publication(bytes, ManifestSignature.sign(bytes, privateKey), lineup).also { current.set(it) }
    }

    private var executor: java.util.concurrent.ExecutorService? = null

    fun start(port: Int): Int {
        if (current.get() == null) loadManifest()
        val s = HttpServer.create(InetSocketAddress(port), 0)
        val pool = Executors.newFixedThreadPool(8)
        executor = pool
        s.executor = pool
        s.createContext("/") { ex -> handle(ex) }
        s.start()
        server = s
        return s.address.port
    }

    fun stop() {
        server?.stop(0)
        executor?.shutdownNow()
    }

    private fun handle(ex: HttpExchange) {
        try {
            if (ex.requestMethod != "GET") return send(ex, 405, error("method not allowed"))
            val q = query(ex.requestURI.rawQuery)
            val pub = current.get()
            when (ex.requestURI.path) {
                "/healthz" -> send(ex, 200, buildJsonObject { put("ok", true); put("revision", pub.lineup.revision) })
                "/v1/time" -> send(ex, 200, buildJsonObject { put("utcMs", clock()) }, cache = "no-store")
                "/v1/manifest" -> {
                    ex.responseHeaders.add(ManifestSignature.HEADER_SIGNATURE, pub.signature)
                    ex.responseHeaders.add(ManifestSignature.HEADER_REVISION, pub.lineup.revision)
                    sendBytes(ex, 200, pub.bytes, "max-age=300")
                }
                "/v1/schedule" -> {
                    val ch = q["channelId"] ?: return send(ex, 400, error("channelId required"))
                    if (pub.lineup.channel(ch) == null) return send(ex, 404, error("unknown channel"))
                    val now = clock()
                    val from = q["fromUtcMs"]?.toLongOrNull() ?: now
                    val to = q["toUtcMs"]?.toLongOrNull() ?: (now + DAY)
                    if (to < from || to - from > 8 * DAY) return send(ex, 400, error("window must be 0..8 days"))
                    val blocks = pub.engine.blocksBetween(ch, from, to)
                    send(ex, 200, buildJsonObject {
                        put("revision", pub.lineup.revision)
                        put("channelId", ch)
                        put("blocks", buildJsonArray { blocks.forEach { add(blockJson(it)) } })
                    })
                }
                "/v1/now" -> {
                    val ch = q["channelId"] ?: return send(ex, 400, error("channelId required"))
                    if (pub.lineup.channel(ch) == null) return send(ex, 404, error("unknown channel"))
                    val now = clock()
                    val r = TuneResolver(pub.engine).resolve(ch, now)
                    send(ex, 200, buildJsonObject {
                        put("revision", pub.lineup.revision)
                        put("utcMs", now)
                        put("block", blockJson(r.block))
                        put("offsetInBlockMs", now - r.block.startUtcMs)
                        when (r) {
                            is TuneResolution.Play -> { put("assetId", r.asset.id); put("filePositionMs", r.filePositionMs) }
                            is TuneResolution.Slate -> put("slate", r.kind.name)
                        }
                    }, cache = "no-store")
                }
                else -> send(ex, 404, error("not found"))
            }
        } catch (e: Exception) {
            send(ex, 500, error("internal error"))
        } finally {
            ex.close()
        }
    }

    private fun blockJson(b: ProgramBlock) = buildJsonObject {
        put("id", b.id); put("channelId", b.channelId); b.assetId?.let { put("assetId", it) }
        put("startUtcMs", b.startUtcMs); put("durationMs", b.durationMs); put("sourceOffsetMs", b.sourceOffsetMs)
        put("displayTitle", b.displayTitle); put("kind", b.kind.name); put("revision", b.revision)
        b.provenance?.let { put("provenance", it.name) }
    }

    private fun error(msg: String) = buildJsonObject { put("error", msg) }

    private fun send(ex: HttpExchange, status: Int, body: JsonObject, cache: String = "max-age=60") =
        sendBytes(ex, status, body.toString().toByteArray(Charsets.UTF_8), cache)

    private fun sendBytes(ex: HttpExchange, status: Int, bytes: ByteArray, cache: String) {
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", cache)
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun query(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { '=' in it }.associate {
            val (k, v) = it.split('=', limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    companion object { const val DAY = 86_400_000L }
}

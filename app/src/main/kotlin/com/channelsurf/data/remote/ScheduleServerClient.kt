package com.channelsurf.data.remote

import android.os.SystemClock
import com.channelsurf.core.security.ManifestSignature
import com.channelsurf.core.time.SyncedBroadcastClock
import com.channelsurf.data.archive.await
import com.channelsurf.data.catalog.CatalogRepository
import com.channelsurf.data.catalog.ImportResult
import com.channelsurf.data.prefs.SettingsRepository
import com.channelsurf.diagnostics.DiagnosticsLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

sealed interface SyncResult {
    data object NotConfigured : SyncResult
    data class Synced(val timeSynced: Boolean, val manifestRevision: String?, val manifestUpdated: Boolean, val message: String) : SyncResult
    data class Failed(val message: String) : SyncResult
}

/**
 * Talks to the optional Channel Surf schedule backend: authoritative UTC time and a signed manifest.
 * With a server, every device computes an identical lineup from identical inputs; without one, each
 * device runs the same deterministic schedule against its own clock.
 */
class ScheduleServerClient(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val catalog: CatalogRepository,
    private val clock: SyncedBroadcastClock,
    private val diagnostics: DiagnosticsLog,
) {
    suspend fun sync(): SyncResult {
        val s = settings.current()
        if (s.scheduleServerUrl.isBlank()) return SyncResult.NotConfigured
        val base = s.scheduleServerUrl.trimEnd('/').toHttpUrlOrNull()
            ?: return SyncResult.Failed("Schedule server URL is not a valid URL")
        if (!base.isHttps) return SyncResult.Failed("Schedule server must use HTTPS")

        val timeSynced = syncTime(base.newBuilder().addPathSegments("v1/time").build().toString())

        if (s.scheduleServerPublicKey.isBlank()) {
            return SyncResult.Synced(timeSynced, null, false, "Time synced; manifest skipped (no public key configured)")
        }
        val key = try { ManifestSignature.publicKeyFromBase64(s.scheduleServerPublicKey) } catch (e: Exception) {
            return SyncResult.Failed("Configured public key is invalid")
        }
        return try {
            val req = Request.Builder().url(base.newBuilder().addPathSegments("v1/manifest").build()).get().build()
            http.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) return SyncResult.Failed("Manifest HTTP ${resp.code}")
                val revision = resp.header(ManifestSignature.HEADER_REVISION)
                val signature = resp.header(ManifestSignature.HEADER_SIGNATURE)
                val bytes = resp.body?.bytes() ?: return SyncResult.Failed("Empty manifest")
                if (bytes.size > MAX_MANIFEST_BYTES) return SyncResult.Failed("Manifest too large")
                if (!ManifestSignature.verify(bytes, signature, key)) {
                    diagnostics.warn("server.manifest", "Signature verification FAILED; manifest ignored")
                    return SyncResult.Failed("Manifest signature invalid")
                }
                val lastServer = catalog.recentRevisions().firstOrNull { it.source == SOURCE }?.revision
                if (revision != null && revision == lastServer) {
                    return SyncResult.Synced(timeSynced, revision, false, "Up to date ($revision)")
                }
                when (val r = catalog.importManifest(bytes.toString(Charsets.UTF_8), SOURCE, signatureVerified = true)) {
                    is ImportResult.Imported -> SyncResult.Synced(timeSynced, r.revision, true, "Imported ${r.revision}")
                    is ImportResult.Rejected -> SyncResult.Failed("Server manifest rejected: ${r.errors.take(3)}")
                }
            }
        } catch (e: IOException) {
            SyncResult.Failed("Network error: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun syncTime(url: String): Boolean {
        return try {
        val sent = SystemClock.elapsedRealtime()
        http.newCall(Request.Builder().url(url).get().header("Cache-Control", "no-store").build()).await().use { resp ->
            val received = SystemClock.elapsedRealtime()
            if (!resp.isSuccessful) return false
            val body = resp.body?.string()?.take(1_000) ?: return false
            val utc = ((Json.parseToJsonElement(body) as? JsonObject)?.get("utcMs") as? JsonPrimitive)?.longOrNull ?: return false
            val ok = clock.applyServerSample(utc, sent, received)
            if (ok) diagnostics.info("server.time", "Clock synced; offset from device ${clock.offsetFromWallMs()} ms, rtt ${received - sent} ms")
            ok
        }
    } catch (e: Exception) {
        diagnostics.warn("server.time", "Time sync failed: ${e.javaClass.simpleName}")
        false
        }
    }

    companion object {
        const val SOURCE = "SERVER"
        const val MAX_MANIFEST_BYTES = 4_000_000
    }
}

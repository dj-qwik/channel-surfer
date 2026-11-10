package com.channelsurf.data.archive

import com.channelsurf.core.archive.UrlPolicy
import com.channelsurf.core.net.BackoffPolicy
import com.channelsurf.core.net.FailureClass
import com.channelsurf.core.net.HttpFailures
import com.channelsurf.core.net.RetryAfter
import com.channelsurf.diagnostics.DiagnosticsLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface HttpResult {
    data class Ok(val body: String, val code: Int, val etag: String?) : HttpResult
    data class Failed(
        val code: Int?,
        val failure: FailureClass,
        val message: String,
        val retryAfterMs: Long? = null,
    ) : HttpResult
}

data class ProbeResult(
    val statusCode: Int?,
    val contentLength: Long?,
    val supportsRange: Boolean,
    val contentType: String?,
    val failure: FailureClass?,
    val message: String,
    val retryAfterMs: Long? = null,
) {
    val ok: Boolean get() = failure == null && statusCode != null && HttpFailures.isSuccess(statusCode)
}

/** Thrown by the network interceptor when a request or redirect leaves the approved hosts. */
class BlockedHostException(host: String) : IOException("Blocked non-approved host: $host")

/**
 * HTTPS client for archive.org with bounded concurrency, exponential backoff with jitter, Retry-After,
 * response size limits, and a redirect guard. Never used for the schedule server (see ScheduleServerClient).
 */
class ArchiveHttp(
    baseClient: OkHttpClient,
    private val diagnostics: DiagnosticsLog,
    private val backoff: BackoffPolicy = BackoffPolicy(),
    maxConcurrent: Int = 3,
    private val approvedHosts: Set<String> = emptySet(),
    private val nowUtcMs: () -> Long = System::currentTimeMillis,
) {
    private val permits = Semaphore(maxConcurrent)

    val client: OkHttpClient = baseClient.newBuilder()
        .addNetworkInterceptor(Interceptor { chain ->
            val url = chain.request().url
            if (!UrlPolicy.isAllowed(url.toString(), approvedHosts)) throw BlockedHostException(url.host)
            chain.proceed(chain.request())
        })
        .build()

    suspend fun getText(url: String, maxChars: Int = MAX_JSON_CHARS): HttpResult {
        if (!UrlPolicy.isAllowed(url, approvedHosts)) return HttpResult.Failed(null, FailureClass.CLIENT, "URL not approved")
        var attempt = 0
        while (true) {
            val result = permits.withPermit { executeText(url, maxChars) }
            if (result is HttpResult.Failed && result.failure == FailureClass.TRANSIENT) {
                attempt++
                if (!backoff.shouldRetry(attempt)) return result
                val wait = backoff.delayMs(attempt, result.retryAfterMs)
                diagnostics.warn("http.retry", "attempt $attempt in ${wait}ms: ${result.message} (${result.code ?: "network"})")
                delay(wait)
                continue
            }
            return result
        }
    }

    private suspend fun executeText(url: String, maxChars: Int): HttpResult {
        val request = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", USER_AGENT).get().build()
        return try {
            client.newCall(request).await().use { resp ->
                if (!resp.isSuccessful) {
                    HttpResult.Failed(
                        resp.code, HttpFailures.classify(resp.code), "HTTP ${resp.code}",
                        RetryAfter.parseDelayMs(resp.header("Retry-After"), nowUtcMs()),
                    )
                } else {
                    val body = resp.body ?: return HttpResult.Failed(resp.code, FailureClass.TRANSIENT, "empty body")
                    val declared = body.contentLength()
                    if (declared > maxChars * 2L) return HttpResult.Failed(resp.code, FailureClass.CLIENT, "response too large")
                    val source = body.source()
                    if (source.request(maxChars * 2L + 1)) return HttpResult.Failed(resp.code, FailureClass.CLIENT, "response too large")
                    val text = source.buffer.readUtf8()
                    if (text.length > maxChars) HttpResult.Failed(resp.code, FailureClass.CLIENT, "response too large")
                    else HttpResult.Ok(text, resp.code, resp.header("ETag"))
                }
            }
        } catch (e: BlockedHostException) {
            HttpResult.Failed(null, FailureClass.CLIENT, e.message ?: "blocked host")
        } catch (e: IOException) {
            HttpResult.Failed(null, FailureClass.TRANSIENT, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    /**
     * Verifies that a media URL is reachable and supports byte-range requests (required for joining a
     * program mid-way). Uses a 2-byte ranged GET, which archive.org handles more reliably than HEAD.
     */
    suspend fun probe(url: String): ProbeResult {
        if (!UrlPolicy.isAllowed(url, approvedHosts)) return ProbeResult(null, null, false, null, FailureClass.CLIENT, "URL not approved")
        var attempt = 0
        while (true) {
            val r = permits.withPermit { executeProbe(url) }
            if (r.failure == FailureClass.TRANSIENT) {
                attempt++
                if (!backoff.shouldRetry(attempt)) return r
                delay(backoff.delayMs(attempt, r.retryAfterMs))
                continue
            }
            return r
        }
    }

    private suspend fun executeProbe(url: String): ProbeResult {
        val request = Request.Builder().url(url).header("Range", "bytes=0-1").header("User-Agent", USER_AGENT).get().build()
        return try {
            client.newCall(request).await().use { resp ->
                val code = resp.code
                if (!resp.isSuccessful) {
                    return ProbeResult(
                        code, null, false, resp.header("Content-Type"), HttpFailures.classify(code), "HTTP $code",
                        RetryAfter.parseDelayMs(resp.header("Retry-After"), nowUtcMs()),
                    )
                }
                val contentRange = resp.header("Content-Range") // "bytes 0-1/123456"
                val total = contentRange?.substringAfter('/', "")?.toLongOrNull()
                val ranged = code == 206 && total != null
                ProbeResult(
                    statusCode = code,
                    contentLength = total ?: resp.body?.contentLength()?.takeIf { it >= 0 },
                    supportsRange = ranged,
                    contentType = resp.header("Content-Type"),
                    failure = null,
                    message = if (ranged) "OK, seekable" else "OK, server ignored Range (not seekable)",
                )
            }
        } catch (e: BlockedHostException) {
            ProbeResult(null, null, false, null, FailureClass.CLIENT, e.message ?: "blocked host")
        } catch (e: IOException) {
            ProbeResult(null, null, false, null, FailureClass.TRANSIENT, e.javaClass.simpleName)
        }
    }

    companion object {
        const val MAX_JSON_CHARS = 6_000_000
        const val USER_AGENT = "ChannelSurf/1.0 (Android; linear-archive-player)"
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { response.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

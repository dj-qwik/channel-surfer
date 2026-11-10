package com.channelsurf.data.archive

import com.channelsurf.core.archive.ArchiveItem
import com.channelsurf.core.archive.ArchiveParsing
import com.channelsurf.core.archive.ArchiveUrls
import com.channelsurf.core.archive.ParseResult
import com.channelsurf.core.archive.PlayableCandidate
import com.channelsurf.core.archive.PlayableFileSelector
import com.channelsurf.core.archive.SearchPage
import com.channelsurf.core.archive.YearEvidence
import com.channelsurf.core.archive.YearSuggestion
import com.channelsurf.core.model.EraPolicy
import com.channelsurf.core.model.HealthStatus
import com.channelsurf.core.model.MediaAsset
import com.channelsurf.core.net.FailureClass
import com.channelsurf.data.local.ArchiveCacheDao
import com.channelsurf.data.local.ArchiveCacheEntity

sealed interface ArchiveOutcome<out T> {
    data class Success<T>(val value: T, val fromStaleCache: Boolean = false) : ArchiveOutcome<T>
    data class Error(val message: String, val code: Int? = null, val permanent: Boolean = false) : ArchiveOutcome<Nothing>
}

data class ResolvedItem(
    val item: ArchiveItem,
    val candidates: List<PlayableCandidate>,
    val captions: List<String>,
    val year: YearSuggestion,
)

data class HealthCheck(val status: HealthStatus, val statusCode: Int?, val seekable: Boolean, val contentLength: Long?, val message: String)

/**
 * Internet Archive discovery, metadata and file validation. Only the documented endpoints are used
 * (advancedsearch.php, /metadata/{id}, /download/{id}/{file}); the website UI is never scraped.
 */
class ArchiveRepository(
    private val http: ArchiveHttp,
    private val cache: ArchiveCacheDao,
    private val nowUtcMs: () -> Long = System::currentTimeMillis,
) {
    /** Free-text search restricted to video items. */
    suspend fun search(query: String, page: Int = 1, titleOnly: Boolean = false, rows: Int = 25): ArchiveOutcome<SearchPage> {
        val q = query.trim().take(300)
        if (q.isEmpty()) return ArchiveOutcome.Error("Enter a search term")
        val expr = if (titleOnly) {
            // Every word must be in the title: "star trek voyager" finds Voyager episodes, not anything mentioning Star Trek.
            val words = q.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.isNotBlank() && it !in STOP_WORDS }
            if (words.isEmpty()) return ArchiveOutcome.Error("Enter a search term")
            "title:(${words.joinToString(" AND ") { if (it.length == 3 && it.all(Char::isDigit)) "$it*" else it }}) AND mediatype:(movies) AND -collection:(tvarchive) AND -collection:(tvnews)"
        } else "($q) AND mediatype:(movies)"
        val url = ArchiveUrls.advancedSearch(expr, page = page, rows = rows, sort = "downloads desc")
        return when (val r = cachedGet(url, SEARCH_TTL_MS)) {
            is ArchiveOutcome.Success -> when (val p = ArchiveParsing.parseSearch(r.value)) {
                is ParseResult.Ok -> ArchiveOutcome.Success(p.value, r.fromStaleCache)
                is ParseResult.NotFound -> ArchiveOutcome.Error(p.reason)
                is ParseResult.Malformed -> ArchiveOutcome.Error("Unexpected search response: ${p.reason}")
            }
            is ArchiveOutcome.Error -> r
        }
    }

    suspend fun metadata(identifier: String): ArchiveOutcome<ArchiveItem> {
        if (!ArchiveUrls.isValidIdentifier(identifier)) return ArchiveOutcome.Error("Not a valid Archive identifier", permanent = true)
        val url = ArchiveUrls.metadata(identifier)
        return when (val r = cachedGet(url, METADATA_TTL_MS)) {
            is ArchiveOutcome.Success -> when (val p = ArchiveParsing.parseMetadata(r.value, identifier)) {
                is ParseResult.Ok -> if (p.value.isDark) ArchiveOutcome.Error("Item is dark (withdrawn by the Archive)", permanent = true)
                else ArchiveOutcome.Success(p.value, r.fromStaleCache)
                is ParseResult.NotFound -> ArchiveOutcome.Error(p.reason, 404, permanent = true)
                is ParseResult.Malformed -> ArchiveOutcome.Error("Unexpected metadata: ${p.reason}")
            }
            is ArchiveOutcome.Error -> r
        }
    }

    suspend fun resolvePlayableFiles(identifier: String, era: EraPolicy, preferLowBandwidth: Boolean = false): ArchiveOutcome<ResolvedItem> =
        when (val m = metadata(identifier)) {
            is ArchiveOutcome.Error -> m
            is ArchiveOutcome.Success -> {
                val item = m.value
                ArchiveOutcome.Success(
                    ResolvedItem(
                        item = item,
                        candidates = PlayableFileSelector.select(item, preferLowBandwidth),
                        captions = PlayableFileSelector.captionCandidates(item).map { ArchiveUrls.download(item.identifier, it.name) },
                        year = YearEvidence.suggest(item, era),
                    ),
                    m.fromStaleCache,
                )
            }
        }

    /** Network-level check of a media URL: reachable, byte-range seekable, plausible content type. */
    suspend fun healthCheck(asset: MediaAsset): HealthCheck = healthCheckUrl(asset.url)

    suspend fun healthCheckUrl(url: String): HealthCheck {
        val p = http.probe(url)
        val status = when {
            p.ok && p.supportsRange && looksLikeMedia(p.contentType) -> HealthStatus.OK
            p.ok && !p.supportsRange -> HealthStatus.DEGRADED
            p.ok -> HealthStatus.DEGRADED
            p.failure == FailureClass.PERMANENT_MEDIA -> HealthStatus.UNHEALTHY
            p.failure == FailureClass.CLIENT -> HealthStatus.UNHEALTHY
            else -> HealthStatus.DEGRADED // transient: keep airing, recheck later
        }
        return HealthCheck(status, p.statusCode, p.supportsRange, p.contentLength, p.message)
    }

    private fun looksLikeMedia(contentType: String?): Boolean {
        val ct = contentType?.lowercase() ?: return true
        return ct.startsWith("video/") || ct.startsWith("application/octet-stream") || ct.contains("mpegurl") || ct.startsWith("audio/")
    }

    /** Fresh cache hit -> network -> stale cache (offline) -> error. */
    private suspend fun cachedGet(url: String, ttlMs: Long): ArchiveOutcome<String> {
        val now = nowUtcMs()
        val cached = cache.get(url)
        if (cached != null && now - cached.fetchedAtUtcMs < ttlMs) return ArchiveOutcome.Success(cached.body)
        return when (val r = http.getText(url)) {
            is HttpResult.Ok -> {
                cache.put(ArchiveCacheEntity(url, r.body, now, r.etag))
                ArchiveOutcome.Success(r.body)
            }
            is HttpResult.Failed -> if (cached != null && r.failure == FailureClass.TRANSIENT) {
                ArchiveOutcome.Success(cached.body, fromStaleCache = true)
            } else {
                ArchiveOutcome.Error(describe(r), r.code, permanent = r.failure == FailureClass.PERMANENT_MEDIA)
            }
        }
    }

    private fun describe(r: HttpResult.Failed): String = when {
        r.code == 429 -> "The Archive is rate-limiting requests. Try again shortly."
        r.code != null && r.code >= 500 -> "The Archive is temporarily unavailable (HTTP ${r.code})."
        r.code == 403 -> "Access to this item is restricted (HTTP 403)."
        r.code == 404 -> "Not found (HTTP 404)."
        r.code == null -> "Network error: ${r.message}"
        else -> r.message
    }

    suspend fun pruneCache() = cache.pruneBefore(nowUtcMs() - 7 * DAY_MS)

    companion object {
        const val DAY_MS = 86_400_000L
        const val SEARCH_TTL_MS = 30 * 60_000L
        const val METADATA_TTL_MS = 6 * 3_600_000L
        private val STOP_WORDS = setOf("the", "of", "a", "and", "with")
    }
}

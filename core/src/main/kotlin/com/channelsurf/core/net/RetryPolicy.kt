package com.channelsurf.core.net

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

enum class FailureClass {
    /** Retry with backoff: 408, 425, 429, 5xx, network failures. */
    TRANSIENT,

    /** The media is gone or forbidden: mark the asset unhealthy and move on. */
    PERMANENT_MEDIA,

    /** Other 4xx: our request is wrong; do not retry. */
    CLIENT,
}

object HttpFailures {
    fun classify(statusCode: Int): FailureClass = when (statusCode) {
        403, 404, 410, 451 -> FailureClass.PERMANENT_MEDIA
        408, 425, 429 -> FailureClass.TRANSIENT
        in 500..599 -> FailureClass.TRANSIENT
        in 400..499 -> FailureClass.CLIENT
        else -> FailureClass.TRANSIENT
    }

    fun isSuccess(statusCode: Int) = statusCode in 200..299
}

object RetryAfter {
    /** Parses a Retry-After header (delta-seconds or HTTP-date) into a delay in ms. Null when absent/invalid. */
    fun parseDelayMs(header: String?, nowUtcMs: Long): Long? {
        val v = header?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        v.toLongOrNull()?.let { return if (it >= 0) it * 1000 else null }
        return try {
            val at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            (at - nowUtcMs).coerceAtLeast(0)
        } catch (e: Exception) {
            null
        }
    }
}

/** Bounded exponential backoff with "equal jitter". Retry-After always wins (capped). */
class BackoffPolicy(
    val baseDelayMs: Long = 1_000,
    val maxDelayMs: Long = 60_000,
    val maxAttempts: Int = 5,
    val maxRetryAfterMs: Long = 10 * 60_000,
    private val random: Random = Random.Default,
) {
    init {
        require(baseDelayMs > 0 && maxDelayMs >= baseDelayMs && maxAttempts >= 1)
    }

    /** [attempt] is the number of failures so far (1 = first failure). */
    fun shouldRetry(attempt: Int): Boolean = attempt < maxAttempts

    fun delayMs(attempt: Int, retryAfterMs: Long? = null): Long {
        if (retryAfterMs != null) return retryAfterMs.coerceIn(0, maxRetryAfterMs)
        val exp = (attempt - 1).coerceIn(0, 30)
        val ceiling = (baseDelayMs shl exp).coerceAtMost(maxDelayMs).coerceAtLeast(baseDelayMs)
        val half = ceiling / 2
        return half + random.nextLong(0, ceiling - half + 1)
    }
}

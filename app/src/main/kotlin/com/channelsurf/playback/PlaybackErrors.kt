package com.channelsurf.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.channelsurf.core.net.FailureClass
import com.channelsurf.core.net.HttpFailures
import com.channelsurf.core.net.RetryAfter

sealed interface PlaybackFailure {
    val description: String

    data class Http(val code: Int, val failure: FailureClass, val retryAfterMs: Long?) : PlaybackFailure {
        override val description get() = "HTTP $code"
    }
    data class Network(override val description: String) : PlaybackFailure
    /** Unsupported codec/container or decoder failure: try an alternate verified rendition. */
    data class Decoder(override val description: String) : PlaybackFailure
    data class Other(override val description: String) : PlaybackFailure
}

object PlaybackErrors {
    @OptIn(UnstableApi::class)
    fun classify(e: PlaybackException, nowUtcMs: Long): PlaybackFailure {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                val retryAfter = cause.headerFields.entries
                    .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }?.value?.firstOrNull()
                return PlaybackFailure.Http(
                    cause.responseCode, HttpFailures.classify(cause.responseCode),
                    RetryAfter.parseDelayMs(retryAfter, nowUtcMs),
                )
            }
            cause = cause.cause
        }
        return when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT,
            -> PlaybackFailure.Network(e.errorCodeName)

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            -> PlaybackFailure.Decoder(e.errorCodeName)

            else -> PlaybackFailure.Other(e.errorCodeName)
        }
    }
}

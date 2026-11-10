package com.channelsurf.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.channelsurf.data.archive.ArchiveHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import kotlin.coroutines.resume

/**
 * Curation-time proof that a file really plays on THIS device before it can air: prepare it with a real
 * (muted, surfaceless) ExoPlayer, read the authoritative duration, then seek to the middle and confirm the
 * player lands there. This catches codecs the emulator cannot decode and servers that break seeking.
 */
@OptIn(UnstableApi::class)
class PlaybackVerifier(private val context: Context, private val callFactory: Call.Factory) {

    data class Result(
        val ok: Boolean,
        val durationMs: Long,
        val seekable: Boolean,
        val videoFormat: String?,
        val message: String,
    )

    suspend fun verify(url: String, timeoutMs: Long = 30_000): Result = withContext(Dispatchers.Main) {
        val p = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(callFactory).setUserAgent(ArchiveHttp.USER_AGENT)))
            .build()
        p.volume = 0f
        try {
            p.setMediaItem(MediaItem.fromUri(url))
            p.prepare()
            when (val r = withTimeoutOrNull(timeoutMs) { awaitReadyOrError(p) }) {
                null -> return@withContext Result(false, 0, false, null, "Timed out preparing media")
                is PlaybackException -> return@withContext Result(false, 0, false, null, "Cannot play: ${r.errorCodeName}")
            }
            val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            val format = p.videoFormat?.let { "${it.sampleMimeType} ${it.width}x${it.height}" }
            if (duration <= 0) return@withContext Result(false, 0, false, format, "Media has no known duration")
            if (!p.isCurrentMediaItemSeekable) return@withContext Result(false, duration, false, format, "Player reports media is not seekable")
            val target = duration / 2
            p.seekTo(target)
            when (val r = withTimeoutOrNull(timeoutMs) { awaitReadyOrError(p) }) {
                null -> return@withContext Result(false, duration, false, format, "Timed out after mid-file seek")
                is PlaybackException -> return@withContext Result(false, duration, false, format, "Seek failed: ${r.errorCodeName}")
            }
            val landed = kotlin.math.abs(p.currentPosition - target) < 10_000
            Result(landed, duration, landed, format, if (landed) "Verified: plays and seeks" else "Seek landed far from target")
        } finally {
            p.release()
        }
    }

    /** Returns Unit when READY, or the PlaybackException on error. */
    private suspend fun awaitReadyOrError(p: ExoPlayer): Any = suspendCancellableCoroutine { cont ->
        if (p.playbackState == Player.STATE_READY) { cont.resume(Unit); return@suspendCancellableCoroutine }
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && cont.isActive) { p.removeListener(this); cont.resume(Unit) }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (cont.isActive) { p.removeListener(this); cont.resume(error) }
            }
        }
        p.addListener(l)
        cont.invokeOnCancellation { p.removeListener(l) }
    }
}

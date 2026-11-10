package com.channelsurf.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.channelsurf.cast.CastManager
import com.channelsurf.cast.RokuDevice
import com.channelsurf.cast.RokuManager
import com.channelsurf.core.model.HealthStatus
import com.google.android.gms.cast.framework.CastContext
import com.channelsurf.core.net.BackoffPolicy
import com.channelsurf.core.net.FailureClass
import com.channelsurf.core.schedule.CatchUpPolicy
import com.channelsurf.core.schedule.DriftPolicy
import com.channelsurf.core.schedule.SlateKind
import com.channelsurf.core.schedule.TuneResolution
import com.channelsurf.core.time.BroadcastClock
import com.channelsurf.core.tuning.TuneSequencer
import com.channelsurf.data.archive.ArchiveHttp
import com.channelsurf.data.catalog.CatalogRepository
import com.channelsurf.data.net.NetworkMonitor
import com.channelsurf.data.prefs.Settings
import com.channelsurf.data.schedule.ScheduleRepository
import com.channelsurf.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call

/**
 * Playback state machine. GUIDE_OPEN is deliberately NOT a phase: overlays are UI state, and opening the
 * guide never touches the player.
 */
enum class PlayerPhase {
    BOOTING, CATALOG_LOADING, TUNING, BUFFERING, PLAYING, RECOVERABLE_ERROR, NO_SIGNAL, OFFLINE,
    /** Metered network with Wi-Fi-only enabled. */
    DATA_BLOCKED,
    /** App in background: decoder released, channel clocks keep running mathematically. */
    SUSPENDED,
}

enum class TuneReason { USER, STARTUP, RESUME, RECONNECT, BLOCK_END, RETRY, TAKEDOWN }

data class PlayerUiState(
    val phase: PlayerPhase = PlayerPhase.BOOTING,
    val channelId: String? = null,
    val resolution: TuneResolution? = null,
    val message: String? = null,
    val muted: Boolean = false,
    /** Changes on every tune; the UI keys the static transition on it. */
    val tuneToken: Long = 0,
    val tuneReason: TuneReason = TuneReason.STARTUP,
    val captionsAvailable: Boolean = false,
    /** Name of the TV we're casting to, or null when playing on this device. */
    val castingTo: String? = null,
) {
    val slateKind: SlateKind? get() = (resolution as? TuneResolution.Slate)?.kind
}

/**
 * Owns the single ExoPlayer instance and enforces broadcast rules: no pause, no seek UI, always join live,
 * always catch up after stalls, advance at block end, substitute on failure without moving the clock.
 * All public methods must be called on the main thread.
 */
@OptIn(UnstableApi::class)
class PlayerController(
    context: Context,
    private val scope: CoroutineScope,
    private val schedule: ScheduleRepository,
    private val catalog: CatalogRepository,
    private val clock: BroadcastClock,
    private val settings: StateFlow<Settings>,
    private val network: NetworkMonitor,
    private val diagnostics: DiagnosticsLog,
    callFactory: Call.Factory,
    private val castManager: CastManager? = null,
    private val rokuManager: RokuManager? = null,
    private val backoff: BackoffPolicy = BackoffPolicy(baseDelayMs = 2_000, maxDelayMs = 30_000, maxAttempts = 6),
) {
    val player: ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                OkHttpDataSource.Factory(callFactory).setUserAgent(ArchiveHttp.USER_AGENT)
            )
        )
        // Start on 1 s of video (fast channel changes); keep up to 45 s ahead, which also pre-loads the next
        // commercial or segment queued behind the current one.
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 45_000, 1_000, 2_500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        // Keep Wi-Fi and CPU awake while streaming with the screen off / app in the background.
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    /** The player currently carrying the broadcast: the local ExoPlayer, or the CastPlayer while casting. */
    private var active: Player = player
    private var castPlayer: CastPlayer? = null
    private var internalSeek = false

    private val _roku = MutableStateFlow<RokuDevice?>(null)
    /** The Roku TV carrying the broadcast (via the Channel Surfer receiver), or null. */
    val rokuTarget: StateFlow<RokuDevice?> = _roku

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private val sequencer = TuneSequencer()
    private var tuneJob: Job? = null
    private var retryJob: Job? = null
    private var current: TuneResolution? = null
    private var suspended = false
    private var retryAttempt = 0
    private var catchUpSeeks = 0
    private var lastDriftCheckUtcMs = 0L
    private var preparedAtUptime = 0L
    private val triedAlternates = HashMap<String, Int>()

    /** What's loaded in the local ExoPlayer, in playlist order: [0] is on screen, [1] is pre-loading. */
    private data class Queued(val res: TuneResolution.Play, val clipStartMs: Long)
    private val playlist = ArrayList<Queued>()
    private var preloadJob: Job? = null
    private val preloadFailed = HashSet<String>()
    private val startup = CatchUpPolicy.StartupEstimator()
    private var speed = 1f
    private var awaitingFirstReady = false
    private val isLocal: Boolean get() = active === player

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> if (_state.value.phase == PlayerPhase.PLAYING) setPhase(PlayerPhase.BUFFERING)
                Player.STATE_READY -> onReady()
                Player.STATE_ENDED -> onMediaEnded()
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Audio-focus loss (a phone call) pauses the player; when it resumes we must rejoin live.
            if (isPlaying) { if (isLocal) keepInSync() else reconcile(adaptiveTolerance()) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) segue()
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Live TV can't be paused: a pause from the TV remote / Google Home is undone and we rejoin live.
            if (!playWhenReady && isCasting && current is TuneResolution.Play) {
                active.playWhenReady = true
                reconcile(DriftPolicy.TUNE_TOLERANCE_MS)
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            if (internalSeek) { internalSeek = false; return }
            // Someone seeked from outside the app (e.g. the Cast receiver's controls): snap back to the live position.
            if (isLocal) keepInSync(forceSeekOverMs = DriftPolicy.TUNE_TOLERANCE_MS) else reconcile(DriftPolicy.TUNE_TOLERANCE_MS)
        }
    }

    private val isCasting: Boolean get() = castPlayer != null && active === castPlayer

    init {
        player.addListener(listener)
        player.playWhenReady = true
        castManager?.whenReady { ctx -> attachCast(ctx) }
        scope.launch { monitorLoop() }
        scope.launch {
            network.status.collect { st ->
                if (st.online && _state.value.phase == PlayerPhase.OFFLINE && !suspended) {
                    _state.value.channelId?.let { tune(it, TuneReason.RECONNECT) }
                } else if (!st.online && current is TuneResolution.Play && !suspended && _roku.value == null) {
                    goOffline()
                }
            }
        }
        scope.launch {
            settings.map { it.captions }.distinctUntilChanged().collect { applyCaptions(it) }
        }
        scope.launch {
            catalog.takedownEvents.collect { identifier ->
                schedule.blockIdentifierNow(identifier)
                val playing = (current as? TuneResolution.Play)?.asset?.archiveIdentifier
                if (playing == identifier) {
                    diagnostics.warn("rights.takedown", "Stopping blocked item $identifier immediately")
                    _state.value.channelId?.let { tune(it, TuneReason.TAKEDOWN) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ public API

    fun tune(channelId: String, reason: TuneReason = TuneReason.USER) {
        val token = sequencer.begin()
        tuneJob?.cancel()
        retryJob?.cancel()
        if (reason == TuneReason.USER || reason == TuneReason.STARTUP) retryAttempt = 0
        suspended = false
        current = null
        catchUpSeeks = 0
        preloadJob?.cancel()
        playlist.clear()
        setSpeed(1f)
        active.stop()
        _state.update {
            it.copy(
                phase = PlayerPhase.TUNING, channelId = channelId, resolution = null, message = null,
                tuneToken = token, tuneReason = reason, captionsAvailable = false,
            )
        }
        tuneJob = scope.launch {
            val res = try {
                schedule.current(channelId, clock.nowUtcMs())
            } catch (e: IllegalArgumentException) {
                if (sequencer.isCurrent(token)) setPhase(PlayerPhase.NO_SIGNAL, "Channel not found")
                return@launch
            }
            if (!sequencer.isCurrent(token) || suspended) return@launch
            applyResolution(res)
        }
    }

    fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
        if (isCasting) runCatching { castManager?.castContext?.sessionManager?.currentCastSession?.isMute = muted }
        _state.update { it.copy(muted = muted) }
    }

    /** App went to background: release the decoder. Channel clocks keep running. */
    fun suspendPlayback() {
        // While casting, the TV keeps the broadcast going; only the local decoder matters here.
        if (isCasting || _roku.value != null) { player.stop(); return }
        suspended = true
        sequencer.begin() // invalidate in-flight work
        tuneJob?.cancel()
        retryJob?.cancel()
        current = null
        active.stop()
        player.playWhenReady = false // the notification card then shows "power on"
        _state.update { it.copy(phase = PlayerPhase.SUSPENDED, resolution = null) }
    }

    /** App returned: never resume a paused frame; recompute and join the live position. */
    fun resumeLive() {
        if (isCasting && current != null) { reconcile(DriftPolicy.TUNE_TOLERANCE_MS); return }
        if (_roku.value != null && current != null) return
        suspended = false
        _state.value.channelId?.let { tune(it, TuneReason.RESUME) }
    }

    fun setCatalogLoading() = setPhase(PlayerPhase.CATALOG_LOADING)

    fun release() {
        player.removeListener(listener)
        player.release()
        castPlayer?.let {
            it.setSessionAvailabilityListener(null)
            it.removeListener(listener)
            it.release() // the Cast session itself stays up; the TV keeps playing the current program
        }
        castPlayer = null
    }

    // ------------------------------------------------------------------ Roku

    /** Sends the broadcast to a Roku running the Channel Surfer receiver; the phone becomes the remote. */
    fun startRoku(device: RokuDevice) {
        if (isCasting) castManager?.disconnect()
        _roku.value = device
        suspended = false
        _state.update { it.copy(castingTo = device.name) }
        _state.value.channelId?.let { tune(it, TuneReason.RESUME) }
    }

    fun stopRoku() {
        val d = _roku.value ?: return
        _roku.value = null
        rokuRefillJob?.cancel()
        rokuQueueJob?.cancel()
        rokuQueueChannel = null
        scope.launch { rokuManager?.disconnect(d) }
        _state.update { it.copy(castingTo = null) }
        _state.value.channelId?.let { tune(it, TuneReason.RESUME) }
    }

    private var rokuQueueJob: Job? = null
    private var rokuRefillJob: Job? = null
    @Volatile private var rokuQueueChannel: String? = null
    @Volatile private var rokuQueueUntilUtcMs: Long = 0

    private fun sendToRoku(device: RokuDevice, res: TuneResolution) {
        val token = sequencer.current
        active.stop()
        _state.update {
            it.copy(
                phase = if (res is TuneResolution.Play) PlayerPhase.PLAYING else PlayerPhase.NO_SIGNAL,
                resolution = res, message = null, castingTo = device.name,
            )
        }
        // The Roku already has this channel's schedule and moves to the next program by itself.
        val reason = _state.value.tuneReason
        val now = clock.nowUtcMs()
        if (reason == TuneReason.BLOCK_END && rokuQueueChannel == res.channel.id && rokuQueueUntilUtcMs > now + 15 * 60_000L) return
        val mgr = rokuManager ?: return
        rokuQueueJob?.cancel()
        rokuQueueJob = scope.launch {
            val ok = sendRokuQueue(mgr, device, res.channel.id)
            if (!sequencer.isCurrent(token)) return@launch
            if (!ok) {
                _state.update { it.copy(phase = PlayerPhase.RECOVERABLE_ERROR, message = "Can't reach ${device.name}. Is it on and on the same Wi-Fi? Retrying…") }
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(5_000)
                    if (sequencer.isCurrent(token) && _roku.value == device) _state.value.channelId?.let { tune(it, TuneReason.RETRY) }
                }
            }
        }
        startRokuRefill(device)
    }

    /** Builds the next [ROKU_QUEUE_MS] of this channel (resolved exactly as the phone would play it) and sends it. */
    private suspend fun sendRokuQueue(mgr: RokuManager, device: RokuDevice, channelId: String): Boolean {
        val from = clock.nowUtcMs()
        val blocks = try { schedule.window(channelId, from, from + ROKU_QUEUE_MS) } catch (e: Exception) { return false }
        val items = ArrayList<RokuManager.QueueItem>()
        var channel: com.channelsurf.core.model.Channel? = null
        for (b in blocks.take(ROKU_QUEUE_MAX_ITEMS)) {
            val at = maxOf(b.startUtcMs, from)
            val r = try { schedule.current(channelId, at) } catch (e: Exception) { continue }
            channel = r.channel
            items += when (r) {
                is TuneResolution.Play -> RokuManager.QueueItem(
                    startUtcMs = b.startUtcMs,
                    endUtcMs = minOf(b.endUtcMs, r.playUntilUtcMs).coerceAtLeast(b.startUtcMs + 1_000),
                    fileOffsetMs = r.expectedFilePositionMs(b.startUtcMs),
                    hls = r.asset.container == "hls",
                    title = b.headline, url = r.mediaUrl,
                )
                is TuneResolution.Slate -> RokuManager.QueueItem(
                    startUtcMs = b.startUtcMs, endUtcMs = b.endUtcMs, fileOffsetMs = 0, hls = false,
                    title = when {
                        b.displayTitle.startsWith(com.channelsurf.core.model.ScheduleIds.UP_NEXT) -> b.displayTitle.uppercase()
                        r.kind == SlateKind.STATION_BREAK -> r.channel.displayName.uppercase()
                        else -> "OFF AIR"
                    }, url = null,
                )
            }
        }
        val ch = channel ?: return false
        val ok = mgr.sendQueue(device, ch.displayNumber, ch.displayName, items) { clock.nowUtcMs() }
        if (ok) {
            rokuQueueChannel = channelId
            rokuQueueUntilUtcMs = items.lastOrNull()?.endUtcMs ?: 0
        }
        return ok
    }

    /** While playing on the Roku, top up its schedule so it never runs out. */
    private fun startRokuRefill(device: RokuDevice) {
        if (rokuRefillJob?.isActive == true) return
        rokuRefillJob = scope.launch {
            while (_roku.value == device) {
                delay(ROKU_REFILL_MS)
                if (_roku.value != device) break
                val ch = _state.value.channelId ?: continue
                rokuManager?.let { sendRokuQueue(it, device, ch) }
            }
        }
    }

    // ------------------------------------------------------------------ casting

    private fun attachCast(ctx: CastContext) {
        val cp = try { CastPlayer(ctx) } catch (e: Exception) {
            diagnostics.warn("cast", "CastPlayer unavailable: ${e.javaClass.simpleName}")
            return
        }
        cp.addListener(listener)
        cp.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = switchTo(cp)
            override fun onCastSessionUnavailable() = switchTo(player)
        })
        castPlayer = cp
        if (cp.isCastSessionAvailable) switchTo(cp)
    }

    /** Moves the broadcast between the phone and the TV, rejoining the LIVE position on the new player. */
    private fun switchTo(target: Player) {
        if (active === target) return
        runCatching { active.stop() }
        preloadJob?.cancel()
        playlist.clear()
        setSpeed(1f)
        active = target
        val casting = isCasting
        if (casting) _roku.value = null
        _state.update { it.copy(castingTo = if (casting) castManager?.status?.value?.connectedDeviceName ?: "TV" else _roku.value?.name) }
        if (casting) suspended = false
        val ch = _state.value.channelId ?: return
        if (!suspended) tune(ch, TuneReason.RESUME)
    }

    // ------------------------------------------------------------------ internals

    private fun applyResolution(res: TuneResolution) {
        current = res
        _roku.value?.let { return sendToRoku(it, res) }
        when (res) {
            is TuneResolution.Slate -> {
                active.stop()
                active.clearMediaItems()
                _state.update { it.copy(phase = PlayerPhase.NO_SIGNAL, resolution = res, message = null) }
            }
            is TuneResolution.Play -> {
                val net = network.status.value
                val s = settings.value
                // While casting the TV streams directly, so the phone's network type doesn't matter.
                if (!isCasting && !net.online) return goOffline()
                if (!isCasting && net.metered && s.wifiOnly) {
                    _state.update {
                        it.copy(phase = PlayerPhase.DATA_BLOCKED, resolution = res, message = "Wi-Fi only is on. Connect to Wi-Fi to watch.")
                    }
                    return
                }
                val local = isLocal
                val builder = mediaItemBuilder(res, s)
                // Locally the item stops exactly at the block's end, so the pre-loaded next item follows seamlessly.
                if (local) builder.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setEndPositionMs(clipEndMs(res)).build()
                )
                // Recompute at prepare time, and start ahead by the time this connection usually takes to get going,
                // so we land on the live point instead of behind it (which used to trigger seek after seek).
                val lead = if (local) startup.estimateMs else 0L
                val position = (res.expectedFilePositionMs(clock.nowUtcMs()) + lead).coerceAtLeast(0)
                    .let { if (local) it.coerceAtMost((clipEndMs(res) - 1_000).coerceAtLeast(0)) else it }
                active.setMediaItem(builder.build(), position)
                active.prepare()
                active.playWhenReady = true
                preparedAtUptime = SystemClock.uptimeMillis()
                awaitingFirstReady = true
                if (local) { playlist += Queued(res, 0); schedulePreload(res) }
                _state.update {
                    it.copy(phase = PlayerPhase.BUFFERING, resolution = res, message = null, captionsAvailable = res.asset.captionUrl != null)
                }
                diagnostics.log(
                    DiagnosticsLog.Event(
                        clock.nowUtcMs(), DiagnosticsLog.Level.INFO, "tune",
                        "${res.channel.displayNumber} ${res.block.displayTitle} @${position}ms (lead ${lead}ms)" + if (res.isSubstitute) " (substitute)" else "",
                        channelId = res.channel.id, archiveIdentifier = res.asset.archiveIdentifier,
                        fileName = res.asset.fileName, codec = res.asset.codec,
                    )
                )
            }
        }
    }

    private fun clipEndMs(res: TuneResolution.Play): Long = res.expectedFilePositionMs(res.playUntilUtcMs).coerceAtLeast(1)

    private fun mediaItemBuilder(res: TuneResolution.Play, s: Settings): MediaItem.Builder {
        val url = chooseUrl(res, s)
        val builder = MediaItem.Builder().setUri(url).setMediaId(res.asset.id)
            .setMimeType(
                when (res.asset.container) {
                    "webm" -> MimeTypes.VIDEO_WEBM
                    "hls" -> MimeTypes.APPLICATION_M3U8
                    else -> MimeTypes.VIDEO_MP4
                }
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(res.block.headline)
                    .setArtist("${res.channel.displayNumber} ${res.channel.displayName}")
                    .build()
            )
        res.asset.captionUrl?.let { cap ->
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(Uri.parse(cap))
                        .setMimeType(if (cap.endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage("en")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        return builder
    }

    /**
     * Queues the next block behind the current one. ExoPlayer starts loading it as soon as the current item is
     * buffered to its end, so commercials and the show's next segment are already on hand when they're due.
     */
    private fun schedulePreload(res: TuneResolution.Play) {
        preloadJob?.cancel()
        if (!isLocal) return
        val token = sequencer.current
        preloadJob = scope.launch {
            // Let the item on screen have the bandwidth first.
            delay(PRELOAD_DELAY_MS)
            if (!sequencer.isCurrent(token) || current !== res || playlist.size != 1) return@launch
            if (res.playUntilUtcMs < res.block.endUtcMs - 1_000) return@launch // file runs short: a slate follows
            val next = try { schedule.current(res.channel.id, res.block.endUtcMs) } catch (e: Exception) { return@launch }
            if (!sequencer.isCurrent(token) || current !== res || playlist.size != 1 || !isLocal) return@launch
            if (next !is TuneResolution.Play || next.asset.id in preloadFailed) return@launch
            val s = settings.value
            val net = network.status.value
            if (!net.online || (net.metered && s.wifiOnly)) return@launch
            val clipStart = next.filePositionMs.coerceAtLeast(0)
            val clipEnd = clipEndMs(next)
            if (clipEnd <= clipStart + 500) return@launch
            val item = mediaItemBuilder(next, s).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clipStart).setEndPositionMs(clipEnd).build()
            ).build()
            player.addMediaItem(item)
            playlist += Queued(next, clipStart)
        }
    }

    /** The player moved on to the pre-loaded item, with no gap and no reload. */
    private fun segue() {
        if (!isLocal || playlist.size < 2 || player.currentMediaItemIndex != 1) return
        player.removeMediaItem(0)
        playlist.removeAt(0)
        val res = playlist[0].res
        current = res
        catchUpSeeks = 0
        retryAttempt = 0
        preparedAtUptime = SystemClock.uptimeMillis()
        _state.update {
            it.copy(
                phase = PlayerPhase.PLAYING, resolution = res, message = null,
                captionsAvailable = res.asset.captionUrl != null, tuneReason = TuneReason.BLOCK_END,
            )
        }
        diagnostics.log(
            DiagnosticsLog.Event(
                clock.nowUtcMs(), DiagnosticsLog.Level.INFO, "segue", "${res.channel.displayNumber} ${res.block.displayTitle} (pre-loaded)",
                channelId = res.channel.id, archiveIdentifier = res.asset.archiveIdentifier,
                fileName = res.asset.fileName, codec = res.asset.codec,
            )
        )
        keepInSync()
        schedulePreload(res)
    }

    /** Position in the media FILE of what's on screen (clipped items report positions from their clip start). */
    private fun filePositionMs(): Long {
        val clip = if (isLocal) playlist.getOrNull(player.currentMediaItemIndex)?.clipStartMs ?: 0L else 0L
        return active.currentPosition + clip
    }

    private fun hasQueuedNext(): Boolean = isLocal && playlist.size >= 2

    private fun setSpeed(s: Float) {
        if (s == speed) return
        speed = s
        runCatching { player.setPlaybackSpeed(s) }
    }

    /**
     * Local sync: nudge the speed for small drift, seek only for big drift. Never decides mid-buffering.
     * [forceSeekOverMs] is for outside seeks (media controls), which should snap back right away.
     */
    private fun keepInSync(forceSeekOverMs: Long? = null) {
        val res = current as? TuneResolution.Play ?: return
        if (!isLocal) return reconcile(adaptiveTolerance())
        val now = clock.nowUtcMs()
        lastDriftCheckUtcMs = now
        if (res.isFinishedAt(now)) {
            if (hasQueuedNext()) {
                // The pre-loaded item takes over by itself; if playback lags well behind, jump to it.
                if (now - res.playUntilUtcMs > SEGUE_GRACE_MS) {
                    val next = playlist[1]
                    internalSeek = true
                    player.seekTo(1, (next.res.expectedFilePositionMs(now) - next.clipStartMs).coerceAtLeast(0))
                }
            } else tune(res.channel.id, TuneReason.BLOCK_END)
            return
        }
        if (player.playbackState != Player.STATE_READY) return
        val drift = filePositionMs() - res.expectedFilePositionMs(now)
        val action = if (forceSeekOverMs != null && kotlin.math.abs(drift) > forceSeekOverMs) CatchUpPolicy.Action.Seek(drift)
        else CatchUpPolicy.decide(drift, SystemClock.uptimeMillis() - preparedAtUptime, adaptiveTolerance(), speed)
        when (action) {
            is CatchUpPolicy.Action.Speed -> {
                setSpeed(action.speed)
                if (kotlin.math.abs(drift) < 1_000) catchUpSeeks = 0
            }
            is CatchUpPolicy.Action.Seek -> {
                catchUpSeeks++
                setSpeed(1f)
                val clipStart = playlist.getOrNull(player.currentMediaItemIndex)?.clipStartMs ?: 0L
                // Land a little past live: the seek itself costs a moment of loading.
                val target = res.expectedFilePositionMs(now) + startup.estimateMs.coerceAtMost(2_500)
                internalSeek = true
                player.seekTo((target - clipStart).coerceAtLeast(0))
                diagnostics.log(
                    DiagnosticsLog.Event(
                        now, DiagnosticsLog.Level.INFO, "drift", "Corrected ${action.driftMs} ms",
                        channelId = res.channel.id, archiveIdentifier = res.asset.archiveIdentifier,
                        seekOutcome = "seek to $target",
                    )
                )
            }
        }
    }

    private fun chooseUrl(res: TuneResolution.Play, s: Settings): String {
        if (s.dataSaver && !res.isSubstitute) {
            res.asset.alternateUrls.firstOrNull { it.contains("512kb", ignoreCase = true) }?.let { return it }
        }
        return res.mediaUrl
    }

    private fun onReady() {
        retryAttempt = 0
        val res = current as? TuneResolution.Play ?: return
        if (awaitingFirstReady) {
            awaitingFirstReady = false
            if (isLocal) startup.record(SystemClock.uptimeMillis() - preparedAtUptime)
        }
        // After the initial buffer (or any stall) catch up to where the broadcast is NOW.
        if (isLocal) keepInSync()
        else reconcile(if (_state.value.phase == PlayerPhase.BUFFERING && catchUpSeeks == 0) DriftPolicy.TUNE_TOLERANCE_MS else adaptiveTolerance())
        if (_state.value.phase != PlayerPhase.PLAYING) {
            _state.update { it.copy(phase = PlayerPhase.PLAYING, message = null) }
            diagnostics.log(
                DiagnosticsLog.Event(
                    clock.nowUtcMs(), DiagnosticsLog.Level.INFO, "ready",
                    "Playing after ${SystemClock.uptimeMillis() - preparedAtUptime} ms",
                    channelId = res.channel.id, archiveIdentifier = res.asset.archiveIdentifier,
                    codec = (active as? ExoPlayer)?.videoFormat?.sampleMimeType ?: res.asset.codec,
                )
            )
        }
    }

    /** Drift reconciliation: an internal seek only. Viewers are never offered seeking. */
    private fun reconcile(toleranceMs: Long) {
        val res = current as? TuneResolution.Play ?: return
        if (active.playbackState == Player.STATE_IDLE) return
        val now = clock.nowUtcMs()
        lastDriftCheckUtcMs = now
        when (val d = DriftPolicy.evaluate(res, now, active.currentPosition, toleranceMs)) {
            DriftPolicy.Decision.InSync -> catchUpSeeks = 0
            is DriftPolicy.Decision.Seek -> {
                catchUpSeeks++
                internalSeek = true
                active.seekTo(d.toFilePositionMs)
                diagnostics.log(
                    DiagnosticsLog.Event(
                        now, DiagnosticsLog.Level.INFO, "drift", "Corrected ${d.driftMs} ms",
                        channelId = res.channel.id, archiveIdentifier = res.asset.archiveIdentifier,
                        seekOutcome = "seek to ${d.toFilePositionMs}",
                    )
                )
            }
            DriftPolicy.Decision.AdvanceToNextBlock -> tune(res.channel.id, TuneReason.BLOCK_END)
        }
    }

    /** Repeated catch-up seeks on a slow link widen the tolerance so we do not seek-thrash. */
    private fun adaptiveTolerance(): Long =
        (DriftPolicy.DEFAULT_TOLERANCE_MS * (1 + catchUpSeeks)).coerceAtMost(MAX_TOLERANCE_MS)

    private fun onMediaEnded() {
        val res = current as? TuneResolution.Play ?: return
        val now = clock.nowUtcMs()
        // Locally the item is clipped to its block, so reaching the clip end only means we ran a little ahead
        // of the clock; the monitor moves on when the block's time is up.
        if (isLocal && filePositionMs() >= clipEndMs(res) - EARLY_END_GRACE_MS) return
        if (now < res.playUntilUtcMs - EARLY_END_GRACE_MS) {
            // The file is shorter than its slot (bad metadata or truncated upload). Never loop it.
            diagnostics.warn("media.ended_early", "${res.asset.archiveIdentifier} ended ${res.playUntilUtcMs - now} ms early")
            val slate = TuneResolution.Slate(res.channel, res.block, now, SlateKind.TECHNICAL_DIFFICULTIES, res.playUntilUtcMs)
            current = slate
            active.stop()
            _state.update { it.copy(phase = PlayerPhase.NO_SIGNAL, resolution = slate) }
        } else {
            tune(res.channel.id, TuneReason.BLOCK_END)
        }
    }

    private fun handleError(e: PlaybackException) {
        val res = current as? TuneResolution.Play ?: return
        failedQueuedItem(e)?.let { bad ->
            // The pre-loaded NEXT item failed, not the one on screen. Skip pre-loading it and carry on live;
            // when its turn comes, the normal tune path substitutes or retries.
            preloadFailed += bad.asset.id
            diagnostics.warn("preload.failed", "${bad.asset.archiveIdentifier}: ${e.errorCodeName}")
            tune(res.channel.id, TuneReason.RETRY)
            return
        }
        val failure = PlaybackErrors.classify(e, clock.nowUtcMs())
        diagnostics.log(
            DiagnosticsLog.Event(
                clock.nowUtcMs(), DiagnosticsLog.Level.ERROR, "playback.error", failure.description,
                channelId = res.channel.id, archiveIdentifier = res.asset.archiveIdentifier, fileName = res.asset.fileName,
                statusCode = (failure as? PlaybackFailure.Http)?.code, codec = res.asset.codec,
            )
        )
        val channelId = res.channel.id
        when {
            failure is PlaybackFailure.Http && failure.failure == FailureClass.PERMANENT_MEDIA ||
                failure is PlaybackFailure.Http && failure.failure == FailureClass.CLIENT -> {
                markUnhealthy(res, (failure as PlaybackFailure.Http).code)
                tune(channelId, TuneReason.RETRY) // resolver substitutes on the same clock
            }
            failure is PlaybackFailure.Decoder -> {
                val alternates = res.asset.alternateUrls
                val next = triedAlternates[res.asset.id] ?: 0
                if (next < alternates.size) {
                    triedAlternates[res.asset.id] = next + 1
                    schedule.useAlternateUrl(res.asset.id, alternates[next])
                    diagnostics.warn("playback.alternate", "Trying alternate rendition ${next + 1}/${alternates.size} for ${res.asset.id}")
                } else {
                    markUnhealthy(res, null)
                }
                tune(channelId, TuneReason.RETRY)
            }
            !network.status.value.online -> goOffline()
            failure is PlaybackFailure.Http || failure is PlaybackFailure.Network -> scheduleRetry(res, (failure as? PlaybackFailure.Http)?.retryAfterMs)
            else -> showTechnicalDifficulties(res)
        }
    }

    private fun failedQueuedItem(e: PlaybackException): TuneResolution.Play? {
        if (!isLocal || playlist.size < 2) return null
        val pid = (e as? ExoPlaybackException)?.mediaPeriodId ?: return null
        val tl = player.currentTimeline
        val p = tl.getIndexOfPeriod(pid.periodUid)
        if (p == C.INDEX_UNSET) return null
        val window = tl.getPeriod(p, Timeline.Period()).windowIndex
        if (window == player.currentMediaItemIndex) return null
        return playlist.getOrNull(window)?.res
    }

    private fun scheduleRetry(res: TuneResolution.Play, retryAfterMs: Long?) {
        retryAttempt++
        if (!backoff.shouldRetry(retryAttempt)) {
            schedulingHealthDegraded(res)
            return showTechnicalDifficulties(res)
        }
        val wait = backoff.delayMs(retryAttempt, retryAfterMs)
        _state.update { it.copy(phase = PlayerPhase.RECOVERABLE_ERROR, message = "Signal lost. Retrying in ${wait / 1000 + 1}s…") }
        val token = sequencer.current
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(wait)
            if (sequencer.isCurrent(token) && !suspended) tune(res.channel.id, TuneReason.RETRY) // rejoins LIVE, not the stalled spot
        }
    }

    private fun schedulingHealthDegraded(res: TuneResolution.Play) {
        scope.launch { catalog.markHealth(res.asset.id, HealthStatus.DEGRADED, null) }
    }

    private fun showTechnicalDifficulties(res: TuneResolution.Play) {
        val slate = TuneResolution.Slate(res.channel, res.block, clock.nowUtcMs(), SlateKind.TECHNICAL_DIFFICULTIES, res.playUntilUtcMs)
        current = slate
        active.stop()
        _state.update { it.copy(phase = PlayerPhase.NO_SIGNAL, resolution = slate, message = null) }
    }

    private fun markUnhealthy(res: TuneResolution.Play, code: Int?) {
        schedule.markUnhealthyNow(res.asset.id)
        scope.launch { catalog.markHealth(res.asset.id, HealthStatus.UNHEALTHY, code) }
    }

    private fun goOffline() {
        active.stop()
        _state.update { it.copy(phase = PlayerPhase.OFFLINE, message = "No signal — check your connection") }
    }

    private suspend fun monitorLoop() {
        while (scope.isActive) {
            delay(1_000)
            if (suspended) continue
            val now = clock.nowUtcMs()
            when (val r = current) {
                is TuneResolution.Play -> when {
                    isLocal && _roku.value == null -> if (r.isFinishedAt(now) || now - lastDriftCheckUtcMs >= LOCAL_SYNC_INTERVAL_MS) keepInSync()
                    r.isFinishedAt(now) -> tune(r.channel.id, TuneReason.BLOCK_END)
                    active.isPlaying && now - lastDriftCheckUtcMs >= DRIFT_CHECK_INTERVAL_MS -> reconcile(adaptiveTolerance())
                }
                is TuneResolution.Slate -> if (now >= r.untilUtcMs) tune(r.channel.id, TuneReason.BLOCK_END)
                null -> Unit
            }
        }
    }

    private fun applyCaptions(enabled: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .setPreferredTextLanguage("en")
            .build()
    }

    private fun setPhase(phase: PlayerPhase, message: String? = null) =
        _state.update { it.copy(phase = phase, message = message) }

    companion object {
        const val DRIFT_CHECK_INTERVAL_MS = 30_000L
        /** Local playback checks drift often: the fixes are gentle speed nudges, not seeks. */
        const val LOCAL_SYNC_INTERVAL_MS = 2_000L
        const val PRELOAD_DELAY_MS = 3_000L
        /** How far behind the clock the pre-loaded item may start before we jump to it. */
        const val SEGUE_GRACE_MS = 4_000L
        const val MAX_TOLERANCE_MS = 15_000L
        const val EARLY_END_GRACE_MS = 2_000L
        /** How much schedule the Roku gets at a time, and how often it's topped up. */
        const val ROKU_QUEUE_MS = 12 * 3_600_000L
        /** Shows now come with their commercials (~20 items per half hour). */
        const val ROKU_QUEUE_MAX_ITEMS = 700
        const val ROKU_REFILL_MS = 30 * 60_000L
    }
}

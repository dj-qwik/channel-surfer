package com.channelsurf.ui

import android.app.Application
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.channelsurf.ChannelSurfApplication
import com.channelsurf.core.guide.GuideBuilder
import com.channelsurf.core.guide.GuideRow
import com.channelsurf.core.guide.GuideSlot
import com.channelsurf.core.model.Channel
import com.channelsurf.core.schedule.ScheduleEngine
import com.channelsurf.core.tuning.ChannelNavigator
import com.channelsurf.core.tuning.NumberEntry
import com.channelsurf.core.tuning.TuneHistory
import com.channelsurf.data.prefs.PlaybackBookmark
import com.channelsurf.data.prefs.PlannedTune
import com.channelsurf.playback.CastKeepAliveService
import com.channelsurf.data.prefs.Settings
import com.channelsurf.playback.PlayerController
import com.channelsurf.playback.PlayerPhase
import com.channelsurf.playback.TuneReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

enum class Screen { TV, SETTINGS, CURATION, DIAGNOSTICS }

data class GuideState(
    val open: Boolean = false,
    val windowStartUtcMs: Long = 0,
    val selectedRow: Int = 0,
    val focusUtcMs: Long = 0,
    val favoritesOnly: Boolean = false,
)

data class OverlayState(
    val booting: Boolean = true,
    val screen: Screen = Screen.TV,
    val guide: GuideState = GuideState(),
    val bannerVisible: Boolean = false,
    val infoExpanded: Boolean = false,
    val numberEntry: String = "",
    val remoteVisible: Boolean = false,
    val toast: String? = null,
    val showMobileDataWarning: Boolean = false,
    val castPickerOpen: Boolean = false,
    /** "My schedule": planned automatic channel changes. */
    val plannerOpen: Boolean = false,
)

data class GuideModel(
    val windowStartUtcMs: Long,
    val windowEndUtcMs: Long,
    val slots: List<GuideSlot>,
    val rows: List<GuideRow>,
    val nowFraction: Float?,
    val selectedRow: Int,
    val focusUtcMs: Long,
    val dayLabel: String,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as ChannelSurfApplication).container

    val settings: StateFlow<Settings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val bookmark: StateFlow<PlaybackBookmark> = c.settings.bookmark.stateIn(viewModelScope, SharingStarted.Eagerly, PlaybackBookmark())

    val controller = PlayerController(
        context = app,
        scope = viewModelScope,
        schedule = c.schedule,
        catalog = c.catalog,
        clock = c.clock,
        settings = settings,
        network = c.network,
        diagnostics = c.diagnostics,
        callFactory = c.archiveHttp.client,
        castManager = c.cast,
        rokuManager = c.roku,
    )
    init {
        // Share the live player with the background playback service; its card's button is TV power.
        c.playbackBridge.player = controller.player
        c.playbackBridge.onPowerOff = { viewModelScope.launch { controller.suspendPlayback(); poweredOff = true } }
        c.playbackBridge.onPowerOn = { viewModelScope.launch { poweredOff = false; controller.resumeLive() } }
    }

    /** True while the TV was switched off from the notification card. */
    @Volatile private var poweredOff = false

    private val _inPip = MutableStateFlow(false)
    /** Picture-in-picture: only the video is drawn. */
    val inPip: StateFlow<Boolean> = _inPip
    fun setInPip(v: Boolean) { _inPip.value = v; if (v) _overlays.update { it.copy(remoteVisible = false, guide = it.guide.copy(open = false), castPickerOpen = false) } }

    fun cyclePictureMode() {
        val next = com.channelsurf.data.prefs.PictureMode.entries.let { it[(settings.value.pictureMode.ordinal + 1) % it.size] }
        viewModelScope.launch { c.settings.setPictureMode(next) }
        toast("Picture: ${next.label}")
    }

    val castStatus = c.cast.status
    val castDevices = c.cast.devices
    val rokuDevices = c.roku.devices
    val rokuSearching = c.roku.searching
    val rokuTarget = controller.rokuTarget
    val playerState = controller.state
    val engine: StateFlow<ScheduleEngine?> = c.schedule.engine

    private val _overlays = MutableStateFlow(OverlayState())
    val overlays: StateFlow<OverlayState> = _overlays

    /** Broadcast clock, ticking once per second for the on-screen clock and progress. */
    private val _now = MutableStateFlow(c.clock.nowUtcMs())
    val now: StateFlow<Long> = _now

    private val _guideSlots = MutableStateFlow(3)
    private var history = TuneHistory()
    private val numberEntry = NumberEntry()
    private var numberJob: Job? = null
    private var bannerJob: Job? = null
    private var toastJob: Job? = null
    private var remoteJob: Job? = null
    private var wasBackgrounded = false

    val channels: StateFlow<List<Channel>> = engine.map { it?.lineup?.enabledChannels ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val hasAnyProgramming: StateFlow<Boolean> = engine.map { e -> e != null && e.lineup.channels.any { e.hasProgramming(it.id) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Half-hour columns shown: the user's zoom, or what fits the screen. */
    val guideSlotCount: StateFlow<Int> = combine(_guideSlots, settings.map { it.guideSlots }.distinctUntilChanged()) { auto, user ->
        if (user > 0) user else auto
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 3)

    val guide: StateFlow<GuideModel?> = combine(
        engine,
        _overlays.map { it.guide }.distinctUntilChanged(),
        _now.map { it / 15_000 }.distinctUntilChanged(),
        guideSlotCount,
        bookmark.map { it.favoriteChannelIds }.distinctUntilChanged(),
    ) { e, g, _, slots, favs ->
        if (e == null || !g.open) return@combine null
        val zone = ZoneId.systemDefault()
        val nowUtc = c.clock.nowUtcMs()
        val end = g.windowStartUtcMs + slots * GuideBuilder.SLOT_MS
        val chans = e.lineup.enabledChannels.let { all -> if (g.favoritesOnly) all.filter { it.id in favs }.ifEmpty { all } else all }
        GuideModel(
            windowStartUtcMs = g.windowStartUtcMs,
            windowEndUtcMs = end,
            slots = GuideBuilder.slots(g.windowStartUtcMs, slots, zone),
            rows = GuideBuilder.rows(e, chans, g.windowStartUtcMs, end, nowUtc),
            nowFraction = GuideBuilder.nowFraction(g.windowStartUtcMs, end, nowUtc),
            selectedRow = g.selectedRow.coerceIn(0, (chans.size - 1).coerceAtLeast(0)),
            focusUtcMs = g.focusUtcMs,
            dayLabel = GuideBuilder.dayLabel(g.windowStartUtcMs, zone),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ------------------------------------------------------------------ my schedule (planned channel changes)

    val planned: StateFlow<List<PlannedTune>> = c.settings.plannedTunes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun openPlanner() = _overlays.update { it.copy(plannerOpen = true, guide = it.guide.copy(open = false), castPickerOpen = false) }
    fun closePlanner() = _overlays.update { it.copy(plannerOpen = false) }

    fun addPlanned(channelId: String, startUtcMs: Long, label: String, daily: Boolean) {
        val ch = engine.value?.lineup?.channel(channelId)
        val t = PlannedTune(
            id = "p" + System.currentTimeMillis().toString(36) + (0..999).random(),
            channelId = channelId, startUtcMs = startUtcMs,
            label = label.ifBlank { ch?.displayName ?: "" }, daily = daily,
        )
        viewModelScope.launch { c.settings.setPlannedTunes(planned.value.filterNot { it.channelId == channelId && it.startUtcMs == startUtcMs } + t) }
        toast("Scheduled: ${ch?.displayNumber ?: ""} ${ch?.displayName ?: ""} at ${GuideBuilder.label(startUtcMs, ZoneId.systemDefault())}")
    }

    fun removePlanned(id: String) = viewModelScope.launch { c.settings.setPlannedTunes(planned.value.filterNot { it.id == id }) }

    fun togglePlannedDaily(id: String) = viewModelScope.launch {
        c.settings.setPlannedTunes(planned.value.map { if (it.id == id) it.copy(daily = !it.daily) else it })
    }

    /** From the guide: switch to this channel when the selected program starts (or un-schedule it). */
    fun togglePlanSelected() {
        val model = guide.value ?: return
        val row = model.rows.getOrNull(model.selectedRow) ?: return
        val cell = row.cellAt(model.focusUtcMs) ?: return
        val existing = planned.value.firstOrNull { it.channelId == row.channel.id && it.startUtcMs == cell.block.startUtcMs }
        if (existing != null) { removePlanned(existing.id); toast("Removed from your schedule"); return }
        if (cell.block.startUtcMs <= c.clock.nowUtcMs()) { toast("That's on now — tap it again to watch"); return }
        addPlanned(row.channel.id, cell.block.startUtcMs, cell.title, daily = false)
    }

    /** Fires due planned changes. Several overdue at once (phone was off): the latest one wins. */
    private suspend fun runPlanned() {
        val now = c.clock.nowUtcMs()
        val list = planned.value
        val due = list.filter { it.startUtcMs <= now }
        if (due.isEmpty()) return
        val latest = due.maxBy { it.startUtcMs }
        val updated = list.mapNotNull { t ->
            when {
                t.startUtcMs > now -> t
                t.daily -> {
                    var next = t.startUtcMs
                    while (next <= now) next += DAY_MS
                    t.copy(startUtcMs = next)
                }
                else -> null
            }
        }
        c.settings.setPlannedTunes(updated)
        // Missed by a lot (app wasn't running): don't jump channels hours later.
        if (now - latest.startUtcMs > PLAN_GRACE_MS || poweredOff || _overlays.value.booting) return
        if (currentChannelId() != latest.channelId) {
            tune(latest.channelId)
            val ch = engine.value?.lineup?.channel(latest.channelId)
            toast("📅 Your schedule: ${ch?.displayNumber ?: ""} ${ch?.displayName ?: latest.label}")
        }
    }

    // ------------------------------------------------------------------ guide zoom & text size

    /** Fewer columns = zoomed in (more room per show); more columns = see further ahead. */
    fun guideZoom(zoomIn: Boolean) {
        val n = (guideSlotCount.value + if (zoomIn) -1 else 1).coerceIn(MIN_GUIDE_SLOTS, MAX_GUIDE_SLOTS)
        viewModelScope.launch { c.settings.setGuideSlots(n) }
        toast("Guide: ${n * 30 / 60.0}".removeSuffix(".0") + " hours")
    }

    fun guideTextSize(bigger: Boolean) {
        val v = ((settings.value.guideTextScale + if (bigger) 0.15f else -0.15f) * 100).toInt() / 100f
        viewModelScope.launch { c.settings.setGuideTextScale(v.coerceIn(0.8f, 1.85f)) }
    }

    /** On-screen remote D-pad and keys behave exactly like a hardware remote. */
    fun remoteKey(keyCode: Int) { onKeyDown(keyCode, KeyEvent(KeyEvent.ACTION_DOWN, keyCode)) }

    init {
        viewModelScope.launch {
            while (true) {
                runCatching { runPlanned() }
                delay(PLAN_CHECK_MS)
            }
        }
        // Playing on a TV: keep the app awake while the phone is locked so the TV never runs dry.
        viewModelScope.launch {
            playerState.map { it.castingTo }.distinctUntilChanged().collect { tv ->
                val ctx = getApplication<Application>()
                if (tv != null) CastKeepAliveService.start(ctx, tv) else CastKeepAliveService.stop(ctx)
            }
        }
    }

    init {
        viewModelScope.launch {
            while (true) {
                _now.value = c.clock.nowUtcMs()
                delay(1_000 - (_now.value % 1_000).coerceAtMost(999))
            }
        }
        viewModelScope.launch { boot() }
        viewModelScope.launch {
            c.network.status.collect { st ->
                val s = settings.value
                if (st.online && st.metered && !s.wifiOnly && !s.mobileDataWarningAcknowledged) {
                    _overlays.update { it.copy(showMobileDataWarning = true) }
                }
            }
        }
    }

    private suspend fun boot() {
        val started = SystemClock.uptimeMillis()
        controller.setCatalogLoading()
        withContext(Dispatchers.IO) {
            c.catalog.seedIfEmpty()
            withTimeoutOrNull(3_000) { c.scheduleServer.sync() }
        }
        val e = c.schedule.awaitEngine()
        val splashLeft = BOOT_SPLASH_MS - (SystemClock.uptimeMillis() - started)
        if (splashLeft > 0) delay(splashLeft)
        val bm = c.settings.currentBookmark()
        history = TuneHistory(bm.lastChannelId, bm.previousChannelId)
        val nav = ChannelNavigator(e.lineup.channels)
        val target = nav.byId(bm.lastChannelId) ?: nav.ordered.firstOrNull()
        _overlays.update { it.copy(booting = false) }
        if (target == null) return
        if (c.settings.current().autoPlay) {
            tune(target.id, TuneReason.STARTUP)
            // Booted while in the background: keep the channel but release the decoder until we return.
            if (wasBackgrounded) controller.suspendPlayback()
        } else {
            history = history.tuned(target.id)
            openGuide()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    fun onForeground() {
        val keptPlaying = keptPlayingInBackground
        keptPlayingInBackground = false
        if (wasBackgrounded && !keptPlaying && !_overlays.value.booting && _overlays.value.screen == Screen.TV) controller.resumeLive()
        if (keptPlaying && poweredOff) { poweredOff = false; controller.resumeLive() }
        wasBackgrounded = false
    }

    private var keptPlayingInBackground = false

    /** True when leaving the app should keep the channel on (PiP, then audio via the media service). */
    fun shouldKeepPlaying(): Boolean =
        settings.value.backgroundPlay && _overlays.value.screen == Screen.TV && !_overlays.value.booting &&
            playerState.value.channelId != null

    fun onBackground() {
        if (shouldKeepPlaying()) {
            keptPlayingInBackground = true
            return
        }
        wasBackgrounded = true
        controller.suspendPlayback()
    }

    override fun onCleared() {
        if (c.playbackBridge.player === controller.player) {
            c.playbackBridge.player = null
            c.playbackBridge.onPowerOff = null
            c.playbackBridge.onPowerOn = null
        }
        controller.release()
        super.onCleared()
    }

    // ------------------------------------------------------------------ tuning

    private fun currentChannelId(): String? = playerState.value.channelId ?: history.currentId

    fun tune(channelId: String, reason: TuneReason = TuneReason.USER) {
        val prev = history.currentId
        history = history.tuned(channelId)
        controller.tune(channelId, reason)
        showBanner()
        viewModelScope.launch { c.settings.recordTune(channelId, history.previousId ?: prev.takeIf { it != channelId }, c.clock.nowUtcMs()) }
    }

    fun channelUp() = navigator()?.up(currentChannelId())?.let { tune(it.id) }
    fun channelDown() = navigator()?.down(currentChannelId())?.let { tune(it.id) }

    fun previousChannel() {
        val prev = history.previousId ?: return toast("No previous channel")
        if (navigator()?.byId(prev) == null) return toast("Previous channel unavailable")
        tune(prev)
    }

    private fun navigator(): ChannelNavigator? = engine.value?.let { ChannelNavigator(it.lineup.channels) }

    fun digit(d: Int) {
        val committed = numberEntry.press(d, SystemClock.uptimeMillis())
        _overlays.update { it.copy(numberEntry = numberEntry.display) }
        if (committed != null) return commitNumber(committed)
        numberJob?.cancel()
        numberJob = viewModelScope.launch {
            delay(NUMBER_TIMEOUT_MS)
            numberEntry.commitIfDue(SystemClock.uptimeMillis())?.let { commitNumber(it) }
        }
    }

    fun enterNumber() {
        numberJob?.cancel()
        numberEntry.commitNow()?.let { commitNumber(it) }
    }

    private fun commitNumber(n: Int) {
        numberJob?.cancel()
        _overlays.update { it.copy(numberEntry = "") }
        val ch = navigator()?.byNumber(n)
        if (ch == null) toast("Channel ${n.toString().padStart(2, '0')} not available") else tune(ch.id)
    }

    // ------------------------------------------------------------------ overlays

    fun showBanner() {
        bannerJob?.cancel()
        _overlays.update { it.copy(bannerVisible = true) }
        bannerJob = viewModelScope.launch {
            delay(BANNER_MS)
            _overlays.update { if (it.infoExpanded) it else it.copy(bannerVisible = false) }
        }
    }

    fun toggleInfo() {
        val o = _overlays.value
        when {
            o.infoExpanded -> _overlays.update { it.copy(infoExpanded = false, bannerVisible = false) }
            o.bannerVisible -> { bannerJob?.cancel(); _overlays.update { it.copy(infoExpanded = true) } }
            else -> showBanner()
        }
    }

    fun toggleGuide() = if (_overlays.value.guide.open) closeGuide() else openGuide()

    fun openGuide() {
        val nowUtc = c.clock.nowUtcMs()
        val chans = guideChannels()
        val row = chans.indexOfFirst { it.id == currentChannelId() }.coerceAtLeast(0)
        _overlays.update {
            it.copy(
                guide = it.guide.copy(
                    open = true,
                    windowStartUtcMs = GuideBuilder.alignToLocalHalfHour(nowUtc, ZoneId.systemDefault()),
                    selectedRow = row,
                    focusUtcMs = nowUtc,
                ),
                infoExpanded = false,
            )
        }
    }

    fun closeGuide() = _overlays.update { it.copy(guide = it.guide.copy(open = false)) }

    fun setGuideSlotCount(n: Int) { _guideSlots.value = n.coerceIn(2, 8) }

    private fun guideChannels(): List<Channel> {
        val all = engine.value?.lineup?.enabledChannels ?: return emptyList()
        val favs = bookmark.value.favoriteChannelIds
        return if (_overlays.value.guide.favoritesOnly) all.filter { it.id in favs }.ifEmpty { all } else all
    }

    fun guideMoveRow(delta: Int) {
        val n = guideChannels().size
        if (n == 0) return
        _overlays.update { it.copy(guide = it.guide.copy(selectedRow = (it.guide.selectedRow + delta).coerceIn(0, n - 1))) }
    }

    fun guideMoveColumn(delta: Int) {
        val model = guide.value ?: return
        val row = model.rows.getOrNull(model.selectedRow) ?: return
        val cell = row.cellAt(model.focusUtcMs) ?: return
        val nowUtc = c.clock.nowUtcMs()
        val minStart = GuideBuilder.alignToLocalHalfHour(nowUtc, ZoneId.systemDefault())
        val maxFocus = nowUtc + MAX_GUIDE_AHEAD_MS
        val g = _overlays.value.guide
        var start = g.windowStartUtcMs
        val windowLen = model.windowEndUtcMs - model.windowStartUtcMs
        val focus = if (delta > 0) {
            cell.block.endUtcMs.coerceAtMost(maxFocus).also { f -> while (f >= start + windowLen) start += GuideBuilder.SLOT_MS }
        } else {
            (cell.block.startUtcMs - 1).coerceAtLeast(nowUtc).also { f -> while (f < start && start > minStart) start -= GuideBuilder.SLOT_MS }
        }
        _overlays.update { it.copy(guide = it.guide.copy(focusUtcMs = focus, windowStartUtcMs = start.coerceAtLeast(minStart))) }
    }

    /** Pages the time window by 30 minutes (touch arrows in the guide header). */
    fun guidePage(deltaSlots: Int) {
        val nowUtc = c.clock.nowUtcMs()
        val minStart = GuideBuilder.alignToLocalHalfHour(nowUtc, ZoneId.systemDefault())
        _overlays.update {
            val start = (it.guide.windowStartUtcMs + deltaSlots * GuideBuilder.SLOT_MS).coerceIn(minStart, nowUtc + MAX_GUIDE_AHEAD_MS)
            it.copy(guide = it.guide.copy(windowStartUtcMs = start, focusUtcMs = maxOf(start, nowUtc)))
        }
    }

    fun guideJumpToNow() = openGuide()

    fun guideSelectCell(row: Int, focusUtcMs: Long) {
        val g = _overlays.value.guide
        if (g.selectedRow == row && g.focusUtcMs.let { guide.value?.rows?.getOrNull(row)?.cellAt(it) } == guide.value?.rows?.getOrNull(row)?.cellAt(focusUtcMs)) {
            guideTuneSelected()
        } else {
            _overlays.update { it.copy(guide = it.guide.copy(selectedRow = row, focusUtcMs = focusUtcMs)) }
        }
    }

    fun toggleFavoritesFilter() = _overlays.update {
        it.copy(guide = it.guide.copy(favoritesOnly = !it.guide.favoritesOnly, selectedRow = 0))
    }

    /** ENTER in the guide tunes the channel LIVE. A future program can never be started early. */
    fun guideTuneSelected() {
        val model = guide.value ?: return
        val row = model.rows.getOrNull(model.selectedRow) ?: return
        val cell = row.cellAt(model.focusUtcMs)
        closeGuide()
        tune(row.channel.id)
        if (cell != null && cell.block.startUtcMs > c.clock.nowUtcMs()) {
            toast("Tuned live. \"${cell.title}\" airs at ${GuideBuilder.label(cell.block.startUtcMs, ZoneId.systemDefault())}")
        }
    }

    fun toggleFavorite() {
        val id = currentChannelId() ?: return
        val wasFav = id in bookmark.value.favoriteChannelIds
        viewModelScope.launch { c.settings.toggleFavorite(id) }
        toast(if (wasFav) "Removed from favorites" else "Added to favorites ★")
    }

    fun toggleMute() = controller.setMuted(!playerState.value.muted)

    /** Explicit on-screen volume action; hardware volume keys stay with the OS. */
    fun adjustVolume(raise: Boolean) {
        val am = getApplication<Application>().getSystemService(AudioManager::class.java)
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
        if (playerState.value.muted && raise) controller.setMuted(false)
    }

    // ------------------------------------------------------------------ casting

    fun openCastPicker() {
        c.cast.initialize()
        c.cast.startDiscovery()
        c.roku.discover()
        _overlays.update { it.copy(castPickerOpen = true, guide = it.guide.copy(open = false)) }
    }

    fun closeCastPicker() {
        c.cast.stopDiscovery()
        _overlays.update { it.copy(castPickerOpen = false) }
    }

    fun selectCastDevice(id: String) {
        c.cast.select(id)
        closeCastPicker()
        toast("Connecting to TV…")
    }

    fun stopCasting() {
        c.cast.disconnect()
        controller.stopRoku()
        closeCastPicker()
    }

    fun rescanRoku() = c.roku.discover()

    fun selectRoku(device: com.channelsurf.cast.RokuDevice) {
        closeCastPicker()
        toast("Connecting to ${device.name}…")
        viewModelScope.launch {
            when (c.roku.readiness(device)) {
                com.channelsurf.cast.RokuManager.Readiness.READY -> controller.startRoku(device)
                com.channelsurf.cast.RokuManager.Readiness.NO_RECEIVER ->
                    toast("Install the Channel Surfer channel on ${device.name} first (developer mode upload).")
                com.channelsurf.cast.RokuManager.Readiness.UNREACHABLE ->
                    toast("${device.name} won't take commands. On the Roku: Settings > System > Advanced system settings > Control by mobile apps > Network access: Default.")
            }
        }
    }

    fun showRemote() {
        remoteJob?.cancel()
        _overlays.update { it.copy(remoteVisible = true) }
        remoteJob = viewModelScope.launch { delay(REMOTE_AUTOHIDE_MS); _overlays.update { it.copy(remoteVisible = false) } }
    }

    fun hideRemote() { remoteJob?.cancel(); _overlays.update { it.copy(remoteVisible = false) } }

    fun keepRemoteAlive() { if (_overlays.value.remoteVisible) showRemote() }

    /** Setup screens replace the TV surface, so the decoder is released and the channel rejoins live on return. */
    fun navigate(screen: Screen) {
        val from = _overlays.value.screen
        _overlays.update { it.copy(screen = screen, guide = it.guide.copy(open = false), remoteVisible = false) }
        if (from == Screen.TV && screen != Screen.TV) controller.suspendPlayback()
        if (from != Screen.TV && screen == Screen.TV && !wasBackgrounded) controller.resumeLive()
    }

    fun toggleCaptions() {
        val on = !settings.value.captions
        viewModelScope.launch { c.settings.setCaptions(on) }
        toast(
            when {
                !on -> "Captions off"
                playerState.value.captionsAvailable -> "Captions on"
                else -> "Captions on (none available for this program)"
            }
        )
    }

    fun acknowledgeContentNotice() = viewModelScope.launch { c.settings.acknowledgeContentNotice() }

    fun dismissMobileDataWarning(switchToWifiOnly: Boolean) {
        _overlays.update { it.copy(showMobileDataWarning = false) }
        viewModelScope.launch {
            c.settings.acknowledgeMobileData()
            if (switchToWifiOnly) {
                c.settings.setWifiOnly(true)
                currentChannelId()?.let { controller.tune(it, TuneReason.RETRY) }
            }
        }
    }

    fun toast(message: String) {
        toastJob?.cancel()
        _overlays.update { it.copy(toast = message) }
        toastJob = viewModelScope.launch { delay(TOAST_MS); _overlays.update { it.copy(toast = null) } }
    }

    // ------------------------------------------------------------------ keys

    /**
     * Remote/keyboard/gamepad mapping. Only handles keys on the TV screen, never keys with modifiers
     * (accessibility and system shortcuts pass through), and never volume keys.
     */
    fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val o = _overlays.value
        if (o.screen != Screen.TV || o.booting) return false
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        val guideOpen = o.guide.open
        keepRemoteAlive()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (guideOpen) guideMoveRow(-1) else channelUp()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (guideOpen) guideMoveRow(1) else channelDown()
            KeyEvent.KEYCODE_DPAD_LEFT -> if (guideOpen) guideMoveColumn(-1) else return false
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (guideOpen) guideMoveColumn(1) else return false
            KeyEvent.KEYCODE_CHANNEL_UP -> channelUp()
            KeyEvent.KEYCODE_CHANNEL_DOWN -> channelDown()
            KeyEvent.KEYCODE_PAGE_UP -> if (guideOpen) guideMoveRow(-PAGE_ROWS) else channelUp()
            KeyEvent.KEYCODE_PAGE_DOWN -> if (guideOpen) guideMoveRow(PAGE_ROWS) else channelDown()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> when {
                numberEntry.isActive -> enterNumber()
                guideOpen -> guideTuneSelected()
                else -> toggleInfo()
            }
            // KEYCODE_BACK (and gesture back) is handled by a Compose BackHandler calling handleBack().
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B -> return handleBack()
            KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_BUTTON_Y -> toggleGuide()
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_BUTTON_X -> toggleInfo()
            KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_BUTTON_L1 -> previousChannel()
            KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_BOOKMARK -> toggleFavorite()
            KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_MUTE -> toggleMute()
            KeyEvent.KEYCODE_C -> if (o.castPickerOpen) closeCastPicker() else openCastPicker()
            KeyEvent.KEYCODE_N -> if (guideOpen) guideJumpToNow() else return false
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_SETTINGS -> navigate(Screen.SETTINGS)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> digit(keyCode - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> digit(keyCode - KeyEvent.KEYCODE_NUMPAD_0)
            // Linear TV: transport keys are swallowed. There is no pause, rewind, skip or restart.
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
            -> toast("LIVE TV — programs can't be paused or skipped")
            else -> return false
        }
        return true
    }

    /** True while something on the TV surface can be dismissed with BACK. */
    fun canHandleBack(o: OverlayState = _overlays.value): Boolean =
        o.screen == Screen.TV && (o.castPickerOpen || o.plannerOpen || o.numberEntry.isNotEmpty() || o.guide.open || o.infoExpanded || o.bannerVisible || o.remoteVisible)

    /** BACK / ESC: dismiss the topmost overlay. Returns false when there is nothing to dismiss. */
    fun handleBack(): Boolean {
        val o = _overlays.value
        when {
            o.castPickerOpen -> closeCastPicker()
            o.plannerOpen -> closePlanner()
            numberEntry.isActive || o.numberEntry.isNotEmpty() -> {
                numberEntry.clear(); numberJob?.cancel(); _overlays.update { it.copy(numberEntry = "") }
            }
            o.guide.open -> closeGuide()
            o.infoExpanded || o.bannerVisible -> { bannerJob?.cancel(); _overlays.update { it.copy(infoExpanded = false, bannerVisible = false) } }
            o.remoteVisible -> hideRemote()
            else -> return false
        }
        return true
    }

    val isPlayingVideo: StateFlow<Boolean> = playerState.map { it.phase == PlayerPhase.PLAYING || it.phase == PlayerPhase.BUFFERING }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    companion object {
        const val BOOT_SPLASH_MS = 2_300L
        const val BANNER_MS = 4_000L
        const val TOAST_MS = 3_000L
        const val REMOTE_AUTOHIDE_MS = 6_000L
        const val NUMBER_TIMEOUT_MS = 1_500L
        const val PAGE_ROWS = 5
        const val MAX_GUIDE_AHEAD_MS = 7 * 24 * 3_600_000L
        const val MIN_GUIDE_SLOTS = 1
        const val MAX_GUIDE_SLOTS = 12
        const val DAY_MS = 24 * 3_600_000L
        const val PLAN_CHECK_MS = 5_000L
        /** A planned change missed by more than this (app wasn't running) is skipped, not applied late. */
        const val PLAN_GRACE_MS = 30 * 60_000L
    }
}

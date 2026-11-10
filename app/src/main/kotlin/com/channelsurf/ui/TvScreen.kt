package com.channelsurf.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.channelsurf.core.archive.UrlPolicy
import com.channelsurf.core.guide.GuideBuilder
import com.channelsurf.core.schedule.SlateKind
import com.channelsurf.data.prefs.AnalogEffects
import com.channelsurf.data.prefs.PictureMode
import com.channelsurf.playback.PlayerPhase
import com.channelsurf.playback.PlayerUiState
import com.channelsurf.playback.TuneReason
import com.channelsurf.ui.controls.CastPicker
import com.channelsurf.ui.controls.PlannerPanel
import com.channelsurf.ui.controls.RemoteActions
import com.channelsurf.ui.controls.RemoteControl
import com.channelsurf.ui.guide.GuideGrid
import com.channelsurf.ui.player.BootSplash
import com.channelsurf.ui.player.BufferingIndicator
import com.channelsurf.ui.player.EmptyCatalogPanel
import com.channelsurf.ui.player.InfoBanner
import com.channelsurf.ui.player.NoticeDialog
import com.channelsurf.ui.player.NumberEntryOsd
import com.channelsurf.ui.player.Scanlines
import com.channelsurf.ui.player.Slate
import com.channelsurf.ui.player.SlateStyle
import com.channelsurf.ui.player.StaticNoise
import com.channelsurf.ui.player.Toast
import com.channelsurf.ui.player.VideoSurface
import kotlinx.coroutines.delay
import java.time.ZoneId

@Composable
fun TvScreen(vm: MainViewModel) {
    val ps by vm.playerState.collectAsStateWithLifecycle()
    val ov by vm.overlays.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val bookmark by vm.bookmark.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val guide by vm.guide.collectAsStateWithLifecycle()
    val hasProgramming by vm.hasAnyProgramming.collectAsStateWithLifecycle()
    val castStatus by vm.castStatus.collectAsStateWithLifecycle()
    val castDevices by vm.castDevices.collectAsStateWithLifecycle()
    val planned by vm.planned.collectAsStateWithLifecycle()
    val plannedKeys = remember(planned) { planned.map { it.channelId to it.startUtcMs }.toSet() }
    val rokuDevices by vm.rokuDevices.collectAsStateWithLifecycle()
    val rokuSearching by vm.rokuSearching.collectAsStateWithLifecycle()
    val rokuTarget by vm.rokuTarget.collectAsStateWithLifecycle()
    val inPip by vm.inPip.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val openArchivePage: (String) -> Unit = { url ->
        if (UrlPolicy.isAllowed(url)) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                vm.toast("No browser available")
            }
        }
    }
    val actions = remember(vm) {
        RemoteActions(
            channelUp = vm::channelUp, channelDown = vm::channelDown, last = vm::previousChannel,
            guide = vm::toggleGuide, info = vm::toggleInfo, favorite = vm::toggleFavorite, mute = vm::toggleMute,
            captions = vm::toggleCaptions, volumeDown = { vm.adjustVolume(false) }, volumeUp = { vm.adjustVolume(true) },
            settings = { vm.navigate(Screen.SETTINGS) }, cast = vm::openCastPicker, picture = vm::cyclePictureMode, digit = vm::digit, enter = vm::enterNumber,
            planner = vm::openPlanner, key = vm::remoteKey,
        )
    }

    // Hardware BACK and gesture back both arrive here; with nothing to dismiss, BACK leaves the app.
    BackHandler(enabled = vm.canHandleBack(ov)) { vm.handleBack() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        // Read constraints here: nested layout scopes can't reach BoxWithConstraints' maxWidth implicitly.
        val wideRemote = maxWidth >= 480.dp
        val guideChannelColumn = if (maxWidth >= 600.dp) 150.dp else 112.dp
        val slotCount = when {
            !landscape -> if (maxWidth >= 600.dp) 4 else 3
            maxWidth >= 1100.dp -> 6
            maxWidth >= 800.dp -> 5
            else -> 4
        }
        LaunchedEffect(slotCount) { vm.setGuideSlotCount(slotCount) }

        if (ov.booting) {
            BootSplash(now)
            return@BoxWithConstraints
        }

        // Picture-in-picture: just the picture, filling the little window.
        if (inPip) {
            VideoSurface(vm.controller.player, settings.pictureMode, Modifier.fillMaxSize())
            return@BoxWithConstraints
        }

        val channel = ps.channelId?.let { id -> engine?.lineup?.channel(id) }
        val isFavorite = channel != null && channel.id in bookmark.favoriteChannelIds
        val videoPane: @Composable (Modifier) -> Unit = { m ->
            VideoPane(
                ps = ps, ov = ov, nowUtcMs = now, effects = settings.analogEffects,
                channelNumber = channel?.displayNumber ?: "--", channelName = channel?.displayName ?: "",
                category = channel?.categoryLabel ?: "", isFavorite = isFavorite,
                hasProgramming = hasProgramming, playerView = { VideoSurface(vm.controller.player, if (landscape) settings.pictureMode else PictureMode.ORIGINAL, Modifier.fillMaxSize()) },
                onOpenArchivePage = openArchivePage, onOpenCuration = { vm.navigate(Screen.CURATION) },
                modifier = m,
            )
        }
        val guidePane: @Composable (Modifier) -> Unit = { m ->
            guide?.let { g ->
                GuideGrid(
                    model = g, lineup = engine?.lineup, nowUtcMs = now, currentChannelId = ps.channelId,
                    favorites = bookmark.favoriteChannelIds, favoritesOnly = ov.guide.favoritesOnly,
                    channelColumnWidth = guideChannelColumn,
                    textScale = settings.guideTextScale,
                    planned = plannedKeys,
                    onZoom = vm::guideZoom, onTextSize = vm::guideTextSize,
                    onTogglePlan = vm::togglePlanSelected, onOpenPlanner = vm::openPlanner,
                    onSelectCell = vm::guideSelectCell, onTuneChannel = { vm.closeGuide(); vm.tune(it) },
                    onPage = vm::guidePage, onJumpToNow = vm::guideJumpToNow, onToggleFavorites = vm::toggleFavoritesFilter,
                    onClose = vm::closeGuide, onOpenArchivePage = openArchivePage, modifier = m,
                )
            }
        }

        if (landscape) {
            Box(Modifier.fillMaxSize()) {
                videoPane(
                    Modifier.fillMaxSize().clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClickLabel = "Show remote",
                    ) { if (ov.remoteVisible) vm.hideRemote() else vm.showRemote() },
                )
                if (ov.guide.open) {
                    guidePane(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.74f).safeDrawingPadding())
                } else if (ov.remoteVisible) {
                    RemoteControl(
                        actions = actions, muted = ps.muted, captionsOn = settings.captions, captionsAvailable = ps.captionsAvailable,
                        isFavorite = isFavorite, guideOpen = false, wide = false, castingTo = ps.castingTo, pictureLabel = settings.pictureMode.label,
                        modifier = Modifier.align(Alignment.CenterEnd).width(330.dp).fillMaxHeight().safeDrawingPadding(),
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                videoPane(Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (ov.guide.open && guide != null) {
                        guidePane(Modifier.fillMaxSize())
                    } else {
                        RemoteControl(
                            actions = actions, muted = ps.muted, captionsOn = settings.captions, captionsAvailable = ps.captionsAvailable,
                            isFavorite = isFavorite, guideOpen = false, wide = wideRemote, castingTo = ps.castingTo, pictureLabel = settings.pictureMode.label,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        if (ov.plannerOpen) {
            PlannerPanel(
                planned = planned, channels = engine?.lineup?.enabledChannels ?: emptyList(),
                currentChannelId = ps.channelId, nowUtcMs = now,
                onAdd = { id, start, daily -> vm.addPlanned(id, start, "", daily) },
                onRemove = { vm.removePlanned(it) }, onToggleDaily = { vm.togglePlannedDaily(it) }, onClose = vm::closePlanner,
            )
        } else if (ov.castPickerOpen) {
            CastPicker(
                status = castStatus, devices = castDevices,
                rokuDevices = rokuDevices, rokuSearching = rokuSearching, rokuTarget = rokuTarget,
                onSelect = vm::selectCastDevice, onSelectRoku = vm::selectRoku, onRescan = vm::rescanRoku,
                onDisconnect = vm::stopCasting, onClose = vm::closeCastPicker,
            )
        } else if (!settings.contentNoticeAcknowledged) {
            NoticeDialog(
                title = "ARCHIVAL BROADCAST NOTICE",
                body = "Channel Surfer rebroadcasts archival 1990–2005 television recordings exactly as they aired, " +
                    "including their original commercials. Some material reflects the attitudes and standards of its time " +
                    "and may not be suitable for children. Programming is live: it cannot be paused, rewound or skipped.",
                primary = "OK",
                onPrimary = { vm.acknowledgeContentNotice() },
            )
        } else if (ov.showMobileDataWarning) {
            NoticeDialog(
                title = "USING MOBILE DATA",
                body = "Full-length VHS recordings can use around 0.5–1 GB per hour. Continue on mobile data, or switch to Wi-Fi only?",
                primary = "CONTINUE",
                onPrimary = { vm.dismissMobileDataWarning(switchToWifiOnly = false) },
                secondary = "WI-FI ONLY",
                onSecondary = { vm.dismissMobileDataWarning(switchToWifiOnly = true) },
            )
        }
    }
}

@Composable
private fun VideoPane(
    ps: PlayerUiState,
    ov: OverlayState,
    nowUtcMs: Long,
    effects: AnalogEffects,
    channelNumber: String,
    channelName: String,
    category: String,
    isFavorite: Boolean,
    hasProgramming: Boolean,
    playerView: @Composable () -> Unit,
    onOpenArchivePage: (String) -> Unit,
    onOpenCuration: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.background(Color.Black).semantics { contentDescription = "Live television, channel $channelNumber $channelName" }) {
        playerView()

        val zone = ZoneId.systemDefault()
        when (ps.phase) {
            PlayerPhase.NO_SIGNAL -> when (ps.slateKind) {
                SlateKind.STATION_BREAK -> {
                    val title = ps.resolution?.block?.displayTitle.orEmpty()
                    if (title.startsWith(com.channelsurf.core.model.ScheduleIds.UP_NEXT)) Slate(
                        SlateStyle.BLUE_SCREEN, "COMING UP NEXT",
                        title.substringAfter(": ", "").ifBlank { channelName } + " · " + channelName, channelNumber,
                    ) else Slate(
                        SlateStyle.BLUE_SCREEN, channelName.uppercase(),
                        "We'll be right back" + (ps.resolution?.block?.daypartTitle?.let { " · $it" } ?: ""), channelNumber,
                    )
                }
                SlateKind.TECHNICAL_DIFFICULTIES -> Slate(
                    SlateStyle.COLOR_BARS, "TECHNICAL DIFFICULTIES\nPLEASE STAND BY",
                    ps.resolution?.let { "Programming resumes at ${GuideBuilder.label(it.block.endUtcMs, zone)}" }, channelNumber,
                )
                else -> Slate(
                    SlateStyle.COLOR_BARS, "OFF AIR",
                    ps.resolution?.block?.daypartTitle?.let { "$it — no recordings found for this block yet. New programs are added automatically." }
                        ?: "No recordings for this channel yet. New programs are added automatically.",
                    channelNumber,
                )
            }
            PlayerPhase.OFFLINE -> Slate(SlateStyle.BLUE_SCREEN, "NO SIGNAL", "Check your Internet connection. The guide still works offline.", channelNumber)
            PlayerPhase.DATA_BLOCKED -> Slate(SlateStyle.BLUE_SCREEN, "WI-FI ONLY", ps.message, channelNumber)
            PlayerPhase.RECOVERABLE_ERROR -> Slate(SlateStyle.BLUE_SCREEN, "SIGNAL LOST", ps.message, channelNumber)
            PlayerPhase.BUFFERING -> {
                var show by remember(ps.tuneToken) { mutableStateOf(false) }
                LaunchedEffect(ps.tuneToken) { delay(600); show = true }
                if (show) BufferingIndicator(Modifier.align(Alignment.TopStart).padding(10.dp))
            }
            PlayerPhase.PLAYING -> if (effects.scanlines) Scanlines()
            else -> Unit
        }

        ps.castingTo?.let { tv ->
            Slate(SlateStyle.BLUE_SCREEN, "PLAYING ON $tv".uppercase(), "Change channels here; the TV follows the live schedule.", channelNumber)
        }

        // Tuning transition: brief analog static, keyed to each user-initiated tune.
        val showsStatic = ps.tuneReason in setOf(TuneReason.USER, TuneReason.STARTUP, TuneReason.RESUME, TuneReason.RECONNECT, TuneReason.TAKEDOWN)
        var staticVisible by remember(ps.tuneToken) { mutableStateOf(showsStatic) }
        LaunchedEffect(ps.tuneToken) {
            delay(effects.tuneStaticMs)
            staticVisible = false
        }
        if (staticVisible) {
            if (effects == AnalogEffects.OFF) Box(Modifier.fillMaxSize().background(Color.Black)) else StaticNoise(effects.noiseAlpha)
        }

        if (!hasProgramming && ps.phase == PlayerPhase.NO_SIGNAL && !staticVisible) {
            EmptyCatalogPanel(onOpenCuration, Modifier.align(Alignment.Center).padding(12.dp))
        }

        if (ov.numberEntry.isNotEmpty()) {
            NumberEntryOsd(ov.numberEntry, Modifier.align(Alignment.TopEnd).padding(10.dp))
        }
        if (ov.bannerVisible || ov.infoExpanded) {
            InfoBanner(
                resolution = ps.resolution, channelNumber = channelNumber, channelName = channelName, category = category,
                nowUtcMs = nowUtcMs, expanded = ov.infoExpanded, isFavorite = isFavorite,
                onOpenArchivePage = onOpenArchivePage,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            )
        }
        ov.toast?.let { Toast(it, Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) }
    }
}

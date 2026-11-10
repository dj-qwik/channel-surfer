package com.channelsurf.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.channelsurf.ChannelSurfApplication
import com.channelsurf.MainActivity

/**
 * Keeps the channel playing when you leave the app (or turn the screen off): a foreground media service
 * with a lock-screen / notification card. It's live TV, so the card has no seek, skip or rewind; its one
 * button is a POWER switch — "pause" turns the TV off, "play" turns it back on at the LIVE position.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val c = (application as ChannelSurfApplication).container
        val bridge = c.playbackBridge
        val base = bridge.player ?: run { stopSelf(); return }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, LiveOnlyPlayer(base, bridge))
            .setId("channel-surfer-live")
            .setSessionActivity(open)
            .build()
            .also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away from Recents while it's off: stop the service. While on, keep playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.playbackState == Player.STATE_IDLE) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            release()
        }
        session = null
        super.onDestroy()
    }
}

/**
 * Hands the live player to the service and routes the card's power button back to the controller.
 * Lives in AppContainer so the service and the screen share one player.
 */
class PlaybackBridge {
    @Volatile var player: Player? = null
    @Volatile var onPowerOff: (() -> Unit)? = null
    @Volatile var onPowerOn: (() -> Unit)? = null
}

/** Strips every transport command except play/pause, and turns those into TV power off / power on (live). */
@OptIn(UnstableApi::class)
private class LiveOnlyPlayer(player: Player, private val bridge: PlaybackBridge) : ForwardingPlayer(player) {
    private val allowed = setOf(
        Player.COMMAND_PLAY_PAUSE, Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
        Player.COMMAND_GET_TIMELINE, Player.COMMAND_GET_AUDIO_ATTRIBUTES, Player.COMMAND_GET_VOLUME,
        Player.COMMAND_GET_DEVICE_VOLUME, Player.COMMAND_SET_VOLUME, Player.COMMAND_RELEASE,
    )

    override fun getAvailableCommands(): Player.Commands =
        Player.Commands.Builder().addAll(*super.getAvailableCommands().let { cmds ->
            allowed.filter { cmds.contains(it) || it == Player.COMMAND_PLAY_PAUSE }.toIntArray()
        }).build()

    override fun isCommandAvailable(command: Int): Boolean =
        command in allowed && (command == Player.COMMAND_PLAY_PAUSE || super.isCommandAvailable(command))

    override fun pause() { bridge.onPowerOff?.invoke() }
    override fun play() { bridge.onPowerOn?.invoke() }
    override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) play() else pause() }
    override fun stop() { bridge.onPowerOff?.invoke() }
}

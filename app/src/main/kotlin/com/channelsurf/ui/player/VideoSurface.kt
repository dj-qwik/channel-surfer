package com.channelsurf.ui.player

import android.graphics.Color
import android.view.View
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.channelsurf.data.prefs.PictureMode

/**
 * The video surface. PlayerView is used ONLY as a renderer: its controller (play/pause, seek bar,
 * rewind/fast-forward) is disabled and can never be shown. [pictureMode] picks fill/4:3/stretch.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(player: Player, pictureMode: PictureMode, modifier: Modifier = Modifier) {
    val resize = when (pictureMode) {
        PictureMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        PictureMode.ORIGINAL -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        PictureMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                controllerAutoShow = false
                resizeMode = resize
                setShutterBackgroundColor(Color.BLACK)
                setBackgroundColor(Color.BLACK)
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setKeepContentOnPlayerReset(false)
                isFocusable = false
                isFocusableInTouchMode = false
                isClickable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                this.player = player
            }
        },
        update = { view ->
            if (view.player !== player) view.player = player
            if (view.resizeMode != resize) view.resizeMode = resize
        },
        onRelease = { view -> view.player = null },
    )
}

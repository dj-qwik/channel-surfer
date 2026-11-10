package com.channelsurf

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.channelsurf.ui.ChannelSurfApp
import com.channelsurf.ui.MainViewModel
import com.channelsurf.ui.Screen
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Draw into the camera cutout too, so landscape video runs truly edge to edge.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        setContent { ChannelSurfApp(vm) }
        requestNotificationPermissionOnce()

        // Auto-enter picture-in-picture when leaving the app while a channel is on (Android 12+).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.isPlayingVideo, vm.settings, vm.overlays) { playing, s, o -> playing && s.backgroundPlay && o.screen == Screen.TV }
                    .collect { updatePipParams(autoEnter = it) }
            }
        }

        // Keep the screen on while video plays; go immersive in landscape on the TV surface.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.isPlayingVideo, vm.overlays) { playing, o -> playing to o.screen }.collect { (playing, screen) ->
                    if (playing && screen == Screen.TV) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    applyImmersive(screen == Screen.TV)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyImmersive(vm.overlays.value.screen == Screen.TV)
    }

    private fun applyImmersive(tvSurface: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (tvSurface && landscape) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
        // Start the media service while we're in the foreground; it keeps playback alive once we leave.
        try {
            startService(Intent(this, com.channelsurf.playback.PlaybackService::class.java))
        } catch (e: Exception) {
            // Background start restrictions: playback still works in the foreground.
        }
    }

    override fun onStop() {
        // With background play off, release the decoder. Channel clocks keep running regardless.
        if (!isChangingConfigurations) vm.onBackground()
        super.onStop()
    }

    /** Pressing Home on Android 8–11 (12+ auto-enters via the params above). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && vm.shouldKeepPlaying() && vm.isPlayingVideo.value) {
            try { enterPictureInPictureMode(pipParams(autoEnter = false)) } catch (e: Exception) { }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        vm.setInPip(isInPictureInPictureMode)
    }

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setAspectRatio(Rational(4, 3))
            .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(true) }
            .build()

    private fun updatePipParams(autoEnter: Boolean) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        try { setPictureInPictureParams(pipParams(autoEnter)) } catch (e: Exception) { }
    }

    /** Android 13+: the background-playback card is a notification, so ask once. Declining is fine. */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
    }

    private val consumedKeys = HashSet<Int>()

    /**
     * Remote/keyboard keys are routed to the cable-box logic BEFORE Compose focus handling, so D-pad
     * up/down always changes channels (or guide rows) instead of moving focus between on-screen buttons.
     * Unhandled keys (Tab, Space, volume, modifiers, accessibility shortcuts) fall through untouched.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (vm.onKeyDown(event.keyCode, event)) {
                consumedKeys += event.keyCode
                return true
            }
            KeyEvent.ACTION_UP -> if (consumedKeys.remove(event.keyCode)) return true
        }
        return super.dispatchKeyEvent(event)
    }
}

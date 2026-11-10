package com.channelsurf.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.channelsurf.data.prefs.ThemeMode
import com.channelsurf.ui.curation.CurationScreen
import com.channelsurf.ui.diagnostics.DiagnosticsScreen
import com.channelsurf.ui.settings.SettingsScreen
import com.channelsurf.ui.theme.ChannelSurfTheme

@Composable
fun ChannelSurfApp(vm: MainViewModel) {
    val overlays by vm.overlays.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val light = settings.theme == ThemeMode.SYSTEM && !isSystemInDarkTheme()
    ChannelSurfTheme(light = light) {
        BackHandler(enabled = overlays.screen != Screen.TV) {
            vm.navigate(if (overlays.screen == Screen.SETTINGS) Screen.TV else Screen.SETTINGS)
        }
        when (overlays.screen) {
            Screen.TV -> TvScreen(vm)
            Screen.SETTINGS -> SettingsScreen(vm)
            Screen.CURATION -> CurationScreen(onBack = { vm.navigate(Screen.SETTINGS) })
            Screen.DIAGNOSTICS -> DiagnosticsScreen(onBack = { vm.navigate(Screen.SETTINGS) })
        }
    }
}

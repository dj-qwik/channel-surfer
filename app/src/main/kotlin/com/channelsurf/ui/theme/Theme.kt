package com.channelsurf.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 1992–1998 cable-box palette. */
object Cable {
    val Navy = Color(0xFF061B5B)
    val NavyDeep = Color(0xFF030F38)
    val Cobalt = Color(0xFF1748A5)
    val CobaltLight = Color(0xFF2A62C9)
    val Teal = Color(0xFF29B6C8)
    val Cyan = Color(0xFF7FE3F0)
    val Mustard = Color(0xFFF2C230)
    val MustardDark = Color(0xFFC79A12)
    val White = Color(0xFFFFFFFF)
    val Grey = Color(0xFFB8C4E0)
    val Black = Color(0xFF000000)
    val BlueScreen = Color(0xFF0000AA)
    val Shadow = Color(0x99000000)
    val Red = Color(0xFFE04848)

    val Border = BorderStroke(1.dp, Color(0xFF0A2C86))
    val BorderLight = BorderStroke(2.dp, Teal)

    /** Pixel-clean sans-serif; numbers use monospace for the cable-box OSD look. */
    val Sans = FontFamily.SansSerif
    val Mono = FontFamily.Monospace

    val Osd = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 34.sp, color = Color(0xFF7CFF7C))
    val GridTitle = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = White)
    val GridLabel = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Cyan)
    val Body = TextStyle(fontFamily = Sans, fontSize = 14.sp, color = White)
    val Small = TextStyle(fontFamily = Sans, fontSize = 12.sp, color = Grey)
    val Heading = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Black, fontSize = 18.sp, color = White, letterSpacing = 1.sp)
    val SlateText = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = White, textAlign = TextAlign.Center)
}

@Composable
fun ChannelSurfTheme(light: Boolean = false, content: @Composable () -> Unit) {
    // The TV surface and guide always use the period palette; "light" only affects setup screens.
    MaterialTheme(
        colorScheme = if (light) lightColorScheme(
            primary = Cable.Cobalt,
            onPrimary = Cable.White,
            secondary = Cable.MustardDark,
            background = Color(0xFFEFF3FB),
            onBackground = Cable.NavyDeep,
            surface = Color(0xFFEFF3FB),
            onSurface = Cable.NavyDeep,
            surfaceVariant = Color(0xFFD5DFF3),
            onSurfaceVariant = Cable.NavyDeep,
            outline = Cable.Cobalt,
            error = Cable.Red,
        ) else darkColorScheme(
            primary = Cable.Mustard,
            onPrimary = Cable.Navy,
            secondary = Cable.Teal,
            onSecondary = Cable.Navy,
            background = Cable.Navy,
            onBackground = Cable.White,
            surface = Cable.Navy,
            onSurface = Cable.White,
            surfaceVariant = Cable.Cobalt,
            onSurfaceVariant = Cable.White,
            outline = Cable.Teal,
            error = Cable.Red,
        ),
        typography = Typography(
            // Colors left unspecified so text follows the scheme in both light and dark setup screens.
            bodyLarge = Cable.Body.copy(color = Color.Unspecified),
            bodyMedium = Cable.Body.copy(color = Color.Unspecified),
            bodySmall = Cable.Small.copy(color = Color.Unspecified),
            titleMedium = Cable.Heading.copy(color = Color.Unspecified),
            labelLarge = Cable.GridTitle.copy(color = Color.Unspecified),
        ),
        content = content,
    )
}

package com.channelsurf.ui.controls

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.channelsurf.ui.theme.Cable

data class RemoteActions(
    val channelUp: () -> Unit,
    val channelDown: () -> Unit,
    val last: () -> Unit,
    val guide: () -> Unit,
    val info: () -> Unit,
    val favorite: () -> Unit,
    val mute: () -> Unit,
    val captions: () -> Unit,
    val volumeDown: () -> Unit,
    val volumeUp: () -> Unit,
    val settings: () -> Unit,
    val cast: () -> Unit,
    val picture: () -> Unit,
    val digit: (Int) -> Unit,
    val enter: () -> Unit,
    /** "MY TV": the viewer's scheduled channel changes. */
    val planner: () -> Unit,
    /** D-pad / OK / EXIT, routed exactly like a hardware remote key. */
    val key: (Int) -> Unit,
)

// ---- remote plastic & rubber
private val BodyTop = Color(0xFF3B3F48)
private val BodyMid = Color(0xFF23262D)
private val BodyBottom = Color(0xFF131418)
private val KeyLight = Color(0xFF5A5F6B)
private val KeyDark = Color(0xFF2B2E35)
private val KeyText = Color(0xFFE9ECF2)
private val LabelGrey = Color(0xFF9AA1AE)
private val RedKey = Color(0xFFD23B3B)
private val YellowKey = Color(0xFFE8B923)
private val GreenKey = Color(0xFF2FA84F)
private val BlueKey = Color(0xFF2E6BD6)

/**
 * The on-screen cable remote, styled like a late-90s satellite/cable remote: rubber keys, a round D-pad,
 * CH and VOL rockers and colored function keys. By design it has NO play/pause, rewind, fast-forward or
 * skip: this is linear television.
 */
@Composable
fun RemoteControl(
    actions: RemoteActions,
    muted: Boolean,
    captionsOn: Boolean,
    captionsAvailable: Boolean,
    isFavorite: Boolean,
    guideOpen: Boolean,
    wide: Boolean,
    castingTo: String? = null,
    pictureLabel: String = "FILL",
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
            .background(Brush.verticalGradient(listOf(BodyTop, BodyMid, BodyBottom)))
            .border(1.dp, Brush.verticalGradient(listOf(Color(0x55FFFFFF), Color(0x11FFFFFF))), RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp, bottomStart = 18.dp, bottomEnd = 18.dp)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 420.dp).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Brand strip + cast
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("CHANNEL SURFER", style = TextStyle(color = Cable.Mustard, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 2.sp), modifier = Modifier.weight(1f))
                PillKey(
                    if (castingTo != null) "📺 ON TV" else "📺 CAST", lit = castingTo != null,
                    description = if (castingTo != null) "Playing on $castingTo. TV options" else "Play on TV", onClick = actions.cast,
                )
            }

            // Colored function keys
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ColorKey("MY TV", RedKey, Modifier.weight(1f), "My schedule: planned channel changes", onClick = actions.planner)
                ColorKey(if (isFavorite) "★ FAV" else "☆ FAV", YellowKey, Modifier.weight(1f),
                    if (isFavorite) "Remove channel from favorites" else "Add channel to favorites", onClick = actions.favorite)
                ColorKey(if (captionsOn) "CC ON" else "CC", GreenKey, Modifier.weight(1f),
                    if (captionsOn) "Turn captions off" else "Turn captions on" + if (!captionsAvailable) " (none for this program)" else "", onClick = actions.captions)
                ColorKey(pictureLabel, BlueKey, Modifier.weight(1f), "Picture size: $pictureLabel. Tap to change", onClick = actions.picture)
            }

            // GUIDE / INFO — D-pad — MENU / EXIT
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    RoundKey("GUIDE", 60.dp, lit = guideOpen, fontSize = 11.sp, description = if (guideOpen) "Close guide" else "Open guide", onClick = actions.guide)
                    RoundKey("MENU", 60.dp, fontSize = 11.sp, description = "Settings", onClick = actions.settings)
                }
                DPad(onKey = actions.key)
                Column(verticalArrangement = Arrangement.spacedBy(28.dp)) {
                    RoundKey("INFO", 60.dp, fontSize = 11.sp, description = "Program information", onClick = actions.info)
                    RoundKey("EXIT", 60.dp, fontSize = 11.sp, description = "Exit", onClick = { actions.key(KeyEvent.KEYCODE_ESCAPE) })
                }
            }

            // VOL rocker — MUTE / PREV — CH rocker
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Rocker("VOL", "+", "−", onTop = actions.volumeUp, onBottom = actions.volumeDown, topDescription = "Volume up", bottomDescription = "Volume down")
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RoundKey(if (muted) "🔇" else "MUTE", 56.dp, lit = muted, fontSize = if (muted) 20.sp else 11.sp, description = if (muted) "Unmute" else "Mute", onClick = actions.mute)
                    RoundKey("PREV", 56.dp, fontSize = 11.sp, description = "Previous channel", onClick = actions.last)
                }
                Rocker("CH", "▲", "▼", onTop = actions.channelUp, onBottom = actions.channelDown, topDescription = "Channel up", bottomDescription = "Channel down")
            }

            // Number pad
            Column(Modifier.fillMaxWidth().padding(horizontal = if (wide) 30.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (r in 0 until 3) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        for (c in 1..3) {
                            val d = r * 3 + c
                            NumberKey("$d", "Digit $d") { actions.digit(d) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    NumberKey("★", "Favorite", small = true, onClick = actions.favorite)
                    NumberKey("0", "Digit 0") { actions.digit(0) }
                    NumberKey("ENTER", "Tune entered channel", small = true, onClick = actions.enter)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Shared press feel: keys sink a little and darken while held. */
@Composable
private fun Modifier.remoteKey(
    shape: Shape,
    top: Color,
    bottom: Color,
    description: String,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.93f else 1f, label = "keyPress")
    return this
        .scale(s)
        .shadow(if (pressed) 1.dp else 5.dp, shape, clip = false)
        .clip(shape)
        .background(Brush.verticalGradient(if (pressed) listOf(bottom, bottom) else listOf(top, bottom)))
        .border(1.dp, Brush.verticalGradient(listOf(Color(0x66FFFFFF), Color(0x14000000))), shape)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }
}

private fun keyText(size: TextUnit, color: Color = KeyText) =
    TextStyle(color = color, fontWeight = FontWeight.Bold, fontSize = size, textAlign = TextAlign.Center, letterSpacing = 0.5.sp)

@Composable
private fun RoundKey(
    label: String, size: Dp, lit: Boolean = false, fontSize: TextUnit = 14.sp, description: String = label, onClick: () -> Unit,
) {
    Box(
        Modifier.size(size).remoteKey(
            CircleShape, if (lit) Cable.Mustard else KeyLight, if (lit) Cable.MustardDark else KeyDark, description, onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = keyText(fontSize, if (lit) Cable.Navy else KeyText))
    }
}

@Composable
private fun NumberKey(label: String, description: String, small: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.width(78.dp).height(48.dp).remoteKey(RoundedCornerShape(50), KeyLight, KeyDark, description, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = keyText(if (small) 12.sp else 20.sp))
    }
}

@Composable
private fun ColorKey(label: String, color: Color, modifier: Modifier, description: String, onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(22.dp).remoteKey(RoundedCornerShape(50), color.copy(alpha = 1f), color.darken(), description, onClick),
        )
        Text(label, style = keyText(10.sp, LabelGrey), maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun PillKey(label: String, lit: Boolean, description: String, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).remoteKey(RoundedCornerShape(50), if (lit) Cable.Mustard else KeyLight, if (lit) Cable.MustardDark else KeyDark, description, onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = keyText(12.sp, if (lit) Cable.Navy else KeyText))
    }
}

/** A long two-way rocker switch (CH ▲/▼, VOL +/−) with its name printed in the middle. */
@Composable
private fun Rocker(
    name: String, topLabel: String, bottomLabel: String,
    onTop: () -> Unit, onBottom: () -> Unit, topDescription: String, bottomDescription: String,
) {
    Column(
        Modifier.width(66.dp).shadow(5.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(listOf(KeyDark, KeyLight, KeyDark)))
            .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(50)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RockerHalf(topLabel, topDescription, onTop)
        Text(name, style = keyText(11.sp, Cable.Mustard), modifier = Modifier.padding(vertical = 2.dp))
        RockerHalf(bottomLabel, bottomDescription, onBottom)
    }
}

@Composable
private fun RockerHalf(label: String, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier.fillMaxWidth().height(52.dp)
            .background(if (pressed) Color(0x55000000) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = keyText(22.sp))
    }
}

/** Round navigation pad: ▲▼ change channel (or move in the guide), ◀▶ move through time in the guide, OK selects. */
@Composable
private fun DPad(onKey: (Int) -> Unit) {
    Box(
        Modifier.size(156.dp).shadow(8.dp, CircleShape).clip(CircleShape)
            .background(Brush.radialGradient(listOf(KeyLight, KeyDark)))
            .border(2.dp, Brush.verticalGradient(listOf(Color(0x88FFFFFF), Color(0x22000000))), CircleShape),
    ) {
        Arrow("▲", "Up", Modifier.align(Alignment.TopCenter)) { onKey(KeyEvent.KEYCODE_DPAD_UP) }
        Arrow("▼", "Down", Modifier.align(Alignment.BottomCenter)) { onKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        Arrow("◀", "Left", Modifier.align(Alignment.CenterStart)) { onKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        Arrow("▶", "Right", Modifier.align(Alignment.CenterEnd)) { onKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        Box(
            Modifier.align(Alignment.Center).size(62.dp)
                .remoteKey(CircleShape, Color(0xFF6B7180), Color(0xFF30333B), "OK") { onKey(KeyEvent.KEYCODE_DPAD_CENTER) },
            contentAlignment = Alignment.Center,
        ) {
            Text("OK", style = keyText(16.sp))
        }
    }
}

@Composable
private fun Arrow(label: String, description: String, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier.size(50.dp).clip(CircleShape)
            .background(if (pressed) Color(0x44000000) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = keyText(18.sp, if (pressed) Cable.Mustard else KeyText))
    }
}

private fun Color.darken(f: Float = 0.62f) = Color(red * f, green * f, blue * f, alpha)

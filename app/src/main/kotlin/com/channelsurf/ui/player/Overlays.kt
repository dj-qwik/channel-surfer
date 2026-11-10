package com.channelsurf.ui.player

import android.graphics.Bitmap
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.channelsurf.core.guide.GuideBuilder
import com.channelsurf.core.model.Provenance
import com.channelsurf.core.model.SegmentKind
import com.channelsurf.core.schedule.TuneResolution
import com.channelsurf.ui.CableButton
import com.channelsurf.ui.theme.Cable
import java.time.ZoneId
import kotlin.random.Random

/** Analog tuning static drawn from a tiny random bitmap scaled up with nearest-neighbour filtering. */
@Composable
fun StaticNoise(alpha: Float, modifier: Modifier = Modifier) {
    var frame by remember { mutableIntStateOf(0) }
    val w = 128
    val h = 96
    val bitmap = remember { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }
    val pixels = remember { IntArray(w * h) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame++ } }
    Canvas(modifier.fillMaxSize().semantics { contentDescription = "Tuning" }) {
        val rnd = Random(frame)
        val roll = rnd.nextInt(h)
        for (y in 0 until h) {
            val band = if (kotlin.math.abs(y - roll) < 6) 60 else 0
            for (x in 0 until w) {
                val v = (rnd.nextInt(200) + band).coerceAtMost(255)
                pixels[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        drawRect(Color.Black)
        drawImage(
            image = bitmap.asImageBitmap(),
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(w, h),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            alpha = alpha.coerceIn(0f, 1f),
            filterQuality = FilterQuality.None,
        )
    }
}

/** Faint scanlines over the picture (FULL effects only). An overlay: the decoded video is untouched. */
@Composable
fun Scanlines(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        val step = 3.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawRect(Color.Black.copy(alpha = 0.10f), topLeft = Offset(0f, y), size = Size(size.width, 1f))
            y += step
        }
    }
}

@Composable
private fun ColorBars(modifier: Modifier = Modifier) {
    val top = listOf(
        Color(0xFFC0C0C0), Color(0xFFC0C000), Color(0xFF00C0C0), Color(0xFF00C000),
        Color(0xFFC000C0), Color(0xFFC00000), Color(0xFF0000C0),
    )
    val mid = listOf(
        Color(0xFF0000C0), Color(0xFF131313), Color(0xFFC000C0), Color(0xFF131313),
        Color(0xFF00C0C0), Color(0xFF131313), Color(0xFFC0C0C0),
    )
    Canvas(modifier.fillMaxSize()) {
        val bw = size.width / 7f
        top.forEachIndexed { i, c -> drawRect(c, Offset(i * bw, 0f), Size(bw + 1, size.height * 0.67f)) }
        mid.forEachIndexed { i, c -> drawRect(c, Offset(i * bw, size.height * 0.67f), Size(bw + 1, size.height * 0.08f)) }
        val lw = size.width / 6f
        listOf(Color(0xFF00214C), Color.White, Color(0xFF32006A), Color(0xFF131313), Color(0xFF090909), Color(0xFF131313))
            .forEachIndexed { i, c -> drawRect(c, Offset(i * lw, size.height * 0.75f), Size(lw + 1, size.height * 0.25f)) }
    }
}

enum class SlateStyle { COLOR_BARS, BLUE_SCREEN }

@Composable
fun Slate(style: SlateStyle, headline: String, detail: String?, channelNumber: String?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(if (style == SlateStyle.BLUE_SCREEN) Cable.BlueScreen else Color.Black)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        if (style == SlateStyle.COLOR_BARS) ColorBars()
        channelNumber?.let {
            Text(it, style = Cable.Osd, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
        }
        Column(
            Modifier.align(Alignment.Center).padding(16.dp)
                .background(Color.Black.copy(alpha = if (style == SlateStyle.COLOR_BARS) 0.78f else 0f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(headline, style = Cable.SlateText)
            detail?.let { Text(it, style = Cable.Small.copy(color = Color.White, textAlign = TextAlign.Center), modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable
fun BufferingIndicator(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "buffer")
    val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "blink")
    Text(
        "▮▮▮ ACQUIRING SIGNAL",
        style = Cable.GridLabel.copy(fontSize = 13.sp),
        modifier = modifier.alpha(a).background(Cable.Shadow).padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics { contentDescription = "Buffering" },
    )
}

@Composable
fun NumberEntryOsd(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Cable.Osd.copy(fontSize = 44.sp),
        modifier = modifier.background(Cable.Shadow).padding(horizontal = 10.dp)
            .semantics { contentDescription = "Channel entry $text"; liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
fun Toast(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = Cable.Body,
        modifier = modifier.widthIn(max = 420.dp).shadow(6.dp, RectangleShape).background(Cable.Navy)
            .border(Cable.BorderLight).padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** Channel banner: number, generic category, title, clock, read-only progress. Never a seek bar. */
@Composable
fun InfoBanner(
    resolution: TuneResolution?,
    channelNumber: String,
    channelName: String,
    category: String,
    nowUtcMs: Long,
    expanded: Boolean,
    isFavorite: Boolean,
    onOpenArchivePage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val block = resolution?.block
    val play = resolution as? TuneResolution.Play
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Cable.Cobalt, Cable.Navy)))
            .border(Cable.Border)
            .padding(10.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(Cable.Mustard).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(channelNumber, style = Cable.Heading.copy(color = Cable.Navy, fontSize = 24.sp))
            }
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(
                    channelName.uppercase() + if (isFavorite) "  ★" else "",
                    style = Cable.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(listOfNotNull(category, block?.daypartTitle?.uppercase()).joinToString(" · "), style = Cable.GridLabel)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(GuideBuilder.label(nowUtcMs, zone), style = Cable.Heading)
                Text(
                    "LIVE", style = Cable.GridTitle.copy(color = Color.White, fontSize = 11.sp),
                    modifier = Modifier.background(Cable.Red).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        if (block != null) {
            Spacer(Modifier.height(6.dp))
            Text(block.displayTitle, style = Cable.GridTitle.copy(fontSize = 16.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    "${GuideBuilder.label(block.startUtcMs, zone)} – ${GuideBuilder.label(block.endUtcMs, zone)}",
                    style = Cable.Small,
                )
                Spacer(Modifier.width(8.dp))
                // Read-only progress through the program. It is not interactive.
                Box(Modifier.weight(1f).height(5.dp).background(Cable.NavyDeep)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(GuideBuilder.progress(block, nowUtcMs)).background(Cable.Teal))
                }
            }
            play?.segment?.takeIf { it.kind != SegmentKind.PROGRAM }?.let { seg ->
                Text(
                    when (seg.kind) {
                        SegmentKind.COMMERCIAL -> "ORIGINAL COMMERCIAL BREAK"
                        SegmentKind.STATION_ID -> "STATION IDENTIFICATION"
                        SegmentKind.PROMO -> "NETWORK PROMO"
                        SegmentKind.BUMPER -> "BUMPER"
                        SegmentKind.INTERRUPTION -> "BROADCAST INTERRUPTION"
                        SegmentKind.PROGRAM -> ""
                    } + if (seg.label.isNotBlank()) " · ${seg.label}" else "",
                    style = Cable.GridLabel.copy(color = Cable.Mustard), modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (play?.isSubstitute == true) {
                Text("Substitute programming — the scheduled recording is unavailable", style = Cable.Small.copy(color = Cable.Mustard))
            }
        }
        if (expanded && block != null) {
            Column(Modifier.padding(top = 8.dp).heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                if (block.description.isNotBlank()) Text(block.description, style = Cable.Body)
                play?.asset?.let { a ->
                    Text(
                        listOfNotNull(
                            a.broadcastYear?.let { "Originally broadcast $it" + if (a.yearVerified) "" else " (unverified)" },
                            if (a.provenance == Provenance.ORIGINAL_RECORDING) "Original off-air recording" else "Reconstructed broadcast block",
                            "Commercials: " + when (a.adOrigin) {
                                com.channelsurf.core.model.AdOrigin.EMBEDDED_ORIGINAL -> "original to the recording"
                                com.channelsurf.core.model.AdOrigin.INSERTED -> "separately archived, inserted"
                                com.channelsurf.core.model.AdOrigin.NONE -> "none"
                            },
                        ).joinToString(" · "),
                        style = Cable.Small, modifier = Modifier.padding(top = 6.dp),
                    )
                    Text("Source: Internet Archive item “${a.archiveIdentifier}”", style = Cable.Small, modifier = Modifier.padding(top = 4.dp))
                    a.creator?.let { Text("Uploaded by: $it", style = Cable.Small) }
                    CableButton("VIEW ON INTERNET ARCHIVE", Modifier.padding(top = 6.dp)) { onOpenArchivePage(a.archivePageUrl) }
                }
            }
        }
    }
}

/**
 * Power-on: like switching on an old CRT. A click and a dot of light, a bright line that snaps open into
 * the picture, a burst of static with the degauss "bwong", then the cable box's welcome screen tunes in.
 */
@Composable
fun BootSplash(nowUtcMs: Long, modifier: Modifier = Modifier, playSound: Boolean = true) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val startNs = remember { System.nanoTime() }
    var ms by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { ms = ((it - startNs) / 1_000_000).toInt().coerceAtLeast(0) } }
    LaunchedEffect(Unit) {
        if (!playSound) return@LaunchedEffect
        runCatching {
            android.media.MediaPlayer.create(context, com.channelsurf.R.raw.tv_on)?.apply {
                setVolume(0.7f, 0.7f)
                setOnCompletionListener { it.release() }
                start()
            }
        }
    }
    val t = ms.toFloat()
    fun phase(from: Float, to: Float) = ((t - from) / (to - from)).coerceIn(0f, 1f)

    Box(modifier.fillMaxSize().background(Color.Black).semantics { contentDescription = "Turning on" }, contentAlignment = Alignment.Center) {
        // 1) Warm-up dot, 2) horizontal line opening across, 3) line snapping open to the full picture.
        val dot = phase(80f, 260f)
        val lineW = phase(260f, 520f)
        val open = phase(520f, 760f)
        val flash = (1f - phase(760f, 1050f))
        if (t < 1050f) Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            if (t < 260f) {
                val r = 3.dp.toPx() + 6.dp.toPx() * dot
                drawCircle(Brush.radialGradient(listOf(Color.White, Color(0x88BFE6FF), Color.Transparent), Offset(cx, cy), r * 3), r * 3, Offset(cx, cy), alpha = dot)
            } else {
                val w = size.width * (0.02f + 0.98f * lineW)
                val h = (2.dp.toPx() + (size.height - 2.dp.toPx()) * open * open)
                drawRect(
                    Brush.verticalGradient(listOf(Color(0x00BFE6FF), Color.White, Color(0x00BFE6FF)), cy - h / 2 - 6.dp.toPx(), cy + h / 2 + 6.dp.toPx()),
                    Offset(cx - w / 2, cy - h / 2 - 6.dp.toPx()), Size(w, h + 12.dp.toPx()), alpha = if (open < 1f) 1f else flash,
                )
            }
        }
        // 4) Static while the "tuner" locks on.
        val staticAlpha = phase(700f, 820f) * (1f - phase(1500f, 1900f))
        if (staticAlpha > 0f) StaticNoise(staticAlpha * 0.9f)
        // 5) The welcome screen rolls in (a little vertical hold wobble), then settles.
        val pic = phase(1400f, 1900f)
        if (pic > 0f) {
            val roll = (1f - pic) * 60f
            Box(
                Modifier.fillMaxSize().alpha(pic)
                    .background(Brush.radialGradient(listOf(Cable.Cobalt, Cable.Navy, Cable.NavyDeep), radius = 1400f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = roll.dp)) {
                    // Big glowing CRT-style logo
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "CHANNEL\nSURFER", textAlign = TextAlign.Center,
                            style = Cable.Heading.copy(fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = 6.sp, color = Cable.Red.copy(alpha = 0.55f)),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                        Text(
                            "CHANNEL\nSURFER", textAlign = TextAlign.Center,
                            style = Cable.Heading.copy(fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = 6.sp, color = Cable.Teal.copy(alpha = 0.55f)),
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Text(
                            "CHANNEL\nSURFER", textAlign = TextAlign.Center,
                            style = Cable.Heading.copy(fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = 6.sp, color = Cable.Mustard),
                        )
                    }
                    Text(
                        "CABLE SYSTEM · DIGITAL TERMINAL", style = Cable.GridLabel.copy(letterSpacing = 2.sp),
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    val blink = (ms / 450) % 2 == 0
                    Text(
                        if (blink) "▶ NOW TUNING" else "  NOW TUNING", style = Cable.SlateText.copy(fontSize = 15.sp, color = Cable.White),
                        modifier = Modifier.padding(top = 22.dp),
                    )
                    Text(GuideBuilder.label(nowUtcMs, ZoneId.systemDefault()), style = Cable.Osd.copy(fontSize = 24.sp, color = Cable.Cyan), modifier = Modifier.padding(top = 6.dp))
                }
                Scanlines()
            }
        }
        // Channel OSD in the corner, like the set announcing its input.
        if (t > 820f) Text(
            "CH 03", style = Cable.Osd.copy(fontSize = 28.sp),
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 24.dp).alpha(if (t < 2600f) 1f else 0f),
        )
    }
}

@Composable
fun BoxScope.NoticeDialog(
    title: String,
    body: String,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(20.dp).widthIn(max = 480.dp).shadow(10.dp, RectangleShape)
                .background(Cable.Navy).border(Cable.BorderLight).padding(16.dp),
        ) {
            Text(title, style = Cable.Heading)
            Text(body, style = Cable.Body, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (secondary != null && onSecondary != null) {
                    CableButton(secondary, Modifier.padding(end = 8.dp), onClick = onSecondary)
                }
                CableButton(primary, highlighted = true, onClick = onPrimary)
            }
        }
    }
}

@Composable
fun EmptyCatalogPanel(onOpenCuration: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.widthIn(max = 520.dp).background(Cable.Navy.copy(alpha = 0.94f)).border(Cable.BorderLight).padding(14.dp),
    ) {
        Text("WELCOME TO CHANNEL SURFER", style = Cable.Heading)
        Text(
            "Your cable lineup is installed and Channel Surfer is now searching the Internet Archive for shows, movies, " +
                "music videos and commercials from 1990–2005 for every channel. Channels light up as recordings are found " +
                "and checked; this keeps happening every day, so the lineup keeps changing.",
            style = Cable.Body, modifier = Modifier.padding(top = 6.dp),
        )
        Text("Want something specific? Add Archive items yourself in Curation.", style = Cable.Small, modifier = Modifier.padding(top = 6.dp))
        CableButton("OPEN CURATION", Modifier.padding(top = 10.dp), highlighted = true, onClick = onOpenCuration)
    }
}

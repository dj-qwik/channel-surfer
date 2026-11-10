package com.channelsurf.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.channelsurf.core.guide.GuideBuilder
import com.channelsurf.core.guide.GuideCell
import com.channelsurf.core.model.BlockKind
import com.channelsurf.core.model.Lineup
import com.channelsurf.core.model.Provenance
import com.channelsurf.ui.CableButton
import com.channelsurf.ui.GuideModel
import com.channelsurf.ui.theme.Cable
import java.time.ZoneId

private val ROW_HEIGHT = 50.dp

/**
 * Two-finger pinch on the grid: spread = zoom in (fewer hours, more room per show), pinch = zoom out.
 * One-finger drags and taps pass straight through to scrolling and cells.
 */
private fun Modifier.pinchToZoom(onZoom: (zoomIn: Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var startSpan = 0f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val a = pressed[0].position
                val b = pressed[1].position
                val span = (a - b).getDistance()
                if (startSpan == 0f) startSpan = span
                else if (span / startSpan > 1.35f) { onZoom(true); startSpan = span }
                else if (span / startSpan < 0.74f) { onZoom(false); startSpan = span }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * The 1990s blue grid guide. Navigating it changes the selection only; ENTER or a second tap tunes the
 * channel LIVE. No cell ever starts a past or future program.
 */
@Composable
fun GuideGrid(
    model: GuideModel,
    lineup: Lineup?,
    nowUtcMs: Long,
    currentChannelId: String?,
    favorites: Set<String>,
    favoritesOnly: Boolean,
    channelColumnWidth: Dp,
    textScale: Float,
    /** (channelId, start) of programs on the viewer's schedule. */
    planned: Set<Pair<String, Long>>,
    onZoom: (zoomIn: Boolean) -> Unit,
    onTextSize: (bigger: Boolean) -> Unit,
    onTogglePlan: () -> Unit,
    onOpenPlanner: () -> Unit,
    onSelectCell: (row: Int, focusUtcMs: Long) -> Unit,
    onTuneChannel: (String) -> Unit,
    onPage: (Int) -> Unit,
    onJumpToNow: () -> Unit,
    onToggleFavorites: () -> Unit,
    onClose: () -> Unit,
    onOpenArchivePage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val listState = rememberLazyListState()
    LaunchedEffect(model.selectedRow) {
        val first = listState.firstVisibleItemIndex
        val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        if (model.selectedRow < first || model.selectedRow >= first + visible - 1) {
            listState.animateScrollToItem((model.selectedRow - 1).coerceAtLeast(0))
        }
    }
    val selectedRow = model.rows.getOrNull(model.selectedRow)
    val selectedCell = selectedRow?.cellAt(model.focusUtcMs)
    val scale = textScale.coerceIn(0.8f, 1.85f)
    val rowHeight = ROW_HEIGHT * maxOf(1f, scale)
    val colWidth = channelColumnWidth * (0.85f + 0.3f * scale)
    // With many columns, label every other (or third) half hour so times stay readable.
    val labelEvery = when { model.slots.size <= 5 -> 1; model.slots.size <= 9 -> 2; else -> 3 }
    val selectedPlanned = selectedRow != null && selectedCell != null && (selectedRow.channel.id to selectedCell.block.startUtcMs) in planned

    Column(modifier.background(Cable.Navy).border(Cable.Border)) {
        // ---- detail panel
        DetailPanel(
            selectedRow?.channel?.displayNumber, selectedRow?.channel?.displayName, selectedCell, lineup, nowUtcMs, zone,
            onOpenArchivePage, isPlanned = selectedPlanned, onTogglePlan = onTogglePlan,
        )

        // ---- toolbar + time header
        Row(Modifier.fillMaxWidth().background(Cable.Cobalt), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(colWidth).padding(horizontal = 6.dp)) {
                Text(model.dayLabel.uppercase(), style = Cable.GridLabel.copy(color = Cable.Mustard))
            }
            model.slots.forEachIndexed { i, slot ->
                Row(Modifier.weight(1f).border(Cable.Border).padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (i == 0) Text(
                        "◀", style = Cable.GridLabel,
                        modifier = Modifier.clickable(role = Role.Button) { onPage(-1) }.padding(end = 4.dp)
                            .semantics { contentDescription = "Earlier" },
                    )
                    Text(
                        if (i % labelEvery == 0) slot.label else "", style = Cable.GridLabel.copy(fontSize = 12.sp * minOf(scale, 1.3f)),
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, modifier = Modifier.weight(1f),
                    )
                    if (i == model.slots.lastIndex) Text(
                        "▶", style = Cable.GridLabel,
                        modifier = Modifier.clickable(role = Role.Button) { onPage(1) }.padding(start = 4.dp)
                            .semantics { contentDescription = "Later" },
                    )
                }
            }
        }

        // ---- rows
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().pinchToZoom(onZoom)) {
            itemsIndexed(model.rows, key = { _, r -> r.channel.id }) { index, row ->
                val ch = row.channel
                val isSelectedRow = index == model.selectedRow
                Row(Modifier.fillMaxWidth().height(rowHeight)) {
                    Row(
                        Modifier.width(colWidth).fillMaxHeight()
                            .background(if (ch.id == currentChannelId) Cable.CobaltLight else Cable.NavyDeep)
                            .border(Cable.Border)
                            .clickable(role = Role.Button) { onTuneChannel(ch.id) }
                            .semantics { contentDescription = "Tune channel ${ch.displayNumber} ${ch.displayName}" }
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(4.dp).fillMaxHeight().padding(vertical = 6.dp).background(parseColor(ch.color)))
                        // Number on top, the full name underneath (wrapping, never cut to "...").
                        Column(Modifier.padding(start = 5.dp), verticalArrangement = Arrangement.Center) {
                            Text(
                                (if (ch.id in favorites) "★" else "") + ch.displayNumber,
                                style = Cable.Heading.copy(fontSize = 13.sp * scale, lineHeight = 14.sp * scale), maxLines = 1,
                            )
                            Text(
                                ch.displayName,
                                style = Cable.GridTitle.copy(fontSize = 10.sp * scale, lineHeight = 11.sp * scale, color = Cable.Cyan),
                                maxLines = 3, overflow = TextOverflow.Clip,
                            )
                        }
                    }
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                        Row(Modifier.fillMaxSize()) {
                            row.cells.forEach { cell ->
                                val sel = isSelectedRow && cell == selectedCell
                                ProgramCell(
                                    cell = cell, selected = sel, zone = zone, scale = scale,
                                    planned = (ch.id to cell.block.startUtcMs) in planned,
                                    channelLabel = "${ch.displayNumber} ${ch.displayName}",
                                    modifier = Modifier.weight(cell.visibleDurationMs.toFloat().coerceAtLeast(1f)),
                                    onClick = { onSelectCell(index, maxOf(cell.visibleStartUtcMs, nowUtcMs)) },
                                )
                            }
                        }
                        model.nowFraction?.let { f ->
                            Box(Modifier.offset(x = maxWidth * f).width(2.dp).fillMaxHeight().background(Cable.Red))
                        }
                    }
                }
            }
        }

        // ---- action bar
        Row(
            Modifier.fillMaxWidth().background(Cable.NavyDeep).horizontalScroll(rememberScrollState()).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CableButton("EXIT", description = "Close guide", onClick = onClose)
            CableButton("NOW", description = "Jump to now", onClick = onJumpToNow)
            CableButton("🔍+", description = "Zoom in: less time, more detail", onClick = { onZoom(true) })
            CableButton("🔍−", description = "Zoom out: see further ahead", onClick = { onZoom(false) })
            CableButton("A+", description = "Bigger text", onClick = { onTextSize(true) })
            CableButton("A−", description = "Smaller text", onClick = { onTextSize(false) })
            CableButton("📅 MY SCHEDULE", description = "Your planned channel changes", onClick = onOpenPlanner)
            CableButton(
                if (favoritesOnly) "★ FAVORITES" else "ALL CHANNELS", highlighted = favoritesOnly,
                description = if (favoritesOnly) "Showing favorites. Show all channels" else "Showing all channels. Show favorites only",
                onClick = onToggleFavorites,
            )
        }
    }
}

@Composable
private fun ProgramCell(
    cell: GuideCell,
    selected: Boolean,
    zone: ZoneId,
    scale: Float,
    planned: Boolean,
    channelLabel: String,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val bg = when {
        selected -> Cable.Mustard
        cell.block.kind != BlockKind.PROGRAM -> Cable.NavyDeep
        cell.isOnNow -> Cable.Cobalt
        else -> Cable.Navy
    }
    val title = (if (planned) "📅 " else "") + (if (cell.continuesFromLeft) "◀ " else "") + cell.title + (if (cell.continuesToRight) " ▶" else "")
    Box(
        modifier.fillMaxHeight()
            .background(bg)
            .border(Cable.Border)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                this.selected = selected
                contentDescription = "$channelLabel: ${cell.title}, ${GuideBuilder.label(cell.block.startUtcMs, zone)} to " +
                    "${GuideBuilder.label(cell.block.endUtcMs, zone)}" + if (cell.isOnNow) ", on now" else ""
            }
            .padding(horizontal = 5.dp, vertical = 3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            title,
            style = Cable.GridTitle.copy(
                color = if (selected) Cable.Navy else if (cell.isSlate) Cable.Grey else Cable.White,
                fontSize = 13.sp * scale, lineHeight = 14.sp * scale,
            ),
            maxLines = if (scale >= 1.3f) 3 else 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailPanel(
    number: String?,
    name: String?,
    cell: GuideCell?,
    lineup: Lineup?,
    nowUtcMs: Long,
    zone: ZoneId,
    onOpenArchivePage: (String) -> Unit,
    isPlanned: Boolean,
    onTogglePlan: () -> Unit,
) {
    val asset = cell?.block?.assetId?.let { lineup?.assets?.get(it) }
    Column(
        Modifier.fillMaxWidth().height(112.dp)
            .background(Brush.verticalGradient(listOf(Cable.CobaltLight, Cable.Cobalt)))
            .border(Cable.Border).padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        if (cell == null) {
            Text("NO LISTINGS", style = Cable.Heading)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(cell.title, style = Cable.Heading, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val status = when {
                cell.isOnNow -> "ON NOW"
                cell.block.startUtcMs > nowUtcMs -> "AIRS ${GuideBuilder.label(cell.block.startUtcMs, zone)}"
                else -> ""
            }
            if (status.isNotEmpty()) Text(
                status, style = Cable.GridTitle.copy(color = Cable.Navy),
                modifier = Modifier.background(if (cell.isOnNow) Cable.Mustard else Cable.Teal).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (cell.block.startUtcMs > nowUtcMs) Text(
                if (isPlanned) "✓ SCHEDULED" else "📅 WATCH THIS",
                style = Cable.GridTitle.copy(color = if (isPlanned) Cable.Navy else Cable.White),
                modifier = Modifier.padding(start = 6.dp)
                    .background(if (isPlanned) Cable.Mustard else Cable.Red)
                    .clickable(role = Role.Button, onClick = onTogglePlan)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics { contentDescription = if (isPlanned) "Remove from my schedule" else "Switch to this channel when it starts" },
            )
        }
        Text(
            "CH ${number ?: "--"} ${name?.uppercase() ?: ""} · ${GuideBuilder.label(cell.block.startUtcMs, zone)} – ${GuideBuilder.label(cell.block.endUtcMs, zone)}" +
                (asset?.broadcastYear?.let { " · $it" } ?: "") + (cell.block.daypartTitle?.let { " · ${it.uppercase()}" } ?: ""),
            style = Cable.GridLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            cell.block.description.ifBlank {
                if (asset?.provenance == Provenance.RECONSTRUCTED) "Reconstructed broadcast block." else ""
            },
            style = Cable.Small.copy(color = Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (asset != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Source: Internet Archive · ${asset.archiveIdentifier}",
                    style = Cable.Small.copy(fontSize = 10.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "ARCHIVE PAGE ↗", style = Cable.GridLabel.copy(fontSize = 10.sp, color = Cable.Mustard),
                    modifier = Modifier.clickable(role = Role.Button) { onOpenArchivePage(asset.archivePageUrl) }
                        .padding(4.dp).semantics { contentDescription = "Open Internet Archive page" },
                )
            }
        }
    }
}

internal fun parseColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (e: IllegalArgumentException) {
    Cable.Teal
}

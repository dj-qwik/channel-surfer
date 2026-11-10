package com.channelsurf.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.channelsurf.core.model.Channel
import com.channelsurf.data.prefs.PlannedTune
import com.channelsurf.ui.CableButton
import com.channelsurf.ui.theme.Cable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "My Schedule": the viewer's planned channel changes. At each time the app switches channels by itself,
 * like setting a VCR, e.g. Star Trek at 2:00, a movie channel at 3:00, Cartoon Network at 6:00.
 */
@Composable
fun BoxScope.PlannerPanel(
    planned: List<PlannedTune>,
    channels: List<Channel>,
    currentChannelId: String?,
    nowUtcMs: Long,
    onAdd: (channelId: String, startUtcMs: Long, daily: Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onToggleDaily: (String) -> Unit,
    onClose: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val dayFmt = remember { DateTimeFormatter.ofPattern("EEE", Locale.getDefault()) }
    val timeFmt = remember { DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()) }

    // New entry: defaults to the next half hour on the current channel.
    val nowLocal = Instant.ofEpochMilli(nowUtcMs).atZone(zone)
    var channelId by remember { mutableStateOf(currentChannelId ?: channels.firstOrNull()?.id) }
    var hour by remember { mutableIntStateOf(nowLocal.plusMinutes(30).hour) }
    var minute by remember { mutableIntStateOf(if (nowLocal.minute < 30) 30 else 0) }
    var dayOffset by remember { mutableIntStateOf(0) }
    var daily by remember { mutableStateOf(false) }
    val startUtc = LocalDate.now(zone).plusDays(dayOffset.toLong()).atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
    val inPast = startUtc <= nowUtcMs

    Box(
        Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.65f)).clickable(
            interactionSource = null, indication = null, onClick = onClose,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.safeDrawingPadding().padding(10.dp).widthIn(max = 560.dp).fillMaxWidth()
                .background(Cable.Navy).border(Cable.BorderLight)
                .clickable(interactionSource = null, indication = null) {}
                .verticalScroll(rememberScrollState()).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📅 MY SCHEDULE", style = Cable.Heading, modifier = Modifier.weight(1f))
                CableButton("CLOSE", onClick = onClose)
            }
            Text(
                "At each time, Channel Surfer changes the channel for you — on the phone or the TV. " +
                    "Keep the app open or playing in the background.",
                style = Cable.Small, modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
            )

            // ---- planned list
            if (planned.isEmpty()) {
                Text("Nothing scheduled yet. Add one below, or pick a show in the GUIDE and tap 📅 WATCH THIS.", style = Cable.Body)
            }
            planned.forEach { t ->
                val ch = channels.firstOrNull { it.id == t.channelId }
                val at = Instant.ofEpochMilli(t.startUtcMs).atZone(zone)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Cable.Cobalt).border(Cable.Border).padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.width(78.dp)) {
                        Text(if (t.daily) "DAILY" else dayFmt.format(at).uppercase(), style = Cable.GridLabel.copy(color = Cable.Mustard))
                        Text(timeFmt.format(at), style = Cable.GridTitle)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                        Text("${ch?.displayNumber ?: "--"} ${ch?.displayName ?: "Unknown channel"}", style = Cable.GridTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (t.label.isNotBlank() && t.label != ch?.displayName) {
                            Text(t.label, style = Cable.Small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    CableButton(if (t.daily) "↻ DAILY" else "ONCE", highlighted = t.daily, description = if (t.daily) "Repeats daily. Make it one time" else "One time. Repeat daily") { onToggleDaily(t.id) }
                    Spacer(Modifier.width(6.dp))
                    CableButton("✕", description = "Remove") { onRemove(t.id) }
                }
            }

            // ---- add one
            Text("ADD A CHANNEL CHANGE", style = Cable.GridLabel.copy(color = Cable.Mustard), modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            Text("CHANNEL", style = Cable.GridLabel)
            val listState = rememberLazyListState()
            LaunchedEffect(Unit) {
                val i = channels.indexOfFirst { it.id == channelId }
                if (i > 1) listState.scrollToItem(i - 1)
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().height(150.dp).border(Cable.Border).background(Cable.NavyDeep)) {
                items(channels, key = { it.id }) { ch ->
                    val sel = ch.id == channelId
                    Text(
                        "${ch.displayNumber}  ${ch.displayName}",
                        style = Cable.GridTitle.copy(color = if (sel) Cable.Navy else Cable.White),
                        modifier = Modifier.fillMaxWidth().background(if (sel) Cable.Mustard else Color.Transparent)
                            .clickable(role = Role.RadioButton) { channelId = ch.id }
                            .padding(horizontal = 10.dp, vertical = 9.dp),
                    )
                }
            }

            Text("TIME", style = Cable.GridLabel, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CableButton("−", description = "Earlier") {
                    val total = (hour * 60 + minute - 15).mod(24 * 60); hour = total / 60; minute = total % 60
                }
                Text(
                    LocalTime.of(hour, minute).format(timeFmt),
                    style = Cable.Heading.copy(fontSize = 24.sp),
                    modifier = Modifier.background(Cable.NavyDeep).border(Cable.Border).padding(horizontal = 14.dp, vertical = 6.dp)
                        .semantics { contentDescription = "Time ${LocalTime.of(hour, minute).format(timeFmt)}" },
                )
                CableButton("+", description = "Later") {
                    val total = (hour * 60 + minute + 15).mod(24 * 60); hour = total / 60; minute = total % 60
                }
                CableButton("+1 HR", description = "One hour later") { hour = (hour + 1) % 24 }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("TODAY", "TOMORROW", LocalDate.now(zone).plusDays(2).dayOfWeek.name.take(3)).forEachIndexed { i, label ->
                    CableButton(label, highlighted = dayOffset == i && !daily) { dayOffset = i; daily = false }
                }
                CableButton("↻ DAILY", highlighted = daily, description = "Every day at this time") { daily = !daily }
            }

            val ch = channels.firstOrNull { it.id == channelId }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (inPast && !daily) "That time has passed — pick a later time or TOMORROW."
                    else "Switch to ${ch?.displayNumber ?: ""} ${ch?.displayName ?: ""} at ${LocalTime.of(hour, minute).format(timeFmt)}" +
                        if (daily) " every day" else "",
                    style = Cable.Small.copy(color = if (inPast && !daily) Cable.Red else Cable.Grey),
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                CableButton("ADD", highlighted = true, enabled = channelId != null && (!inPast || daily)) {
                    val id = channelId ?: return@CableButton
                    // A daily change whose time already passed today starts tomorrow.
                    var start = startUtc
                    while (start <= nowUtcMs) start += 24 * 3_600_000L
                    onAdd(id, start, daily)
                }
            }
            Spacer(Modifier.heightIn(min = 4.dp))
        }
    }
}

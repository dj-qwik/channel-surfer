package com.channelsurf.ui.curation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.channelsurf.BuildConfig
import com.channelsurf.core.model.AdOrigin
import com.channelsurf.core.model.Channel
import com.channelsurf.core.model.ContentType
import com.channelsurf.core.model.MediaAsset
import com.channelsurf.core.model.Provenance
import com.channelsurf.core.model.RightsStatus
import com.channelsurf.data.catalog.CatalogRepository
import com.channelsurf.ui.CableButton
import com.channelsurf.ui.SectionHeader
import com.channelsurf.ui.theme.Cable

private enum class CurationTab(val label: String) { DISCOVER("Discover"), REVIEW("Review queue"), ON_AIR("Lineup"), TAKEDOWNS("Takedowns") }

/**
 * Administrator curation: discover Archive items, prove they play, confirm the broadcast year, record rights
 * evidence, schedule them on channels, and process takedowns. Nothing reaches a channel without this review.
 */
@Composable
fun CurationScreen(onBack: () -> Unit, vm: CurationViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(CurationTab.DISCOVER) }
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CURATION", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            CableButton("BACK", highlighted = true, onClick = onBack)
        }
        TabRow(selectedTabIndex = tab.ordinal) {
            CurationTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
        }
        busy?.let {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        message?.let {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, modifier = Modifier.weight(1f))
                Text("✕", modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Dismiss") { vm.dismissMessage() }.padding(8.dp))
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                CurationTab.DISCOVER -> DiscoverTab(vm, busy != null)
                CurationTab.REVIEW -> ReviewTab(vm, busy != null)
                CurationTab.ON_AIR -> LineupTab(vm)
                CurationTab.TAKEDOWNS -> TakedownTab(vm)
            }
        }
    }
}

@Composable
private fun DiscoverTab(vm: CurationViewModel, busy: Boolean) {
    val s by vm.search.collectAsStateWithLifecycle()
    var identifier by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            SectionHeader("Automatic discovery")
            Text("Searches the Archive for every channel's shows and blocks, verifies the files play, and schedules them.", style = MaterialTheme.typography.bodySmall)
            CableButton("FIND NEW PROGRAMS NOW", Modifier.padding(top = 6.dp), highlighted = true) { vm.findNewNow() }
            SectionHeader("Add by Archive identifier")
            Text(
                "Paste an identifier or archive.org/details link. The best stream-ready file is probed, " +
                    "playback is verified on this device, and the item enters the review queue.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    identifier, { identifier = it.take(200) }, singleLine = true,
                    label = { Text("Identifier") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { vm.addIdentifier(identifier) }),
                )
                CableButton("ADD", Modifier.padding(start = 8.dp), enabled = !busy && identifier.isNotBlank()) { vm.addIdentifier(identifier) }
            }
            SectionHeader("Search the Internet Archive")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    s.query, vm::setQuery, singleLine = true, label = { Text("Search (video items)") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.runSearch() }),
                )
                CableButton("SEARCH", Modifier.padding(start = 8.dp), enabled = !s.loading && s.query.isNotBlank()) { vm.runSearch() }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                items(CurationViewModel.PRESET_QUERIES) { q ->
                    FilterChip(selected = s.query == q, onClick = { vm.setQuery(q); vm.runSearch() }, label = { Text(q) })
                }
            }
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (s.results.isNotEmpty()) {
                Text(
                    "${s.numFound} results · page ${s.page}" + if (s.stale) " · offline (cached)" else "",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        items(s.results, key = { it.identifier }) { doc ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp).border(Cable.Border).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(doc.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(doc.identifier, doc.year?.toString() ?: doc.date, doc.collections.firstOrNull()).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    doc.licenseUrl?.let { Text("License: $it", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                CableButton("ADD", Modifier.padding(start = 8.dp), enabled = !busy) { vm.addIdentifier(doc.identifier) }
            }
        }
        if (s.results.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                CableButton("◀ PREV", enabled = s.page > 1 && !s.loading) { vm.runSearch(s.page - 1) }
                CableButton("NEXT ▶", enabled = s.page * 25 < s.numFound && !s.loading) { vm.runSearch(s.page + 1) }
            }
        }
    }
}

@Composable
private fun ReviewTab(vm: CurationViewModel, busy: Boolean) {
    val rows by vm.assets.collectAsStateWithLifecycle()
    val channels by vm.channels.collectAsStateWithLifecycle()
    var showEligible by rememberSaveable { mutableStateOf(false) }
    val visible = rows.filter { showEligible || !it.eligible }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    "${rows.count { !it.eligible }} awaiting review · ${rows.count { it.eligible }} approved",
                    modifier = Modifier.weight(1f),
                )
                Text("Show approved", style = MaterialTheme.typography.bodySmall)
                Switch(showEligible, { showEligible = it }, Modifier.padding(start = 6.dp))
            }
            if (visible.isEmpty()) Text("Nothing here yet. Add items from Discover.", style = MaterialTheme.typography.bodySmall)
        }
        items(visible, key = { it.item.asset.id }) { row ->
            ReviewCard(row, channels, busy, vm)
        }
    }
}

@Composable
private fun ReviewCard(row: AssetRow, channels: List<Channel>, busy: Boolean, vm: CurationViewModel) {
    val a: MediaAsset = row.item.asset
    var expanded by rememberSaveable(a.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).border(if (row.eligible) Cable.BorderLight else Cable.Border).padding(10.dp)) {
        Row(Modifier.clickable(onClickLabel = if (expanded) "Collapse" else "Review") { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    "${a.archiveIdentifier} · ${a.fileName} · ${if (a.durationMs > 0) "${a.durationMs / 60_000} min" else "duration unknown"} · ${a.codec ?: "?"} · ${a.healthStatus}",
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (row.eligible) "✓ Approved for air" else "Needs: " + row.ineligibleReasons.joinToString("; "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.eligible) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                )
            }
            Text(if (expanded) "▲" else "▼", modifier = Modifier.padding(start = 8.dp))
        }
        if (expanded) ReviewEditor(a, row, channels, busy, vm)
    }
}

@Composable
private fun ReviewEditor(a: MediaAsset, row: AssetRow, channels: List<Channel>, busy: Boolean, vm: CurationViewModel) {
    var title by rememberSaveable(a.id) { mutableStateOf(a.title) }
    var year by rememberSaveable(a.id) { mutableStateOf(a.broadcastYear?.toString() ?: "") }
    var yearVerified by rememberSaveable(a.id) { mutableStateOf(a.yearVerified) }
    var evidence by rememberSaveable(a.id) { mutableStateOf(a.yearEvidence ?: "") }
    var rights by rememberSaveable(a.id) { mutableStateOf(a.rightsStatus) }
    var notes by rememberSaveable(a.id) { mutableStateOf(a.rightsNotes ?: "") }
    var provenance by rememberSaveable(a.id) { mutableStateOf(a.provenance) }
    var adOrigin by rememberSaveable(a.id) { mutableStateOf(a.adOrigin) }
    var startSec by rememberSaveable(a.id) { mutableStateOf((a.sourceStartMs / 1000).toString()) }
    var category by rememberSaveable(a.id) { mutableStateOf(a.category ?: "") }
    var contentType by rememberSaveable(a.id) { mutableStateOf(a.contentType) }
    var series by rememberSaveable(a.id) { mutableStateOf(a.series ?: "") }
    var takedownReason by rememberSaveable(a.id) { mutableStateOf("") }
    var assignOpen by rememberSaveable(a.id) { mutableStateOf(false) }
    var asFallback by rememberSaveable(a.id) { mutableStateOf(false) }

    Column(Modifier.padding(top = 8.dp)) {
        if (a.description.isNotBlank()) Text(a.description, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
        OutlinedTextField(title, { title = it.take(300) }, label = { Text("Title (episode title, or e.g. “1994 Saturday Morning VHS Block”)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(series, { series = it.take(200) }, label = { Text("Series (shown in the guide for episodes)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SectionHeader("Type")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ContentType.entries) { t ->
                FilterChip(contentType == t, { contentType = t }, label = { Text(t.name.lowercase().replace('_', ' ')) })
            }
        }

        SectionHeader("Original broadcast year")
        Text("Upload dates are not broadcast dates. Confirm from station IDs, slates, ads, guide data or the description.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Year") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
            Checkbox(yearVerified, { yearVerified = it })
            Text("Verified by review")
        }
        OutlinedTextField(evidence, { evidence = it.take(1000) }, label = { Text("Year evidence") }, modifier = Modifier.fillMaxWidth())

        SectionHeader("Rights")
        Text("Public availability on the Archive is not a redistribution license. Clear only with recorded evidence.", style = MaterialTheme.typography.bodySmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val options = RightsStatus.entries.filter { it != RightsStatus.TEST_ONLY || BuildConfig.ALLOW_TEST_ASSETS }
            items(options) { r -> FilterChip(rights == r, { rights = r }, label = { Text(r.name.replace('_', ' ')) }) }
        }
        OutlinedTextField(notes, { notes = it.take(2000) }, label = { Text("Rights evidence / notes (required to clear)") }, modifier = Modifier.fillMaxWidth())

        SectionHeader("Provenance")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Provenance.entries) { p -> FilterChip(provenance == p, { provenance = p }, label = { Text(if (p == Provenance.ORIGINAL_RECORDING) "Original off-air" else "Reconstructed") }) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            items(AdOrigin.entries) { o ->
                FilterChip(adOrigin == o, { adOrigin = o }, label = {
                    Text(when (o) { AdOrigin.EMBEDDED_ORIGINAL -> "Ads original to tape"; AdOrigin.INSERTED -> "Ads inserted"; AdOrigin.NONE -> "No ads" })
                })
            }
        }
        Row {
            OutlinedTextField(
                startSec, { startSec = it.filter(Char::isDigit).take(6) }, label = { Text("Skip tape leader (seconds)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
            )
            OutlinedTextField(category, { category = it.take(60) }, label = { Text("Category") }, singleLine = true, modifier = Modifier.weight(1f).padding(start = 8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            CableButton("SAVE REVIEW", highlighted = true) {
                vm.saveReview(
                    a.id,
                    CatalogRepository.Review(
                        title = title, broadcastYear = year.toIntOrNull(), yearVerified = yearVerified,
                        yearEvidence = evidence.ifBlank { null }, rightsStatus = rights, rightsNotes = notes.ifBlank { null },
                        provenance = provenance, adOrigin = adOrigin, sourceStartMs = (startSec.toLongOrNull() ?: 0) * 1000,
                        category = category.ifBlank { null }, contentType = contentType, series = series,
                    ),
                )
            }
            CableButton(if (row.item.playbackVerified) "RE-VERIFY" else "VERIFY PLAYBACK", enabled = !busy) { vm.verifyPlayback(a.id) }
        }

        SectionHeader("Schedule")
        if (a.tags.isNotEmpty()) {
            Text("Airs in pools:", style = MaterialTheme.typography.bodySmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(a.tags) { tag -> FilterChip(true, { vm.removeTag(a.id, tag) }, label = { Text("#$tag  ✕") }) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                CableButton("ADD TO CHANNEL…") { assignOpen = true }
                DropdownMenu(expanded = assignOpen, onDismissRequest = { assignOpen = false }) {
                    channels.forEach { ch ->
                        DropdownMenuItem(
                            text = { Text("${ch.displayNumber} ${ch.displayName}") },
                            onClick = { assignOpen = false; vm.assign(a.id, ch.id, asFallback) },
                        )
                        ch.dayparts.forEach { d ->
                            DropdownMenuItem(
                                text = { Text("      ${ch.displayNumber} ${ch.displayName} · ${d.title}") },
                                onClick = { assignOpen = false; vm.assign(a.id, ch.id, false, d.tags.firstOrNull()) },
                            )
                        }
                    }
                }
            }
            Checkbox(asFallback, { asFallback = it }, Modifier.padding(start = 8.dp))
            Text("as fallback")
        }
        Text("Dayparted channels pick programs by pool; new additions join from the next broadcast day.", style = MaterialTheme.typography.bodySmall)

        SectionHeader("Remove")
        OutlinedTextField(takedownReason, { takedownReason = it.take(500) }, label = { Text("Takedown reason / request reference") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            CableButton("TAKE DOWN ITEM") { vm.takedown(a.archiveIdentifier, takedownReason) }
            CableButton("DELETE FROM CATALOG") { vm.delete(a.id) }
        }
    }
}

@Composable
private fun LineupTab(vm: CurationViewModel) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    val rows by vm.assets.collectAsStateWithLifecycle()
    val byId = rows.associateBy { it.item.asset.id }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(channels, key = { it.id }) { ch ->
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).border(Cable.Border).padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${ch.displayNumber}  ${ch.displayName}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(if (ch.enabled) "Enabled" else "Hidden", style = MaterialTheme.typography.bodySmall)
                    Switch(ch.enabled, { vm.setChannelEnabled(ch.id, it) }, Modifier.padding(start = 6.dp))
                }
                Text("${ch.genre} · ${ch.policy}", style = MaterialTheme.typography.bodySmall)
                if (ch.policy == com.channelsurf.core.model.SchedulePolicy.DAYPARTED) {
                    fun count(tags: List<String>) = rows.count { r -> r.eligible && r.item.asset.tags.any { it in tags } }
                    Text("All day · ${count(ch.defaultTags)} recordings", style = MaterialTheme.typography.bodySmall)
                    ch.dayparts.forEach { d ->
                        Text(
                            "${d.title} · ${hhmm(d.startMinute)}–${hhmm(d.endMinute)} ${days(d.days)} · ${count(d.tags)} recordings",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    PlaylistList("Schedule (in order)", ch, ch.playlist, false, byId, vm)
                    PlaylistList("Fallback pool", ch, ch.fallbackPool, true, byId, vm)
                }
            }
        }
    }
}

@Composable
private fun PlaylistList(label: String, ch: Channel, ids: List<String>, fallback: Boolean, byId: Map<String, AssetRow>, vm: CurationViewModel) {
    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    if (ids.isEmpty()) Text("— empty —", style = MaterialTheme.typography.bodySmall)
    ids.forEachIndexed { i, id ->
        val row = byId[id]
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Text(
                "${i + 1}. ${row?.item?.asset?.title ?: id}" + if (row != null && !row.eligible) "  (not airing: ${row.ineligibleReasons.first()})" else "",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            CableButton("▲", description = "Move up", enabled = i > 0) { vm.move(ch.id, fallback, i, i - 1) }
            CableButton("▼", Modifier.padding(start = 4.dp), description = "Move down", enabled = i < ids.lastIndex) { vm.move(ch.id, fallback, i, i + 1) }
            CableButton("✕", Modifier.padding(start = 4.dp), description = "Remove from $label") { vm.unassign(id, ch.id, fallback) }
        }
    }
}

@Composable
private fun TakedownTab(vm: CurationViewModel) {
    val list by vm.takedowns.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Text(
                "Takedowns block every file of an Archive item immediately, remove it from all channels, stop it if it is " +
                    "on the air, and keep it blocked even if a later manifest includes it.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp),
            )
            if (list.isEmpty()) Text("No takedowns recorded.")
        }
        items(list, key = { it.archiveIdentifier }) { t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).border(Cable.Border).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.archiveIdentifier)
                    Text("${java.time.Instant.ofEpochMilli(t.atUtcMs)} · ${t.reason}", style = MaterialTheme.typography.bodySmall)
                }
                CableButton("LIFT") { vm.liftTakedown(t.archiveIdentifier) }
            }
        }
    }
}

private fun hhmm(minute: Int): String {
    val m = minute % (24 * 60)
    val h = m / 60
    return "%d:%02d %s".format(if (h % 12 == 0) 12 else h % 12, m % 60, if (h < 12) "AM" else "PM")
}

private fun days(d: Set<Int>): String = when (d) {
    (1..7).toSet() -> "daily"
    (1..5).toSet() -> "weekdays"
    setOf(6, 7) -> "weekends"
    else -> d.sorted().joinToString("/") { listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[it - 1] }
}

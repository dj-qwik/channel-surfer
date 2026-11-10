package com.channelsurf.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.channelsurf.BuildConfig
import com.channelsurf.ChannelSurfApplication
import com.channelsurf.data.catalog.ImportResult
import com.channelsurf.data.prefs.AnalogEffects
import com.channelsurf.data.prefs.ThemeMode
import com.channelsurf.data.remote.SyncResult
import com.channelsurf.ui.CableButton
import com.channelsurf.ui.MainViewModel
import com.channelsurf.ui.Screen
import com.channelsurf.ui.SectionHeader
import com.channelsurf.ui.ToggleRow
import com.channelsurf.work.RefreshScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val container = (context.applicationContext as ChannelSurfApplication).container
    val settings by vm.settings.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var status by rememberSaveable { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = withContext(Dispatchers.IO) {
                try {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytesCompat(com.channelsurf.core.manifest.ManifestCodec.MAX_BYTES + 1)
                        if (bytes.size > com.channelsurf.core.manifest.ManifestCodec.MAX_BYTES) null else bytes.toString(Charsets.UTF_8)
                    }
                    if (text == null) "Import failed: file is too large or unreadable"
                    else when (val r = container.catalog.importManifest(text, source = "FILE", signatureVerified = false)) {
                        is ImportResult.Imported -> "Imported ${r.revision}: ${r.channels} channels, ${r.assets} assets" +
                            if (r.warnings.isNotEmpty()) " (${r.warnings.size} warnings)" else ""
                        is ImportResult.Rejected -> "Rejected: " + r.errors.take(5).joinToString("; ")
                    }
                } catch (e: Exception) {
                    "Import failed: ${e.javaClass.simpleName}"
                }
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = withContext(Dispatchers.IO) {
                try {
                    val json = container.catalog.exportManifest()
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    "Exported channel manifest"
                } catch (e: Exception) {
                    "Export failed: ${e.javaClass.simpleName}"
                }
            }
        }
    }

    var serverUrl by rememberSaveable { mutableStateOf("") }
    var serverKey by rememberSaveable { mutableStateOf("") }
    var loadedServer by remember { mutableStateOf(false) }
    LaunchedEffect(settings.scheduleServerUrl, settings.scheduleServerPublicKey) {
        if (!loadedServer) {
            serverUrl = settings.scheduleServerUrl
            serverKey = settings.scheduleServerPublicKey
            loadedServer = true
        }
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SETUP", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            CableButton("BACK TO TV", highlighted = true) { vm.navigate(Screen.TV) }
        }
        status?.let { Text(it, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp)) }

        SectionHeader("Picture & sound")
        Text("Analog effects (tuning static, scanlines)", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            AnalogEffects.entries.forEach { e ->
                FilterChip(
                    selected = settings.analogEffects == e,
                    onClick = { scope.launch { container.settings.setAnalogEffects(e) } },
                    label = { Text(e.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        Text("Picture size in landscape", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            com.channelsurf.data.prefs.PictureMode.entries.forEach { m ->
                FilterChip(
                    selected = settings.pictureMode == m,
                    onClick = { scope.launch { container.settings.setPictureMode(m) } },
                    label = { Text(when (m) { com.channelsurf.data.prefs.PictureMode.FILL -> "Fill screen"; com.channelsurf.data.prefs.PictureMode.ORIGINAL -> "Original 4:3"; com.channelsurf.data.prefs.PictureMode.STRETCH -> "Stretch" }) },
                )
            }
        }
        ToggleRow(
            "Keep playing when I leave the app",
            "Shrinks to a picture-in-picture window, and keeps the sound going with the screen off. Use the notification's button to turn the TV off.",
            settings.backgroundPlay,
        ) { scope.launch { container.settings.setBackgroundPlay(it) } }
        ToggleRow("Closed captions", "Shown when the Archive item includes a caption file", settings.captions) {
            scope.launch { container.settings.setCaptions(it) }
        }
        ToggleRow("Start playing on launch", "Off: open the program guide first", settings.autoPlay) {
            scope.launch { container.settings.setAutoPlay(it) }
        }
        ToggleRow("Follow system light/dark for setup screens", "The TV and guide always use the classic blue look", settings.theme == ThemeMode.SYSTEM) {
            scope.launch { container.settings.setTheme(if (it) ThemeMode.SYSTEM else ThemeMode.CLASSIC_DARK) }
        }

        SectionHeader("Data")
        ToggleRow("Wi-Fi only", "Never stream on metered networks", settings.wifiOnly) { on ->
            scope.launch {
                container.settings.setWifiOnly(on)
                RefreshScheduler.ensureScheduled(context, on, replace = true)
                com.channelsurf.work.DiscoveryScheduler.ensureScheduled(context, on, replace = true)
            }
        }
        ToggleRow("Data saver", "Prefer verified low-bandwidth renditions where available", settings.dataSaver) {
            scope.launch { container.settings.setDataSaver(it) }
        }

        SectionHeader("Programming")
        ToggleRow(
            "Find new programs automatically",
            "Every day, search the Internet Archive for each channel, verify the files play, and add them. " +
                "New finds join the schedule from the next broadcast day, so the lineup keeps changing.",
            settings.autoDiscovery,
        ) { on ->
            scope.launch {
                container.settings.setAutoDiscovery(on)
                if (on) com.channelsurf.work.DiscoveryScheduler.ensureScheduled(context, settings.wifiOnly)
                else com.channelsurf.work.DiscoveryScheduler.cancel(context)
            }
        }
        ToggleRow(
            "Air new finds without review",
            "On: discovered recordings air automatically (anything detected as outside 1990–2005 is skipped; use Curation to take items down). " +
                "Off: every recording needs a rights and broadcast-year sign-off in Curation first.",
            settings.autoApprove,
        ) { scope.launch { container.settings.setAutoApprove(it) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            CableButton("FIND NEW PROGRAMS NOW", highlighted = true) {
                com.channelsurf.work.DiscoveryScheduler.runNow(context)
                status = "Searching the Archive for every channel in the background. Empty channels start airing finds right away; busier channels pick them up at the next 6 AM rollover."
            }
        }

        SectionHeader("Channel lineup")
        Text(
            "Revision ${engine?.revision ?: "…"} · ${engine?.lineup?.channels?.size ?: 0} channels · " +
                "${engine?.lineup?.assets?.size ?: 0} catalog items",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            CableButton("CURATION") { vm.navigate(Screen.CURATION) }
            CableButton("IMPORT MANIFEST") { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
            CableButton("EXPORT MANIFEST") { exportLauncher.launch("channel-surf-manifest.json") }
        }

        SectionHeader("Schedule server (optional)")
        Text(
            "With a server, all devices share one authoritative clock and signed lineup. Without one, each device " +
                "runs the same deterministic schedule on its own clock. When a server manifest is configured it replaces local curation.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(serverUrl, { serverUrl = it.take(300) }, label = { Text("Server URL (https://…)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(top = 6.dp))
        OutlinedTextField(serverKey, { serverKey = it.take(400) }, label = { Text("Manifest public key (base64)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(top = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            CableButton("SAVE & SYNC", highlighted = true) {
                scope.launch {
                    container.settings.setScheduleServer(serverUrl, serverKey)
                    status = when (val r = withContext(Dispatchers.IO) { container.scheduleServer.sync() }) {
                        SyncResult.NotConfigured -> "Schedule server cleared; using the local deterministic schedule"
                        is SyncResult.Synced -> r.message
                        is SyncResult.Failed -> "Sync failed: ${r.message}"
                    }
                }
            }
            CableButton("REFRESH NOW") {
                RefreshScheduler.runNow(context)
                status = "Refresh queued (health checks + schedule rebuild)"
            }
        }

        SectionHeader("About")
        Text(
            "Channel Surfer ${BuildConfig.VERSION_NAME}. Recordings stream directly from the Internet Archive; the app caches " +
                "metadata and schedules, not video. Archive availability is not a license: only operator-cleared material airs.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            CableButton("DIAGNOSTICS") { vm.navigate(Screen.DIAGNOSTICS) }
        }
    }
}

/** InputStream.readNBytes is API 33+; this works on API 26. */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var total = 0
    while (total < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}

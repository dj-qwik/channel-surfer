package com.channelsurf.ui.diagnostics

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.channelsurf.ChannelSurfApplication
import com.channelsurf.diagnostics.DiagnosticsLog
import com.channelsurf.ui.CableButton
import com.channelsurf.ui.theme.Cable

/** Local playback telemetry and clock status. Sharing is explicit and contains no personal data. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = (context.applicationContext as ChannelSurfApplication).container
    val events by c.diagnostics.events.collectAsStateWithLifecycle()
    val network by c.network.status.collectAsStateWithLifecycle()
    var blocks by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) { blocks = c.schedule.materializedBlockCount() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DIAGNOSTICS", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            CableButton("BACK", highlighted = true, onClick = onBack)
        }
        Text(
            "Clock: ${if (c.clock.isServerSynced) "server-synced (offset ${c.clock.offsetFromWallMs()} ms)" else "device clock"} · " +
                "Network: ${if (network.online) (if (network.metered) "metered" else "unmetered") else "offline"} · " +
                "Materialized blocks: ${if (blocks < 0) "…" else blocks}",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CableButton("SHARE LOG") {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, c.diagnostics.export())
                context.startActivity(Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            CableButton("CLEAR") { c.diagnostics.clear() }
        }
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            items(events.reversed()) { e ->
                Text(
                    "${java.time.Instant.ofEpochMilli(e.atUtcMs).toString().substring(11, 19)} ${e.line()}",
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = when (e.level) {
                        DiagnosticsLog.Level.ERROR -> Cable.Red
                        DiagnosticsLog.Level.WARN -> MaterialTheme.colorScheme.secondary
                        DiagnosticsLog.Level.INFO -> MaterialTheme.colorScheme.onBackground
                    },
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

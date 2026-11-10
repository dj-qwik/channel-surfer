package com.channelsurf.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.channelsurf.cast.CastDevice
import com.channelsurf.cast.CastStatus
import com.channelsurf.cast.RokuDevice
import com.channelsurf.ui.CableButton
import com.channelsurf.ui.theme.Cable

/** Cable-styled picker for Roku TVs (Channel Surfer receiver) and Google Cast devices. */
@Composable
fun BoxScope.CastPicker(
    status: CastStatus,
    devices: List<CastDevice>,
    rokuDevices: List<RokuDevice>,
    rokuSearching: Boolean,
    rokuTarget: RokuDevice?,
    onSelect: (String) -> Unit,
    onSelectRoku: (RokuDevice) -> Unit,
    onRescan: () -> Unit,
    onDisconnect: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(20.dp).widthIn(max = 460.dp).shadow(10.dp, RectangleShape)
                .background(Cable.Navy).border(Cable.BorderLight).padding(16.dp),
        ) {
            Text("PLAY ON TV", style = Cable.Heading)
            val connectedName = rokuTarget?.name ?: status.connectedDeviceName
            when {
                connectedName != null -> {
                    Text("Now playing on $connectedName. The TV follows the live schedule; changing channels here changes the TV.",
                        style = Cable.Body, modifier = Modifier.padding(top = 8.dp))
                    CableButton("STOP PLAYING ON TV", Modifier.padding(top = 12.dp), highlighted = true, onClick = onDisconnect)
                }
                status.connecting -> Text("Connecting…", style = Cable.Body, modifier = Modifier.padding(top = 8.dp))
                rokuDevices.isEmpty() && devices.isEmpty() -> Text(
                    if (rokuSearching || status.available) "Searching for TVs on your Wi-Fi… Make sure the TV is on and on the same network."
                    else "No TVs found. Make sure the TV is on and on the same Wi-Fi as this phone.",
                    style = Cable.Body, modifier = Modifier.padding(top = 8.dp),
                )
                else -> LazyColumn(Modifier.padding(top = 8.dp).heightIn(max = 280.dp)) {
                    items(rokuDevices, key = { "roku:" + it.host }) { d ->
                        CableButton(
                            d.name + " · Roku",
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            description = "Play on ${d.name}",
                        ) { onSelectRoku(d) }
                    }
                    items(devices, key = { "cast:" + it.id }) { d ->
                        CableButton(
                            d.name + (d.description?.let { " · $it" } ?: " · Cast"),
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            description = "Cast to ${d.name}",
                        ) { onSelect(d.id) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
                if (connectedName == null) CableButton(if (rokuSearching) "SEARCHING…" else "SEARCH AGAIN", Modifier.padding(end = 8.dp), onClick = onRescan)
                CableButton("CLOSE", onClick = onClose)
            }
        }
    }
}

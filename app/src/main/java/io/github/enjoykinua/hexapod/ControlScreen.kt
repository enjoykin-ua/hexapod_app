package io.github.enjoykinua.hexapod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Achsen-Kurzlabels für die `/joy`-out-Anzeige (Reihenfolge = Contract §1). */
private val AXIS_LABELS = listOf("lx", "ly", "l2", "rx", "ry", "r2", "dpadX", "dpadY")

/**
 * Phase-2-Hauptschirm: rosbridge-Connect-Leiste + Anzeige der ausgehenden `/joy`-Nachricht +
 * der bestehende Kishi-Roh-Reader (Debug). **Ein** Scroll-Container; die Sub-Sections scrollen
 * nicht selbst.
 */
@Composable
fun ControlScreen(
    gamepadState: GamepadState,
    connection: ConnectionState,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ConnectBar(connection, onConnect, onDisconnect)
        JoyOutSection(connection)
        HorizontalDivider()
        GamepadReaderSection(gamepadState)
    }
}

@Composable
private fun ConnectBar(
    connection: ConnectionState,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "rosbridge-Verbindung",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Beim Verbinden/Verbunden das Feld sperren (Host ändert sich nicht mitten drin).
            val busy = connection.state == ConnState.CONNECTED || connection.state == ConnState.CONNECTING
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host") },
                placeholder = { Text("Desktop-IP, z. B. 192.168.x.y") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { if (busy) onDisconnect() else onConnect(host.trim()) },
                enabled = busy || host.isNotBlank()
            ) {
                Text(if (busy) "Trennen" else "Verbinden")
            }
        }
        StatusLine(connection)
    }
}

@Composable
private fun StatusLine(connection: ConnectionState) {
    val (label, color) = when (connection.state) {
        ConnState.DISCONNECTED -> "getrennt" to MaterialTheme.colorScheme.onSurfaceVariant
        ConnState.CONNECTING -> "verbinde…" to MaterialTheme.colorScheme.primary
        ConnState.CONNECTED -> "verbunden ✓ — /joy @ ~30 Hz" to MaterialTheme.colorScheme.primary
        ConnState.ERROR -> ("Fehler: " + (connection.error ?: "unbekannt")) to MaterialTheme.colorScheme.error
    }
    Text(
        "Status: $label",
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun JoyOutSection(connection: ConnectionState) {
    // Read von lastJoy bewusst HIER (nicht in ControlScreen), damit nur diese Section bei
    // ~30 Hz rekomponiert und Connect-Bar/Roh-Reader ruhig bleiben.
    val joy = connection.lastJoy
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle("/joy ausgehend (~30 Hz, gemappt)")
        if (joy == null) {
            Hint("Noch nichts — im Vordergrund wird stetig gemappt (auch ohne Verbindung).")
            return@Column
        }
        for (i in joy.axes.indices) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "axes[$i] ${AXIS_LABELS.getOrElse(i) { "" }}",
                    fontFamily = FontFamily.Monospace
                )
                Text("%+.2f".format(joy.axes[i]), fontFamily = FontFamily.Monospace)
            }
        }
        Text(
            "buttons  " + joy.buttons.joinToString(" "),
            fontFamily = FontFamily.Monospace
        )
    }
}

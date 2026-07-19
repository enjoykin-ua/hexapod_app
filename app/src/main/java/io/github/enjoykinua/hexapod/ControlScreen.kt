package io.github.enjoykinua.hexapod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Achsen-Kurzlabels für die `/joy`-out-Anzeige (Reihenfolge = Contract §1). */
private val AXIS_LABELS = listOf("lx", "ly", "l2", "rx", "ry", "r2", "dpadX", "dpadY")

/**
 * Phase-3-Hauptschirm: Connect-Leiste + **Lifecycle-Karte** (Stack-Status + Buttons +
 * 2-stufiger Shutdown-Dialog); der Phase-2-Debug (`/joy`-out + Roh-Reader) liegt als
 * **einklappbarer** Dev-Bereich darunter (default zu). **Ein** Scroll-Container.
 */
@Composable
fun ControlScreen(
    gamepadState: GamepadState,
    connection: ConnectionState,
    lifecycle: LifecycleState,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onAction: (LifecycleAction) -> Unit,
    onRefreshStatus: () -> Unit,
    onDrive: () -> Unit,
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
        LifecycleCard(connection, lifecycle, onAction, onRefreshStatus)
        // Phase 4: in den Fahr-Screen (Video + Overlay-Shell). Erreichbar, sobald verbunden;
        // die Kamera-View self-gated zusätzlich auf laufenden Stack (Contract §5).
        Button(
            onClick = onDrive,
            enabled = connection.state == ConnState.CONNECTED,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Fahren →")
        }
        HorizontalDivider()
        DebugSection(gamepadState, connection)
    }
}

@Composable
private fun ConnectBar(
    connection: ConnectionState,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    // Host liegt in [ConnectionState] (hochgezogen), damit der Fahr-Screen die Video-URL ableiten
    // kann. Bei Verbindungsabbruch bleibt der zuletzt eingegebene Host stehen (Wieder-Verbinden).
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
        // Beim Verbinden/Verbunden Feld + Modus sperren (ändern sich nicht mitten in der Verbindung).
        val busy = connection.state == ConnState.CONNECTED || connection.state == ConnState.CONNECTING
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = connection.host,
                onValueChange = { connection.host = it },
                label = { Text("Host") },
                placeholder = { Text("Desktop-IP, z. B. 192.168.x.y") },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { if (busy) onDisconnect() else onConnect(connection.host.trim()) },
                enabled = busy || connection.host.isNotBlank()
            ) {
                Text(if (busy) "Trennen" else "Verbinden")
            }
        }
        // Phase 7B: Sim/HW-Modus → Video-`type` (mjpeg / ros_compressed) + camera_enable (nur HW).
        ModeSwitch(connection.mode, enabled = !busy) { connection.mode = it }
        StatusLine(connection)
    }
}

/**
 * Sim/HW-Umschalter (Phase 7B): koppelt den Video-`type` (Sim=`mjpeg` / HW=`ros_compressed`) und die
 * `camera_enable`-Steuerung. Nur änderbar, solange nicht verbunden — der Modus gehört zum Kontext.
 */
@Composable
private fun ModeSwitch(mode: ConnMode, enabled: Boolean, onSet: (ConnMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Modus:", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface)) {
            ModeSeg("Sim", mode == ConnMode.SIM, enabled) { onSet(ConnMode.SIM) }
            ModeSeg("HW", mode == ConnMode.HW, enabled) { onSet(ConnMode.HW) }
        }
        Text(
            "Video: " + streamType(mode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModeSeg(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        selected -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        label,
        color = fg,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
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

// --- Phase 3: Lifecycle-Steuerung ---

@Composable
private fun LifecycleCard(
    connection: ConnectionState,
    lifecycle: LifecycleState,
    onAction: (LifecycleAction) -> Unit,
    onRefreshStatus: () -> Unit,
) {
    val enabled = buttonEnablement(connection.state, lifecycle.stack, lifecycle.pendingAction)
    val idle = connection.state == ConnState.CONNECTED && lifecycle.pendingAction == null
    var shutdownStage by remember { mutableStateOf(0) }   // 0 = zu, 1 = Stufe 1, 2 = Stufe 2

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Steuerung", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        StackStatusLine(connection, lifecycle)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(LifecycleAction.START, enabled, lifecycle.pendingAction, onAction, Modifier.weight(1f))
            ActionButton(LifecycleAction.STOP, enabled, lifecycle.pendingAction, onAction, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(LifecycleAction.STAND_UP, enabled, lifecycle.pendingAction, onAction, Modifier.weight(1f))
            ActionButton(LifecycleAction.SIT_DOWN, enabled, lifecycle.pendingAction, onAction, Modifier.weight(1f))
        }
        Button(onClick = onRefreshStatus, enabled = idle, modifier = Modifier.fillMaxWidth()) {
            Text("Status aktualisieren")
        }
        Button(
            onClick = { shutdownStage = 1 },
            enabled = enabled[LifecycleAction.PI_SHUTDOWN] == true,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (lifecycle.pendingAction == LifecycleAction.PI_SHUTDOWN) "Pi ausschalten …" else "Pi ausschalten"
            )
        }

        lifecycle.notice?.let {
            Text(
                it,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    when (shutdownStage) {
        1 -> ShutdownDialog(
            title = "Pi ausschalten?",
            body = "Der Roboter setzt sich hin und der Pi wird heruntergefahren.",
            confirmText = "Weiter",
            onConfirm = { shutdownStage = 2 },
            onCancel = { shutdownStage = 0 },
        )
        2 -> ShutdownDialog(
            title = "Wirklich herunterfahren?",
            body = "Die Verbindung wird danach getrennt. Nicht rückgängig.",
            confirmText = "Ja, ausschalten",
            destructive = true,
            onConfirm = { shutdownStage = 0; onAction(LifecycleAction.PI_SHUTDOWN) },
            onCancel = { shutdownStage = 0 },
        )
    }
}

@Composable
private fun ActionButton(
    action: LifecycleAction,
    enabled: Map<LifecycleAction, Boolean>,
    pending: LifecycleAction?,
    onAction: (LifecycleAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = { onAction(action) },
        enabled = enabled[action] == true,
        modifier = modifier
    ) {
        Text(if (pending == action) "${action.label} …" else action.label)
    }
}

@Composable
private fun StackStatusLine(connection: ConnectionState, lifecycle: LifecycleState) {
    val disconnected = connection.state != ConnState.CONNECTED
    val (label, color) = when {
        lifecycle.shuttingDown && disconnected ->
            "heruntergefahren" to MaterialTheme.colorScheme.onSurfaceVariant
        disconnected ->
            "—  (nicht verbunden)" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> when (lifecycle.stack) {
            StackState.RUNNING -> (lifecycle.statusMessage ?: "läuft") to MaterialTheme.colorScheme.primary
            StackState.STOPPED -> "gestoppt" to MaterialTheme.colorScheme.onSurfaceVariant
            StackState.UNKNOWN -> "unbekannt" to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    Text(
        "Stack: $label",
        color = color,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun ShutdownDialog(
    title: String,
    body: String,
    confirmText: String,
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Abbrechen") } },
    )
}

// --- Phase 2: Debug (einklappbar, default zu) ---

@Composable
private fun DebugSection(gamepadState: GamepadState, connection: ConnectionState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            (if (expanded) "▾ " else "▸ ") + "Dev/Debug (/joy-out + Roh-Reader)",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp)
        )
        if (expanded) {
            JoyOutSection(connection)
            HorizontalDivider()
            GamepadReaderSection(gamepadState)
        }
    }
}

@Composable
private fun JoyOutSection(connection: ConnectionState) {
    // Read von lastJoy bewusst HIER (nicht in ControlScreen), damit nur diese Section bei
    // ~30 Hz rekomponiert und Connect-Bar/Lifecycle/Roh-Reader ruhig bleiben.
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

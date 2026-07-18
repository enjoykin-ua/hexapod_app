package io.github.enjoykinua.hexapod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Phase-4-Fahr-Screen (Querformat): **Vollbild-Video** als Center-View (center-crop) + **3-Wege-
 * Center-Toggle** + **Kamera-an/aus** + **alle Overlay-Slots aus §5 positioniert, aber leer/Label**
 * (Vertrag für Phase 5). Navigation Lifecycle ↔ Drive per [onBack]/BackHandler.
 *
 * **Bewusst noch NICHT (Phase 5/6):** Live-Daten in den Slots, Dropdown-Logik, 3D-Viz,
 * Config-/Alerts-/Show-Inhalte, scharfer E-Stop. Hier nur die **positionierte, leere Shell**.
 *
 * Ebene 0 = Center-View (füllt den Screen, hinter den Systemleisten). Ebene 1 = Overlay
 * (innerhalb der Safe-Insets via [contentPadding]).
 */
@Composable
fun DriveScreen(
    connection: ConnectionState,
    lifecycle: LifecycleState,
    video: VideoState,
    hmi: HmiState,
    onSetCenter: (CenterView) -> Unit,
    onToggleCam: () -> Unit,
    onBack: () -> Unit,
    onSetParam: (ParamSpec, ParamValue) -> Unit,
    onRequestParams: () -> Unit,
    onSetGait: (String) -> Unit,
    onSetStanceTarget: (Int) -> Unit,
    onSetTempoTarget: (Int) -> Unit,
    onClearAlerts: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    var openPanel by remember { mutableStateOf<OverlayPanel?>(null) }
    val running = lifecycle.stack == StackState.RUNNING

    // Zurück: offenes Panel schließen hat Vorrang, sonst zurück zum Lifecycle-Screen.
    BackHandler(enabled = openPanel != null) { openPanel = null }
    BackHandler(enabled = openPanel == null) { onBack() }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        // --- Ebene 0: Center-View (Vollbild) ---
        CenterPane(video, hmi, running, Modifier.fillMaxSize())

        // --- Ebene 1: Overlay (Top-/Bottom-Leiste) ---
        Column(
            Modifier.fillMaxSize().padding(contentPadding).padding(8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            TopBar(connection, lifecycle, hmi.status, video.centerView, onBack, onSetCenter) { openPanel = it }
            BottomBar(hmi, video.centerView == CenterView.KAMERA, onToggleCam, onSetGait, onSetStanceTarget, onSetTempoTarget)
        }

        // --- Overlay-Panels: config gefüllt (P5.11); alerts/show noch Platzhalter (P5.12) ---
        openPanel?.let { panel ->
            when (panel) {
                OverlayPanel.CONFIG -> ConfigPanelOverlay(
                    hmi = hmi,
                    lifecycle = lifecycle,
                    contentPadding = contentPadding,
                    onSetParam = onSetParam,
                    onRequestParams = onRequestParams,
                    onClose = { openPanel = null },
                )
                OverlayPanel.ALERTS -> AlertsPanelOverlay(
                    alerts = hmi.alerts,
                    contentPadding = contentPadding,
                    onClear = onClearAlerts,
                    onClose = { openPanel = null },
                )
                else -> OverlayPanelView(panel) { openPanel = null }
            }
        }
    }
}

// --- Ebene 0 ---

@Composable
private fun CenterPane(video: VideoState, hmi: HmiState, running: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        when (video.centerView) {
            CenterView.NOTHING -> {}   // dunkler Hintergrund, nur Overlay
            CenterView.KAMERA -> {
                val frame = video.frame
                when {
                    !running -> CenterHint("Kamera: zuerst Hexapod starten — Stream danach verfügbar")
                    frame != null -> Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "Kamera",
                        contentScale = ContentScale.Crop,          // center-crop-to-fill, keine Balken
                        modifier = Modifier.fillMaxSize(),
                    )
                    video.error != null -> CenterHint("Kein Stream: ${video.error}")
                    else -> CenterHint("Verbinde zum Video-Stream …")
                }
            }
            CenterView.ROBOT3D -> Robot3dView(hmi.jointPositions, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Text(text, color = Color(0xFFBBBBBB), style = MaterialTheme.typography.bodyMedium)
}

// --- Ebene 1: Top-Leiste ---

@Composable
private fun TopBar(
    connection: ConnectionState,
    lifecycle: LifecycleState,
    status: StatusSnapshot?,
    centerView: CenterView,
    onBack: () -> Unit,
    onSetCenter: (CenterView) -> Unit,
    onOpenPanel: (OverlayPanel) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SlotButton("‹ zurück", onClick = onBack)
            ConnSlot(connection, lifecycle)
            SafetySlot(status)
            TipSlot(status)
        }
        CenterToggle(centerView, onSetCenter)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SlotButton("⚠ alerts") { onOpenPanel(OverlayPanel.ALERTS) }
            SlotButton("⚙ config") { onOpenPanel(OverlayPanel.CONFIG) }
            SlotButton("show") { onOpenPanel(OverlayPanel.SHOW) }
        }
    }
}

@Composable
private fun ConnSlot(connection: ConnectionState, lifecycle: LifecycleState) {
    val connected = connection.state == ConnState.CONNECTED
    val stack = when {
        !connected -> ""
        lifecycle.stack == StackState.RUNNING -> " · läuft"
        lifecycle.stack == StackState.STOPPED -> " · gestoppt"
        else -> " · ?"
    }
    Slot(
        text = (if (connected) "● verbunden" else "○ getrennt") + stack,
        textColor = if (connected) Color(0xFF7CFC9A) else Color(0xFFBBBBBB),
    )
}

@Composable
private fun CenterToggle(active: CenterView, onSet: (CenterView) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(SCRIM),
    ) {
        SegItem("Nichts", active == CenterView.NOTHING, enabled = true) { onSet(CenterView.NOTHING) }
        SegItem("Kamera", active == CenterView.KAMERA, enabled = true) { onSet(CenterView.KAMERA) }
        SegItem("3D", active == CenterView.ROBOT3D, enabled = true) { onSet(CenterView.ROBOT3D) }
    }
}

@Composable
private fun SegItem(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = when {
        !enabled -> Color(0xFF666666)
        selected -> MaterialTheme.colorScheme.onPrimary
        else -> Color.White
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
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

// --- Ebene 1: Bottom-Leiste ---

@Composable
private fun BottomBar(
    hmi: HmiState,
    camOn: Boolean,
    onToggleCam: () -> Unit,
    onSetGait: (String) -> Unit,
    onSetStanceTarget: (Int) -> Unit,
    onSetTempoTarget: (Int) -> Unit,
) {
    val status = hmi.status
    val caps = hmi.capabilities
    val standing = status?.isStanding == true   // gait/stance/tempo sind standing-gated (§6a)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            FootGrid(hmi.footContacts)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ValueSlot("state", status?.state.orNull())
                SelectableSlot("stance", status?.stance.orNull(), caps?.stanceModes ?: emptyList(), standing, hmi.cyclingStance) { idx, _ -> onSetStanceTarget(idx) }
                SelectableSlot("gait", status?.gait.orNull(), caps?.gaits ?: emptyList(), standing, busy = false) { _, name -> onSetGait(name) }
                SelectableSlot("tempo", hmi.tempo?.tempo.orNull(), caps?.tempoPresets ?: emptyList(), standing, hmi.cyclingTempo) { idx, _ -> onSetTempoTarget(idx) }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EStopSlot()   // reservierte Position, disabled → Phase 6
            CamToggle(camOn, onToggleCam)
        }
    }
}

/**
 * Fuß-Kontakt-Raster 2×3 „1 4 / 2 5 / 3 6" (Phase 5): grün = Bodenkontakt, grau = kein Kontakt /
 * noch keine Daten. [contacts] hat genau [FOOT_COUNT] Einträge oder ist leer (Platzhalter grau).
 * Index→Position: 0→„1" 1→„2" 2→„3" 3→„4" 4→„5" 5→„6" (Bein-Zuordnung beim Live-Test verifiziert).
 */
@Composable
private fun FootGrid(contacts: List<Boolean>) {
    fun c(i: Int): Boolean? = contacts.getOrNull(i)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { FootChip("1", c(0)); FootChip("4", c(3)) }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { FootChip("2", c(1)); FootChip("5", c(4)) }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { FootChip("3", c(2)); FootChip("6", c(5)) }
    }
}

@Composable
private fun FootChip(n: String, contact: Boolean?) {
    val bg = if (contact == true) FOOT_CONTACT_GREEN else SCRIM
    val fg = if (contact == true) Color.White else LABEL_GREY
    Box(
        Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(n, color = fg, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EStopSlot() {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x55B00020)).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text("⛔ E-STOP", color = Color(0xFFEE9999), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CamToggle(on: Boolean, onClick: () -> Unit) {
    val bg = if (on) MaterialTheme.colorScheme.primary else SCRIM
    val fg = if (on) MaterialTheme.colorScheme.onPrimary else Color.White
    Text(
        "📷 cam ${if (on) "AN" else "AUS"}",
        color = fg,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

// --- gemeinsame Slot-Bausteine ---

private val SCRIM = Color(0x88000000)
private val FOOT_CONTACT_GREEN = Color(0xFF2E7D32)
private val PLACEHOLDER = Color(0xFF777777)
private val LABEL_GREY = Color(0xFF999999)

/** Leerer String → `null`, damit der Wert-Slot den Platzhalter „—" zeigt. */
private fun String?.orNull(): String? = this?.takeIf { it.isNotEmpty() }

/**
 * Wert-Slot „label wert" (Phase 5). [value]=`null` → Platzhalter „—" (grau); [valueColor] färbt den
 * Wert (z. B. safety/tip). Der Label-Teil bleibt dezent grau.
 */
@Composable
private fun ValueSlot(label: String, value: String?, valueColor: Color = Color.White) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(SCRIM).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = LABEL_GREY, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(
                value ?: "—",
                color = if (value == null) PLACEHOLDER else valueColor,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** safety-Slot: FROZEN (rot) / ok (grün) / Platzhalter (kein Status). */
@Composable
private fun SafetySlot(status: StatusSnapshot?) {
    val frozen = status?.safetyFrozen == true
    ValueSlot(
        label = "safety",
        value = when {
            status == null -> null
            frozen -> "FROZEN"
            else -> "ok"
        },
        valueColor = if (frozen) Color(0xFFEE5555) else Color(0xFF7CFC9A),
    )
}

/** tip-Slot: none (grün) / warn (gelb) / crit (rot) / Platzhalter (kein Status). */
@Composable
private fun TipSlot(status: StatusSnapshot?) {
    val tip = status?.tip
    val color = when (tip) {
        "crit" -> Color(0xFFEE5555)
        "warn" -> Color(0xFFEEDD55)
        "none" -> Color(0xFF7CFC9A)
        else -> PLACEHOLDER
    }
    ValueSlot("tip", tip.orNull(), valueColor = color)
}

/**
 * Antippbarer Wert-Slot mit Popup (P5.12, Q1=a): zeigt den Live-Wert; bei [enabled] öffnet ein Tap
 * ein [DropdownMenu] mit [options]. [busy] → „…" während eines laufenden cycle-to-target. Disabled
 * (nicht STANDING / keine Optionen) → nur Anzeige, kein ▾. [onSelect] liefert (Index, Name).
 */
@Composable
private fun SelectableSlot(
    label: String,
    value: String?,
    options: List<String>,
    enabled: Boolean,
    busy: Boolean,
    onSelect: (Int, String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val canOpen = enabled && options.isNotEmpty()
    val suffix = when {
        busy -> " …"
        canOpen -> " ▾"
        else -> ""
    }
    Box {
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(SCRIM)
                .then(if (canOpen) Modifier.clickable { open = true } else Modifier)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, color = LABEL_GREY, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Text(
                    (value ?: "—") + suffix,
                    color = if (value == null) PLACEHOLDER else Color.White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { idx, opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { open = false; onSelect(idx, opt) })
            }
        }
    }
}

@Composable
private fun Slot(text: String, textColor: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(SCRIM).padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, color = textColor, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SlotButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SCRIM)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

// --- Overlay-Panels (leer, Phase-5-Platzhalter) ---

/** Die vom Overlay aus öffenbaren (leeren) Views; Inhalt = Phase 5. */
enum class OverlayPanel(val title: String) {
    CONFIG("⚙ Konfiguration"),
    ALERTS("⚠ Alerts"),
    SHOW("Show-Posen"),
}

@Composable
private fun OverlayPanelView(panel: OverlayPanel, onClose: () -> Unit) {
    // Scrim: Tap außerhalb schließt. Der innere Block konsumiert Taps (kein Durchschlagen).
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClose,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 380.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(panel.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Phase 5 — noch leer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onClose) { Text("Schließen") }
        }
    }
}

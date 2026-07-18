package io.github.enjoykinua.hexapod

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong

/**
 * P5.11 — generisches, rqt-artiges **Config-Panel** aus `/hexapod/config_manifest` (Contract §6a).
 * Vollbild-scrollbares Overlay (aus dem `⚙ config`-Slot des Fahr-Screens). Rendert die Gruppen/
 * Widgets **generisch** aus dem Manifest; Werte lesen/setzen laufen über die Activity-Callbacks
 * ([onSetParam]/[onRequestParams] → native rosbridge-Param-Services). Reine Logik = [ConfigLogic].
 *
 * Pflichten (Contract §6a): Gating (`standing`) → Widget disabled außerhalb STANDING; Dynamic-Cap →
 * `max` geklemmt; Reject-`reason` je Param angezeigt. „— Erweitert" (16 Gains) eingeklappt + Warnung.
 */
@Composable
fun ConfigPanelOverlay(
    hmi: HmiState,
    lifecycle: LifecycleState,
    contentPadding: PaddingValues,
    onSetParam: (ParamSpec, ParamValue) -> Unit,
    onRequestParams: () -> Unit,
    onClose: () -> Unit,
) {
    // Beim Öffnen aktuelle Werte holen (no-op, wenn Stack aus / kein Manifest).
    LaunchedEffect(Unit) { onRequestParams() }

    val manifest = hmi.manifest
    val stackRunning = lifecycle.stack == StackState.RUNNING

    Box(Modifier.fillMaxSize().background(PANEL_BG)) {
        Column(
            Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⚙ Konfiguration", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TEXT)
                TextButton(onClick = onClose) { Text("Schließen") }
            }
            if (!stackRunning) {
                Text(
                    "Stack nicht aktiv — Werte werden erst nach dem Start gelesen/gesetzt.",
                    color = AMBER,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (manifest == null) {
                Text("Manifest noch nicht geladen …", color = HINT, modifier = Modifier.padding(top = 12.dp))
            } else {
                val groups = remember(manifest) { groupsInOrder(manifest) }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((group, params) in groups) {
                        GroupSection(group, params, hmi, stackRunning, onSetParam)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupSection(
    group: String,
    params: List<ParamSpec>,
    hmi: HmiState,
    stackRunning: Boolean,
    onSetParam: (ParamSpec, ParamValue) -> Unit,
) {
    val advanced = remember(params) { groupIsAdvanced(params) }
    var expanded by remember(group) { mutableStateOf(!advanced) }   // advanced-Gruppe zu, Rest offen

    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = DIVIDER)
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (if (expanded) "▾ " else "▸ ") + group,
                color = TEXT,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (advanced) {
            Text(
                "⚠ Feintuning — falsche Werte können den Roboter aufschwingen lassen.",
                color = AMBER,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (expanded) {
            for (p in params) {
                ParamRow(p, hmi, stackRunning, onSetParam)
                HorizontalDivider(color = DIVIDER_SUBTLE)
            }
        }
    }
}

@Composable
private fun ParamRow(
    spec: ParamSpec,
    hmi: HmiState,
    stackRunning: Boolean,
    onSetParam: (ParamSpec, ParamValue) -> Unit,
) {
    val enabled = isParamEnabled(spec, hmi.status, stackRunning)
    val value = currentOrDefault(spec, hmi.paramValues)
    val error = hmi.paramErrors[spec.key()]

    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (spec.widget) {
            Widget.SLIDER -> SliderParam(spec, value, enabled, hmi.status, error, onSetParam)
            Widget.TOGGLE -> ToggleParam(spec, value, enabled, onSetParam)
            Widget.DROPDOWN -> DropdownParam(spec, value, enabled, onSetParam)
            Widget.UNKNOWN -> Text("${spec.label} (unbekanntes Widget)", color = HINT)
        }
        error?.let {
            Text("⚠ abgelehnt: $it", color = RED, style = MaterialTheme.typography.bodySmall)
        }
        if (!enabled) {
            Text(gatingHint(spec, stackRunning), color = HINT, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Hinweis, warum ein Widget gerade disabled ist. */
private fun gatingHint(spec: ParamSpec, stackRunning: Boolean): String = when {
    !stackRunning -> "erst nach Hexapod-Start"
    spec.gating == "standing" -> "nur im Stand (STANDING)"
    else -> ""
}

@Composable
private fun SliderParam(
    spec: ParamSpec,
    value: ParamValue?,
    enabled: Boolean,
    status: StatusSnapshot?,
    revertSignal: String?,
    onSetParam: (ParamSpec, ParamValue) -> Unit,
) {
    val min = spec.min ?: 0.0
    val max = maxOf(effectiveMax(spec, status) ?: spec.max ?: (min + 1.0), min + (spec.step ?: 0.001))
    val cur = (value?.asDoubleOrNull() ?: min).coerceIn(min, max)

    fun commit(d: Double) {
        val snapped = clampDouble(d, spec.min, effectiveMax(spec, status), spec.step)
        val pv = if (spec.type == ParamType.INT) ParamValue.IntV(snapped.roundToLong()) else ParamValue.DoubleV(snapped)
        onSetParam(spec, pv)
    }

    // Slider-Zwischenwert lokal halten, erst bei Loslassen setzen (kein set_parameters-Flood). Reset
    // auf den bestätigten Wert, wenn sich cur/max ändert ODER ein Reject kommt (revertSignal): dann
    // springt der Slider auf den letzten bestätigten Wert zurück (kein optimistisches Übernehmen).
    var draft by remember(cur, max, revertSignal) { mutableStateOf(cur.toFloat()) }

    LabelRow(spec) {
        ValueField(spec, cur, enabled) { text -> parseTypedInput(text, spec, status)?.let { onSetParam(spec, it) } }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StepButton("−", enabled) { commit(nudge(cur, spec, up = false, status)) }
        Slider(
            value = draft,
            onValueChange = { draft = it },
            onValueChangeFinished = { commit(draft.toDouble()) },
            valueRange = min.toFloat()..max.toFloat(),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        StepButton("+", enabled) { commit(nudge(cur, spec, up = true, status)) }
    }
    Text(
        "min ${formatDouble(min, spec.step)} · max ${formatDouble(max, spec.step)}" +
            (spec.dynamicCap?.let { " (Cap aktiv)" } ?: ""),
        color = HINT,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ToggleParam(
    spec: ParamSpec,
    value: ParamValue?,
    enabled: Boolean,
    onSetParam: (ParamSpec, ParamValue) -> Unit,
) {
    val checked = (value as? ParamValue.BoolV)?.v ?: false
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelText(spec, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onSetParam(spec, ParamValue.BoolV(it)) }, enabled = enabled)
    }
}

@Composable
private fun DropdownParam(
    spec: ParamSpec,
    value: ParamValue?,
    enabled: Boolean,
    onSetParam: (ParamSpec, ParamValue) -> Unit,
) {
    val options = spec.options ?: emptyList()
    val current = (value as? ParamValue.StringV)?.v ?: ""
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelText(spec, Modifier.weight(1f))
        Box {
            StepButton(if (current.isEmpty()) "wählen ▾" else "$current ▾", enabled) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for (opt in options) {
                    DropdownMenuItem(
                        text = { Text(opt) },
                        onClick = { open = false; onSetParam(spec, ParamValue.StringV(opt)) },
                    )
                }
            }
        }
    }
}

// --- gemeinsame Bausteine ---

@Composable
private fun LabelRow(spec: ParamSpec, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelText(spec, Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun LabelText(spec: ParamSpec, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(spec.label + (spec.unit?.let { " ($it)" } ?: ""), color = TEXT, fontWeight = FontWeight.Medium)
        if (spec.hint.isNotEmpty()) {
            Text(spec.hint, color = HINT, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Eintipp-Feld für den exakten Wert; committet bei „Fertig" (IME-Done). */
@Composable
private fun ValueField(spec: ParamSpec, cur: Double, enabled: Boolean, onCommit: (String) -> Unit) {
    var text by remember(cur) { mutableStateOf(formatDouble(cur, spec.step)) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit(text) }),
        modifier = Modifier.width(110.dp),
    )
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) MaterialTheme.colorScheme.primary else Color(0xFF3A3A3A)
    val fg = if (enabled) MaterialTheme.colorScheme.onPrimary else Color(0xFF777777)
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

private val PANEL_BG = Color(0xF2101014)
private val TEXT = Color(0xFFECECEC)
private val HINT = Color(0xFF9A9A9A)
private val AMBER = Color(0xFFE0B341)
private val RED = Color(0xFFEE6666)
private val DIVIDER = Color(0x33FFFFFF)
private val DIVIDER_SUBTLE = Color(0x14FFFFFF)

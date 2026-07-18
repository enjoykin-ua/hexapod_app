package io.github.enjoykinua.hexapod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * P5.12 — Alerts-View (Contract §6a): Liste der `/hexapod/alerts` (neueste oben, Level-farbig) +
 * **„Alles kopieren"** (Clipboard) + lokaler **„Löschen"**-Button (leert nur die App-Liste; die
 * latched Historie liefert beim Reconnect neu). Reine Listen-Logik = [AlertLogic].
 */
@Composable
fun AlertsPanelOverlay(
    alerts: List<Alert>,
    contentPadding: PaddingValues,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Box(Modifier.fillMaxSize().background(ALERTS_BG)) {
        Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "⚠ Alerts (${alerts.size})",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = A_TEXT,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { clipboard.setText(AnnotatedString(alertsToClipboard(alerts))) },
                        enabled = alerts.isNotEmpty(),
                    ) { Text("Kopieren") }
                    TextButton(onClick = onClear, enabled = alerts.isNotEmpty()) { Text("Löschen") }
                    TextButton(onClick = onClose) { Text("Schließen") }
                }
            }

            if (alerts.isEmpty()) {
                Text("Keine Alerts.", color = A_HINT, modifier = Modifier.padding(top = 12.dp))
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (a in alerts) AlertRow(a)
                }
            }
        }
    }
}

@Composable
private fun AlertRow(a: Alert) {
    val levelColor = when (a.level) {
        "FATAL", "ERROR" -> A_RED
        "WARN" -> A_AMBER
        else -> A_HINT
    }
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(a.level, color = levelColor, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(a.name, color = A_HINT, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
        Text(a.msg, color = A_TEXT, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider(color = A_DIVIDER, modifier = Modifier.padding(top = 4.dp))
    }
}

private val ALERTS_BG = Color(0xF2101014)
private val A_TEXT = Color(0xFFECECEC)
private val A_HINT = Color(0xFF9A9A9A)
private val A_AMBER = Color(0xFFE0B341)
private val A_RED = Color(0xFFEE6666)
private val A_DIVIDER = Color(0x14FFFFFF)

package io.github.enjoykinua.hexapod

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Reine Anzeige der aktuell gemessenen Kishi-**Roh**-Eingaben (Debug/Diagnose).
 * Achsen kommen dynamisch aus den motionRanges, Buttons erscheinen bei erstem Druck.
 * Aktive Zeilen (Achse ausgelenkt / Button gedrueckt) werden hervorgehoben, damit beim
 * "eine Eingabe nach der anderen"-Vorgehen eindeutig ist, welche Konstante reagiert.
 *
 * **Section** (kein eigenes Scroll/fillMaxSize) — wird von [ControlScreen] in dessen
 * Scroll-Column eingebettet.
 */
@Composable
fun GamepadReaderSection(state: GamepadState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DeviceHeader(state)

        state.lastEvent?.let {
            Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }

        SectionTitle("Achsen (dynamisch aus motionRanges)")
        if (state.axes.isEmpty()) {
            Hint("Noch keine Achsen — Sticks / Trigger / D-Pad bewegen.")
        } else {
            for ((axis, reading) in state.axes.entries.sortedBy { it.key }) {
                AxisRow(axis, reading)
            }
        }

        SectionTitle("Buttons (bei Druck erkannt)")
        if (state.buttons.isEmpty()) {
            Hint("Noch keine Buttons — jeden Knopf einmal druecken.")
        } else {
            for ((keyCode, pressed) in state.buttons.entries.sortedBy { it.key }) {
                ButtonRow(keyCode, pressed)
            }
        }
    }
}

@Composable
private fun DeviceHeader(state: GamepadState) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            if (state.deviceFound) "Gamepad gefunden: ja" else "Gamepad gefunden: nein",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
        if (state.deviceFound) {
            Text("Name: ${state.deviceName ?: "?"}", fontFamily = FontFamily.Monospace)
            val match =
                if (matchesKishi(state.vendorId, state.productId)) "✓ Kishi V2" else "✗ nicht erwartet"
            Text(
                "Vendor/Product: ${hex4(state.vendorId)} / ${hex4(state.productId)}  ($match)",
                fontFamily = FontFamily.Monospace
            )
        } else {
            Text("Kishi anstecken bzw. Handy im Kishi verbinden.")
        }
    }
}

@Composable
private fun AxisRow(axis: Int, reading: AxisReading) {
    val norm = normalize(reading.value, reading.min, reading.max)
    // Ruhelage heuristisch: bipolare Achsen (min < 0) ruhen mittig, unipolare (Trigger) bei 0.
    val bipolar = reading.min < 0f
    val rest = if (bipolar) 0.5f else 0f
    val active = abs(norm - rest) > 0.15f
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                MotionEvent.axisToString(axis),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
            Text(
                "%+.2f  [%.0f..%.0f]".format(reading.value, reading.min, reading.max),
                fontFamily = FontFamily.Monospace
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(norm)
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun ButtonRow(keyCode: Int, pressed: Boolean) {
    val bg = if (pressed) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            KeyEvent.keyCodeToString(keyCode),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
        Text(if (pressed) "● gedrueckt" else "○ frei", fontFamily = FontFamily.Monospace)
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

@Composable
internal fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

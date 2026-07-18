package io.github.enjoykinua.hexapod

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput

/**
 * P5.13 — leichtgewichtige **3D-Roboter-Ansicht** (zero-dep, [ADR-P5-4]): zeichnet den Hexapod aus
 * `/joint_states` als Strichmodell auf ein Compose-`Canvas`. FK + Kamera-Projektion sind rein
 * ([Robot3dLogic], rechtshändig → **spiegelfrei**); hier Gesten + Zeichnen. Kein 3D-Engine, kein Mesh.
 *
 * **Interaktion:** 1 Finger = orbitieren (horizontal = Azimuth, vertikal = Elevation, ~oben↔seitlich),
 * 2 Finger = zoomen. Die **Kamera ist fix** (feste Skalierung/Zentrierung, unabhängig von der Live-
 * Pose) → das Modell „schwingt" nicht, wenn sich die Beine bewegen. Zoom/Blickwinkel bleiben über
 * Pose-Änderungen erhalten; sie werden **nicht** über einen Neustart gespeichert (bewusst).
 *
 * **Füße:** je Fuß grün bei Bodenkontakt ([footContacts]), sonst grau. Bein 1 hat einen grünen Femur
 * zur Orientierung. Fehlen die Joints → statische Default-Pose (alle Winkel 0) + Hinweis.
 */
@Composable
fun Robot3dView(joints: Map<String, Double>, footContacts: List<Boolean>, modifier: Modifier = Modifier) {
    val legs = remember(joints) { robotLegs(joints) }
    var scale by remember { mutableStateOf(1f) }
    var azimuth by remember { mutableStateOf(0f) }
    var elevation by remember { mutableStateOf(DEFAULT_ELEV) }

    Box(
        modifier.pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)          // 2 Finger: zoomen
                azimuth += pan.x * ROT_SPEED                                 // 1 Finger horiz.: orbitieren
                elevation = (elevation - pan.y * ROT_SPEED).coerceIn(EL_MIN, EL_MAX)  // vert.: hoch = mehr von oben
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            // Feste px-pro-Meter-Skalierung (× Nutzer-Zoom): unabhängig von der Live-Pose -> kein Schwingen.
            val s = minOf(size.width, size.height) * FIT_FRACTION / MODEL_RADIUS_M * scale
            val az = azimuth.toDouble()
            val el = elevation.toDouble()
            fun screen(v: Vec3): Offset {
                val pt = cameraProject(v, az, el)
                return Offset(cx + (pt.x * s).toFloat(), cy + (pt.y * s).toFloat())
            }

            val projLegs = legs.map { leg -> leg.map { screen(it) } }

            // Body-Umriss: die 6 Mount-Punkte (leg[i][0]) in Bein-Reihenfolge verbinden.
            val body = projLegs.map { it[0] }
            for (i in body.indices) {
                drawLine(BODY, body[i], body[(i + 1) % body.size], strokeWidth = 3f, cap = StrokeCap.Round)
            }

            // Beine: coxa / femur / tibia + Fuß-Punkt. Bein 1 (Index 0) = grüner Femur; Fuß grün bei Kontakt.
            projLegs.forEachIndexed { idx, leg ->
                drawLine(COXA, leg[0], leg[1], strokeWidth = 6f, cap = StrokeCap.Round)
                drawLine(if (idx == 0) LEG1_FEMUR else FEMUR, leg[1], leg[2], strokeWidth = 6f, cap = StrokeCap.Round)
                drawLine(TIBIA, leg[2], leg[3], strokeWidth = 5f, cap = StrokeCap.Round)
                drawCircle(if (footContacts.getOrNull(idx) == true) FOOT_CONTACT else FOOT_NONE, radius = FOOT_RADIUS_PX, center = leg[3])
            }
        }
        if (joints.isEmpty()) {
            Text("Warte auf /joint_states …", color = Color(0xFFBBBBBB), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val MODEL_RADIUS_M = 0.34f   // ~ max Fuß-Reichweite vom Zentrum (feste Kamera-Referenz)
private const val FIT_FRACTION = 0.42f      // Modell-Halbradius als Anteil der kürzeren Kante bei Zoom 1
private const val MIN_ZOOM = 0.4f
private const val MAX_ZOOM = 6f
private const val ROT_SPEED = 0.007f        // rad pro gezogenem px
private const val DEFAULT_ELEV = 0.6f       // ~34° (3/4-Blick)
private const val EL_MIN = 0.15f            // ~9° (fast von der Seite)
private const val EL_MAX = 1.5f             // ~86° (fast von oben; < 90° = keine Singularität)
private const val FOOT_RADIUS_PX = 12f      // 2× (vorher 6) — besser erkennbar

private val BODY = Color(0xFF66CCFF)
private val COXA = Color(0xFFAAAAAA)
private val FEMUR = Color(0xFFFF9944)
private val LEG1_FEMUR = Color(0xFF55EE77)   // Bein 1 grün markiert
private val TIBIA = Color(0xFFDDDDDD)
private val FOOT_CONTACT = Color(0xFF44EE66)  // Fuß mit Bodenkontakt
private val FOOT_NONE = Color(0xFF888888)     // Fuß ohne Kontakt (grau)

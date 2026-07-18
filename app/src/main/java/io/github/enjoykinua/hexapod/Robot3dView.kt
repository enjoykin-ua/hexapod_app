package io.github.enjoykinua.hexapod

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap

/**
 * P5.13 — leichtgewichtige **3D-Roboter-Ansicht** (zero-dep, [ADR-P5-4]): zeichnet den Hexapod aus
 * `/joint_states` als isometrisches Strichmodell auf ein Compose-`Canvas`. FK + Projektion sind rein
 * ([Robot3dLogic]); hier nur Auto-Fit + Zeichnen. Kein 3D-Engine, kein Mesh.
 *
 * Fehlen die Joints (Stack aus / noch keine Daten) → statische Default-Pose (alle Winkel 0) + Hinweis.
 */
@Composable
fun Robot3dView(joints: Map<String, Double>, modifier: Modifier = Modifier) {
    val legs = remember(joints) { robotLegs(joints) }

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val projLegs = legs.map { leg -> leg.map { project(it) } }
            val all = projLegs.flatten()
            if (all.isEmpty()) return@Canvas

            val minX = all.minOf { it.x }
            val maxX = all.maxOf { it.x }
            val minY = all.minOf { it.y }
            val maxY = all.maxOf { it.y }
            val pad = 0.12f * minOf(size.width, size.height)
            val spanX = (maxX - minX).coerceAtLeast(1e-6)
            val spanY = (maxY - minY).coerceAtLeast(1e-6)
            val scale = minOf((size.width - 2 * pad) / spanX, (size.height - 2 * pad) / spanY)
            // zentrieren: Rest-Platz gleichmäßig verteilen
            val offX = (size.width - spanX * scale) / 2.0
            val offY = (size.height - spanY * scale) / 2.0
            fun screen(p: Pt2) = Offset(
                (offX + (p.x - minX) * scale).toFloat(),
                (offY + (p.y - minY) * scale).toFloat(),
            )

            // Body-Umriss: die 6 Mount-Punkte (leg[i][0]) in Bein-Reihenfolge verbinden.
            val body = projLegs.map { screen(it[0]) }
            for (i in body.indices) {
                drawLine(BODY, body[i], body[(i + 1) % body.size], strokeWidth = 3f, cap = StrokeCap.Round)
            }

            // Beine: coxa / femur / tibia + Fuß-Punkt.
            for (leg in projLegs) {
                val p = leg.map { screen(it) }
                drawLine(COXA, p[0], p[1], strokeWidth = 6f, cap = StrokeCap.Round)
                drawLine(FEMUR, p[1], p[2], strokeWidth = 6f, cap = StrokeCap.Round)
                drawLine(TIBIA, p[2], p[3], strokeWidth = 5f, cap = StrokeCap.Round)
                drawCircle(FOOT, radius = 6f, center = p[3])
            }
        }
        if (joints.isEmpty()) {
            Text("Warte auf /joint_states …", color = Color(0xFFBBBBBB), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private val BODY = Color(0xFF66CCFF)
private val COXA = Color(0xFFAAAAAA)
private val FEMUR = Color(0xFFFF9944)
private val TIBIA = Color(0xFFDDDDDD)
private val FOOT = Color(0xFF66FF99)

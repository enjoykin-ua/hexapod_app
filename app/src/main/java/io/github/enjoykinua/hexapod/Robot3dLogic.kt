package io.github.enjoykinua.hexapod

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reine 3D-Kinematik-/Projektions-Logik (Phase 5, P5.13) — per JUnit **ohne Gerät/Netz** testbar
 * ([Robot3dLogicTest]). Zero-dependency (kein 3D-Engine, [ADR-P5-4]): Forward-Kinematics aus den 18
 * Joint-Winkeln (`/joint_states`) + isometrische Projektion → 2D-Punkte, die [Robot3dView] auf ein
 * Compose-`Canvas` zeichnet.
 *
 * **Geometrie statisch aus der `hexapod_description`-URDF** (dokumentierte Quelle, kein Laufzeit-
 * Contract): Link-Längen aus `hexapod_physical_properties.xacro`, Bein-Mounts + Joint-Achsen/-Origins
 * aus `leg.xacro`/`hexapod.urdf.xacro`. Kette je Bein: base → coxa(Achse z) → femur(y) → tibia(y) → Fuß.
 */

// Link-Längen [m] (hexapod_physical_properties.xacro).
private const val COXA_LENGTH = 0.0436
private const val FEMUR_LENGTH = 0.060
private const val TIBIA_LENGTH = 0.134

/** Ein 3D-Punkt im base_link-Frame [m] (x vorne, y links, z oben). */
data class Vec3(val x: Double, val y: Double, val z: Double)

/** Ein projizierter 2D-Punkt (Canvas-Koordinaten, noch unskaliert/unzentriert). */
data class Pt2(val x: Double, val y: Double)

/** Bein-Montagepunkt am Body: Position + Gier-Winkel (Ausrichtung nach außen). */
data class LegMount(val x: Double, val y: Double, val z: Double, val yaw: Double)

/**
 * Die 6 Bein-Mounts (hexapod.urdf.xacro), Reihenfolge = Bein-ID 1..6:
 * 1 vorne-rechts, 2 mitte-rechts, 3 hinten-rechts, 4 hinten-links, 5 mitte-links, 6 vorne-links.
 * body_length/2=0.0875, body_width/2=0.065, body_width_middle/2=0.085.
 */
val LEG_MOUNTS: List<LegMount> = listOf(
    LegMount(0.0875, -0.065, 0.0, -PI / 4),
    LegMount(0.0, -0.085, 0.0, -PI / 2),
    LegMount(-0.0875, -0.065, 0.0, -3 * PI / 4),
    LegMount(-0.0875, 0.065, 0.0, 3 * PI / 4),
    LegMount(0.0, 0.085, 0.0, PI / 2),
    LegMount(0.0875, 0.065, 0.0, PI / 4),
)

/**
 * Forward-Kinematics eines Beins → 4 Punkte im base-Frame: Mount → Femur-Gelenk → Tibia-Gelenk →
 * Fuß. Coxa dreht in der XY-Ebene (Achse z: horizontaler Winkel = `yaw + coxa`); femur/tibia kippen
 * in der Vertikalebene entlang dieser Richtung (Achse y: kumulierte Winkel `femur`, `femur+tibia`).
 */
fun legPoints(m: LegMount, coxa: Double, femur: Double, tibia: Double): List<Vec3> {
    val dir = m.yaw + coxa
    val ch = cos(dir)
    val sh = sin(dir)
    val p0 = Vec3(m.x, m.y, m.z)
    val p1 = Vec3(p0.x + COXA_LENGTH * ch, p0.y + COXA_LENGTH * sh, p0.z)
    val cf = cos(femur)
    val p2 = Vec3(p1.x + FEMUR_LENGTH * cf * ch, p1.y + FEMUR_LENGTH * cf * sh, p1.z - FEMUR_LENGTH * sin(femur))
    val cft = cos(femur + tibia)
    val p3 = Vec3(p2.x + TIBIA_LENGTH * cft * ch, p2.y + TIBIA_LENGTH * cft * sh, p2.z - TIBIA_LENGTH * sin(femur + tibia))
    return listOf(p0, p1, p2, p3)
}

/**
 * Alle 6 Beine als je 4 base-Frame-Punkte aus den `/joint_states`-Winkeln (fehlender Joint → 0).
 * Joint-Namen wie in der URDF: `leg_<id>_{coxa,femur,tibia}_joint`.
 */
fun robotLegs(joints: Map<String, Double>): List<List<Vec3>> =
    (1..6).map { id ->
        legPoints(
            LEG_MOUNTS[id - 1],
            joints["leg_${id}_coxa_joint"] ?: 0.0,
            joints["leg_${id}_femur_joint"] ?: 0.0,
            joints["leg_${id}_tibia_joint"] ?: 0.0,
        )
    }

/** Isometrischer Blickwinkel (30°) für die Projektion. */
private const val ISO = PI / 6

/**
 * Isometrische Projektion base-Frame → 2D: `sx = (x−y)·cos30`, `sy = (x+y)·sin30 − z`. Höheres z
 * (weiter oben) → kleineres `sy` → im Canvas (y nach unten) weiter oben. Skalierung/Zentrierung
 * macht [Robot3dView] (auto-fit).
 */
fun project(v: Vec3): Pt2 = Pt2(
    x = (v.x - v.y) * cos(ISO),
    y = (v.x + v.y) * sin(ISO) - v.z,
)

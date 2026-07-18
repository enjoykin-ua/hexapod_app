package io.github.enjoykinua.hexapod

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reine 3D-FK/Projektion ([Robot3dLogic]) — bekannte Winkel → bekannte Punkte. */
class Robot3dLogicTest {

    private val totalReach = 0.0436 + 0.060 + 0.134   // coxa+femur+tibia

    @Test
    fun `legPoints Nullstellung streckt entlang yaw in z gleich 0`() {
        // Bein 2: Mount (0,-0.085,0), yaw -pi/2 -> Richtung (0,-1); alle Winkel 0 -> flach gestreckt
        val pts = legPoints(LEG_MOUNTS[1], 0.0, 0.0, 0.0)
        assertEquals(4, pts.size)
        val foot = pts[3]
        assertEquals(0.0, foot.x, 1e-6)
        assertEquals(-0.085 - totalReach, foot.y, 1e-6)
        assertEquals(0.0, foot.z, 1e-6)
    }

    @Test
    fun `femur-Winkel kippt den Fuss nach unten`() {
        // femur = pi/2 -> Femur-Ende (p2) genau femur_length unter dem Femur-Gelenk
        val pts = legPoints(LEG_MOUNTS[1], 0.0, PI / 2, 0.0)
        assertEquals(-0.060, pts[2].z, 1e-6)
    }

    @Test
    fun `robotLegs liefert 6 Beine je 4 Punkte`() {
        val legs = robotLegs(emptyMap())
        assertEquals(6, legs.size)
        assertTrue(legs.all { it.size == 4 })
    }

    @Test
    fun `robotLegs nutzt die Joint-Winkel je Bein`() {
        val legs = robotLegs(mapOf("leg_1_femur_joint" to PI / 2))
        assertEquals(-0.060, legs[0][2].z, 1e-6)   // Bein 1 femur gesetzt
        assertEquals(0.0, legs[1][2].z, 1e-6)      // Bein 2 unberührt (0)
    }

    @Test
    fun `project ist isometrisch und z zeigt nach oben`() {
        val px = project(Vec3(1.0, 0.0, 0.0))
        assertEquals(cos(PI / 6), px.x, 1e-9)
        assertEquals(sin(PI / 6), px.y, 1e-9)
        val pz = project(Vec3(0.0, 0.0, 1.0))
        assertEquals(0.0, pz.x, 1e-9)
        assertEquals(-1.0, pz.y, 1e-9)   // höheres z -> kleineres sy (oben)
    }
}

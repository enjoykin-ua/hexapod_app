package io.github.enjoykinua.hexapod

import kotlin.math.PI
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
    fun `cameraProject spiegelfrei von oben`() {
        // Von oben (elevation pi/2, azimuth 0): x vorne -> oben, +y links -> links, Ursprung fix.
        val front = cameraProject(Vec3(1.0, 0.0, 0.0), 0.0, PI / 2)
        assertEquals(0.0, front.x, 1e-9)
        assertEquals(-1.0, front.y, 1e-9)   // vorne (x+) -> screen oben (kleineres y)
        val left = cameraProject(Vec3(0.0, 1.0, 0.0), 0.0, PI / 2)
        assertEquals(-1.0, left.x, 1e-9)    // links (y+) -> screen links (kein Spiegel)
        assertEquals(0.0, left.y, 1e-9)
        val origin = cameraProject(Vec3(0.0, 0.0, 0.0), 1.2, 0.7)
        assertEquals(0.0, origin.x, 1e-9)   // Body-Ursprung bleibt fix (Zentrum)
        assertEquals(0.0, origin.y, 1e-9)
    }

    @Test
    fun `cameraProject z zeigt nach oben`() {
        // Seitenblick (elevation 0): z -> nach oben (screen y negativ).
        val up = cameraProject(Vec3(0.0, 0.0, 1.0), 0.0, 0.0)
        assertEquals(0.0, up.x, 1e-9)
        assertEquals(-1.0, up.y, 1e-9)
    }

    @Test
    fun `cameraProject Bein 1 liegt oben-rechts`() {
        // 3/4-Blick: Bein 1 (vorne-rechts, y<0) -> rechts (x>0) und oben (y<0). Chiralität wie Sim.
        val leg1Mount = LEG_MOUNTS[0]
        val p = cameraProject(Vec3(leg1Mount.x, leg1Mount.y, leg1Mount.z), 0.0, 0.6)
        assertTrue("Bein 1 rechts", p.x > 0.0)
        assertTrue("Bein 1 oben", p.y < 0.0)
    }
}

package io.github.enjoykinua.hexapod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reine Config-Panel-Logik ([ConfigLogic]) — ohne Gerät/Netz. Deckt die Contract-§6a-Pflichten:
 * Dynamic-Cap ([effectiveMax]), Gating ([isParamEnabled]), Step-/Range-Klemmung, Eintipp-Validierung.
 */
class ConfigLogicTest {

    private fun spec(
        node: String = "/gait_node",
        param: String = "p",
        group: String = "G",
        widget: Widget = Widget.SLIDER,
        type: ParamType = ParamType.DOUBLE,
        default: ParamValue? = null,
        min: Double? = null,
        max: Double? = null,
        step: Double? = null,
        gating: String? = null,
        dynamicCap: String? = null,
        advanced: Boolean = false,
    ) = ParamSpec(node, param, group, "label", "hint", widget, type, default, min, max, step, null, null, gating, dynamicCap, advanced)

    private fun status(
        state: String = "STANDING",
        stepHeightCap: Double? = null,
        stepLengthCap: Double? = null,
    ) = StatusSnapshot(state, 1, "mittel", "tripod", false, "none", stepHeightCap, stepLengthCap)

    // --- effectiveMax (Dynamic-Cap) ---

    @Test
    fun `effectiveMax klemmt auf den Cap wenn kleiner`() {
        val s = spec(max = 0.09, dynamicCap = "step_height_cap")
        assertEquals(0.05, effectiveMax(s, status(stepHeightCap = 0.05))!!, 1e-9)
    }

    @Test
    fun `effectiveMax bleibt Manifest-max wenn Cap groesser`() {
        val s = spec(max = 0.09, dynamicCap = "step_height_cap")
        assertEquals(0.09, effectiveMax(s, status(stepHeightCap = 0.12))!!, 1e-9)
    }

    @Test
    fun `effectiveMax ohne Cap oder ohne Status = Manifest-max`() {
        assertEquals(0.09, effectiveMax(spec(max = 0.09), status())!!, 1e-9)
        assertEquals(0.09, effectiveMax(spec(max = 0.09, dynamicCap = "step_height_cap"), null)!!, 1e-9)
    }

    @Test
    fun `effectiveMax ohne max = null`() {
        assertNull(effectiveMax(spec(max = null), status()))
    }

    // --- isParamEnabled (Gating + Stack) ---

    @Test
    fun `isParamEnabled false wenn Stack aus`() {
        assertFalse(isParamEnabled(spec(), status(), stackRunning = false))
    }

    @Test
    fun `isParamEnabled true ohne Gating bei laufendem Stack`() {
        assertTrue(isParamEnabled(spec(gating = null), status(state = "WALKING"), stackRunning = true))
    }

    @Test
    fun `isParamEnabled standing-gated nur im Stand`() {
        val s = spec(gating = "standing")
        assertTrue(isParamEnabled(s, status(state = "STANDING"), stackRunning = true))
        assertFalse(isParamEnabled(s, status(state = "WALKING"), stackRunning = true))
        assertFalse(isParamEnabled(s, null, stackRunning = true))
    }

    // --- clampDouble / nudge ---

    @Test
    fun `clampDouble rundet aufs Step-Raster`() {
        assertEquals(0.035, clampDouble(0.037, 0.01, 0.09, 0.005), 1e-9)
    }

    @Test
    fun `clampDouble klemmt an die Grenzen`() {
        assertEquals(0.09, clampDouble(0.2, 0.01, 0.09, 0.005), 1e-9)
        assertEquals(0.0, clampDouble(-1.0, 0.0, 1.0, null), 1e-9)
        assertEquals(1.0, clampDouble(5.0, 0.0, 1.0, null), 1e-9)
    }

    @Test
    fun `nudge geht einen Schritt und klemmt an den Grenzen`() {
        val s = spec(min = 0.01, max = 0.09, step = 0.005)
        assertEquals(0.055, nudge(0.05, s, up = true, status = null), 1e-9)
        assertEquals(0.09, nudge(0.09, s, up = true, status = null), 1e-9)
        assertEquals(0.01, nudge(0.01, s, up = false, status = null), 1e-9)
    }

    @Test
    fun `nudge respektiert den dynamischen Cap`() {
        val s = spec(min = 0.01, max = 0.09, step = 0.005, dynamicCap = "step_height_cap")
        // Cap 0.05 < Manifest-max -> hoch von 0.05 bleibt 0.05
        assertEquals(0.05, nudge(0.05, s, up = true, status = status(stepHeightCap = 0.05)), 1e-9)
    }

    // --- parseTypedInput ---

    @Test
    fun `parseTypedInput double akzeptiert Komma und klemmt Range`() {
        val s = spec(type = ParamType.DOUBLE, min = 0.01, max = 0.09)
        assertEquals(ParamValue.DoubleV(0.055), parseTypedInput("0,055", s, null))
        assertEquals(ParamValue.DoubleV(0.09), parseTypedInput("0.5", s, null))  // über max -> geklemmt
    }

    @Test
    fun `parseTypedInput ungueltig oder leer = null`() {
        val s = spec(type = ParamType.DOUBLE, min = 0.0, max = 1.0)
        assertNull(parseTypedInput("abc", s, null))
        assertNull(parseTypedInput("", s, null))
    }

    @Test
    fun `parseTypedInput int und bool und string`() {
        assertEquals(ParamValue.IntV(3), parseTypedInput("3", spec(type = ParamType.INT, min = 0.0, max = 10.0), null))
        assertEquals(ParamValue.BoolV(true), parseTypedInput("true", spec(type = ParamType.BOOL), null))
        assertNull(parseTypedInput("xyz", spec(type = ParamType.BOOL), null))
        assertEquals(ParamValue.StringV("auto"), parseTypedInput("auto", spec(type = ParamType.STRING), null))
    }

    // --- Gruppierung ---

    @Test
    fun `groupsInOrder erhaelt Reihenfolge und buendelt`() {
        val m = ConfigManifest(
            1,
            listOf(
                spec(param = "a", group = "Lauf"),
                spec(param = "b", group = "Lauf"),
                spec(param = "c", group = "Teleop"),
            ),
        )
        val groups = groupsInOrder(m)
        assertEquals(listOf("Lauf", "Teleop"), groups.map { it.first })
        assertEquals(2, groups[0].second.size)
        assertEquals(1, groups[1].second.size)
    }

    @Test
    fun `groupIsAdvanced nur wenn alle Params advanced`() {
        assertTrue(groupIsAdvanced(listOf(spec(advanced = true), spec(advanced = true))))
        assertFalse(groupIsAdvanced(listOf(spec(advanced = true), spec(advanced = false))))
        assertFalse(groupIsAdvanced(emptyList()))
    }

    // --- Formatierung + Default ---

    @Test
    fun `decimalsForStep aus dem Step`() {
        assertEquals(3, decimalsForStep(0.005))
        assertEquals(2, decimalsForStep(0.01))
        assertEquals(1, decimalsForStep(0.1))
        assertEquals(0, decimalsForStep(1.0))
        assertEquals(2, decimalsForStep(null))
    }

    @Test
    fun `currentOrDefault nimmt Wert sonst Default`() {
        val s = spec(param = "step_height", default = ParamValue.DoubleV(0.05))
        assertEquals(ParamValue.DoubleV(0.07), currentOrDefault(s, mapOf(s.key() to ParamValue.DoubleV(0.07))))
        assertEquals(ParamValue.DoubleV(0.05), currentOrDefault(s, emptyMap()))
    }
}

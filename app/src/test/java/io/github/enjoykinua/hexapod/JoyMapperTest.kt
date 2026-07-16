package io.github.enjoykinua.hexapod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit-Tests der reinen Transform-Logik [JoyMapper] gegen interface_contract.md v0.3 §1.
 * Kein Gerät/Netz nötig. Bewusst NICHT hier: der org.json-Envelope ([rosbridgePublishJoy])
 * und der Android-Extractor ([toControllerInput]) — beide über die Integration verifiziert.
 */
class JoyMapperTest {

    private val eps = 1e-6f

    @Test
    fun arrayLengths_are_8_axes_and_15_buttons() {
        val joy = JoyMapper.toJoy(ControllerInput())
        assertEquals(8, joy.axes.size)
        assertEquals(15, joy.buttons.size)
    }

    @Test
    fun neutral_input_zeros_sticks_dpad_and_idles_triggers_to_plus1() {
        val joy = JoyMapper.toJoy(ControllerInput())
        // Sticks + D-Pad = 0
        for (i in listOf(0, 1, 3, 4, 6, 7)) assertEquals("axes[$i]", 0f, joy.axes[i], eps)
        // Trigger idle → +1 (sonst Fehl-Stance beim Start)
        assertEquals(1f, joy.axes[2], eps)
        assertEquals(1f, joy.axes[5], eps)
        // keine Buttons
        assertEquals(List(15) { 0 }, joy.buttons)
    }

    @Test
    fun sticks_are_negated() {
        // Kishi: hoch/links = −1  →  PS4-/joy: +1
        val up = JoyMapper.toJoy(ControllerInput(leftStickY = -1f))
        assertEquals(1f, up.axes[1], eps)            // vorwärts
        val left = JoyMapper.toJoy(ControllerInput(leftStickX = -1f))
        assertEquals(1f, left.axes[0], eps)
        val rUp = JoyMapper.toJoy(ControllerInput(rightStickY = -1f))
        assertEquals(1f, rUp.axes[4], eps)
        val rLeft = JoyMapper.toJoy(ControllerInput(rightStickX = -1f))
        assertEquals(1f, rLeft.axes[3], eps)
        // Gegenrichtung
        val down = JoyMapper.toJoy(ControllerInput(leftStickY = 1f))
        assertEquals(-1f, down.axes[1], eps)
    }

    @Test
    fun triggers_map_0to1_onto_plus1toMinus1() {
        assertEquals(1f, JoyMapper.toJoy(ControllerInput(l2 = 0f)).axes[2], eps)
        assertEquals(0f, JoyMapper.toJoy(ControllerInput(l2 = 0.5f)).axes[2], eps)
        assertEquals(-1f, JoyMapper.toJoy(ControllerInput(l2 = 1f)).axes[2], eps)
        assertEquals(-1f, JoyMapper.toJoy(ControllerInput(r2 = 1f)).axes[5], eps)
    }

    @Test
    fun dpad_passthrough_without_negation() {
        val j = JoyMapper.toJoy(ControllerInput(dpadX = 1f, dpadY = -1f))
        assertEquals(1f, j.axes[6], eps)
        assertEquals(-1f, j.axes[7], eps)
    }

    @Test
    fun face_buttons_are_position_based() {
        assertEquals(1, JoyMapper.toJoy(ControllerInput(a = true)).buttons[0])   // unten → Cross
        assertEquals(1, JoyMapper.toJoy(ControllerInput(b = true)).buttons[1])   // rechts → Circle
        assertEquals(1, JoyMapper.toJoy(ControllerInput(y = true)).buttons[2])   // oben → Triangle
        assertEquals(1, JoyMapper.toJoy(ControllerInput(x = true)).buttons[3])   // links → Square
    }

    @Test
    fun shoulders_deadman_and_digital_triggers() {
        assertEquals(1, JoyMapper.toJoy(ControllerInput(l1 = true)).buttons[4])
        assertEquals(1, JoyMapper.toJoy(ControllerInput(r1 = true)).buttons[5])  // Dead-Man
        assertEquals(1, JoyMapper.toJoy(ControllerInput(l2btn = true)).buttons[6])
        assertEquals(1, JoyMapper.toJoy(ControllerInput(r2btn = true)).buttons[7])
    }

    @Test
    fun menu_buttons_map_to_share_options_guide() {
        assertEquals(1, JoyMapper.toJoy(ControllerInput(select = true)).buttons[8])
        assertEquals(1, JoyMapper.toJoy(ControllerInput(start = true)).buttons[9])
        assertEquals(1, JoyMapper.toJoy(ControllerInput(mode = true)).buttons[10])
    }

    @Test
    fun stick_clicks_and_kishi_extras_fill_slots_11_to_14() {
        assertEquals(1, JoyMapper.toJoy(ControllerInput(thumbL = true)).buttons[11])
        assertEquals(1, JoyMapper.toJoy(ControllerInput(thumbR = true)).buttons[12])
        assertEquals(1, JoyMapper.toJoy(ControllerInput(l4 = true)).buttons[13])
        assertEquals(1, JoyMapper.toJoy(ControllerInput(r4 = true)).buttons[14])
    }
}

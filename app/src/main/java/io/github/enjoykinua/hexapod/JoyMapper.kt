package io.github.enjoykinua.hexapod

/**
 * Contract-konforme `sensor_msgs/Joy`-Nutzlast: **8 PS4-Achsen + 15 Buttons**
 * (13 PS4-Buttons + Kishi-Extra L4/R4). Reine Daten — die rosbridge-Hülle baut
 * [rosbridgePublishJoy].
 */
data class JoyMessage(val axes: List<Float>, val buttons: List<Int>)

/**
 * **Reine** Kishi-Roh → PS4-`/joy`-Transform-Logik (interface_contract.md v0.3 §1).
 * Framework-frei → per JUnit ohne Gerät testbar ([JoyMapperTest]).
 *
 * Regeln (§1):
 * - Sticks negiert (Kishi hoch/links = −1, PS4-`/joy` erwartet +1).
 * - Trigger `1 − 2·t` **jeden Frame** (idle 0 → +1; voll 1 → −1).
 * - Face-Buttons **positionsbasiert** (Kishi Xbox-beschriftet): A=unten→0, B=rechts→1,
 *   Y=oben→2, X=links→3.
 * - Keine App-Deadzone (der Roboter filtert).
 */
object JoyMapper {
    const val AXES = 8
    const val BUTTONS = 15

    fun toJoy(input: ControllerInput): JoyMessage {
        val axes = FloatArray(AXES)
        axes[0] = -input.leftStickX          // axis_lx
        axes[1] = -input.leftStickY          // axis_ly
        axes[2] = 1f - 2f * input.l2         // axis_l2 (idle +1 / gedrückt −1)
        axes[3] = -input.rightStickX         // axis_rx
        axes[4] = -input.rightStickY         // axis_ry
        axes[5] = 1f - 2f * input.r2         // axis_r2
        axes[6] = input.dpadX                // axis_dpad_x (Vorzeichen Integration)
        axes[7] = input.dpadY                // axis_dpad_y

        val buttons = IntArray(BUTTONS)
        buttons[0] = input.a.bit()           // Cross    (unten)
        buttons[1] = input.b.bit()           // Circle   (rechts)
        buttons[2] = input.y.bit()           // Triangle (oben)  ← positionsbasiert
        buttons[3] = input.x.bit()           // Square   (links)
        buttons[4] = input.l1.bit()          // L1 (slow)
        buttons[5] = input.r1.bit()          // R1 (Dead-Man)
        buttons[6] = input.l2btn.bit()       // L2 (digital)
        buttons[7] = input.r2btn.bit()       // R2 (digital)
        buttons[8] = input.select.bit()      // Share
        buttons[9] = input.start.bit()       // Options
        buttons[10] = input.mode.bit()       // PS/Guide
        buttons[11] = input.thumbL.bit()     // L3
        buttons[12] = input.thumbR.bit()     // R3
        buttons[13] = input.l4.bit()         // Kishi-Extra L4
        buttons[14] = input.r4.bit()         // Kishi-Extra R4

        return JoyMessage(axes.toList(), buttons.toList())
    }

    private fun Boolean.bit(): Int = if (this) 1 else 0
}

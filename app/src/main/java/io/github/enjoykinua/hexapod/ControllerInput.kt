package io.github.enjoykinua.hexapod

/**
 * Semantische, **framework-freie** Sicht auf die Controller-Eingaben — mit den **rohen**
 * Android-Werten (Sticks −1..+1 mit hoch/links = −1; Trigger 0..1; D-Pad −1/0/+1; Buttons
 * gedrückt/frei). Bewusst KEINE Umrechnung hier: die Kishi→PS4-`/joy`-Transforms macht
 * ausschließlich [JoyMapper] (interface_contract.md v0.3 §1). Dieser Typ ist nur der
 * Transport zwischen dem Android-Extractor ([toControllerInput]) und der reinen, testbaren
 * Transform-Logik.
 */
data class ControllerInput(
    val leftStickX: Float = 0f,   // AXIS_X  (links = −1)
    val leftStickY: Float = 0f,   // AXIS_Y  (hoch = −1)
    val rightStickX: Float = 0f,  // AXIS_Z  (links = −1)
    val rightStickY: Float = 0f,  // AXIS_RZ (hoch = −1)
    val l2: Float = 0f,           // AXIS_LTRIGGER 0..1
    val r2: Float = 0f,           // AXIS_RTRIGGER 0..1
    val dpadX: Float = 0f,        // AXIS_HAT_X −1/0/+1
    val dpadY: Float = 0f,        // AXIS_HAT_Y −1/0/+1
    val a: Boolean = false,       // BUTTON_A (unten)
    val b: Boolean = false,       // BUTTON_B (rechts)
    val x: Boolean = false,       // BUTTON_X (links)
    val y: Boolean = false,       // BUTTON_Y (oben)
    val l1: Boolean = false,
    val r1: Boolean = false,
    val l2btn: Boolean = false,   // BUTTON_L2 (digitaler Trigger-Anschlag)
    val r2btn: Boolean = false,   // BUTTON_R2
    val select: Boolean = false,  // BUTTON_SELECT ("3 Punkte")
    val start: Boolean = false,   // BUTTON_START  ("3 Striche")
    val mode: Boolean = false,    // BUTTON_MODE   (Kreis-mit-Pfeil)
    val thumbL: Boolean = false,  // BUTTON_THUMBL (linker Stick-Klick, L3)
    val thumbR: Boolean = false,  // BUTTON_THUMBR (rechter Stick-Klick, R3)
    val l4: Boolean = false,      // BUTTON_C (Kishi-Extra L4)
    val r4: Boolean = false,      // BUTTON_Z (Kishi-Extra R4)
)

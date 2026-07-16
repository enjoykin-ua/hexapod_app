package io.github.enjoykinua.hexapod

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * Android-Layer: übersetzt den roh gemessenen [GamepadState] (gekeyt auf `MotionEvent.AXIS_*`
 * / `KeyEvent.KEYCODE_*`) in das framework-freie [ControllerInput]. **Rein deklarativ** — die
 * Konstanten-Zuordnung ist die 1:1-Transkription der Roh-Index-Tabelle aus
 * interface_contract.md v0.3 §1 (Kishi V2 @ S22+). Die eigentliche Logik (Negierung, Trigger,
 * Positions-Mapping) liegt danach im testbaren [JoyMapper].
 *
 * Bewusst NICHT unit-getestet (nutzt Android-Konstanten → android.jar-Stubs); die Richtigkeit
 * der Zuordnung wird in der Integration per `ros2 topic echo /joy` bestätigt (Contract §1).
 */
fun GamepadState.toControllerInput(): ControllerInput = ControllerInput(
    leftStickX = axisValue(MotionEvent.AXIS_X),
    leftStickY = axisValue(MotionEvent.AXIS_Y),
    rightStickX = axisValue(MotionEvent.AXIS_Z),
    rightStickY = axisValue(MotionEvent.AXIS_RZ),
    l2 = axisValue(MotionEvent.AXIS_LTRIGGER),   // AXIS_BRAKE-Dublette bewusst ignoriert
    r2 = axisValue(MotionEvent.AXIS_RTRIGGER),   // AXIS_GAS-Dublette bewusst ignoriert
    dpadX = axisValue(MotionEvent.AXIS_HAT_X),
    dpadY = axisValue(MotionEvent.AXIS_HAT_Y),
    a = pressed(KeyEvent.KEYCODE_BUTTON_A),
    b = pressed(KeyEvent.KEYCODE_BUTTON_B),
    x = pressed(KeyEvent.KEYCODE_BUTTON_X),
    y = pressed(KeyEvent.KEYCODE_BUTTON_Y),
    l1 = pressed(KeyEvent.KEYCODE_BUTTON_L1),
    r1 = pressed(KeyEvent.KEYCODE_BUTTON_R1),
    l2btn = pressed(KeyEvent.KEYCODE_BUTTON_L2),
    r2btn = pressed(KeyEvent.KEYCODE_BUTTON_R2),
    select = pressed(KeyEvent.KEYCODE_BUTTON_SELECT),
    start = pressed(KeyEvent.KEYCODE_BUTTON_START),
    mode = pressed(KeyEvent.KEYCODE_BUTTON_MODE),
    thumbL = pressed(KeyEvent.KEYCODE_BUTTON_THUMBL),
    thumbR = pressed(KeyEvent.KEYCODE_BUTTON_THUMBR),
    l4 = pressed(KeyEvent.KEYCODE_BUTTON_C),
    r4 = pressed(KeyEvent.KEYCODE_BUTTON_Z),
)

/** Rohwert der Achse oder 0, falls das Gerät sie nicht meldet. */
private fun GamepadState.axisValue(axis: Int): Float = axes[axis]?.value ?: 0f

/** Button gedrückt? Fehlender Eintrag = noch nie gedrückt = frei. */
private fun GamepadState.pressed(keyCode: Int): Boolean = buttons[keyCode] == true

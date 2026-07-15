package io.github.enjoykinua.hexapod

import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import io.github.enjoykinua.hexapod.ui.theme.Hexapod_appTheme

/**
 * Phase 1 · Stufe B — Kishi-Gamepad-Hello-World.
 *
 * Liest den Controller ueber Framework-Input (kein ROS, kein Netz) und zeigt je Achse/Button
 * den Live-Wert samt Android-Konstante. Gamepad-Events werden auf **Activity-/dispatch-Ebene**
 * abgegriffen (nicht ueber Compose-Fokus): so kommen sie zuverlaessig an, unabhaengig davon,
 * was im Compose-Baum den Fokus hat.
 */
class MainActivity : ComponentActivity() {

    private lateinit var inputManager: InputManager
    private val gamepadState = GamepadState()

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshDevices()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshDevices()
        override fun onInputDeviceChanged(deviceId: Int) = refreshDevices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inputManager = getSystemService(InputManager::class.java)
        // Bildschirm waehrend des Tests wach halten (Handy steckt im Kishi, Querformat ist im
        // Manifest gesperrt). Kein Immersive-/Kiosk-Modus -> Verlassen per System-Navigation
        // bleibt normal moeglich.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        enableEdgeToEdge()
        setContent {
            Hexapod_appTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    GamepadReaderScreen(gamepadState, Modifier.padding(innerPadding))
                }
            }
        }

        refreshDevices()
    }

    override fun onResume() {
        super.onResume()
        // Hot-Plug beobachten; Listener an den Lifecycle koppeln, sonst Leak.
        inputManager.registerInputDeviceListener(deviceListener, Handler(Looper.getMainLooper()))
        refreshDevices()
    }

    override fun onPause() {
        super.onPause()
        inputManager.unregisterInputDeviceListener(deviceListener)
    }

    /** Erstes Geraet mit Gamepad-/Joystick-Source suchen und Achsenliste vorbelegen. */
    private fun refreshDevices() {
        for (id in inputManager.inputDeviceIds) {
            val device = inputManager.getInputDevice(id) ?: continue
            val sources = device.sources
            val isGamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
            val isJoystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            if (isGamepad || isJoystick) {
                gamepadState.setDevice(device.name, device.vendorId, device.productId)
                // Achsen sofort auflisten (Wert 0), damit die Bereiche schon vor der ersten
                // Bewegung sichtbar sind.
                for (range in device.motionRanges) {
                    gamepadState.onAxis(range.axis, 0f, range.min, range.max)
                }
                return
            }
        }
        gamepadState.clearDevice()
    }

    // dispatch*-Ebene statt onGenericMotionEvent/onKeyDown: wird garantiert vor der
    // View-Zustellung aufgerufen -> nichts kann uns die Gamepad-Events "wegschnappen".

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            captureAxes(event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val fromGamepad = event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            KeyEvent.isGamepadButton(keyCode)
        if (fromGamepad) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    gamepadState.onButton(keyCode, true)
                    gamepadState.lastEvent = "Taste ↓ ${KeyEvent.keyCodeToString(keyCode)}"
                }
                KeyEvent.ACTION_UP -> gamepadState.onButton(keyCode, false)
            }
            // Konsumieren: verhindert u. a., dass BUTTON_B als "Zurueck" die App schliesst
            // oder das D-Pad den Fokus verschiebt.
            return true
        }
        // Alles Nicht-Gamepad (Zurueck, Home, Lautstaerke, ...) normal weiterreichen.
        return super.dispatchKeyEvent(event)
    }

    /** Alle Achsen des Geraets aus dem aktuellen Event lesen und in den State schieben. */
    private fun captureAxes(event: MotionEvent) {
        val device = event.device ?: return
        for (range in device.motionRanges) {
            gamepadState.onAxis(range.axis, event.getAxisValue(range.axis), range.min, range.max)
        }
    }
}

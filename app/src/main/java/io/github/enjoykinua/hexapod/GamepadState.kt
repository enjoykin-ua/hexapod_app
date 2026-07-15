package io.github.enjoykinua.hexapod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Eine gemessene Achse: aktueller Wert plus der vom Geraet gemeldete Wertebereich. */
data class AxisReading(val value: Float, val min: Float, val max: Float)

/**
 * Beobachtbarer State-Halter (Compose-Snapshot-State).
 *
 * Die Activity schreibt hier rein (aus den Input-Callbacks), die Compose-UI liest ->
 * jede Aenderung an [mutableStateOf]/[mutableStateMapOf] loest automatisch eine
 * Rekomposition der betroffenen UI aus. Fuer dieses Hello-World bewusst eine schlichte
 * Klasse statt ViewModel (Punkt 2 im Plan).
 */
class GamepadState {
    var deviceFound by mutableStateOf(false)
        private set
    var deviceName by mutableStateOf<String?>(null)
        private set
    var vendorId by mutableStateOf(0)
        private set
    var productId by mutableStateOf(0)
        private set

    /** Kurzer Text ueber das zuletzt eingegangene Ereignis (v. a. fuer Buttons). */
    var lastEvent by mutableStateOf<String?>(null)

    /** Achsen dynamisch, gekeyt auf die Android-Achsenkonstante (MotionEvent.AXIS_*). */
    val axes = mutableStateMapOf<Int, AxisReading>()

    /** Buttons dynamisch, gekeyt auf den Android-KeyCode (KeyEvent.KEYCODE_*). */
    val buttons = mutableStateMapOf<Int, Boolean>()

    fun setDevice(name: String?, vendorId: Int, productId: Int) {
        deviceFound = true
        deviceName = name
        this.vendorId = vendorId
        this.productId = productId
    }

    fun clearDevice() {
        deviceFound = false
        deviceName = null
        vendorId = 0
        productId = 0
        axes.clear()
        buttons.clear()
        lastEvent = null
    }

    fun onAxis(axis: Int, value: Float, min: Float, max: Float) {
        axes[axis] = AxisReading(value, min, max)
    }

    fun onButton(keyCode: Int, pressed: Boolean) {
        buttons[keyCode] = pressed
    }
}

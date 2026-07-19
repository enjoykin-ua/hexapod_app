package io.github.enjoykinua.hexapod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Beobachtbarer Halter für den Verbindungs-/Sende-Zustand (Compose-Snapshot-State), analog
 * zu [GamepadState]. Die Activity schreibt (aus dem RosbridgeClient-Callback, auf den
 * Main-Thread marshallt, sowie aus der Publish-Schleife), die UI liest.
 */
class ConnectionState {
    var state by mutableStateOf(ConnState.DISCONNECTED)
    var error by mutableStateOf<String?>(null)

    /**
     * Eingegebener rosbridge-Host (IP ohne Schema/Port). Hochgezogen aus [ControlScreen] (war dort
     * lokal), damit der Fahr-Screen die **Video-URL** daraus ableiten kann (`videoStreamUrl`,
     * Contract §0/§5: gleiche IP, nur Port 8080).
     */
    var host by mutableStateOf("")

    /**
     * Verbindungs-Modus (Phase 7B): **Sim** (Desktop) → Video `type=mjpeg`, **HW** (Pi) →
     * `type=ros_compressed` + `camera_enable`-Kopplung. Manueller Schalter im Verbinden-Screen,
     * nur änderbar, solange nicht verbunden (gehört zum Verbindungs-Kontext). Default [ConnMode.SIM].
     */
    var mode by mutableStateOf(ConnMode.SIM)

    /** Zuletzt gebaute `/joy`-Nachricht (für die Debug-Anzeige / Vorzeichen-Check). */
    var lastJoy by mutableStateOf<JoyMessage?>(null)
}

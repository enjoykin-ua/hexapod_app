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

    /** Zuletzt gebaute `/joy`-Nachricht (für die Debug-Anzeige / Vorzeichen-Check). */
    var lastJoy by mutableStateOf<JoyMessage?>(null)
}

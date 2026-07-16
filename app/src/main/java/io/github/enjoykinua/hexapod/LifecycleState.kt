package io.github.enjoykinua.hexapod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Beobachtbarer Halter für den Lifecycle-/Stack-Zustand (Compose-Snapshot-State), analog zu
 * [ConnectionState]. Die Activity schreibt (aus den auf Main marshallten Service-Callbacks),
 * die UI liest. Reine Logik/Interpretation liegt in [LifecycleLogic] (unit-getestet).
 */
class LifecycleState {
    /** Stack läuft/aus/unbekannt (aus [interpretStatus] der Status-Antwort). */
    var stack by mutableStateOf(StackState.UNKNOWN)

    /** Roh-`message` der letzten Status-Antwort, z. B. "running (pid=1234)" — für die Anzeige. */
    var statusMessage by mutableStateOf<String?>(null)

    /** Gerade laufende Aktion → Buttons gesperrt + "…"-Anzeige (genau **eine** gleichzeitig). */
    var pendingAction by mutableStateOf<LifecycleAction?>(null)

    /** Pi-Shutdown ausgelöst → ein danach folgender Verbindungsabbruch ist **gewollt**. */
    var shuttingDown by mutableStateOf(false)

    /** Letzte Aktions-Rückmeldung (Erfolg/Fehler-`message`) für die Statuszeile. */
    var notice by mutableStateOf<String?>(null)
}

package io.github.enjoykinua.hexapod

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Beobachtbarer Halter für den Fahr-Screen-/Video-Zustand (Compose-Snapshot-State), analog zu
 * [ConnectionState]/[LifecycleState]. Die Activity schreibt (Center-Toggle + die auf Main
 * marshallten [MjpegStream]-Callbacks), die UI liest. Reine Logik liegt in [VideoLogic].
 */
class VideoState {
    /** Aktive Center-View (3-Wege-Toggle, Default „Nichts"). */
    var centerView by mutableStateOf(CenterView.NOTHING)

    /** Letztes dekodiertes Kamerabild (`null` = noch keins / Stream aus). ~11 Hz aktualisiert. */
    var frame by mutableStateOf<Bitmap?>(null)

    /** Läuft/aufgebaut der Stream gerade? Für die Gate-Idempotenz in `syncVideo`. */
    var streaming by mutableStateOf(false)

    /** Letzter Stream-Fehler (Connection-refused/Read) → Hinweis im Center statt Absturz. */
    var error by mutableStateOf<String?>(null)
}

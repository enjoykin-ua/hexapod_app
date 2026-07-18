package io.github.enjoykinua.hexapod

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Beobachtbarer Halter für die Phase-5-HMI-Live-Daten (Compose-Snapshot-State), analog zu
 * [ConnectionState]/[LifecycleState]/[VideoState]. Die Activity schreibt (aus den auf Main
 * marshallten rosbridge-Topic-Handlern), die UI liest. Reine Logik/Parsen liegt außerhalb
 * ([HmiProtocol]/[FootLogic]/…).
 *
 * Wächst über Phase 5: Overlay-Daten (P5.10); Capabilities/Manifest/Param-Werte/Alerts/Joints
 * folgen in P5.11–P5.13.
 */
class HmiState {
    /** Letzter `/hexapod/status`-Snapshot (`null` = noch keiner / Stack aus). */
    var status by mutableStateOf<StatusSnapshot?>(null)

    /** Aktives Tempo aus `/hexapod/tempo` (latched; `null` = noch keins). */
    var tempo by mutableStateOf<TempoInfo?>(null)

    /** Fuß-Kontakte (genau [FOOT_COUNT] Einträge, oder leer solange keine Daten). */
    var footContacts by mutableStateOf<List<Boolean>>(emptyList())

    /**
     * Stack-gebundene Live-Daten (status/tempo/foot) invalidieren — z. B. wenn der Stack stoppt,
     * damit das Overlay nicht veraltete Werte zeigt. (Always-On-Daten wie Capabilities/Manifest
     * bleiben; die kommen ab P5.11 dazu und überleben einen Stack-Stopp.)
     */
    fun clearStackData() {
        status = null
        tempo = null
        footContacts = emptyList()
    }

    /** Bei Verbindungsabbruch: alle Live-Daten invalidieren (Overlay zeigt Platzhalter). */
    fun clear() = clearStackData()
}

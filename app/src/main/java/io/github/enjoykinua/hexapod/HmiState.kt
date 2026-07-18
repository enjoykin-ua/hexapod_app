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

    /** Config-Manifest aus `/hexapod/config_manifest` (Always-On, latched; `null` = noch keins). */
    var manifest by mutableStateOf<ConfigManifest?>(null)

    /** Bestätigte Parameter-Werte (Key `node|param`, s. [paramKey]) — aus `get_parameters`/erfolgreichem Set. */
    var paramValues by mutableStateOf<Map<String, ParamValue>>(emptyMap())

    /** Letzter Reject-`reason` je Param (Key `node|param`) — Contract §6a Pflicht 3. */
    var paramErrors by mutableStateOf<Map<String, String>>(emptyMap())

    /**
     * Stack-gebundene Live-Daten invalidieren — status/tempo/foot **und** die Param-Werte/-Fehler
     * (aus den Stack-Nodes), z. B. wenn der Stack stoppt. Das **Manifest** (Always-On) bleibt →
     * das Panel rendert weiter (mit Defaults), nur get/set ruht bis der Stack wieder läuft.
     */
    fun clearStackData() {
        status = null
        tempo = null
        footContacts = emptyList()
        paramValues = emptyMap()
        paramErrors = emptyMap()
    }

    /** Bei Verbindungsabbruch: alle Live-Daten invalidieren (inkl. Always-On-Manifest). */
    fun clear() {
        clearStackData()
        manifest = null
    }
}

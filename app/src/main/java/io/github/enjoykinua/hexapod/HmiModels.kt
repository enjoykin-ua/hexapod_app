package io.github.enjoykinua.hexapod

/**
 * Reine Datenmodelle der Phase-5-HMI-Naht (Contract §6a) — framework-frei, direkt aus den
 * JSON-Topics abgeleitet. **Keine** org.json-Abhängigkeit hier: das Parsen liegt im Glue
 * ([HmiProtocol], integrationsverifiziert), diese Klassen sind reine Werte (unit-testbar).
 *
 * Wächst über Phase 5: [StatusSnapshot]/[TempoInfo] (P5.10 Overlay); Capabilities/Manifest/
 * Alert/ParamValue folgen in P5.11/P5.12.
 */

/**
 * Live-Zustand aus `/hexapod/status` (`std_msgs/String`-JSON, ~5 Hz, aus `gait_node`).
 * Felder gemäß Contract §6a. [stanceIdx] = -1, [stepHeightCap]/[stepLengthCap] = `null`, wenn im
 * Frame nicht vorhanden (defensiv — die App klemmt dann nur auf `manifest.max`).
 */
data class StatusSnapshot(
    val state: String,
    val stanceIdx: Int,
    val stance: String,
    val gait: String,
    val safetyFrozen: Boolean,
    val tip: String,               // none | warn | crit
    val stepHeightCap: Double?,    // dynamischer H1-Cap des aktuellen Stance
    val stepLengthCap: Double?,    // dynamischer H2-Cap des aktuellen Stance
) {
    /** Ist der Roboter STANDING? Basis fürs Gating (Contract §6a Pflicht 1). */
    val isStanding: Boolean get() = state == STATE_STANDING

    companion object {
        const val STATE_STANDING = "STANDING"
    }
}

/**
 * Aktives Tempo-Preset aus `/hexapod/tempo` (`std_msgs/String`-JSON, latched, aus `joy_to_twist`).
 * Die 3 Scale-Werte dienen dem Config-Panel (Slider-Nachzug nach Tempo-Wechsel, Contract §4.1) —
 * `null`, falls im Frame nicht vorhanden.
 */
data class TempoInfo(
    val tempo: String,
    val tempoIdx: Int,
    val linearXScale: Double?,
    val linearYScale: Double?,
    val angularZScale: Double?,
)

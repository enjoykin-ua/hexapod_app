package io.github.enjoykinua.hexapod

/**
 * Reine Datenmodelle der Phase-5-HMI-Naht (Contract §6a) — framework-frei, direkt aus den
 * JSON-Topics abgeleitet. **Keine** org.json-Abhängigkeit hier: das Parsen liegt im Glue
 * ([HmiProtocol], integrationsverifiziert), diese Klassen sind reine Werte (unit-testbar).
 *
 * Wächst über Phase 5: [StatusSnapshot]/[TempoInfo] (P5.10 Overlay); [ConfigManifest]/[ParamSpec]/
 * [ParamValue] (P5.11 Config-Panel); Capabilities/Alert folgen in P5.12.
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

// --- P5.11: Config-Manifest + Parameter (Contract §6a) ---

/** Widget-Typ eines Manifest-Params. [UNKNOWN] = unbekannt (defensiv → nicht gerendert). */
enum class Widget { SLIDER, TOGGLE, DROPDOWN, UNKNOWN }

/** Datentyp eines Params (steuert get/set-Kodierung + Eintipp-Validierung). */
enum class ParamType { DOUBLE, INT, BOOL, STRING, UNKNOWN }

/**
 * Typisierter Parameter-Wert (get/set über die nativen rosbridge-Param-Services). Die rosbridge-
 * `ParameterValue.type`-Kodierung (BOOL=1/INT=2/DOUBLE=3/STRING=4) lebt im Glue ([HmiProtocol]).
 */
sealed interface ParamValue {
    data class DoubleV(val v: Double) : ParamValue
    data class IntV(val v: Long) : ParamValue
    data class BoolV(val v: Boolean) : ParamValue
    data class StringV(val v: String) : ParamValue
}

/** [ParamValue] als Double, falls numerisch (Slider/±); sonst `null`. */
fun ParamValue.asDoubleOrNull(): Double? = when (this) {
    is ParamValue.DoubleV -> v
    is ParamValue.IntV -> v.toDouble()
    else -> null
}

/**
 * Ein verstellbarer Parameter aus `/hexapod/config_manifest` (Contract §6a). Die App rendert das
 * Panel **generisch** hieraus; get/set über `/<node>/get_parameters`/`set_parameters`.
 * [min]/[max]/[step]/[unit] nur bei `slider`; [options] nur bei `dropdown`.
 * [gating]="standing" → außerhalb STANDING disabled; [dynamicCap] = `/hexapod/status`-Feld
 * (`step_height_cap`/`step_length_cap`) → App klemmt `max`; [advanced]=true → eingeklappt.
 */
data class ParamSpec(
    val node: String,
    val param: String,
    val group: String,
    val label: String,
    val hint: String,
    val widget: Widget,
    val type: ParamType,
    val default: ParamValue?,
    val min: Double?,
    val max: Double?,
    val step: Double?,
    val unit: String?,
    val options: List<String>?,
    val gating: String?,
    val dynamicCap: String?,
    val advanced: Boolean,
)

/** Das ganze Config-Manifest (`version` + Whitelist der [ParamSpec]s). */
data class ConfigManifest(
    val version: Int,
    val params: List<ParamSpec>,
)

/** Ergebnis eines `set_parameters`-Eintrags: [successful] + Klartext-[reason] bei Reject (Contract §6a). */
data class SetResult(val successful: Boolean, val reason: String)

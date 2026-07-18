package io.github.enjoykinua.hexapod

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Reine Config-Panel-Logik (Phase 5, P5.11) — per JUnit **ohne Gerät/Netz** testbar
 * ([ConfigLogicTest]). Bildet die Contract-§6a-Pflichten ab: Gating, Dynamic-Cap, Step-/Range-
 * Klemmung, Eintipp-Validierung — alles **rein**, entkoppelt vom org.json-Glue ([HmiProtocol]) und
 * der Compose-UI ([ConfigPanel]).
 */

/** Eindeutiger Schlüssel für einen Param über Nodes hinweg (State-Map + Fehler-Map). */
fun paramKey(node: String, param: String): String = "$node|$param"

/** Kurzform für einen [ParamSpec]. */
fun ParamSpec.key(): String = paramKey(node, param)

/**
 * Params in **Manifest-Reihenfolge** nach `group` bündeln (erste-Sicht-Reihenfolge der Gruppen
 * bleibt erhalten) → Liste von (Gruppenname, Params).
 */
fun groupsInOrder(manifest: ConfigManifest): List<Pair<String, List<ParamSpec>>> {
    val order = LinkedHashMap<String, MutableList<ParamSpec>>()
    for (p in manifest.params) order.getOrPut(p.group) { mutableListOf() }.add(p)
    return order.map { (g, ps) -> g to ps.toList() }
}

/** Gruppe standardmäßig eingeklappt? → wenn **alle** ihre Params `advanced` sind (die 16 Gains). */
fun groupIsAdvanced(params: List<ParamSpec>): Boolean =
    params.isNotEmpty() && params.all { it.advanced }

/**
 * Effektives Slider-Maximum (Contract §6a Pflicht 2): `min(manifest.max, status[dynamic_cap])`.
 * Ohne `dynamic_cap` oder ohne Status → nur `manifest.max`; kein `max` → `null`.
 */
fun effectiveMax(spec: ParamSpec, status: StatusSnapshot?): Double? {
    val m = spec.max ?: return null
    val cap = when (spec.dynamicCap) {
        "step_height_cap" -> status?.stepHeightCap
        "step_length_cap" -> status?.stepLengthCap
        else -> null
    }
    return if (cap != null) minOf(m, cap) else m
}

/**
 * Ist das Widget bedienbar? (Contract §6a Pflicht 1) — der **Stack muss laufen** (Nodes existieren,
 * sonst kein get/set) **und** bei `gating:"standing"` muss der Roboter STANDING sein.
 */
fun isParamEnabled(spec: ParamSpec, status: StatusSnapshot?, stackRunning: Boolean): Boolean {
    if (!stackRunning) return false
    return spec.gating != "standing" || status?.isStanding == true
}

/**
 * Wert auf das Step-Raster runden (relativ zu [min]) und in `[min, max]` klemmen. [step]=`null` →
 * nur klemmen (exakter Eintipp-Wert). Rundung macht die ±-Schritte „glatt".
 */
fun clampDouble(v: Double, min: Double?, max: Double?, step: Double?): Double {
    var x = v
    if (step != null && step > 0.0 && min != null) {
        x = min + ((x - min) / step).roundToLong() * step
    }
    if (min != null) x = maxOf(x, min)
    if (max != null) x = minOf(x, max)
    return x
}

/**
 * ±-Schritt auf einem numerischen Param: aktueller Wert ± `step`, geklemmt auf `[min, effektiv-max]`.
 * [up]=true → größer. Ohne `step` bleibt der Wert unverändert.
 */
fun nudge(current: Double, spec: ParamSpec, up: Boolean, status: StatusSnapshot?): Double {
    val step = spec.step ?: return current
    val raw = current + if (up) step else -step
    return clampDouble(raw, spec.min, effectiveMax(spec, status), step)
}

/**
 * Eintipp-Text → gültiger [ParamValue] oder `null` (ungültig → nicht setzen). Double/Int werden auf
 * `[min, effektiv-max]` geklemmt (aber **nicht** aufs Step-Raster gerundet — der User tippt exakt).
 * Komma wird als Dezimalpunkt akzeptiert.
 */
fun parseTypedInput(text: String, spec: ParamSpec, status: StatusSnapshot?): ParamValue? {
    val t = text.trim().replace(',', '.')
    if (t.isEmpty()) return null
    return when (spec.type) {
        ParamType.DOUBLE ->
            t.toDoubleOrNull()?.let { ParamValue.DoubleV(clampDouble(it, spec.min, effectiveMax(spec, status), null)) }
        ParamType.INT ->
            t.toLongOrNull()?.let {
                ParamValue.IntV(clampDouble(it.toDouble(), spec.min, effectiveMax(spec, status), null).roundToLong())
            }
        ParamType.BOOL -> t.toBooleanStrictOrNull()?.let { ParamValue.BoolV(it) }
        ParamType.STRING -> ParamValue.StringV(t)
        ParamType.UNKNOWN -> null
    }
}

/** Der anzuzeigende Wert: bestätigter [paramValues]-Wert, sonst der Manifest-Default. */
fun currentOrDefault(spec: ParamSpec, paramValues: Map<String, ParamValue>): ParamValue? =
    paramValues[spec.key()] ?: spec.default

/** Nachkommastellen fürs Anzeigen aus dem `step` ableiten (Manifest-Steps: 0.005…5.0). */
fun decimalsForStep(step: Double?): Int = when {
    step == null || step <= 0.0 -> 2
    step >= 1.0 -> 0
    step >= 0.1 -> 1
    step >= 0.01 -> 2
    else -> 3
}

/** Double gemäß Step-Präzision formatieren (Punkt als Dezimaltrenner, locale-unabhängig). */
fun formatDouble(v: Double, step: Double?): String =
    String.format(Locale.US, "%.${decimalsForStep(step)}f", v)

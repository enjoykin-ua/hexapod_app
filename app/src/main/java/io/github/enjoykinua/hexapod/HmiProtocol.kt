package io.github.enjoykinua.hexapod

import org.json.JSONException
import org.json.JSONObject

/**
 * Phase-5-HMI-Parser (Contract §6a): rosbridge-`msg`-Objekte → reine Datenmodelle ([HmiModels]).
 * `org.json`-Glue wie [RosbridgeProtocol] → **integrationsverifiziert, NICHT unit-getestet** (der
 * `org.json`-Stub ist in reinem JUnit nicht lauffähig). Die *reine* Logik obendrauf ([footContacts],
 * später [ConfigLogic]/[CycleLogic]) ist separat unit-getestet.
 *
 * **Konvention:** die Funktionen bekommen das rosbridge-**`msg`**-Objekt (aus [parsePublish]). Für
 * `std_msgs/String`-Topics steckt die eigentliche JSON-Nutzlast als **String** in `msg.data` → erst
 * `data` ziehen, dann als JSON parsen. Defensiv: ungültig/fehlend → `null` bzw. leere Liste.
 */

/** Den JSON-String aus `msg.data` (`std_msgs/String`) ziehen und als Objekt parsen; `null` bei Fehler. */
private fun stringPayload(msg: JSONObject): JSONObject? {
    val data = msg.optString("data", "")
    if (data.isEmpty()) return null
    return try {
        JSONObject(data)
    } catch (e: JSONException) {
        null
    }
}

/** Optionalen Double lesen (nur wenn Feld vorhanden **und** numerisch), sonst `null`. */
private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null

/** `/hexapod/status` → [StatusSnapshot] (Contract §6a). */
fun parseStatus(msg: JSONObject): StatusSnapshot? {
    val o = stringPayload(msg) ?: return null
    return StatusSnapshot(
        state = o.optString("state", ""),
        stanceIdx = o.optInt("stance_idx", -1),
        stance = o.optString("stance", ""),
        gait = o.optString("gait", ""),
        safetyFrozen = o.optBoolean("safety_frozen", false),
        tip = o.optString("tip", "none"),
        stepHeightCap = o.optDoubleOrNull("step_height_cap"),
        stepLengthCap = o.optDoubleOrNull("step_length_cap"),
    )
}

/** `/hexapod/tempo` → [TempoInfo] (Contract §6a, latched). */
fun parseTempo(msg: JSONObject): TempoInfo? {
    val o = stringPayload(msg) ?: return null
    return TempoInfo(
        tempo = o.optString("tempo", ""),
        tempoIdx = o.optInt("tempo_idx", -1),
        linearXScale = o.optDoubleOrNull("linear_x_scale"),
        linearYScale = o.optDoubleOrNull("linear_y_scale"),
        angularZScale = o.optDoubleOrNull("angular_z_scale"),
    )
}

/**
 * `/foot_contacts` (`std_msgs/Float64MultiArray`) → Roh-`data`-Liste (Doubles). Die *Kontakt*-
 * Interpretation macht die reine [footContacts]-Logik. Leer, wenn `data` fehlt.
 */
fun parseFootContactsRaw(msg: JSONObject): List<Double> {
    val arr = msg.optJSONArray("data") ?: return emptyList()
    return (0 until arr.length()).map { arr.optDouble(it, 0.0) }
}

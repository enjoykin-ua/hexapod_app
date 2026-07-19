package io.github.enjoykinua.hexapod

import org.json.JSONArray
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

// --- P5.11: Config-Manifest + native Parameter-Services (rcl_interfaces) ---

// rosbridge/ROS-`ParameterType`: BOOL=1, INTEGER=2, DOUBLE=3, STRING=4 (0 = NOT_SET/undeklariert).
private const val PT_BOOL = 1
private const val PT_INT = 2
private const val PT_DOUBLE = 3
private const val PT_STRING = 4

private fun widgetOf(s: String): Widget = when (s) {
    "slider" -> Widget.SLIDER
    "toggle" -> Widget.TOGGLE
    "dropdown" -> Widget.DROPDOWN
    else -> Widget.UNKNOWN
}

private fun typeOf(s: String): ParamType = when (s) {
    "double" -> ParamType.DOUBLE
    "int" -> ParamType.INT
    "bool" -> ParamType.BOOL
    "string" -> ParamType.STRING
    else -> ParamType.UNKNOWN
}

/** Manifest-`default` gemäß deklariertem `type` als [ParamValue] lesen (`null`, wenn abwesend). */
private fun defaultOf(o: JSONObject, type: ParamType): ParamValue? {
    if (!o.has("default") || o.isNull("default")) return null
    return when (type) {
        ParamType.DOUBLE -> ParamValue.DoubleV(o.optDouble("default"))
        ParamType.INT -> ParamValue.IntV(o.optLong("default"))
        ParamType.BOOL -> ParamValue.BoolV(o.optBoolean("default"))
        ParamType.STRING -> ParamValue.StringV(o.optString("default"))
        ParamType.UNKNOWN -> null
    }
}

private fun stringList(arr: JSONArray?): List<String>? {
    if (arr == null) return null
    return (0 until arr.length()).map { arr.optString(it, "") }
}

/** `/hexapod/config_manifest` → [ConfigManifest] (Contract §6a, latched). */
fun parseManifest(msg: JSONObject): ConfigManifest? {
    val o = stringPayload(msg) ?: return null
    val arr = o.optJSONArray("params") ?: return null
    val params = ArrayList<ParamSpec>(arr.length())
    for (i in 0 until arr.length()) {
        val p = arr.optJSONObject(i) ?: continue
        val type = typeOf(p.optString("type", ""))
        params.add(
            ParamSpec(
                node = p.optString("node", ""),
                param = p.optString("param", ""),
                group = p.optString("group", ""),
                label = p.optString("label", ""),
                hint = p.optString("hint", ""),
                widget = widgetOf(p.optString("widget", "")),
                type = type,
                default = defaultOf(p, type),
                min = p.optDoubleOrNull("min"),
                max = p.optDoubleOrNull("max"),
                step = p.optDoubleOrNull("step"),
                unit = p.optString("unit", "").takeIf { it.isNotEmpty() },
                options = stringList(p.optJSONArray("options")),
                gating = p.optString("gating", "").takeIf { it.isNotEmpty() },
                dynamicCap = p.optString("dynamic_cap", "").takeIf { it.isNotEmpty() },
                advanced = p.optBoolean("advanced", false),
            ),
        )
    }
    return ConfigManifest(version = o.optInt("version", 0), params = params)
}

/** `get_parameters`-Args: `{names:[…]}`. Service = `<node>/get_parameters`. */
fun getParametersArgs(names: List<String>): JSONObject =
    JSONObject().put("names", JSONArray(names))

/** Ein `rcl_interfaces/ParameterValue`-Objekt aus einem typisierten [ParamValue] bauen. */
fun paramValueToJson(value: ParamValue): JSONObject = when (value) {
    is ParamValue.BoolV -> JSONObject().put("type", PT_BOOL).put("bool_value", value.v)
    is ParamValue.IntV -> JSONObject().put("type", PT_INT).put("integer_value", value.v)
    is ParamValue.DoubleV -> JSONObject().put("type", PT_DOUBLE).put("double_value", value.v)
    is ParamValue.StringV -> JSONObject().put("type", PT_STRING).put("string_value", value.v)
}

/** `set_parameters`-Args: `{parameters:[{name,value},…]}`. Service = `<node>/set_parameters`. */
fun setParametersArgs(params: List<Pair<String, ParamValue>>): JSONObject {
    val arr = JSONArray()
    for ((name, value) in params) {
        arr.put(JSONObject().put("name", name).put("value", paramValueToJson(value)))
    }
    return JSONObject().put("parameters", arr)
}

/** Ein `ParameterValue`-Objekt → [ParamValue] (`null` bei Typ 0 = undeklariert/unbekannt). */
fun parseParamValue(o: JSONObject): ParamValue? = when (o.optInt("type", 0)) {
    PT_BOOL -> ParamValue.BoolV(o.optBoolean("bool_value", false))
    PT_INT -> ParamValue.IntV(o.optLong("integer_value", 0))
    PT_DOUBLE -> ParamValue.DoubleV(o.optDouble("double_value", 0.0))
    PT_STRING -> ParamValue.StringV(o.optString("string_value", ""))
    else -> null
}

/**
 * `get_parameters`-Antwort (`values`-Objekt) → Map `node|param → ParamValue`. Die Reihenfolge des
 * `values`-Arrays entspricht den angefragten [names] (rcl_interfaces-Kontrakt).
 */
fun parseGetParametersValues(values: JSONObject, node: String, names: List<String>): Map<String, ParamValue> {
    val arr = values.optJSONArray("values") ?: return emptyMap()
    val out = LinkedHashMap<String, ParamValue>()
    val n = minOf(arr.length(), names.size)
    for (i in 0 until n) {
        val entry = arr.optJSONObject(i) ?: continue
        val pv = parseParamValue(entry) ?: continue
        out[paramKey(node, names[i])] = pv
    }
    return out
}

/** `set_parameters`-Antwort (`values`-Objekt) → Liste [SetResult] (`successful` + `reason`). */
fun parseSetParametersResults(values: JSONObject): List<SetResult> {
    val arr = values.optJSONArray("results") ?: return emptyList()
    return (0 until arr.length()).map { i ->
        val o = arr.optJSONObject(i) ?: JSONObject()
        SetResult(o.optBoolean("successful", false), o.optString("reason", ""))
    }
}

// --- P5.12: Capabilities + Alerts + SetBool ---

/** `/hexapod/capabilities` → [Capabilities] (Contract §6a, latched). */
fun parseCapabilities(msg: JSONObject): Capabilities? {
    val o = stringPayload(msg) ?: return null
    return Capabilities(
        gaits = stringList(o.optJSONArray("gaits")) ?: emptyList(),
        stanceModes = stringList(o.optJSONArray("stance_modes")) ?: emptyList(),
        tempoPresets = stringList(o.optJSONArray("tempo_presets")) ?: emptyList(),
    )
}

/** `/hexapod/alerts` → ein [Alert] (Contract §6a; ein Alert je Nachricht, latched Historie). */
fun parseAlert(msg: JSONObject): Alert? {
    val o = stringPayload(msg) ?: return null
    return Alert(
        stamp = o.optDouble("stamp", 0.0),
        level = o.optString("level", ""),
        name = o.optString("name", ""),
        msg = o.optString("msg", ""),
    )
}

/** `std_srvs/SetBool`-Args: `{data:bool}` (cycle_stance/cycle_tempo, `true`=höher/schneller). */
fun setBoolArgs(data: Boolean): JSONObject = JSONObject().put("data", data)

/** `std_msgs/Bool`-`msg` → Boolean (Phase 7A: `/hexapod/sound_enabled`, latched); `null` wenn Feld fehlt. */
fun parseBoolData(msg: JSONObject): Boolean? = if (msg.has("data")) msg.optBoolean("data") else null

// --- P5.13: /joint_states (3D-Viz) ---

/** `/joint_states` (`sensor_msgs/JointState`) → Map `joint-name → position` [rad] (Reihenfolge parallel). */
fun parseJointStates(msg: JSONObject): Map<String, Double> {
    val names = msg.optJSONArray("name") ?: return emptyMap()
    val pos = msg.optJSONArray("position") ?: return emptyMap()
    val n = minOf(names.length(), pos.length())
    val out = LinkedHashMap<String, Double>(n)
    for (i in 0 until n) out[names.optString(i)] = pos.optDouble(i, 0.0)
    return out
}

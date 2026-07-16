package io.github.enjoykinua.hexapod

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * rosbridge-v2-Protokollrahmen (JSON) für `/joy`. Format 1:1 wie der Referenz-Publisher
 * `hexapod_ws/tools/joy_ws_test_client.py` (advertise → publish-Schleife).
 *
 * `org.json` (User-Entscheidung Q1): in Android eingebaut, null Extra-Dependency. **Nicht**
 * JUnit-mockbar → diese Hülle wird über die Integration verifiziert, nicht unit-getestet;
 * die *Zahlen* (axes/buttons) kommen aus dem getesteten [JoyMapper].
 */

private const val TOPIC = "/joy"
private const val TYPE = "sensor_msgs/Joy"

/** `advertise`-Frame — ohne QoS-Override: rosbridge-Default = RELIABLE (Contract §0, Pflicht). */
fun rosbridgeAdvertiseJoy(): String = JSONObject()
    .put("op", "advertise")
    .put("topic", TOPIC)
    .put("type", TYPE)
    .toString()

fun rosbridgeUnadvertiseJoy(): String = JSONObject()
    .put("op", "unadvertise")
    .put("topic", TOPIC)
    .toString()

/** `publish`-Frame mit der `sensor_msgs/Joy`-Nutzlast (Header-Stamp 0, wie Referenz-Client). */
fun rosbridgePublishJoy(joy: JoyMessage): String {
    val stamp = JSONObject().put("sec", 0).put("nanosec", 0)
    val header = JSONObject().put("stamp", stamp).put("frame_id", "")
    val msg = JSONObject()
        .put("header", header)
        .put("axes", JSONArray(joy.axes))
        .put("buttons", JSONArray(joy.buttons))
    return JSONObject()
        .put("op", "publish")
        .put("topic", TOPIC)
        .put("msg", msg)
        .toString()
}

// --- Phase 3: Service-Calls (rosbridge `call_service` / `service_response`) ---
// Wie der /joy-Envelope org.json-basiert → integrationsverifiziert, NICHT unit-getestet. Die
// *Interpretation* der Rohdaten (StackState, Button-FSM) liegt rein in [LifecycleLogic].

/**
 * Ergebnis eines `call_service`. [ok] = rosbridge-`result` **und** Trigger-`success`;
 * [message] = Trigger-`message` bzw. Fehlertext bei `!ok` (Timeout / nicht verbunden / rosbridge-
 * Fehler).
 */
data class ServiceResult(val ok: Boolean, val message: String)

/** Roh aus einem `service_response`-Frame gezogen (std_srvs/Trigger-Form: success + message). */
data class RawServiceResponse(
    val id: String,
    val result: Boolean,
    val success: Boolean,
    val message: String,
)

/** `call_service`-Frame; `args={}` = leerer std_srvs/Trigger-Request. */
fun rosbridgeCallService(id: String, service: String): String = JSONObject()
    .put("op", "call_service")
    .put("id", id)
    .put("service", service)
    .put("args", JSONObject())
    .toString()

/**
 * Zieht einen `service_response`-Frame roh heraus; `null`, wenn es keiner ist (andere rosbridge-
 * Frames) oder die `id` fehlt. Defensiv: fehlende Felder → false/"". Fehlerfall (rosbridge
 * `result=false`): `values` ist dann ein Fehlertext-**String** statt eines Objekts → als
 * `message` übernommen.
 */
fun parseServiceResponse(text: String): RawServiceResponse? {
    val obj = try {
        JSONObject(text)
    } catch (e: JSONException) {
        return null
    }
    if (obj.optString("op") != "service_response") return null
    val id = obj.optString("id")
    if (id.isEmpty()) return null
    val values = obj.optJSONObject("values")
    val message = if (values != null) values.optString("message", "") else obj.optString("values", "")
    return RawServiceResponse(
        id = id,
        result = obj.optBoolean("result", false),
        success = values?.optBoolean("success", false) ?: false,
        message = message,
    )
}

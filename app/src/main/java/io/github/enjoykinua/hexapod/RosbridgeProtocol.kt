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

// --- Phase 3/5: Service-Calls + Subscriptions (rosbridge call_service / subscribe / publish) ---
// Wie der /joy-Envelope org.json-basiert → integrationsverifiziert, NICHT unit-getestet. Die
// *Interpretation* der Rohdaten liegt rein in [LifecycleLogic]/[HmiProtocol]/[FootLogic].

/**
 * Ergebnis eines std_srvs/Trigger- (oder SetBool-) `call_service`. [ok] = rosbridge-`result`
 * **und** `success`; [message] = `message` bzw. Fehlertext bei `!ok` (Timeout / nicht verbunden /
 * rosbridge-Fehler).
 */
data class ServiceResult(val ok: Boolean, val message: String)

/**
 * Generische `service_response`-Rohantwort (Phase 5, id-korreliert): [result] = rosbridge-Erfolg des
 * Calls, [values] = das `values`-Objekt (get/set_parameters lesen daraus selbst; `null` bei
 * rosbridge-Fehler oder synthetischem Fehler), [error] = Fehlertext bei `!result`.
 */
data class RawResponse(
    val id: String,
    val result: Boolean,
    val values: JSONObject?,
    val error: String,
)

/** Trigger-/SetBool-Sicht (success + message) auf eine generische [RawResponse]. */
fun RawResponse.asTriggerResult(): ServiceResult {
    val success = values?.optBoolean("success", false) ?: false
    val message = values?.optString("message", "")?.takeUnless { it.isEmpty() } ?: error
    return ServiceResult(ok = result && success, message = message)
}

/** `call_service`-Frame mit beliebigen [args] (Phase 5: get/set_parameters, SetBool). */
fun rosbridgeCallServiceArgs(id: String, service: String, args: JSONObject): String = JSONObject()
    .put("op", "call_service")
    .put("id", id)
    .put("service", service)
    .put("args", args)
    .toString()

// --- Phase 7A: generisches advertise/publish (Soundboard /hexapod/play_sound) ---

/** Generischer `advertise`-Frame (RELIABLE-Default genügt, Contract §6b). */
fun rosbridgeAdvertise(topic: String, type: String): String = JSONObject()
    .put("op", "advertise")
    .put("topic", topic)
    .put("type", type)
    .toString()

/** Generischer `unadvertise`-Frame. */
fun rosbridgeUnadvertise(topic: String): String = JSONObject()
    .put("op", "unadvertise")
    .put("topic", topic)
    .toString()

/** `publish`-Frame für ein `std_msgs/String` (Phase 7A: Soundboard-Key in `msg.data`). */
fun rosbridgePublishString(topic: String, data: String): String = JSONObject()
    .put("op", "publish")
    .put("topic", topic)
    .put("msg", JSONObject().put("data", data))
    .toString()

/**
 * `subscribe`-Frame. [latched]=true → explizites `transient_local`+`reliable`-QoS (Contract §7.4),
 * damit der gelatchte Wert beim (späten) Subscribe ankommt; [depth] = Queue-Tiefe (Alerts: 50).
 * [latched]=false → rosbridge-Default-QoS (nicht-latched: status/foot_contacts/joint_states).
 */
fun rosbridgeSubscribe(topic: String, type: String, latched: Boolean, depth: Int = 1, queueLength: Int? = null): String {
    val frame = JSONObject()
        .put("op", "subscribe")
        .put("topic", topic)
        .put("type", type)
    // queue_length:1 -> rosbridge hält nur den neuesten Frame (kein Backlog) => weniger Latenz bei High-Rate.
    if (queueLength != null) frame.put("queue_length", queueLength)
    if (latched) {
        frame.put(
            "qos",
            JSONObject()
                .put("history", "keep_last")
                .put("depth", depth)
                .put("durability", "transient_local")
                .put("reliability", "reliable"),
        )
    }
    return frame.toString()
}

/** `unsubscribe`-Frame. */
fun rosbridgeUnsubscribe(topic: String): String = JSONObject()
    .put("op", "unsubscribe")
    .put("topic", topic)
    .toString()

/**
 * Zieht `(topic, msg)` aus einem `publish`-Frame; `null`, wenn es keiner ist. Der [HmiProtocol]-
 * Parser des jeweiligen Topics interpretiert das `msg`-Objekt weiter.
 */
fun parsePublish(text: String): Pair<String, JSONObject>? {
    val obj = try {
        JSONObject(text)
    } catch (e: JSONException) {
        return null
    }
    if (obj.optString("op") != "publish") return null
    val topic = obj.optString("topic")
    if (topic.isEmpty()) return null
    val msg = obj.optJSONObject("msg") ?: return null
    return topic to msg
}

/**
 * Zieht einen `service_response`-Frame roh heraus; `null`, wenn es keiner ist (andere Frames) oder
 * die `id` fehlt. Defensiv: bei rosbridge-Fehler (`result=false`) ist `values` oft ein Fehlertext-
 * **String** statt eines Objekts → als [RawResponse.error] übernommen.
 */
fun parseRawResponse(text: String): RawResponse? {
    val obj = try {
        JSONObject(text)
    } catch (e: JSONException) {
        return null
    }
    if (obj.optString("op") != "service_response") return null
    val id = obj.optString("id")
    if (id.isEmpty()) return null
    val values = obj.optJSONObject("values")
    val error = if (values == null) obj.optString("values", "") else ""
    return RawResponse(
        id = id,
        result = obj.optBoolean("result", false),
        values = values,
        error = error,
    )
}

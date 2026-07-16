package io.github.enjoykinua.hexapod

import org.json.JSONArray
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

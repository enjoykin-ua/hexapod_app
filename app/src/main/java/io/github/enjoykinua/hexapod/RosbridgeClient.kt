package io.github.enjoykinua.hexapod

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * Dünner rosbridge-WebSocket-Client (OkHttp). Verbindet sich mit `ws://host:port`, advertised
 * `/joy` und sendet publish-Frames; ruft ab Phase 3 zusätzlich Services auf ([callService],
 * `call_service`/`service_response` mit id-Korrelation). **Kein Auto-Reconnect** (Phase 8) —
 * Connect/Disconnect ist manuell.
 *
 * **Threading:** OkHttp-Callbacks laufen auf einem OkHttp-Thread. [onState] wird von dort
 * aufgerufen; der Aufrufer (Activity) muss auf den Main-Thread marshallen, bevor er
 * Compose-State schreibt. [ready]/[isOpen] sind `@Volatile` → aus der Publish-Schleife sicher
 * lesbar.
 *
 * **Discovery-Delay:** Nach `onOpen`+advertise braucht rosbridge kurz, bis der Publisher
 * registriert und die DDS-Discovery zu `joy_to_twist` steht. [ready] wird erst
 * [READY_DELAY_MS] ms danach `true` — die Schleife publisht vorher nicht (sonst gingen die
 * ersten Frames verloren; wie die 0.5 s im Referenz-Client).
 */
class RosbridgeClient(
    private val onState: (ConnState, String?) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)   // tote Sockets erkennen / Verbindung halten
        .build()

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Volatile private var socket: WebSocket? = null
    @Volatile var isOpen: Boolean = false
        private set
    @Volatile var ready: Boolean = false
        private set

    /** Offene Service-Calls (id → Roh-Callback). Zugriff aus OkHttp- (onMessage) + Main-Thread. */
    private val pending = ConcurrentHashMap<String, (RawResponse) -> Unit>()
    private val callCounter = AtomicInteger()

    /** Aktive Topic-Subscriptions (topic → Handler auf das rosbridge-`msg`-Objekt). Phase 5. */
    private val topicHandlers = ConcurrentHashMap<String, (JSONObject) -> Unit>()

    fun connect(host: String, port: Int = DEFAULT_PORT) {
        if (socket != null) return   // schon verbunden/verbindend
        onState(ConnState.CONNECTING, null)
        val request = try {
            Request.Builder().url("ws://$host:$port").build()
        } catch (e: IllegalArgumentException) {
            onState(ConnState.ERROR, "Ungültiger Host: $host")
            return
        }
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(rosbridgeAdvertiseJoy())
                isOpen = true
                onState(ConnState.CONNECTED, null)
                // erst nach dem Discovery-Delay als sendebereit markieren
                scope.launch {
                    delay(READY_DELAY_MS)
                    if (socket === webSocket) ready = true
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val resp = parseRawResponse(text)
                if (resp != null) {
                    // genau einmal: remove ist atomar (Antwort ODER Timeout, nie beides)
                    pending.remove(resp.id)?.invoke(resp)
                    return
                }
                val pub = parsePublish(text)
                if (pub != null) {
                    topicHandlers[pub.first]?.invoke(pub.second)   // Phase-5-Topic-Routing
                    return
                }
                Log.d(TAG, "rosbridge → $text")   // sonstige rosbridge-Frames
            }

            // Callbacks eines bereits ersetzten/getrennten Sockets ignorieren, damit ein
            // spät eintreffendes onClosed/onFailure keine frische Verbindung überschreibt.
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket !== webSocket) return
                reset()
                onState(ConnState.ERROR, t.message)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket !== webSocket) return
                reset()
                onState(ConnState.DISCONNECTED, null)
            }
        })
    }

    /** Sendet einen fertigen publish-Frame; no-op, solange nicht [ready]. */
    fun publish(json: String) {
        if (ready) socket?.send(json)
    }

    /**
     * Ruft einen std_srvs/Trigger-Service (leere Args) auf. [onResult] wird **genau einmal**
     * aufgerufen — bei Antwort, [CALL_TIMEOUT_MS]-Timeout oder Verbindungsabbruch. **Threading:**
     * [onResult] feuert vom OkHttp-/Coroutine-Thread → der Aufrufer marshallt auf Main (wie [onState]).
     */
    fun callService(service: String, onResult: (ServiceResult) -> Unit) {
        callServiceArgs(service, JSONObject()) { raw -> onResult(raw.asTriggerResult()) }
    }

    /**
     * Generischer `call_service` mit [args] (Phase 5: get/set_parameters, SetBool). [onRaw] wird
     * **genau einmal** aufgerufen (Antwort/Timeout/Abbruch) und liefert die rohe [RawResponse] —
     * der Aufrufer zieht `values` selbst. Threading wie [callService].
     */
    fun callServiceArgs(service: String, args: JSONObject, onRaw: (RawResponse) -> Unit) {
        val s = socket
        if (s == null || !isOpen) {
            onRaw(RawResponse(id = "", result = false, values = null, error = "nicht verbunden"))
            return
        }
        val id = "call-${callCounter.incrementAndGet()}"
        pending[id] = onRaw
        s.send(rosbridgeCallServiceArgs(id, service, args))
        scope.launch {
            delay(CALL_TIMEOUT_MS)
            pending.remove(id)?.invoke(
                RawResponse(id, result = false, values = null, error = "Timeout (${CALL_TIMEOUT_MS / 1000} s)")
            )
        }
    }

    /**
     * Ein rosbridge-Topic subscriben (Phase 5). [onMsg] bekommt das rohe `msg`-Objekt (vom
     * OkHttp-Thread → Aufrufer marshallt auf Main). [latched]=true → transient_local+reliable-QoS
     * (Contract §7.4, für die gelatchten Topics); [depth] = Queue-Tiefe (Alerts: 50). Der Handler
     * wird auch registriert, wenn (noch) nicht offen — der Aufrufer subscribt bei CONNECTED.
     */
    fun subscribe(
        topic: String,
        type: String,
        latched: Boolean,
        depth: Int = 1,
        queueLength: Int? = null,
        onMsg: (JSONObject) -> Unit,
    ) {
        topicHandlers[topic] = onMsg
        if (isOpen) socket?.send(rosbridgeSubscribe(topic, type, latched, depth, queueLength))
    }

    /** Eine Topic-Subscription lösen. */
    fun unsubscribe(topic: String) {
        topicHandlers.remove(topic)
        if (isOpen) socket?.send(rosbridgeUnsubscribe(topic))
    }

    fun disconnect() {
        socket?.let { s ->
            if (isOpen) s.send(rosbridgeUnadvertiseJoy())
            s.close(NORMAL_CLOSURE, "client disconnect")
        }
        reset()
        onState(ConnState.DISCONNECTED, null)
    }

    /** Endgültig aufräumen (Activity-onDestroy): Socket schließen + Coroutine-Scope beenden. */
    fun dispose() {
        socket?.close(NORMAL_CLOSURE, "dispose")
        reset()
        scope.cancel()
    }

    private fun reset() {
        socket = null
        isOpen = false
        ready = false
        // offene Calls abschließen, damit Buttons nicht in "…" hängen bleiben
        for (id in pending.keys.toList()) {
            pending.remove(id)?.invoke(RawResponse(id, result = false, values = null, error = "Verbindung getrennt"))
        }
        // Subscriptions verwerfen — der Aufrufer subscribt bei erneutem CONNECT neu.
        topicHandlers.clear()
    }

    companion object {
        private const val TAG = "RosbridgeClient"
        const val DEFAULT_PORT = 9090
        private const val READY_DELAY_MS = 500L
        private const val CALL_TIMEOUT_MS = 8_000L   // Service-Antwort-Timeout (User-Entscheidung P3)
        private const val NORMAL_CLOSURE = 1000
    }
}

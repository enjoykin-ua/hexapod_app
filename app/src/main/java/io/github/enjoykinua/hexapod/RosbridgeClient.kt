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
import java.util.concurrent.TimeUnit

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/**
 * Dünner rosbridge-WebSocket-Client (OkHttp). Verbindet sich mit `ws://host:port`, advertised
 * `/joy` und sendet publish-Frames. **Kein Auto-Reconnect** (Phase 8) — Connect/Disconnect ist
 * manuell.
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
                Log.d(TAG, "rosbridge → $text")   // Status/Fehler-Frames von rosbridge
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
    }

    companion object {
        private const val TAG = "RosbridgeClient"
        const val DEFAULT_PORT = 9090
        private const val READY_DELAY_MS = 500L
        private const val NORMAL_CLOSURE = 1000
    }
}

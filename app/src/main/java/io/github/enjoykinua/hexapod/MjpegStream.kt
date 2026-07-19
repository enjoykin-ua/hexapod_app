package io.github.enjoykinua.hexapod

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Video-Glue (Phase 4, latenz-optimiert): lädt die rohe MJPEG-URL (Contract §5) über das vorhandene
 * OkHttp und rendert **immer das neueste Frame** (Frame-Drop gegen Buffer-Bloat). Der reine
 * [parseMjpegStream]-Parser bleibt unverändert (unit-getestet); nur die Threading-/Puffer-Strategie
 * ändert sich.
 *
 * **Warum Frame-Drop:** `web_video_server` produziert 720p-MJPEG schneller, als WLAN + Handy es
 * konsumieren. Würde — wie zuvor — jeder Frame **in der Leseschleife** dekodiert und angezeigt, könnte
 * der Reader den Socket nicht schnell genug leeren → er verarbeitet ständig **alte** Frames aus den
 * TCP-/App-Puffern, der Rückstand **wächst** (10–15 s). Lösung: **Reader** leert den Socket so schnell
 * wie möglich und legt nur die **zuletzt gelesenen JPEG-Bytes** in einen **Single-Slot** ([pending],
 * überschreiben — **keine** Queue); ein **Decoder** dekodiert stets nur diesen neuesten Slot und meldet
 * **ein** Bitmap auf Main. Zwischenframes fallen weg → kein Rückstau, Latenz bleibt niedrig + wächst nicht.
 *
 * Eigener [OkHttpClient] mit **readTimeout 0** (Dauer-Stream) + kurzem `connectTimeout` (schnelles Fail,
 * wenn Port 8080 noch zu ist). Der RosbridgeClient-Client wird bewusst **nicht** geteilt.
 *
 * **Threading:** pro [start] eine [Session] (Reader + Decoder, beide Daemon). [stop]/[start] beenden die
 * alte Session **sauber** (Call canceln → das blockierende `read()` wirft; `active=false` + notify → der
 * Decoder verlässt sein `wait()`) **bevor** eine neue startet → nie zwei parallele Streams. [onFrame]/
 * [onError] laufen auf Main; ein Fehler nach gewolltem [stop] (`isCanceled`/`!active`) wird unterdrückt.
 */
class MjpegStream(
    private val onFrame: (Bitmap) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // Dauer-Stream → kein Read-Timeout
        .build()

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var session: Session? = null

    fun start(url: String) {
        stop()
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            main.post { onError("Ungültige URL: $url") }
            return
        }
        session = Session(request).also { it.start() }
    }

    /** Stream stoppen: aktive Session beenden (Call canceln + Decoder wecken). Idempotent. */
    fun stop() {
        session?.stop()
        session = null
    }

    /**
     * Eine Streaming-Session: [readLoop] leert den Socket und hält nur den neuesten JPEG-Slot;
     * [decodeLoop] dekodiert stets den neuesten und postet auf Main. Frame-Drop = [pending] wird
     * überschrieben, nicht gequeued.
     */
    private inner class Session(request: Request) {
        @Volatile private var active = true
        private val lock = Object()
        private var pending: ByteArray? = null          // Single-Slot (guarded by lock): neuestes JPEG
        // Call vorab anlegen, damit ein sofortiges stop() ihn sicher canceln kann (kein Null-Race).
        private val call: Call = client.newCall(request)

        fun start() {
            Thread(::readLoop, "mjpeg-reader").apply { isDaemon = true; start() }
            Thread(::decodeLoop, "mjpeg-decoder").apply { isDaemon = true; start() }
        }

        fun stop() {
            active = false
            call.cancel()                                // bricht das blockierende read() ab
            synchronized(lock) { lock.notifyAll() }      // Decoder aus dem wait() wecken
        }

        /** Reader: Socket so schnell wie möglich leeren; nur die letzten JPEG-Bytes behalten (Drop). */
        private fun readLoop() {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        if (active) main.post { onError("HTTP ${resp.code}") }
                        return@use
                    }
                    val boundary = extractBoundary(resp.header("Content-Type"))
                    val body = resp.body ?: run {
                        if (active) main.post { onError("keine Antwort") }
                        return@use
                    }
                    parseMjpegStream(body.byteStream(), boundary) { bytes ->
                        // Überschreiben statt anfügen → ältere, noch nicht dekodierte Frames fallen weg.
                        synchronized(lock) {
                            pending = bytes
                            lock.notify()
                        }
                    }
                    // Regulär hier heraus = Stream endete von selbst (unerwartet bei web_video_server) →
                    // als Fehler melden, damit der Aufrufer retryt + die Session (Decoder) sauber stoppt.
                    if (active) main.post { onError("Stream endete") }
                }
            } catch (e: IOException) {
                if (active && !call.isCanceled()) main.post { onError(e.message ?: "Stream-Fehler") }
            } catch (e: Exception) {
                if (active && !call.isCanceled()) main.post { onError(e.message ?: "Fehler") }
            }
        }

        /** Decoder: immer den neuesten Slot dekodieren (Zwischenframes übersprungen), auf Main melden. */
        private fun decodeLoop() {
            while (active) {
                val bytes: ByteArray
                synchronized(lock) {
                    while (active && pending == null) {
                        try { lock.wait() } catch (e: InterruptedException) { return }
                    }
                    if (!active) return
                    bytes = pending!!
                    pending = null
                }
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null && active) main.post { onFrame(bmp) }
            }
        }
    }
}

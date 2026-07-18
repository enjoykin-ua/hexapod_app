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
 * Video-Glue (Phase 4, Plan-[ADR-1]): lädt die rohe MJPEG-URL (Contract §5) über **das schon
 * vorhandene OkHttp**, speist den Body-Stream in den reinen [parseMjpegStream]-Parser, dekodiert
 * jedes JPEG per [BitmapFactory] und meldet das Bitmap **auf dem Main-Thread** (Handler) an den
 * Aufrufer. **Nicht** unit-getestet (Netz/Bitmap = Integration); der Frame-Parser ist es ([ADR-4]).
 *
 * Eigener [OkHttpClient] mit **readTimeout 0** (der Stream endet nie) + kurzem `connectTimeout`
 * (schnelles Fail, wenn Port 8080 noch zu ist — Stack nicht gestartet). Der RosbridgeClient-Client
 * wird bewusst **nicht** geteilt (dessen Timeouts/pingInterval passen nicht zu einem Dauer-Stream).
 *
 * **Threading:** [start] öffnet einen Daemon-Thread (synchrones `execute()` + blockierendes
 * Lesen). [stop] cancelt den Call → das blockierende `read()` wirft → der Thread endet.
 * [onFrame]/[onError] werden auf Main gepostet; ein Fehler nach einem gewollten [stop]
 * (`call.isCanceled()`) wird unterdrückt.
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

    @Volatile private var call: Call? = null

    fun start(url: String) {
        stop()
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            main.post { onError("Ungültige URL: $url") }
            return
        }
        val c = client.newCall(request)
        call = c
        Thread({
            try {
                c.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        main.post { onError("HTTP ${resp.code}") }
                        return@use
                    }
                    val boundary = extractBoundary(resp.header("Content-Type"))
                    val body = resp.body ?: run {
                        main.post { onError("keine Antwort") }
                        return@use
                    }
                    parseMjpegStream(body.byteStream(), boundary) { bytes ->
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) main.post { onFrame(bmp) }
                    }
                }
            } catch (e: IOException) {
                if (!c.isCanceled()) main.post { onError(e.message ?: "Stream-Fehler") }
            } catch (e: Exception) {
                if (!c.isCanceled()) main.post { onError(e.message ?: "Fehler") }
            }
        }, "mjpeg-stream").apply { isDaemon = true; start() }
    }

    /** Stream stoppen: Call canceln (bricht das blockierende Lesen ab). Idempotent. */
    fun stop() {
        call?.cancel()
        call = null
    }
}

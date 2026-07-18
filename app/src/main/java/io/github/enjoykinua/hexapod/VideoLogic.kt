package io.github.enjoykinua.hexapod

/**
 * Reine, framework-freie Video-/Nav-Logik (Phase 4) — per JUnit **ohne Gerät/Netz** testbar
 * ([VideoLogicTest]). Interface = interface_contract.md **v0.8 §5** (Video/MJPEG :8080,
 * `/camera/image_raw`, Host = gleiche IP wie rosbridge, nur Port 8080) — hier **referenziert**,
 * nicht kopiert ([D10]).
 *
 * Bewusst getrennt vom Compose-Halter [VideoState], vom Netz/Decode-Teil ([MjpegStream]) und vom
 * reinen Frame-Parser ([parseMjpegStream]): **nur diese reine Schicht** wird unit-getestet.
 */

/** Die Screens der App (Phase 4: leichtgewichtige Compose-State-Navigation, Plan-[ADR-3]). */
enum class Screen { LIFECYCLE, DRIVE }

/**
 * Center-View des Fahr-Screens (3-Wege-Toggle, Default [NOTHING]). [ROBOT3D] ist reserviert
 * (Phase 5, disabled). [KAMERA] lädt den MJPEG-Stream (gated auf laufenden Stack, §5).
 */
enum class CenterView { NOTHING, KAMERA, ROBOT3D }

/** Port des Video-Stream-Servers (`web_video_server`); getrennt von rosbridge (9090). Contract §5. */
private const val VIDEO_PORT = 8080

/** Image-Topic + Kodierung der rohen MJPEG-URL (Contract §5, live gegen den Server verifiziert). */
private const val VIDEO_STREAM_PATH = "/stream?topic=/camera/image_raw&type=mjpeg"

/**
 * Rohe MJPEG-URL aus dem Host ableiten: **gleiche IP wie rosbridge, nur Port 8080** (Contract
 * §0/§5). `host` ist der bereits eingegebene rosbridge-Host (ohne Schema/Port).
 */
fun videoStreamUrl(host: String): String =
    "http://${host.trim()}:$VIDEO_PORT$VIDEO_STREAM_PATH"

/**
 * cam-Toggle (Slot 📷): schaltet zwischen [CenterView.KAMERA] und [CenterView.NOTHING]
 * (Plan-[ADR-6]). Aus [ROBOT3D] heraus (später) schaltet der cam-Button auf Kamera. `centerView`
 * bleibt die einzige Wahrheit fürs Center — kein zweites, widersprüchliches Gate.
 */
fun toggleCam(current: CenterView): CenterView =
    if (current == CenterView.KAMERA) CenterView.NOTHING else CenterView.KAMERA

/**
 * Soll der Stream gerade laufen? Kamera nur bei **laufendem Stack** laden (Port 8080 erst nach
 * `bringup_start` offen, Contract §5) + verbunden + Fahr-Screen sichtbar + Center=Kamera + im
 * Vordergrund (`resumed`, NF1). Rein → unit-getestet; die Activity ruft es aus `syncVideo`.
 */
fun shouldStream(
    screen: Screen,
    centerView: CenterView,
    connected: Boolean,
    stackRunning: Boolean,
    resumed: Boolean,
): Boolean =
    resumed && connected && stackRunning &&
        screen == Screen.DRIVE && centerView == CenterView.KAMERA

/**
 * Boundary-Marker aus einem `multipart/x-mixed-replace`-Content-Type ziehen
 * (`multipart/x-mixed-replace;boundary=xyz`) — ohne führende `--`. `null`, wenn keiner da ist →
 * der Parser entdeckt ihn dann aus der ersten Boundary-Zeile des Bodys ([parseMjpegStream]).
 */
fun extractBoundary(contentType: String?): String? {
    if (contentType == null) return null
    val marker = "boundary="
    val i = contentType.indexOf(marker, ignoreCase = true)
    if (i < 0) return null
    val b = contentType.substring(i + marker.length)
        .substringBefore(';')
        .trim()
        .trim('"')
        .removePrefix("--")
    return b.ifEmpty { null }
}

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

/** Topic-Basis der Stream-URL (Sim = roh, HW = /compressed durch web_video_server). Contract §5. */
private const val VIDEO_TOPIC = "/camera/image_raw"

/**
 * Verbindungs-Kontext (Phase 7B): steuert den Stream-`type` (§5 Variante A) und `camera_enable`
 * (nur HW). Manueller Sim/HW-Schalter im Verbinden-Screen (User-Entscheidung F2), Default [SIM].
 */
enum class ConnMode { SIM, HW }

/**
 * Stream-`type` je Modus (Contract §5 Variante A): **Sim = `mjpeg`** (rohes Bild, Gazebo),
 * **HW = `ros_compressed`** (web_video_server reicht die rpicam-JPEGs durch → kein Pi-Decode).
 */
fun streamType(mode: ConnMode): String = when (mode) {
    ConnMode.SIM -> "mjpeg"
    ConnMode.HW -> "ros_compressed"
}

/**
 * Bandbreiten-Drossel (Latenz-Fix, `web_video_server`-Query-Params): etwas kleineres Bild
 * (1120×630 = 16:9, ~¾ der 720p-Pixel — nur um ¼ reduziert, gegen zu starke Pixeligkeit) + moderate
 * JPEG-Qualität (70) = weniger WLAN-Last, aber schärfer als der erste Wurf (640×360/q50). **Nur für
 * `mjpeg`** (Server re-encodet neu); bei `ros_compressed` (HW) werden die Quell-JPEGs **durchgereicht**
 * (kein Pi-Decode, Contract §5) → ein Downscale dort erzwänge genau diesen Decode. Auflösung auf HW =
 * rpicam-Quelle (ROS-Seite). Der [MjpegStream]-Frame-Drop hält die Latenz auch bei mehr Bandbreite niedrig.
 */
private const val VIDEO_MJPEG_TUNING = "&quality=70&width=1120&height=630"

/**
 * Rohe MJPEG-URL aus Host + [type] ableiten: **gleiche IP wie rosbridge, nur Port 8080** (Contract
 * §0/§5). `host` ist der bereits eingegebene rosbridge-Host (ohne Schema/Port); [type] aus [streamType].
 * Für `mjpeg` wird [VIDEO_MJPEG_TUNING] angehängt (Bandbreite/Latenz).
 */
fun videoStreamUrl(host: String, type: String): String {
    val base = "http://${host.trim()}:$VIDEO_PORT/stream?topic=$VIDEO_TOPIC&type=$type"
    return if (type == "mjpeg") base + VIDEO_MJPEG_TUNING else base
}

/** Node/Param für `camera_enable` (rpicam-Subprozess an/aus, Strom/Wärme). Contract §5/§6. */
const val CAMERA_NODE = "/hexapod_camera"
const val CAMERA_ENABLE_PARAM = "camera_enable"

/**
 * Soll die Kamera (rpicam) an sein? Folgt dem Stream-Gate ([shouldStream]), aber **nur auf HW** —
 * in Sim gibt es den `/hexapod_camera`-Node nicht (Gazebo-Kamera) → dort nie setzen. Contract §5/§6.
 */
fun wantCameraEnable(mode: ConnMode, streamWanted: Boolean): Boolean =
    mode == ConnMode.HW && streamWanted

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

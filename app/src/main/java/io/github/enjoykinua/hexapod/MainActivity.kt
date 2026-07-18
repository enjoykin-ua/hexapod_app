package io.github.enjoykinua.hexapod

import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.github.enjoykinua.hexapod.ui.theme.Hexapod_appTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Phase 1 · Stufe B — Kishi-Gamepad-Hello-World.
 *
 * Liest den Controller ueber Framework-Input (kein ROS, kein Netz) und zeigt je Achse/Button
 * den Live-Wert samt Android-Konstante. Gamepad-Events werden auf **Activity-/dispatch-Ebene**
 * abgegriffen (nicht ueber Compose-Fokus): so kommen sie zuverlaessig an, unabhaengig davon,
 * was im Compose-Baum den Fokus hat.
 */
class MainActivity : ComponentActivity() {

    private lateinit var inputManager: InputManager
    private val gamepadState = GamepadState()
    private val connection = ConnectionState()
    private val lifecycleState = LifecycleState()
    private val videoState = VideoState()
    private val hmi = HmiState()
    private lateinit var ros: RosbridgeClient
    private lateinit var video: MjpegStream
    private var publishJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Phase 4: leichtgewichtige Compose-State-Navigation (Lifecycle <-> Drive). Als Activity-
    // gehaltener Snapshot-State (wie die anderen Halter) -> Activity UND Compose lesen/schreiben.
    private var screen by mutableStateOf(Screen.LIFECYCLE)
    private var isResumed = false
    private var videoRetries = 0

    // P5.12: cycle-to-target-Zustand je Art (Stance/Tempo). Nur auf dem Main-Thread zugegriffen.
    private val cycleTarget = mutableMapOf<CycleKind, Int>()
    private val cycleLastIdx = mutableMapOf<CycleKind, Int>()
    private val cycleSteps = mutableMapOf<CycleKind, Int>()
    private val cycleTimeouts = mutableMapOf<CycleKind, Runnable>()

    // P5.13: /joint_states nur abonnieren, solange die 3D-Ansicht aktiv ist (spart Bandbreite/Parse).
    private var jointsSubscribed = false

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshDevices()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshDevices()
        override fun onInputDeviceChanged(deviceId: Int) = refreshDevices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inputManager = getSystemService(InputManager::class.java)
        // RosbridgeClient-Statuswechsel kommen vom OkHttp-Thread -> auf den Main-Thread
        // marshallen, bevor Compose-State geschrieben wird.
        ros = RosbridgeClient { state, err ->
            mainHandler.post {
                connection.state = state
                connection.error = err
                when (state) {
                    // frisch verbunden → Stack-Status holen (Option A: Polling) + HMI-Topics subscriben
                    ConnState.CONNECTED -> {
                        lifecycleState.shuttingDown = false
                        lifecycleState.notice = null
                        pollStatus()
                        subscribeHmi()
                    }
                    // getrennt/Fehler → Lifecycle- + HMI-Zustand invalidieren, laufende Aktion lösen
                    ConnState.DISCONNECTED, ConnState.ERROR -> {
                        lifecycleState.stack = StackState.UNKNOWN
                        lifecycleState.statusMessage = null
                        lifecycleState.pendingAction = null
                        hmi.clear()
                        jointsSubscribed = false   // Client hat die Handler verworfen -> Flag zurück
                    }
                    ConnState.CONNECTING -> {}
                }
                // Verbindungswechsel wirkt auch aufs Kamera-Gate (Stream nur bei verbunden).
                syncVideo()
            }
        }
        // Video-Stream-Client (Kanal 2, MJPEG). Callbacks kommen bereits auf Main (Handler in
        // MjpegStream) -> hier direkt Compose-State schreiben. Kein ROS/rosbridge (getrennter Kanal).
        video = MjpegStream(
            onFrame = { bmp ->
                videoState.frame = bmp
                videoState.error = null
                videoRetries = 0
            },
            onError = { msg -> onVideoError(msg) },
        )
        // Bildschirm waehrend des Fahrens wach halten (Handy steckt im Kishi, Querformat ist im
        // Manifest gesperrt). Kein Immersive-/Kiosk-Modus -> Verlassen per System-Navigation
        // bleibt normal moeglich.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        enableEdgeToEdge()
        setContent {
            Hexapod_appTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when (screen) {
                        Screen.LIFECYCLE -> ControlScreen(
                            gamepadState = gamepadState,
                            connection = connection,
                            lifecycle = lifecycleState,
                            onConnect = { host -> ros.connect(host) },
                            onDisconnect = { lifecycleState.shuttingDown = false; ros.disconnect() },
                            onAction = { action -> onLifecycleAction(action) },
                            onRefreshStatus = { pollStatus() },
                            onDrive = { goToDrive() },
                            modifier = Modifier.padding(innerPadding)
                        )
                        Screen.DRIVE -> DriveScreen(
                            connection = connection,
                            lifecycle = lifecycleState,
                            video = videoState,
                            hmi = hmi,
                            onSetCenter = { cv -> videoState.centerView = cv; syncVideo() },
                            onToggleCam = { videoState.centerView = toggleCam(videoState.centerView); syncVideo() },
                            onBack = { screen = Screen.LIFECYCLE; syncVideo() },
                            onSetParam = { spec, value -> setParam(spec, value) },
                            onRequestParams = { requestParamValues() },
                            onSetGait = { name -> setGait(name) },
                            onSetStanceTarget = { idx -> startCycle(CycleKind.STANCE, idx) },
                            onSetTempoTarget = { idx -> startCycle(CycleKind.TEMPO, idx) },
                            onClearAlerts = { hmi.alerts = emptyList() },
                            contentPadding = innerPadding,
                        )
                    }
                }
            }
        }

        refreshDevices()
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        // Hot-Plug beobachten; Listener an den Lifecycle koppeln, sonst Leak.
        inputManager.registerInputDeviceListener(deviceListener, mainHandler)
        refreshDevices()
        startPublishing()
        syncVideo()   // im Vordergrund ggf. Stream (wieder) starten (Gate entscheidet)
    }

    /**
     * 30-Hz-`/joy`-Schleife, an den Vordergrund-Lifecycle gekoppelt. Laeuft auf dem Main-
     * Dispatcher (lifecycleScope): Snapshot-State lesen + org.json bauen + OkHttp-send sind
     * dort unkritisch. Bei onPause abgebrochen -> Publish verstummt -> cmd_vel_timeout haelt
     * den Roboter (NF1-Failsafe, gewollt). Es wird stets gemappt (fuer die /joy-out-Anzeige),
     * gesendet aber nur, wenn der Client sendebereit ist.
     */
    private fun startPublishing() {
        publishJob?.cancel()
        publishJob = lifecycleScope.launch {
            while (isActive) {
                val joy = JoyMapper.toJoy(gamepadState.toControllerInput())
                connection.lastJoy = joy
                if (ros.ready) ros.publish(rosbridgePublishJoy(joy))
                delay(PUBLISH_PERIOD_MS)
            }
        }
    }

    // --- Phase 3: Lifecycle-Services (rosbridge call_service; Callbacks auf Main marshallen) ---

    /** Stack-Status pollen (Option A): nach Connect + nach jedem Start/Stop + manuell. */
    private fun pollStatus() {
        ros.callService(BRINGUP_STATUS_SERVICE) { result ->
            mainHandler.post {
                if (result.ok) {
                    lifecycleState.stack = interpretStatus(result.message)
                    lifecycleState.statusMessage = result.message
                } else {
                    lifecycleState.stack = StackState.UNKNOWN
                    lifecycleState.statusMessage = null
                }
                // Stack nicht (mehr) aktiv -> stack-gebundene Overlay-Daten invalidieren (kein Stale);
                // läuft er -> aktuelle Param-Werte holen (Panel zeigt echte statt Default-Werte).
                if (lifecycleState.stack != StackState.RUNNING) hmi.clearStackData() else requestParamValues()
                // frischer Stack-Status -> Kamera-Gate nachziehen (startet/stoppt den Stream)
                syncVideo()
            }
        }
    }

    // --- Phase 5: HMI-Live-Topics (Overlay) subscriben; Handler → HmiState (auf Main) ---

    /**
     * Die Overlay-Live-Topics subscriben (nach CONNECTED). Die Handler feuern vom OkHttp-Thread →
     * org.json-Parsen dort, dann auf Main marshallen, bevor Compose-State geschrieben wird (wie bei
     * [onState]). `status`/`tempo` liefern erst nach `bringup_start`; die Handler feuern dann.
     * Ungültige/leere Frames → letzten guten Wert behalten (kein Wipe).
     */
    private fun subscribeHmi() {
        ros.subscribe("/hexapod/status", "std_msgs/msg/String", latched = false) { msg ->
            parseStatus(msg)?.let { s ->
                mainHandler.post {
                    hmi.status = s
                    onCycleIndexUpdate(CycleKind.STANCE)   // Fortschritt eines Stance-cycle prüfen
                }
            }
        }
        ros.subscribe("/hexapod/tempo", "std_msgs/msg/String", latched = true) { msg ->
            parseTempo(msg)?.let { t ->
                mainHandler.post {
                    hmi.tempo = t
                    // §4.1: ein Tempo-Wechsel überschreibt die Scale-Params in joy_to_twist -> neu lesen,
                    // damit das Config-Panel die echten Werte zeigt.
                    requestParamValues(JOY_NODE)
                    onCycleIndexUpdate(CycleKind.TEMPO)   // Fortschritt eines Tempo-cycle prüfen
                }
            }
        }
        ros.subscribe("/foot_contacts", "std_msgs/msg/Float64MultiArray", latched = false) { msg ->
            val raw = parseFootContactsRaw(msg)
            if (raw.isNotEmpty()) mainHandler.post { hmi.footContacts = footContacts(raw) }
        }
        // Config-Manifest (Always-On, latched) -> schon beim Connect da; Panel rendert generisch daraus.
        // Nach Empfang Werte holen (greift, sobald der Stack läuft) -> schließt die Race Manifest<->Poll.
        ros.subscribe("/hexapod/config_manifest", "std_msgs/msg/String", latched = true) { msg ->
            parseManifest(msg)?.let { m -> mainHandler.post { hmi.manifest = m; requestParamValues() } }
        }
        // Capabilities (Always-On, latched) -> Dropdown-Enums schon beim Connect.
        ros.subscribe("/hexapod/capabilities", "std_msgs/msg/String", latched = true) { msg ->
            parseCapabilities(msg)?.let { c -> mainHandler.post { hmi.capabilities = c } }
        }
        // Alerts (Always-On, latched Historie 50) -> Liste akkumulieren (neueste oben, dedup/cap).
        ros.subscribe("/hexapod/alerts", "std_msgs/msg/String", latched = true, depth = ALERTS_CAP) { msg ->
            parseAlert(msg)?.let { a -> mainHandler.post { hmi.alerts = appendAlert(hmi.alerts, a) } }
        }
    }

    /**
     * Aktuelle Parameter-Werte lesen (gebündelt **je Node**, ein Call pro Node). [nodeFilter] != null
     * → nur dieser Node (z. B. joy_to_twist nach Tempo-Wechsel). No-op ohne Manifest oder wenn der
     * Stack nicht läuft (die Ziel-Nodes existieren nur im laufenden Stack).
     */
    private fun requestParamValues(nodeFilter: String? = null) {
        val m = hmi.manifest ?: return
        if (lifecycleState.stack != StackState.RUNNING) return
        for ((node, specs) in m.params.groupBy { it.node }) {
            if (nodeFilter != null && node != nodeFilter) continue
            val names = specs.map { it.param }
            ros.callServiceArgs("$node/get_parameters", getParametersArgs(names)) { raw ->
                if (raw.result && raw.values != null) {
                    val vals = parseGetParametersValues(raw.values, node, names)
                    if (vals.isNotEmpty()) mainHandler.post { hmi.paramValues = hmi.paramValues + vals }
                }
            }
        }
    }

    /**
     * Einen Param live setzen (rosbridge `set_parameters`). Erfolg → [HmiState.paramValues] aktualisieren
     * + Fehler löschen; Reject (`successful=false`) → `reason` in [HmiState.paramErrors] (Contract §6a
     * Pflicht 3); der angezeigte Wert bleibt der zuletzt bestätigte (kein optimistisches Übernehmen).
     */
    private fun setParam(spec: ParamSpec, value: ParamValue) {
        val key = spec.key()
        ros.callServiceArgs("${spec.node}/set_parameters", setParametersArgs(listOf(spec.param to value))) { raw ->
            mainHandler.post {
                val result = if (raw.result && raw.values != null) {
                    parseSetParametersResults(raw.values).firstOrNull()
                } else {
                    null
                }
                when {
                    result != null && result.successful -> {
                        hmi.paramValues = hmi.paramValues + (key to value)
                        hmi.paramErrors = hmi.paramErrors - key
                    }
                    result != null -> hmi.paramErrors = hmi.paramErrors + (key to result.reason.ifEmpty { "abgelehnt" })
                    else -> hmi.paramErrors = hmi.paramErrors + (key to raw.error.ifEmpty { "Fehler beim Setzen" })
                }
            }
        }
    }

    // --- Phase 5: Dropdowns (gait direkt / stance+tempo cycle-to-target) + Alerts ---

    /** gait-Dropdown: `gait_pattern` direkt setzen (namensbasiert; standing-gated in der UI, §ADR-P5-2). */
    private fun setGait(name: String) {
        ros.callServiceArgs(
            "/gait_node/set_parameters",
            setParametersArgs(listOf("gait_pattern" to ParamValue.StringV(name))),
        ) { /* standing-gated -> Erfolg erwartet; Status-Topic spiegelt den neuen gait. */ }
    }

    /** Aktueller Ist-Index einer cycle-to-target-Art (aus status/tempo); `null` = keine Daten. */
    private fun currentCycleIdx(kind: CycleKind): Int? = when (kind) {
        CycleKind.STANCE -> hmi.status?.stanceIdx?.takeIf { it >= 0 }
        CycleKind.TEMPO -> hmi.tempo?.tempoIdx?.takeIf { it >= 0 }
    }

    private fun setCycling(kind: CycleKind, on: Boolean) = when (kind) {
        CycleKind.STANCE -> hmi.cyclingStance = on
        CycleKind.TEMPO -> hmi.cyclingTempo = on
    }

    /** Dropdown-Auswahl (stance/tempo): Ziel-Index setzen und den ersten Schritt feuern. */
    private fun startCycle(kind: CycleKind, target: Int) {
        val cur = currentCycleIdx(kind) ?: return
        if (cur == target) return
        cycleTarget[kind] = target
        cycleSteps[kind] = 0
        setCycling(kind, true)
        fireCycleStep(kind)
    }

    /**
     * Einen Cycle-Schritt Richtung Ziel feuern (SetBool). Danach **nicht** sofort nachfeuern —
     * erst das nächste status/tempo-Update ([onCycleIndexUpdate]) treibt den nächsten Schritt
     * (Contract §6a: `success=false` = blockiert/nicht STANDING). Step-Cap + Timeout gegen Endlos.
     */
    private fun fireCycleStep(kind: CycleKind) {
        val target = cycleTarget[kind] ?: return
        val cur = currentCycleIdx(kind) ?: return finishCycle(kind)
        val dir = nextCycleStep(cur, target) ?: return finishCycle(kind)
        if ((cycleSteps[kind] ?: 0) >= CYCLE_STEP_CAP) return finishCycle(kind)
        cycleSteps[kind] = (cycleSteps[kind] ?: 0) + 1
        cycleLastIdx[kind] = cur
        ros.callServiceArgs(kind.service, setBoolArgs(dir)) { /* Ergebnis egal: Fortschritt via Topic-Update */ }
        armCycleTimeout(kind)
    }

    /** Nach [CYCLE_TIMEOUT_MS] ohne Fortschritt den Cycle abbrechen (Ziel unerreichbar / blockiert). */
    private fun armCycleTimeout(kind: CycleKind) {
        cycleTimeouts.remove(kind)?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { finishCycle(kind) }
        cycleTimeouts[kind] = r
        mainHandler.postDelayed(r, CYCLE_TIMEOUT_MS)
    }

    /** Aus dem status/tempo-Handler: Ziel erreicht → fertig; Ist-Index änderte sich → nächster Schritt. */
    private fun onCycleIndexUpdate(kind: CycleKind) {
        val target = cycleTarget[kind] ?: return
        val cur = currentCycleIdx(kind) ?: return
        when {
            cur == target -> finishCycle(kind)
            cur != cycleLastIdx[kind] -> fireCycleStep(kind)   // Fortschritt -> nächster Schritt
            // sonst: keine Änderung (blockiert) -> weiter warten (Timeout greift)
        }
    }

    private fun finishCycle(kind: CycleKind) {
        cycleTarget.remove(kind)
        cycleSteps.remove(kind)
        cycleLastIdx.remove(kind)
        cycleTimeouts.remove(kind)?.let { mainHandler.removeCallbacks(it) }
        setCycling(kind, false)
    }

    // --- Phase 4: Navigation + Video-Stream-Orchestrierung ---

    /** In den Fahr-Screen wechseln + **Betreten-Poll** (frischer Stack-Status fürs Kamera-Gate). */
    private fun goToDrive() {
        screen = Screen.DRIVE
        pollStatus()   // Betreten-Poll (User-Entscheidung): Gate mit frischem Status öffnen
        syncVideo()
    }

    /**
     * Kamera-Stream an-/abschalten gemäß Gate (rein: [shouldStream]), idempotent über
     * [VideoState.streaming]. Aufgerufen bei jeder Änderung von screen/centerView/Verbindung/Stack
     * + in onResume/onPause. Contract §5: Stream erst bei laufendem Stack (Port 8080).
     */
    private fun syncVideo() {
        val want = shouldStream(
            screen = screen,
            centerView = videoState.centerView,
            connected = connection.state == ConnState.CONNECTED,
            stackRunning = lifecycleState.stack == StackState.RUNNING,
            resumed = isResumed,
        )
        if (want && !videoState.streaming) {
            videoRetries = 0
            videoState.error = null
            videoState.streaming = true
            video.start(videoStreamUrl(connection.host))
        } else if (!want && videoState.streaming) {
            videoState.streaming = false
            videoState.frame = null
            video.stop()
        }
        syncJointStates()   // 3D-Viz-Subscription am selben Gate nachziehen
    }

    /**
     * `/joint_states` nur abonnieren, während die **3D-Ansicht** aktiv sichtbar ist (verbunden +
     * Fahr-Screen + Center=3D + Vordergrund). Beim Verlassen wieder abbestellen + Puffer leeren →
     * kein Joint-Stream/Parse im Hintergrund (P5.13).
     */
    private fun syncJointStates() {
        val want = isResumed && connection.state == ConnState.CONNECTED &&
            screen == Screen.DRIVE && videoState.centerView == CenterView.ROBOT3D
        if (want && !jointsSubscribed) {
            jointsSubscribed = true
            ros.subscribe("/joint_states", "sensor_msgs/msg/JointState", latched = false) { msg ->
                val jp = parseJointStates(msg)
                if (jp.isNotEmpty()) mainHandler.post { hmi.jointPositions = jp }
            }
        } else if (!want && jointsSubscribed) {
            jointsSubscribed = false
            ros.unsubscribe("/joint_states")
            hmi.jointPositions = emptyMap()
        }
    }

    /**
     * Stream-Fehler (Connection-refused/Read): Hinweis zeigen, Stream als gestoppt markieren (kein
     * Crash). Begrenzter Auto-Retry — Port 8080 kann dem gepollten Status minimal hinterherhinken.
     */
    private fun onVideoError(msg: String) {
        videoState.error = msg
        videoState.frame = null
        video.stop()
        videoState.streaming = false
        if (videoRetries < VIDEO_MAX_RETRIES) {
            videoRetries++
            mainHandler.postDelayed({ syncVideo() }, VIDEO_RETRY_DELAY_MS)
        }
    }

    /**
     * Einen Lifecycle-Button ausführen: Buttons sperren ([LifecycleState.pendingAction]) →
     * Service rufen → Rückmeldung anzeigen. Bei Start/Stop danach Status neu pollen. PI_SHUTDOWN
     * setzt shuttingDown (ein danach folgender Drop = „heruntergefahren", nicht Fehler).
     */
    private fun onLifecycleAction(action: LifecycleAction) {
        lifecycleState.shuttingDown = action == LifecycleAction.PI_SHUTDOWN
        lifecycleState.pendingAction = action
        lifecycleState.notice = null
        ros.callService(action.service) { result ->
            mainHandler.post {
                lifecycleState.pendingAction = null
                lifecycleState.notice = "${action.label}: ${result.message}"
                if (result.ok && (action == LifecycleAction.START || action == LifecycleAction.STOP)) {
                    pollStatus()
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        isResumed = false
        inputManager.unregisterInputDeviceListener(deviceListener)
        publishJob?.cancel()
        publishJob = null
        // isResumed=false -> Gate schliesst -> Stream stoppen (kein Video/Bandbreite im Hintergrund).
        syncVideo()
    }

    override fun onDestroy() {
        super.onDestroy()
        ros.dispose()
        video.stop()
    }

    /** Erstes Geraet mit Gamepad-/Joystick-Source suchen und Achsenliste vorbelegen. */
    private fun refreshDevices() {
        for (id in inputManager.inputDeviceIds) {
            val device = inputManager.getInputDevice(id) ?: continue
            val sources = device.sources
            val isGamepad = sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
            val isJoystick = sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
            if (isGamepad || isJoystick) {
                gamepadState.setDevice(device.name, device.vendorId, device.productId)
                // Achsen sofort auflisten (Wert 0), damit die Bereiche schon vor der ersten
                // Bewegung sichtbar sind.
                for (range in device.motionRanges) {
                    gamepadState.onAxis(range.axis, 0f, range.min, range.max)
                }
                return
            }
        }
        gamepadState.clearDevice()
    }

    // dispatch*-Ebene statt onGenericMotionEvent/onKeyDown: wird garantiert vor der
    // View-Zustellung aufgerufen -> nichts kann uns die Gamepad-Events "wegschnappen".

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
            captureAxes(event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val fromGamepad = event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            KeyEvent.isGamepadButton(keyCode)
        if (fromGamepad) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    gamepadState.onButton(keyCode, true)
                    gamepadState.lastEvent = "Taste ↓ ${KeyEvent.keyCodeToString(keyCode)}"
                }
                KeyEvent.ACTION_UP -> gamepadState.onButton(keyCode, false)
            }
            // Konsumieren: verhindert u. a., dass BUTTON_B als "Zurueck" die App schliesst
            // oder das D-Pad den Fokus verschiebt.
            return true
        }
        // Alles Nicht-Gamepad (Zurueck, Home, Lautstaerke, ...) normal weiterreichen.
        return super.dispatchKeyEvent(event)
    }

    /** Alle Achsen des Geraets aus dem aktuellen Event lesen und in den State schieben. */
    private fun captureAxes(event: MotionEvent) {
        val device = event.device ?: return
        for (range in device.motionRanges) {
            gamepadState.onAxis(range.axis, event.getAxisValue(range.axis), range.min, range.max)
        }
    }

    companion object {
        private const val JOY_NODE = "/joy_to_twist"   // Host der Tempo-Scale-Params (§4.1-Reload)
        private const val CYCLE_STEP_CAP = 6        // max Cycle-Schritte (Backstop; 3-4 Presets)
        private const val CYCLE_TIMEOUT_MS = 4_000L // Cycle-to-target abbrechen, wenn kein Fortschritt
        private const val PUBLISH_PERIOD_MS = 33L   // ~30 Hz (NF1: stetig, auch bei neutral)
        private const val VIDEO_MAX_RETRIES = 3     // Stream-Auto-Retry (Port 8080 vs. Status-Latenz)
        private const val VIDEO_RETRY_DELAY_MS = 1_500L
    }
}

package io.github.enjoykinua.hexapod

import android.os.Handler
import android.util.Log

/**
 * Framework-leichter **HMI-Orchestrator** (Phase 5): kapselt die rosbridge-Subscriptions, das
 * Param-`get`/`set` und die **cycle-to-target**-Steuerung (Stance/Tempo), damit die Activity schlank
 * bleibt (B3). Hält Referenzen auf [ros] + [hmi] + [mainHandler]; die wenigen Activity-Zustände
 * kommen als Provider ([stackRunning]).
 *
 * **Threading:** die rosbridge-Handler feuern vom OkHttp-Thread → hier via [mainHandler] auf den
 * Main-Thread marshallt, bevor [HmiState] geschrieben wird (wie zuvor in der Activity). Alle übrigen
 * Methoden werden vom Main-Thread aufgerufen; die Cycle-Maps sind daher nicht synchronisiert.
 *
 * **Lifecycle (B4):** Subscriptions sind an Vordergrund **und** Verbindung gekoppelt — die Activity
 * ruft [startSubscriptions]/[stopSubscriptions] aus onResume/onPause/Connect/Disconnect, damit im
 * Hintergrund kein Topic-Verkehr (status 5 Hz/foot) verarbeitet wird.
 */
class HmiController(
    private val ros: RosbridgeClient,
    private val hmi: HmiState,
    private val mainHandler: Handler,
    private val stackRunning: () -> Boolean,
) {
    private var subscribed = false
    private var jointsSubscribed = false

    // cycle-to-target-Zustand je Art (nur Main-Thread).
    private val cycleTarget = mutableMapOf<CycleKind, Int>()
    private val cycleLastIdx = mutableMapOf<CycleKind, Int>()
    private val cycleSteps = mutableMapOf<CycleKind, Int>()
    private val cycleTimeouts = mutableMapOf<CycleKind, Runnable>()

    // --- Subscriptions ---

    /** Die HMI-Topics abonnieren (idempotent). status/tempo liefern erst nach bringup_start. */
    fun startSubscriptions() {
        if (subscribed) return
        subscribed = true
        ros.subscribe("/hexapod/status", "std_msgs/msg/String", latched = false, queueLength = 1) { msg ->
            parseStatus(msg)?.let { s -> mainHandler.post { hmi.status = s; onCycleIndexUpdate(CycleKind.STANCE) } }
        }
        ros.subscribe("/hexapod/tempo", "std_msgs/msg/String", latched = true) { msg ->
            parseTempo(msg)?.let { t ->
                mainHandler.post {
                    hmi.tempo = t
                    requestParamValues(JOY_NODE)   // §4.1: Tempo-Wechsel überschreibt die Scale-Params
                    onCycleIndexUpdate(CycleKind.TEMPO)
                }
            }
        }
        ros.subscribe("/foot_contacts", "std_msgs/msg/Float64MultiArray", latched = false, queueLength = 1) { msg ->
            val raw = parseFootContactsRaw(msg)
            if (raw.isNotEmpty()) mainHandler.post { hmi.footContacts = footContacts(raw) }
        }
        ros.subscribe("/hexapod/config_manifest", "std_msgs/msg/String", latched = true) { msg ->
            parseManifest(msg)?.let { m -> mainHandler.post { hmi.manifest = m; requestParamValues() } }
        }
        ros.subscribe("/hexapod/capabilities", "std_msgs/msg/String", latched = true) { msg ->
            parseCapabilities(msg)?.let { c -> mainHandler.post { hmi.capabilities = c } }
        }
        ros.subscribe("/hexapod/alerts", "std_msgs/msg/String", latched = true, depth = ALERTS_CAP) { msg ->
            parseAlert(msg)?.let { a -> mainHandler.post { hmi.alerts = appendAlert(hmi.alerts, a) } }
        }
        // Phase 7A: Auto-Sound-Mute (latched Bool, kommt sobald der On-Demand-Stack läuft).
        ros.subscribe(SOUND_ENABLED_TOPIC, "std_msgs/msg/Bool", latched = true) { msg ->
            parseBoolData(msg)?.let { b -> mainHandler.post { hmi.soundEnabled = b } }
        }
        // Phase 7A: Soundboard-Publisher früh advertisen, damit der erste Tap zuverlässig ankommt.
        ros.advertise(PLAY_SOUND_TOPIC, "std_msgs/msg/String")
    }

    /** Die HMI-Topics abbestellen (Hintergrund/onPause). Der Socket bleibt offen (Reconnect billig). */
    fun stopSubscriptions() {
        if (!subscribed) return
        subscribed = false
        for (topic in HMI_TOPICS) ros.unsubscribe(topic)
    }

    /** `/joint_states` nur abonnieren, solange die 3D-Ansicht aktiv sichtbar ist ([want]). */
    fun syncJointStates(want: Boolean) {
        if (want && !jointsSubscribed) {
            jointsSubscribed = true
            ros.subscribe("/joint_states", "sensor_msgs/msg/JointState", latched = false, queueLength = 1) { msg ->
                val jp = parseJointStates(msg)
                if (jp.isNotEmpty()) mainHandler.post { hmi.jointPositions = jp }
            }
        } else if (!want && jointsSubscribed) {
            jointsSubscribed = false
            ros.unsubscribe("/joint_states")
            hmi.jointPositions = emptyMap()
        }
    }

    /** Verbindung getrennt: der Client hat die Handler verworfen → lokale Flags/Cycles zurück. */
    fun onDisconnected() {
        subscribed = false
        jointsSubscribed = false
        for (kind in CycleKind.entries) finishCycle(kind)
    }

    /** Aufräumen (onDestroy): anstehende Cycle-Timeouts vom Main-Handler entfernen. */
    fun dispose() {
        for (runnable in cycleTimeouts.values) mainHandler.removeCallbacks(runnable)
        cycleTimeouts.clear()
    }

    // --- Param get/set (native rosbridge-Parameter-Services) ---

    /**
     * Aktuelle Parameter-Werte lesen (gebündelt **je Node**). [nodeFilter] != null → nur dieser Node
     * (z. B. joy_to_twist nach Tempo-Wechsel). No-op ohne Manifest oder wenn der Stack nicht läuft.
     */
    fun requestParamValues(nodeFilter: String? = null) {
        val m = hmi.manifest ?: return
        if (!stackRunning()) return
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
     * Einen Param live setzen. Erfolg → [HmiState.paramValues] + Fehler löschen; Reject
     * (`successful=false`) → `reason` in [HmiState.paramErrors] (Contract §6a Pflicht 3).
     */
    fun setParam(spec: ParamSpec, value: ParamValue) {
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

    /** gait-Dropdown: `gait_pattern` direkt setzen (namensbasiert; standing-gated in der UI, ADR-P5-2). */
    fun setGait(name: String) {
        ros.callServiceArgs(
            "/gait_node/set_parameters",
            setParametersArgs(listOf("gait_pattern" to ParamValue.StringV(name))),
        ) { /* standing-gated -> Erfolg erwartet; Status-Topic spiegelt den neuen gait. */ }
    }

    // --- Phase 7A: Audio (Auto-Sound-Mute + Soundboard) ---

    /**
     * Auto-Bewegungs-Sounds an/aus (`sound_enable` BOOL auf `/hexapod_audio`). Fire-and-forget — die
     * Toggle-Anzeige folgt dem latched `/hexapod/sound_enabled`, **nicht** dieser Response (§6b-Pflicht 2).
     */
    fun setSoundEnable(on: Boolean) {
        ros.callServiceArgs(
            "$AUDIO_NODE/set_parameters",
            setParametersArgs(listOf(SOUND_ENABLE_PARAM to ParamValue.BoolV(on))),
        ) { /* Anzeige aus dem Topic, nicht aus der Response */ }
    }

    /** Soundboard-Key publishen (spielt immer). Advertise ist idempotent (früh in [startSubscriptions] gesetzt). */
    fun playSound(key: String) {
        ros.advertise(PLAY_SOUND_TOPIC, "std_msgs/msg/String")
        ros.publish(rosbridgePublishString(PLAY_SOUND_TOPIC, key))
    }

    // --- Phase 7B: camera_enable (rpicam an/aus, nur HW — s. MainActivity-Gate) ---

    /**
     * rpicam-Subprozess an/aus (`camera_enable` BOOL auf `/hexapod_camera`). Fire-and-forget; ein
     * Fehlschlag (Node fehlt, z. B. versehentlich in Sim) wird nur geloggt — Video hängt nicht daran.
     */
    fun setCameraEnable(on: Boolean) {
        ros.callServiceArgs(
            "$CAMERA_NODE/set_parameters",
            setParametersArgs(listOf(CAMERA_ENABLE_PARAM to ParamValue.BoolV(on))),
        ) { raw -> if (!raw.result) Log.w(TAG, "camera_enable=$on fehlgeschlagen: ${raw.error}") }
    }

    // --- cycle-to-target (Stance/Tempo) ---

    /** Dropdown-Auswahl (stance/tempo): Ziel-Index setzen und den ersten Schritt feuern. */
    fun startCycle(kind: CycleKind, target: Int) {
        val cur = currentCycleIdx(kind) ?: return
        if (cur == target) return
        cycleTarget[kind] = target
        cycleSteps[kind] = 0
        setCycling(kind, true)
        fireCycleStep(kind)
    }

    private fun currentCycleIdx(kind: CycleKind): Int? = when (kind) {
        CycleKind.STANCE -> hmi.status?.stanceIdx?.takeIf { it >= 0 }
        CycleKind.TEMPO -> hmi.tempo?.tempoIdx?.takeIf { it >= 0 }
    }

    private fun setCycling(kind: CycleKind, on: Boolean) = when (kind) {
        CycleKind.STANCE -> hmi.cyclingStance = on
        CycleKind.TEMPO -> hmi.cyclingTempo = on
    }

    /**
     * Einen Cycle-Schritt Richtung Ziel feuern (SetBool). Danach **nicht** sofort nachfeuern — erst
     * das nächste status/tempo-Update ([onCycleIndexUpdate]) treibt den nächsten Schritt (Contract
     * §6a: `success=false` = blockiert/nicht STANDING). Step-Cap + Timeout gegen Endlos.
     */
    private fun fireCycleStep(kind: CycleKind) {
        val target = cycleTarget[kind] ?: return
        val cur = currentCycleIdx(kind) ?: return finishCycle(kind)
        val dir = nextCycleStep(cur, target) ?: return finishCycle(kind)
        if ((cycleSteps[kind] ?: 0) >= CYCLE_STEP_CAP) return finishCycle(kind)
        cycleSteps[kind] = (cycleSteps[kind] ?: 0) + 1
        cycleLastIdx[kind] = cur
        ros.callServiceArgs(kind.service, setBoolArgs(dir)) { /* Fortschritt via Topic-Update */ }
        armCycleTimeout(kind)
    }

    private fun armCycleTimeout(kind: CycleKind) {
        cycleTimeouts.remove(kind)?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable { finishCycle(kind) }
        cycleTimeouts[kind] = runnable
        mainHandler.postDelayed(runnable, CYCLE_TIMEOUT_MS)
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

    companion object {
        private const val TAG = "HmiController"
        private const val JOY_NODE = "/joy_to_twist"   // Host der Tempo-Scale-Params (§4.1-Reload)
        private const val CYCLE_STEP_CAP = 6           // max Cycle-Schritte (Backstop; 3-4 Presets)
        private const val CYCLE_TIMEOUT_MS = 4_000L    // Cycle abbrechen, wenn kein Fortschritt
        private val HMI_TOPICS = listOf(
            "/hexapod/status", "/hexapod/tempo", "/foot_contacts",
            "/hexapod/config_manifest", "/hexapod/capabilities", "/hexapod/alerts",
            SOUND_ENABLED_TOPIC,   // Phase 7A
        )
    }
}

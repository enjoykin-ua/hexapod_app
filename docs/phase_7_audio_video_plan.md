# Phase-7-App-Plan — Audio (7A) + Video-`type` je Host (7B)

> **App-Seite von Phase 7A + 7B** in einer App-Session. Die ROS-Seite ist fertig + verifiziert;
> hier **nur die App-UI** gegen den festgezurrten Contract — **kein ROS-Code, kein neues Interface**.
>
> **Interface (read-only, referenziert, nie kopiert — [D10]):** `interface_contract.md` **v0.12.1**
> — **§6b** (Audio: `play_sound`, `sound_enabled`, Param `sound_enable`, kopierbare Frames), **§5**
> (Video: `type` je Host + `camera_enable`-Frame), **§4** (Params), **§3** (Topics + Latched-QoS),
> **§0** (zwei Verbindungs-Modi Sim/HW). ROS-Briefs: `phase_7a_audio_plan.md §5`, `phase_7b_camera_plan.md §5`.

---

## 0. Ziel + festgelegte Entscheidungen (User-Freigabe)

**Audio (7A):**
- **F1 = (b):** Kein „Fahren mit/ohne Audio"-Doppelbutton beim Übergang. Der Fahren-Übergang bleibt
  ein schlichtes **„Fahren →"**. Der Sound wird über **einen einzelnen An/Aus-Toggle** im Fahr-Modus
  geschaltet. *(Abweichung vom 7A-Brief „zwei Buttons" — bewusst, User-Entscheidung; die App baut nur
  die App-Seite, die ROS-Naht bleibt gleich.)*
- Toggle-Zustand kommt **ausschließlich** aus dem latched Topic **`/hexapod/sound_enabled`**
  (`std_msgs/Bool`) — nicht den eigenen Button-State raten.
- **3 Soundboard-Buttons** „Sound 1/2/3" → publishen `/hexapod/play_sound` (`sound_01/02/03`),
  spielen **immer** (unabhängig von `sound_enable`).
- **F3:** Audio-UI **rechts** (schmale vertikale Spalte am rechten Rand, Fahr-Modus).

**Video (7B):**
- **F2:** Manueller **Sim/HW-Schalter** im Verbinden-Screen. **Sim → `type=mjpeg`**, **HW →
  `type=ros_compressed`**. Default **Sim** (heutiges Verhalten bleibt unverändert).
- **F5:** **`camera_enable` an die Center-Ansicht gekoppelt** (kein separater Toggle): **Video →
  Kamera an**, **None/3D → Kamera aus**. **Nur im HW-Modus** (in Sim existiert `/hexapod_camera`
  nicht → unangetastet). Hängt am vorhandenen `syncVideo`-Gate.

**Gemeinsam:** Audio-Controls **und** Video/Kamera gibt es erst **nach `bringup_start`** (On-Demand-
Stack) → an `StackState.RUNNING` bzw. `/hexapod/bringup_running` gekoppelt.

**Bewusst NICHT:** H264/WebRTC, Aufnahme, Foto-Snapshot; App-Speaker (Ton nur am Roboter, [D5]);
separater `camera_enable`-Toggle.

---

## 1. Betroffene Dateien

| Datei | Änderung |
|---|---|
| **NEU** `AudioLogic.kt` | reine Konstanten: Topic-/Param-/Node-Namen (`/hexapod/play_sound`, `/hexapod/sound_enabled`, `/hexapod_audio`, `sound_enable`) + Soundboard-Liste (`SoundButton(label, key)` × 3) — gegen den Contract gepinnt |
| **NEU** `AudioLogicTest.kt` | pinnt Konstanten + Soundboard (Tippfehler-Regression) |
| `VideoLogic.kt` (erw.) | `enum ConnMode { SIM, HW }`; `streamType(mode)`; `videoStreamUrl(host, type)`; `wantCameraEnable(mode, streamWanted)`; Kamera-Node-/Param-Const |
| `VideoLogicTest.kt` (erw.) | `streamType`, `videoStreamUrl(type)`, `wantCameraEnable` |
| `RosbridgeProtocol.kt` (erw.) | generische Frames: `rosbridgeAdvertise(topic,type)`, `rosbridgeUnadvertise(topic)`, `rosbridgePublishString(topic,data)`; Parser `parseBoolData(msg)` |
| `RosbridgeClient.kt` (erw.) | generisches `advertise(topic,type)` (+ Cleanup bei disconnect); `publish(json)`-Reuse für `play_sound` |
| `ConnectionState.kt` (erw.) | `var mode: ConnMode = SIM` |
| `HmiState.kt` (erw.) | `var soundEnabled: Boolean?` (aus latched Topic) |
| `HmiController.kt` (erw.) | Subscribe `/hexapod/sound_enabled`; `setSoundEnable(Boolean)`; `playSound(key)`; `setCameraEnable(Boolean)`; Topic in Unsub-Liste |
| `ControlScreen.kt` (erw.) | Sim/HW-Schalter in der Connect-Bar; „Fahren →" bleibt **einzeln** |
| `DriveScreen.kt` (erw.) | rechte **Audio-Spalte** (Toggle + Sound 1/2/3), gated auf RUNNING |
| `MainActivity.kt` (erw.) | Video-URL mit `streamType(mode)`; `syncVideo` koppelt `camera_enable` (HW); Audio-Callbacks verdrahten |

---

## 2. Layout-Skizze — Fahr-Screen (rechte Audio-Spalte)

```
┌───────────────────────────────────────────────────────────────┐
│ ‹ zurück           [ None | Video | 3D ]        ⚠ ⚙ show      │  ← TopBar (unverändert)
│ ● verbunden ·läuft                                             │
│ safety ok                                          ┌─────────┐ │
│ tip none                                           │ 🔊 AN   │ │  ← NEU: Audio-Spalte,
│                                                    │ Sound 1 │ │     rechts mittig
│                (Video / 3D / None)                 │ Sound 2 │ │     (Alignment.CenterEnd),
│                                                    │ Sound 3 │ │     nur wenn Stack läuft
│                                                    └─────────┘ │
│ [foot] state stance gait tempo              ⛔ E-STOP          │  ← BottomBar (unverändert)
│                                             📷 cam AN          │
└───────────────────────────────────────────────────────────────┘
```
- Neue Spalte via `Modifier.align(Alignment.CenterEnd)` im äußeren `Box` → rechter Rand, vertikal
  mittig; kollidiert nicht mit den Top-Right-Slots (oben) oder E-STOP/cam (unten).
- **Nur gerendert, wenn `StackState.RUNNING`** (Audio-Node existiert sonst nicht; Toggle-Wert kommt
  ohnehin erst dann). Der Safety-Banner (Phase 6) liegt weiterhin darüber.

---

## 3. Logik-Skizze / Pseudocode + Begründung

### 3a. `AudioLogic.kt` (rein, gepinnt)
```kotlin
const val PLAY_SOUND_TOPIC   = "/hexapod/play_sound"      // std_msgs/String, App→Roboter
const val SOUND_ENABLED_TOPIC = "/hexapod/sound_enabled"  // std_msgs/Bool, latched, Roboter→App
const val AUDIO_NODE         = "/hexapod_audio"
const val SOUND_ENABLE_PARAM = "sound_enable"             // BOOL

data class SoundButton(val label: String, val key: String)
val SOUNDBOARD = listOf(
    SoundButton("Sound 1", "sound_01"),
    SoundButton("Sound 2", "sound_02"),
    SoundButton("Sound 3", "sound_03"),
)
```
**Begründung:** Wie `SafetyLogic` — Contract-Strings an **einer** Stelle, per Test gepinnt (kein
Tippfehler an drei Publish-Stellen). Labels rein app-seitig (Contract kennt nur Keys).

### 3b. `VideoLogic.kt` (Erweiterung)
```kotlin
enum class ConnMode { SIM, HW }

fun streamType(mode: ConnMode): String = when (mode) {
    ConnMode.SIM -> "mjpeg"          // Gazebo, roh
    ConnMode.HW  -> "ros_compressed" // rpicam-JPEGs durchgereicht (kein Pi-Decode), Contract §5 Variante A
}

fun videoStreamUrl(host: String, type: String): String =
    "http://${host.trim()}:$VIDEO_PORT/stream?topic=/camera/image_raw&type=$type"

const val CAMERA_NODE = "/hexapod_camera"
const val CAMERA_ENABLE_PARAM = "camera_enable"   // BOOL

/** camera_enable folgt dem Stream-Gate, aber NUR auf HW (in Sim gibt es /hexapod_camera nicht). */
fun wantCameraEnable(mode: ConnMode, streamWanted: Boolean): Boolean =
    mode == ConnMode.HW && streamWanted
```
**Begründung:** `type` ist die **einzige** 7B-Änderung (Variante A). Manueller Schalter =
deterministisch, keine `rosapi`-Abhängigkeit/Async (F2). `wantCameraEnable` wiederverwendet das
bestehende `shouldStream`-Signal → eine Wahrheit fürs Kamera-An/Aus, kein zweites Gate. **Verworfen:**
Auto-Erkennung via `rosapi/topics` (mehr bewegliche Teile, Node-Abhängigkeit) — F2 = manuell.

### 3c. `HmiController.kt` (Erweiterung)
```kotlin
// (1) Subscribe in startSubscriptions() — latched Bool, kommt sofort sobald der Stack läuft:
ros.subscribe(SOUND_ENABLED_TOPIC, "std_msgs/msg/Bool", latched = true) { msg ->
    parseBoolData(msg)?.let { b -> mainHandler.post { hmi.soundEnabled = b } }
}
// SOUND_ENABLED_TOPIC zusätzlich in HMI_TOPICS (Unsub-Liste).

// (2) Auto-Sound-Mute setzen (Muster wie setParam/setGait):
fun setSoundEnable(on: Boolean) {
    ros.callServiceArgs("$AUDIO_NODE/set_parameters",
        setParametersArgs(listOf(SOUND_ENABLE_PARAM to ParamValue.BoolV(on)))) { /* Toggle spiegelt Topic */ }
}

// (3) Soundboard publish (advertise einmal, dann publish je Tap):
fun playSound(key: String) {
    ros.advertise(PLAY_SOUND_TOPIC, "std_msgs/msg/String")   // idempotent im Client
    ros.publish(rosbridgePublishString(PLAY_SOUND_TOPIC, key))
}

// (4) camera_enable (HW), fire-and-forget; Fehlschlag (Node fehlt) nur geloggt:
fun setCameraEnable(on: Boolean) {
    ros.callServiceArgs("$CAMERA_NODE/set_parameters",
        setParametersArgs(listOf(CAMERA_ENABLE_PARAM to ParamValue.BoolV(on)))) { /* log bei !result */ }
}
```
**Begründung:** Alles über den **bestehenden** `callServiceArgs`/`subscribe`-Pfad (Phase 5/6-Muster).
`setSoundEnable`/`setCameraEnable` fire-and-forget: der **Toggle** spiegelt das latched Topic (nicht
die Response, §6b-Pflicht 2); `camera_enable` ist ein reiner Nebeneffekt. `playSound` braucht den neuen
generischen advertise/publish-Pfad.

### 3d. `RosbridgeClient.kt` / `RosbridgeProtocol.kt` (Erweiterung)
```kotlin
// Protokoll: generische Frames (analog rosbridgeAdvertiseJoy):
fun rosbridgeAdvertise(topic: String, type: String): String   // {"op":"advertise",...}
fun rosbridgePublishString(topic: String, data: String): String // {"op":"publish","msg":{"data":...}}
fun parseBoolData(msg: JSONObject): Boolean? = if (msg.has("data")) msg.optBoolean("data") else null

// Client: idempotentes advertise (merkt sich Topics, unadvertise bei disconnect/reset):
fun advertise(topic: String, type: String) { if (advertised.add(topic) && isOpen) socket?.send(rosbridgeAdvertise(topic,type)) }
```
**Begründung:** Der Client advertised bisher nur `/joy`. Ein kleines, generisches, **idempotentes**
`advertise` (Set der advertisten Topics; bei `reset()` mit-aufräumen) hält den Soundboard-Publish sauber.
`publish(json)` wird wiederverwendet (sendet, wenn `ready` — nach Connect längst true).

### 3e. `ControlScreen.kt` (Erweiterung)
- In der Connect-Bar ein **Sim/HW-Umschalter** (z. B. zwei SegItems „Sim"/„HW", wie der Center-Toggle),
  schreibt `connection.mode`. Nur änderbar, solange **nicht verbunden** (wie das Host-Feld) — der Modus
  gehört zum Verbindungs-Kontext.
- **„Fahren →"** bleibt ein einzelner Button (F1=(b)), enabled bei `CONNECTED` (unverändert).

### 3f. `DriveScreen.kt` (Erweiterung)
```kotlin
// rechte Audio-Spalte, nur bei laufendem Stack:
if (running) AudioColumn(
    modifier = Modifier.align(Alignment.CenterEnd),
    soundOn = hmi.soundEnabled,          // null = noch kein Wert -> „…"/neutral
    onToggle = onToggleSound,            // -> setSoundEnable(!current)
    onPlay = onPlaySound,                // -> playSound(key)
)
```
- Toggle-Label „🔊 AN"/„🔇 AUS" nach `hmi.soundEnabled`; `null` → neutral/disabled bis der latched
  Wert da ist. Soundboard = 3 Buttons aus `SOUNDBOARD`.
- **Begründung:** Toggle spiegelt die Wahrheit (Topic), nicht den Tap. Soundboard immer aktiv (spielt
  immer). Rendern nur bei RUNNING = §6b-Pflicht 1 (Node existiert sonst nicht).

### 3g. `MainActivity.kt` (Erweiterung)
```kotlin
// Video-URL mit type je Modus:
video.start(videoStreamUrl(connection.host, streamType(connection.mode)))

// in syncVideo(): camera_enable an dasselbe want-Signal koppeln (HW-only):
val want = shouldStream(...)
if (lifecycleState.stack == StackState.RUNNING)
    hmiController.setCameraEnable(wantCameraEnable(connection.mode, want))
// (nur bei Änderung feuern -> letzten gesetzten Wert merken, kein Spam pro syncVideo-Aufruf)

// DriveScreen-Callbacks:
onToggleSound = { hmiController.setSoundEnable(!(hmi.soundEnabled ?: false)) },
onPlaySound   = { key -> hmiController.playSound(key) },
// ControlScreen: Modus-Umschalter schreibt connection.mode; Änderung -> syncVideo (Stream neu laden).
```
**Begründung:** `camera_enable` hängt am schon vorhandenen `want` (kein neues Gate). „Nur bei Änderung"
verhindert wiederholte set_parameters bei jedem `syncVideo`. Modus-Wechsel während Streaming → Stream
mit neuem `type` neu laden.

---

## 4. Tests-Liste (+ was bewusst NICHT)

**Unit (JUnit, ohne Gerät/Netz):**
- `VideoLogicTest`: `streamType(SIM)=="mjpeg"`, `streamType(HW)=="ros_compressed"`;
  `videoStreamUrl(host,"mjpeg")` und `…("ros_compressed")` bauen die korrekte URL (Port 8080, topic);
  `wantCameraEnable(HW,true)=true`, `(SIM,true)=false`, `(HW,false)=false`.
- `AudioLogicTest`: Topic-/Param-/Node-Strings == Contract; `SOUNDBOARD` = 3 Einträge mit Keys
  `sound_01/02/03` + Labels „Sound 1/2/3".

**Bewusst NICHT (Unit):**
- org.json-Frames (`advertise`/`publish`/`set_parameters`), `subscribe`-Glue, `parseBoolData` →
  Android-SDK-Stub, nicht JUnit-lauffähig → Integration/Sim (wie Phase 3/5/6).
- Compose-Rendering (Audio-Spalte, Sim/HW-Schalter) → visuell im Sim.
- rpicam-Strom/Wärme + echter Ton → **HW (T7A/T7B, später mit User)**.

**Sim-E2E (mit User, Desktop):**
- **Audio:** `bringup_start` → Fahren → Toggle: `ros2 param get /hexapod_audio sound_enable` folgt +
  `/hexapod/sound_enabled` (echo) spiegelt den Toggle live; 3 Sound-Buttons → Roboter-Log „would play
  sound_0X" (Sim = log-only). Toggle mutet **nur** Auto-Sounds, Soundboard spielt immer.
- **Video Sim:** Default-Modus Sim → Stream lädt wie bisher (`type=mjpeg`).
- **Video HW-type (gegen Sim-Stack):** Schalter auf **HW** + `camera.launch.py source:=test` (Desktop-
  E2E-Node aus §5) → App baut `type=ros_compressed` → **Bild kommt**. (camera_enable-Set schlägt hier
  ggf. harmlos fehl, falls der Test-Node den Param nicht führt → nur Log.)

---

## 5. Progress-Checkliste (Done-Vertrag, App-lokal)
```
Phase 7A/7B App (Audio + Video-type):
- [x] P7.1  AudioLogic.kt (Konstanten + SOUNDBOARD) + AudioLogicTest gruen
- [x] P7.2  VideoLogic: ConnMode + streamType + videoStreamUrl(host,type) + wantCameraEnable + Tests gruen
- [x] P7.3  RosbridgeProtocol/Client: generisches advertise + publishString + parseBoolData; advertise-Cleanup
- [x] P7.4  HmiState.soundEnabled; HmiController: subscribe sound_enabled + setSoundEnable + playSound + setCameraEnable
- [x] P7.5  ConnectionState.mode; ControlScreen: Sim/HW-Schalter (nur getrennt aenderbar); „Fahren →" bleibt einzeln
- [x] P7.6  DriveScreen: rechte Audio-Spalte (Toggle spiegelt sound_enabled + Sound 1/2/3), nur bei RUNNING
- [x] P7.7  MainActivity: Video-URL mit streamType(mode); syncVideo koppelt camera_enable (HW, nur bei Aenderung); Audio-Callbacks
- [x] P7.8  testDebugUnitTest gruen (89/0, +4) + kritischer Self-Review (§7)
- [ ] P7.9  Sim-E2E mit User (Audio-Toggle/Soundboard + Video mjpeg + HW-type ros_compressed) — offen (User testet); HW-Ton/rpicam spaeter
```

---

## 7. Umsetzung + kritischer Self-Review (2026-07-19)

**Geändert:** `AudioLogic.kt`+`AudioLogicTest.kt` (neu) · `VideoLogic.kt`/`VideoLogicTest.kt` (ConnMode/
streamType/videoStreamUrl(type)/wantCameraEnable) · `RosbridgeProtocol.kt` (advertise/publishString) ·
`HmiProtocol.kt` (parseBoolData) · `RosbridgeClient.kt` (idempotentes `advertise` + Cleanup) ·
`ConnectionState.kt` (mode) · `HmiState.kt` (soundEnabled) · `HmiController.kt` (Subscribe+3 Methoden) ·
`ControlScreen.kt` (Sim/HW-Schalter) · `DriveScreen.kt` (Audio-Spalte) · `MainActivity.kt` (URL-type +
camera_enable-Kopplung + Audio-Callbacks). `testDebugUnitTest` **89/0**.

| # | Punkt | Status |
|---|---|---|
| 1 | Toggle spiegelt `/hexapod/sound_enabled` (latched), nicht die Response — §6b-Pflicht 2 | OK |
| 2 | Audio-Controls nur bei `RUNNING` gerendert (Node existiert sonst nicht) — §6b-Pflicht 1 | OK |
| 3 | Soundboard spielt **immer** (eigener publish, unabhängig vom Mute-Toggle) — §6b | OK |
| 4 | Soundboard-Publisher **früh** advertised (startSubscriptions) → erster Tap kommt an; advertise idempotent + bei reset() geleert (reconnect-sicher) | OK |
| 5 | Video-`type` je Modus (Sim=mjpeg / HW=ros_compressed); Default Sim → heutiges Verhalten unverändert | OK |
| 6 | `camera_enable` **nur HW** + nur RUNNING + nur bei Änderung (kein set-Spam); in Sim nie gerufen (Node fehlt) | OK |
| 7 | Modus nur **getrennt** änderbar → Video-`type` bleibt je Session konsistent | OK |
| 8 | `soundEnabled` bei Stack-Stop/Disconnect invalidiert (`clearStackData`) → Toggle nicht stale | OK |
| 9 | Threading: Topic-Handler → `mainHandler.post` vor Compose-Write; Sender vom Main | OK |
| 10 | Regression: `videoStreamUrl` 2-arg — einziger Caller (MainActivity) + Test angepasst; alle Alt-Tests grün | OK |
| 11 | Audio-Spalte wird von offenem Overlay-Panel (config/alerts) verdeckt — wie E-STOP; unkritisch | 🟡 (Fahren mit offenem Panel unüblich) |
| 12 | rpicam-Hochlauf-Latenz beim Umschalten auf Video (HW) → vorhandenes Video-Retry fängt es | 🟡 (HW-T7B beobachten) |
| 13 | Abweichung 7A-Brief (ein Toggle statt zwei Entry-Buttons) — bewusste User-Entscheidung F1(b) | OK (dokumentiert §6.4) |

**Keine 🔴.** Die 🟡 sind bewusste v1-Grenzen bzw. HW-Beobachtungspunkte. Offen: Sim-E2E (P7.9).

---

## 6. Offene Punkte / Annahmen (im Test zu verifizieren)

1. **`type=ros_compressed`-Pfad in Sim** nur über den `source:=test`-Node gegenprüfbar (kein echtes
   rpicam am Desktop) — reicht laut Contract (Desktop-E2E verifiziert). Echte Pi-FPS/-Ton = HW.
2. **`camera_enable`-Verfügbarkeit:** Node `/hexapod_camera` existiert nur auf HW (bzw. `camera.launch.py`).
   In Sim wird `setCameraEnable` **nicht** gerufen (`mode==HW`-Guard). Fehlschlag (Node fehlt) ist
   harmlos (Log). rpicam-Hochlauf-Latenz beim Umschalten auf Video → vorhandenes Video-Retry fängt es ab.
3. **`sound_enabled` latched** braucht laufenden On-Demand-Stack (kommt nicht schon beim Connect wie die
   Always-On-Topics). Bis dahin `soundEnabled == null` → Toggle neutral/disabled (nur bei RUNNING gerendert).
4. **Abweichung vom 7A-Brief** (zwei Entry-Buttons → ein Toggle): bewusste User-Entscheidung (F1=(b)),
   App-seitig; die ROS-Naht (`sound_enable`/`sound_enabled`) bleibt unverändert. Falls die ROS-Seite die
   „mit/ohne"-Entscheidung am Übergang erwartet → an ROS-Session zurückmelden (kein Contract-Change nötig).
```

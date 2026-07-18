# Architektur — hexapod_app (App-Seite)

> **Zweck:** App-seitige Architektur an einem Ort — Laufzeit-Kanäle, Eingabe-Pipeline und die
> interne Code-Struktur (Ist + geplant). **Spec/Requirements werden hier nicht dupliziert**
> ([D10]); die **verbindliche Schnittstelle** ist der versionierte `interface_contract.md` in
> `~/hexapod_ws/project_finalization/app_control_requirements/` ([D9]).
>
> Architektur-Grundsatzentscheidungen tragen `[D1]…[D10]` und liegen (mit verworfenen
> Alternativen) in `~/hexapod_ws/.../decisions.md`. App-**interne** Entscheidungen stehen als
> ADRs in den Phasen-Implementierungs-Docs (z. B. [`phase_1_stage_b_implementation.md`](phase_1_stage_b_implementation.md)).

---

## 1. Kontext (kurz)

Ein Handy im **Razer Kishi V2** (USB-C-Gamepad-Halter) ersetzt die alte PS4-Bluetooth-Steuerung
des Hexapod. Physische Sticks/Tasten fahren den Roboter; der Screen zeigt (später) Video-Vollbild
+ Overlay + Menüs. Native Kotlin/Compose-App ([D1]), **kein ROS auf dem Handy** ([D2]).

Gesamtprojekt = **zwei getrennte Codebasen**, gekoppelt nur über den versionierten Contract:

```
  Roboter / ROS  ── ~/hexapod_ws (colcon, ROS 2 Jazzy)          [Session 1]
        ▲
        │  interface_contract.md  (versioniert, Single Source of Truth)
        ▼
  App / Handy    ── dieses Repo   (Android Studio, Gradle, Kotlin) [Session 2 = hier]
```

## 2. Laufzeit-Kanäle (App ↔ Roboter)

Zwei **getrennte** Kanäle über das lokale Netz (Handy-Hotspot Variante A / Pi-AP Variante B,
**kein Internet**, [D4]):

```
                         Handy (diese App)
        ┌───────────────────────────────────────────────┐
        │  Kishi V2 (HID-Gamepad, USB-C)                 │
        │        │  Android InputManager                 │
        │        ▼                                       │
        │  Eingabe-Pipeline → sensor_msgs/Joy (PS4)      │
        │        │                                       │
        │  Touch/Menüs → Service-/Param-Calls            │
        └────────┼───────────────────────────┬──────────┘
                 │ Kanal 1                    │ Kanal 2
                 ▼                            ▼
     rosbridge_server (Pi:9090)      Video-Stream-Server (Pi)
        WebSocket + JSON                MJPEG → später H.264/WebRTC
                 │                            │
                 ▼                            ▼
        bestehende ROS-Fahr-Logik        Kamera
        (joy_to_twist unverändert)
```

- **Kanal 1 — Steuerung/Status: rosbridge** ([D2]). WebSocket + JSON, roslibjs-artiges
  Protokoll (`op: publish | subscribe | call_service | …`). Client-Bibliothek (**seit
  Phase 2**): **OkHttp**-WebSocket (`RosbridgeClient` → `/joy`-Publisher @ ~30 Hz).
- **Kanal 2 — Video** ([D2]): eigener Stream-Server, **getrennt** von rosbridge. Erstwurf MJPEG,
  später RTSP/H.264 oder WebRTC. Bibliothek (geplant ab Phase 4): **Media3/ExoPlayer**.

**Zwei Wege über Kanal 1** ([D3]):
1. **`sensor_msgs/Joy`** aus den physischen Controller-Eingaben — trägt die **komplette
   bestehende Fahr-Logik** am Roboter (Deadzone, Scaling, Dead-Man, Sit/Stand, Stance, Gait,
   Tempo, Show) **unverändert**. Kein neuer Steuer-Code am Roboter.
2. **Touch-Aktionen** (Parameter, Menüs, Buttons) als **direkte Service-/Param-Calls** —
   **nicht** über `/joy`.

## 3. Eingabe-Pipeline (Kishi → PS4-`/joy`)

Ziel: Android-Roh-Eingaben so umsetzen, dass am Roboter exakt das ankommt, was vorher der
PS4-Controller lieferte.

```
  Kishi-Rohindizes            Abstraktes Action-Set        PS4-/joy-Layout
  (AXIS_*, KEYCODE_*)   ──►   (translate, rotate,    ──►   (axes[], buttons[]
   je Controller ein          sit_stand, stance_up,        wie ps4_usb.yaml)
   Profil (JSON)              …)  [D8]                      [D3]
```

- **[D8] Eingabe-Abstraktion:** Controller-Eingaben laufen über ein **abstraktes Action-Set**,
  entkoppelt von physischen Tasten. Pro Controller ein **Profil (JSON)**: Achsen-/Button-Index
  → Action. **Kein fixes Kishi-Hardcoding.**
- **Ziel-Layout** der Normalisierung: `~/hexapod_ws/src/hexapod_teleop/config/ps4_usb.yaml`.
- **Stand:** Phase 1 hat die Kishi-Rohindizes gemessen (Contract §1). **Phase 2 realisiert die
  Normalisierung** als **feste** Kishi→PS4-Abbildung (`toControllerInput` + `JoyMapper`) plus den
  `/joy`-Publisher über rosbridge. Die **JSON-Profil-Verallgemeinerung [D8]** (mehrere Controller)
  bleibt **Phase 8** — Phase 2 hält das Mapping bewusst hardcoded, aber isoliert austauschbar.

## 4. App-interne Struktur (Code)

### 4.1 Ist-Zustand (Phase 1 Stufe B — reine Input-Validierung)

Eine `ComponentActivity` mit Compose-UI; Gamepad-Events auf `dispatch*`-Ebene der Activity
(nicht über Compose-Fokus). Details + ADRs: [`phase_1_stage_b_implementation.md`](phase_1_stage_b_implementation.md).

| Datei (`app/src/main/java/.../hexapod/`) | Rolle |
|---|---|
| `MainActivity.kt` | Activity: `InputManager`-Erkennung + Hot-Plug; `dispatchGenericMotionEvent`/`dispatchKeyEvent` greifen Achsen/Buttons ab; Querformat + keep-screen-on |
| `GamepadState.kt` | Beobachtbarer State-Halter (Compose-Snapshot-State) — Activity schreibt, UI liest |
| `GamepadReaderScreen.kt` | Compose-UI: Geräte-Header, Achsen-Liste (Live-Balken), Button-Liste; aktive Zeile hervorgehoben |
| `GamepadFormat.kt` | Reine (Framework-freie) Helfer: Hex, Normalisierung, Kishi-Match → unit-testbar |
| `ui/theme/…` | Material3-Theme (Scaffold-Standard) |

**Datenfluss (Ist):**
```
Kishi ─HID─► Android ─► Activity.dispatch*Event ─► GamepadState (Snapshot-State)
                                                        │ liest
                                                        ▼
                                              GamepadReaderScreen (Compose)
```

### 4.2 Ist-Zustand (Phase 2 — `/joy`-WebSocket-Client)

Auf die Phase-1-Eingangsstufe (`GamepadState`) setzt der Steuer-Pfad auf: Normalisierung auf das
PS4-Layout (Contract §1) + rosbridge-Transport. Details + ADRs:
[`phase_2_joy_client_plan.md`](phase_2_joy_client_plan.md) §7.

| Datei | Rolle |
|---|---|
| `ControllerInput.kt` | framework-freier, semantischer Eingabe-Snapshot (**rohe** Android-Werte) |
| `GamepadExtract.kt` | `GamepadState.toControllerInput()` — Android-Konstanten → semantisch (Contract §1, deklarativ) |
| `JoyMapper.kt` | **reine** Transform-Logik → `JoyMessage` (8 Achsen / 15 Buttons): Negierung, `1−2t`, positionsbasierte Buttons — unit-getestet |
| `RosbridgeProtocol.kt` | rosbridge-JSON (advertise/publish/unadvertise, `org.json`) |
| `RosbridgeClient.kt` | OkHttp-WebSocket: connect/advertise/publish/close + `ConnState`-Callback; kein Auto-Reconnect |
| `ConnectionState.kt` | Compose-Snapshot-Halter für Verbindungs-/Sende-Zustand |
| `ControlScreen.kt` | Connect-Leiste + `/joy`-out-Debug + eingebetteter Roh-Reader |
| `MainActivity.kt` (erweitert) | hält `RosbridgeClient`; 30-Hz-Publish-Schleife an Activity-Lifecycle (NF1-Failsafe) |
| `GamepadReaderScreen.kt` (→ `GamepadReaderSection`) | Roh-Reader jetzt einbettbar (kein eigenes Scroll) |

**Datenfluss (Steuer-Pfad, Phase 2):**
```
GamepadState ─toControllerInput()─► ControllerInput ─JoyMapper─► JoyMessage
                                                                    │ rosbridgePublishJoy (org.json)
                                                                    ▼
  30-Hz-Schleife (lifecycleScope, onResume↔onPause) ─► RosbridgeClient ─ws://host:9090─► rosbridge → /joy
```
Pause/Screen-Lock → Schleife abgebrochen → `/joy` verstummt → `cmd_vel_timeout` hält den
Roboter (NF1).

### 4.3 Ist-Zustand (Phase 4 — Fahr-Screen-Shell + Video)

Zweiter Screen (**Fahr-Screen**) + **Video-Kanal 2**. Navigation = **leichtgewichtige
Compose-State-Navigation** (ein `Screen`-Enum, `BackHandler`) — **keine Nav-Bibliothek** (nur
2 Screens). Der 30-Hz-`/joy`-Pfad läuft über den Screen-Wechsel **unverändert** weiter
(Compose-State-Wechsel = kein `onPause`; Gamepad-Events werden ohnehin auf Activity-`dispatch*`-
Ebene abgegriffen). Details + ADRs: [`phase_4_video_shell_plan.md`](phase_4_video_shell_plan.md).

**Video (Kanal 2) = eigener OkHttp-MJPEG-Decoder** (nicht Media3/ExoPlayer — [ADR-1]): reuse des
schon vorhandenen OkHttp, **keine neue Abhängigkeit**. `multipart/x-mixed-replace` wird selbst
geparst → `Bitmap` → Compose (`ContentScale.Crop`).

| Datei | Rolle |
|---|---|
| `VideoLogic.kt` | **reine** Helfer: `Screen`/`CenterView`-Enums, `videoStreamUrl(host)` (Port 8080, Contract §5), `toggleCam`, `shouldStream`, `extractBoundary` — unit-getestet |
| `MjpegParser.kt` | **reiner** `parseMjpegStream(InputStream)`: Content-Length-Fast-Path + Boundary-Scan-Fallback + Split-Read-fest + Hint-Selbstkorrektur — unit-getestet (kein Android) |
| `MjpegStream.kt` | OkHttp-GET (`readTimeout 0`) + `BitmapFactory` → auf Main gepostet; start/stop-Lifecycle + Fehlerbild/Auto-Retry (Glue, integrationsverifiziert) |
| `VideoState.kt` | Compose-Snapshot-Halter (centerView, frame, streaming, error) |
| `DriveScreen.kt` | Fahr-Screen: Center-View (Ebene 0) + Overlay-Slots §5 (Ebene 1) + leere config/alerts/show-Panels |
| `MainActivity.kt` (erweitert) | Screen-State + `syncVideo()`-Gate (an `StackState.RUNNING` gekoppelt, Contract §5) + Betreten-Poll; hält `MjpegStream` |
| `ConnectionState.kt` (erweitert) | `host` hochgezogen (Fahr-Screen leitet die Video-URL daraus ab) |

**Datenfluss (Video-Pfad, Phase 4):**
```
web_video_server (host:8080, MJPEG) ─ws/http─► MjpegStream (OkHttp GET, BG-Thread)
   └─ parseMjpegStream ─► JPEG-Bytes ─► BitmapFactory ─(Main)─► VideoState.frame
                                                                    │ liest
                                                                    ▼
                                       DriveScreen · CenterPane (ContentScale.Crop, Vollbild)
```
Gate: Stream nur bei `verbunden && StackState.RUNNING && Fahr-Screen && Center=Kamera && Vordergrund`
(`shouldStream`, rein). onPause/Back/Toggle-weg → `MjpegStream.stop()`.

### 4.5 Ist-Zustand (Phase 5 — Status-Overlay + Config-Panel + Dropdowns + 3D-Viz)

Die Phase-4-Shell wird mit **Live-Daten** gefüllt. Kern: der `RosbridgeClient` wird um **Subscribe +
`publish`-Routing** und **generisches `call_service` mit Args** erweitert (der Phase-2/3-`/joy`-/
Trigger-Pfad bleibt unverändert). Interface = `interface_contract.md` **v0.9.1 §6a** (5 JSON-Topics +
Config-Manifest + native Param-Services + Set-Stance/Set-Tempo). Details/ADRs:
[`phase_5_status_config_plan.md`](phase_5_status_config_plan.md).

**Schichtung wie gehabt:** reine, unit-getestete Logik getrennt vom org.json-/Compose-Glue.

| Datei | Rolle |
|---|---|
| `HmiModels.kt` | reine Datenmodelle (Status/Tempo/Capabilities/ParamSpec/ConfigManifest/Alert/…) |
| `ConfigLogic.kt` · `CycleLogic.kt` · `FootLogic.kt` · `AlertLogic.kt` · `Robot3dLogic.kt` | **reine** Logik (Gating/Cap/Step/Validierung; cycle-to-target-Schritt; Foot; Alerts; FK+Projektion) — unit-getestet |
| `HmiProtocol.kt` | org.json-Parser der Topics + Param-Service-Kodierung (get/set_parameters, SetBool) — Glue |
| `HmiState.kt` | Compose-Snapshot-Halter aller HMI-Live-Daten |
| `RosbridgeProtocol.kt` (erw.) | + subscribe-Frames (latched-QoS §7.4), `callServiceArgs`, generische `RawResponse`, `parsePublish` |
| `RosbridgeClient.kt` (erw.) | + `subscribe`/`unsubscribe` + `publish`-Routing an Topic-Handler; `callServiceArgs` |
| `HmiController.kt` | **framework-leichter Orchestrator** (B3): Subscriptions + Param get/set + cycle-to-target; hält `ros`+`hmi`+`Handler` |
| `ConfigPanel.kt` · `AlertsPanel.kt` · `Robot3dView.kt` | Compose-UI (generisches Panel; Alerts-Liste; 3D-Canvas mit 1-Finger-Rotation/2-Finger-Zoom) |
| `DriveScreen.kt` (erw.) | Overlay-Slots live (Status-Chips links vertikal); antippbare Dropdown-Slots; Panels gefüllt; 3D-Center aktiv |
| `MainActivity.kt` (erw.) | delegiert die HMI-Orchestrierung an `HmiController`; Lifecycle-Gates (Video/Subs/joints) |

**Verfügbarkeits-Schichten (Contract §6a):** `capabilities`/`config_manifest`/`alerts` laufen in der
**Always-On-Schicht** (`hmi_status`) → schon **beim Connect** da (Panel + Dropdowns sofort befüllbar,
noch vor dem Roboterstart). `status`/`tempo`/`foot_contacts`/`joint_states` kommen aus dem
**On-Demand-Stack** → erst **nach `bringup_start`**. Darum rendert das Config-Panel vor dem Start
(aus dem Manifest, mit Defaults), aber `get/set_parameters` greifen erst, wenn der Stack läuft.

**Subscription-Lifecycle:** Die HMI-Subscriptions sind an **verbunden × Vordergrund** gekoppelt
(`HmiController.start/stopSubscriptions`, B4) — im Hintergrund kein status/foot-Verkehr. `/joint_states`
(high-rate) ist zusätzlich nur abonniert, solange die 3D-Ansicht aktiv ist. Bei Reconnect verwirft der
`RosbridgeClient` die Handler (`reset`), die Activity subscribt beim nächsten CONNECTED neu.

**Datenfluss (HMI, Phase 5):**
```
rosbridge (host:9090) ──subscribe/publish──► RosbridgeClient.topicHandlers
   status/tempo/foot/capabilities/manifest/alerts/joint_states
        └─ HmiProtocol.parse* ─(OkHttp-Thread)─► (Main) HmiState  ◄─── DriveScreen/ConfigPanel/… liest
   Touch → onSetParam/onSet*/startCycle → callServiceArgs(get/set_parameters | SetBool)
```
- **Overlay:** `status` + `tempo` gemergt; `/foot_contacts` → grünes Raster.
- **Config-Panel:** generisch aus `config_manifest`; Werte via native `get/set_parameters`; Gating/
  Dynamic-Cap/Reject-`reason` (Contract §6a).
- **Dropdowns:** antippbare Slots aus `capabilities`; gait = `set_parameters(gait_pattern)`, stance/
  tempo = cycle-to-target (`/hexapod_cycle_stance`/`_tempo`), standing-gated.
- **3D-Viz:** zero-dep FK+Iso-Projektion aus `/joint_states` (Geometrie statisch aus
  `hexapod_description`); `/joint_states` nur abonniert, solange die 3D-Ansicht aktiv ist.

### 4.4 Geplante Erweiterung (Richtung, keine Festlegung)

Wächst phasenweise; jede neue Schicht kommt **erst bei Bedarf** und über den Version-Catalog:

| Ab Phase | Baustein (geplant) | Bibliothek |
|---|---|---|
| (offen) | **Show-Posen-Panel** — der `show`-Slot öffnet noch ein leeres Panel (Show-Toggle/`cmd_show`); nicht im P5-Scope | — |
| Upgrade | Video-Latenz: RTSP/H.264 oder WebRTC (löst MJPEG ab) | **Media3/ExoPlayer** (dafür reserviert) |
| 4+ | Verbindungs-/Foreground-Service (Netz-Lifecycle) — HMI-Subs sind seit Phase 5 an den Vordergrund gekoppelt (B4), ein Service würde das formalisieren | — |
| 6 | E-Stop scharf + Recovery (`safety_freeze`/`safety_reset` + Recovery-Service) | — |
| 8 | Controller-Profil (JSON) statt fester Kishi-Abbildung [D8]; Auto-Reconnect; ViewModel/SavedState (Activity ist mit Phase 5 groß geworden) | — |

> **Phase 5 ist umgesetzt (Ist-Zustand §4.5)** — die frühere „geplant"-Zeile dazu ist entfernt.

> Neue Topics/Services/Felder werden **nicht hier erfunden**: Ist etwas im Contract noch
> `[TBD-Phase N]`, geht es als offener Punkt an die ROS/Contract-Session zurück ([D9]).

## 5. Verweise

- **Schnittstelle (SoT):** `~/hexapod_ws/.../interface_contract.md`
- **Architektur-Entscheidungen [D1]–[D10]:** `~/hexapod_ws/.../decisions.md`
- **PS4-`/joy`-Ziel-Layout:** `~/hexapod_ws/src/hexapod_teleop/config/ps4_usb.yaml`
- **Arbeitsweise/Konventionen:** [`../CLAUDE.md`](../CLAUDE.md)

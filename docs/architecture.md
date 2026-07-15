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
  Protokoll (`op: publish | subscribe | call_service | …`). Client-Bibliothek (geplant ab
  Phase 2): **OkHttp**-WebSocket.
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
- **Stand:** **Phase 1 Stufe B misst die Kishi-Rohindizes** (`AXIS_*`/`KEYCODE_*`) — das
  Fundament der Abbildung. Die Profil-/Normalisierungs-Schicht und der `/joy`-Publisher
  entstehen **ab Phase 2** (Contract §1).

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

### 4.2 Geplante Erweiterung (Richtung, keine Festlegung)

Wächst phasenweise; jede neue Schicht kommt **erst bei Bedarf** und über den Version-Catalog:

| Ab Phase | Baustein (geplant) | Bibliothek |
|---|---|---|
| 2 | Controller-Profil (JSON) + Normalisierung auf PS4-Layout | — |
| 2 | rosbridge-Client (WebSocket) + `sensor_msgs/Joy`-Publisher | OkHttp |
| 3+ | Touch-Aktionen → Service-/Param-Calls; Status-Overlay | OkHttp |
| 4 | Video-Vollbild (Kanal 2) | Media3/ExoPlayer |
| 4+ | Verbindungs-/Foreground-Service (Netz-Lifecycle) | — |

> Neue Topics/Services/Felder werden **nicht hier erfunden**: Ist etwas im Contract noch
> `[TBD-Phase N]`, geht es als offener Punkt an die ROS/Contract-Session zurück ([D9]).

## 5. Verweise

- **Schnittstelle (SoT):** `~/hexapod_ws/.../interface_contract.md`
- **Architektur-Entscheidungen [D1]–[D10]:** `~/hexapod_ws/.../decisions.md`
- **PS4-`/joy`-Ziel-Layout:** `~/hexapod_ws/src/hexapod_teleop/config/ps4_usb.yaml`
- **Arbeitsweise/Konventionen:** [`../CLAUDE.md`](../CLAUDE.md)

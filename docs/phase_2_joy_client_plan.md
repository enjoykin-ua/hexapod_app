# Phase 2 · App — `/joy`-WebSocket-Client (Plan zur Freigabe)

> **App-Seite des Meilensteins** (PS4-Parität in der Sim). Die App verbindet sich per
> WebSocket mit `rosbridge_server` und publisht `sensor_msgs/Joy` → bestehende
> `joy_to_twist`-Kette → Sim-Roboter fährt. **Kein Pi, kein Video, keine Touch-Parameter.**
>
> **Verbindliche Quellen (read-only, hexapod_ws):**
> Interface = `interface_contract.md` **v0.3** (§0 Transport/QoS, §1 `/joy`-Layout).
> Aufgabe/Akzeptanz = `phase_2_control_baseline_plan.md` **§5** (App-Seiten-Brief).
> Referenz-Nachrichtenformat = `tools/joy_ws_test_client.py` (funktionierender `/joy`-Publisher).
>
> **Status: 🟡 Plan — wartet auf Freigabe.** Erst nach Freigabe wird Code geschrieben (§3-Prinzip).

---

## 0. Ziel + Abgrenzung

**Ziel:** Aus den in Phase 1 bereits roh abgegriffenen Kishi-Eingaben (`GamepadState`) eine
Contract-konforme `sensor_msgs/Joy`-Nachricht bauen und **~30 Hz stetig** über rosbridge
publishen, sodass der Sim-Roboter wie mit dem Test-Client fährt.

**Bewusst NICHT in Phase 2** (spätere Phasen): Touch-Parameter-UI, Video, Status-Overlay,
Not-Halt-Service, **Controller-Profil-JSON** ([D8]/Phase 8), Auto-Reconnect (Phase 8),
Persistenz der Host-Eingabe. Das Kishi→PS4-Mapping ist in P2 **hardcoded**, aber sauber in
`JoyMapper`/Extractor isoliert, damit ein Profil es später ersetzt.

---

## 1. Logik-Skizze + Design-Begründungen

### 1.1 Datenfluss (neu obendrauf auf Phase 1)

```
GamepadState (Phase 1, Android-Konstanten-gekeyt)
   │  toControllerInput()      [Android-Layer: deklarative Transkription Contract §1]
   ▼
ControllerInput (plain Kotlin: leftStickX/Y, rightStickX/Y, l2, r2, dpadX/Y, a,b,x,y,l1,r1,
   l2btn,r2btn, select,start,mode, thumbL,thumbR, l4,r4 — ROHE Android-Werte)
   │  JoyMapper.toJoy()        [rein, framework-frei, unit-getestet: DIE Transform-Logik]
   ▼
JoyMessage(axes: List<Float>[8], buttons: List<Int>[15])
   │  rosbridgePublishJson()   [org.json-Envelope, Integration-verifiziert]
   ▼
RosbridgeClient.publish(json)  [OkHttp-WebSocket → ws://<host>:9090]
```

**Die 30-Hz-Schleife** (an Activity-Lifecycle gekoppelt):
```
onResume: lifecycleScope.launch {
    warte bis client.state == CONNECTED
    delay(500)                         // rosbridge-Advertise + DDS-Discovery, sonst erste Frames weg
    while (isActive) {
        if (client.ready) {
            val joy = JoyMapper.toJoy(gamepadState.toControllerInput())
            client.publish(rosbridgePublishJson(joy))   // sendet AUCH bei neutral (NF1)
        }
        delay(33)                      // ~30 Hz
    }
}
onPause: job.cancel()                  // Publish stoppt → cmd_vel_timeout → Roboter hält (NF1-Failsafe, GEWOLLT)
```

### 1.2 JoyMapper — die Transforms (Contract §1, exakt)

**Achsen (8):** `axes[0]=−lx`, `axes[1]=−ly`, `axes[3]=−rx`, `axes[4]=−ry` (Sticks negiert);
`axes[2]=1−2·l2`, `axes[5]=1−2·r2` (Trigger, jeden Frame → idle 0 ergibt +1); `axes[6]=dpadX`,
`axes[7]=dpadY` (D-Pad passthrough, Vorzeichen in Integration prüfen).

**Buttons (15, positionsbasiert):** `[0]=a [1]=b [2]=y [3]=x` (Xbox-Position→PS4), `[4]=l1
[5]=r1` (Dead-Man), `[6]=l2btn [7]=r2btn`, `[8]=select [9]=start [10]=mode`, `[11]=thumbL
[12]=thumbR`, `[13]=l4(C) [14]=r4(Z)`. `true→1`, sonst `0`. **Keine App-Deadzone** (Roboter filtert).

### 1.3 Design-Begründungen (ADRs, mit verworfenen Alternativen)

**ADR-2A1 — Reiner `JoyMapper` + `ControllerInput`-Zwischentyp.**
Gewählt: Android-Konstanten (`MotionEvent.AXIS_*`/`KeyEvent.KEYCODE_*`) werden in einem dünnen
Android-Extractor gelesen und in ein plain-Kotlin `ControllerInput` überführt; die eigentliche
Transform-Logik (Negierung, `1−2t`, Positions-Mapping, Array-Aufbau) liegt im framework-freien
`JoyMapper` → per JUnit ohne Gerät testbar (wie `GamepadFormat`). Verworfen: (a) `JoyMapper`
direkt auf `MotionEvent.AXIS_*` → nicht JUnit-testbar (android.jar-Stubs); (b) Framework-Int-Werte
als eigene Konstanten duplizieren → fragil (`AXIS_Z` vs `AXIS_RZ` etc. leicht falsch).

**ADR-2A2 — `org.json` fürs rosbridge-Envelope** (User-Entscheidung). Null Extra-Dependency,
Nachricht ist klein+fix. Verworfen: `kotlinx.serialization` (+Plugin/Dep). **Konsequenz:**
`org.json` ist in JUnit nicht gemockt → der JSON-String wird **nicht** unit-getestet, sondern
über den Integrationstest (Roboter fährt) + Logging verifiziert. Die *Zahlen* (axes/buttons)
sind über `JoyMapper` getestet, nur die Hülle nicht.

**ADR-2A3 — Publish-Schleife an Activity-Lifecycle, KEIN Foreground-Service.**
Bei App-Pause/Screen-Lock stoppt der Publish → `cmd_vel_timeout` → Roboter hält. Das ist der
**gewollte NF1-Failsafe**. Ein Foreground-Service würde die App im Hintergrund weiterfahren
lassen → Failsafe untergraben + mehr Komplexität. Verworfen daher bewusst.

**ADR-2A4 — OkHttp-WebSocket** (CLAUDE.md §2 vorgesehen). Verworfen: Java-WebSocket/Ktor
(keine Vorteile hier, OkHttp ist Android-Standard).

**ADR-2A5 — Schleife auf Main-Dispatcher** (`lifecycleScope`, Default = Main). Compose-Snapshot-
State ist thread-sicher lesbar, `delay` ist non-blocking, OkHttp-`send` enqueued nicht-blockierend,
30 Hz ist trivial. Verworfen: Default-Dispatcher → unnötige Cross-Thread-State-Reads.

**ADR-2A6 — Kishi-Mapping hardcoded in P2**, isoliert in Extractor/`JoyMapper`. Das
Controller-Profil-System ([D8], JSON Achsen/Button-Index→Action) ist **Phase 8**; hier würde es
overengineeren. Isolation hält den späteren Austausch billig.

### 1.4 RosbridgeClient (OkHttp)

`connect(host, port=9090)`: `ws://host:port`, `newWebSocket` + Listener. `onOpen` → sende
`{"op":"advertise","topic":"/joy","type":"sensor_msgs/Joy"}`, Status → CONNECTED (Referenz-Client
spiegeln: **kein** QoS-Override → rosbridge-Default = RELIABLE, Contract §0). `onMessage` → loggen
(rosbridge-Status/Fehler). `onFailure`/`onClosed` → Status DISCONNECTED/ERROR. `publish(json)` →
`webSocket.send(json)` (no-op wenn nicht offen). `disconnect()` → `unadvertise` + `close(1000)`.
Status als Callback `(ConnState)->Unit`; die Activity marshallt auf den Main-Thread und schiebt
ihn in Compose-State.

### 1.5 UI (Compose)

Bestehenden Phase-1-Roh-Reader **behalten** (User-Entscheidung) + ergänzen:
- **Connect-Leiste:** Host-`TextField` (in-memory `remember`, Default leer + Hinweis „Desktop-IP,
  `hostname -I`"), Connect/Disconnect-Button, farbcodierter Status.
- **`/joy`-out-Anzeige:** die 8 Achsen + 15 Buttons, die gerade gesendet werden — direkt aus
  `JoyMapper`. Hilft beim Vorzeichen-Check am Gerät (P2.9).

### 1.6 Build + Manifest

- **Dependency** (Version-Catalog): `okhttp` (Vorschlag `4.12.0`). `kotlinx-coroutines`
  transitив über `lifecycle-runtime-ktx`/Compose vorhanden; falls Build meckert → explizit
  `kotlinx-coroutines-android` nachziehen.
- **Manifest:** `<uses-permission android:name="android.permission.INTERNET"/>` +
  `android:usesCleartextTraffic="true"` (`ws://` im lokalen Netz; App hat keinen Internet-Zugriff
  → vertretbar; Alternative wäre eine engere `network-security-config`, für ein reines No-Internet-
  Kontroll-Gerät Overkill).

---

## 2. Tests-Liste + was bewusst NICHT getestet wird

**Automatisiert (JUnit, `JoyMapperTest` — rein):**

| Test | Prüft |
|---|---|
| Neutral-Eingabe | `axes` alle 0 **außer** `axes[2]=+1`, `axes[5]=+1` (Trigger-Idle); `buttons` alle 0 |
| Stick hoch (`ly=−1`) | `axes[1]=+1` (vorwärts); links (`lx=−1`) → `axes[0]=+1` |
| Rechter Stick | `rx=−1`→`axes[3]=+1`, `ry=−1`→`axes[4]=+1` |
| Trigger | `l2=0`→`axes[2]=+1`; `l2=1`→`−1`; `l2=0.5`→`0` (analog R2/`axes[5]`) |
| Face-Buttons positionsbasiert | `a`→`[0]`, `b`→`[1]`, `y`→`[2]`, `x`→`[3]` |
| Dead-Man / Schultern | `r1`→`[5]`, `l1`→`[4]` |
| Extra-Slots | `thumbL`→`[11]`, `thumbR`→`[12]`, `l4`→`[13]`, `r4`→`[14]` |
| Array-Längen | `axes.size==8`, `buttons.size==15` |

**Manuell / Integration (P2.9, User am Gerät):** Handy im Kishi → App verbindet → Sim fährt
(R1 halten + Stick). Dann **Vorzeichen-Endverifikation** via `ros2 topic echo /joy` (Contract §1):
Stick hoch=vorwärts / links=links, Trigger idle=+1, D-Pad-Richtung. Abweichung → Transform bzw.
`sign_*`-Params justieren.

**Bewusst NICHT getestet:** rosbridge-JSON-Envelope (org.json nicht JUnit-mockbar → Integration);
WebSocket-Connect/Fehlerpfade (manuell); NF1-Comms-Loss (ROS-seitig T2.5 + Integration);
D-Pad-/Stick-Vorzeichen (Integration); Latenz (qualitativ, später); Auto-Reconnect (Phase 8);
Instrumented-/UI-Tests (keine in dieser Phase).

---

## 3. Progress-Checkliste (Done-Vertrag)

```
Phase 2 (App — /joy-WebSocket-Client):
- [x] P2A.1 OkHttp via Version-Catalog eingebunden; assembleDebug gruen
- [x] P2A.2 Manifest: INTERNET-Permission + usesCleartextTraffic=true
- [x] P2A.3 ControllerInput + toControllerInput()-Extractor (GamepadState -> semantisch, Contract §1)
- [x] P2A.4 JoyMapper (alle Transforms) + JoyMapperTest gruen (9 Tests)
- [x] P2A.5 rosbridgePublishJson() (org.json-Envelope, Format wie Referenz-Client)
- [x] P2A.6 RosbridgeClient (OkHttp: connect/advertise/publish/close + Status-Callback)
- [x] P2A.7 30-Hz-Publish-Schleife an onResume/onPause gekoppelt + Discovery-Delay
- [x] P2A.8 UI: Connect-Leiste (Host/Connect/Status) + /joy-out-Debug + Roh-Reader behalten
- [x] P2A.9 assembleDebug + Unit-Tests gruen (16/0); kritischer Self-Review (Tabelle §7)
- [ ] P2A.10 [Integration, User] Handy -> Desktop-rosbridge -> Sim faehrt; Vorzeichen-Check via echo
```

---

## 4. Offene Punkte für User-Review (vor Code-Beginn)

1. **OkHttp-Version:** Vorschlag `4.12.0` (breit erprobt, stabil). Ok, oder eine bestimmte
   (z. B. 5.x) gewünscht?
2. **Host-Feld-Default:** leer lassen + Hinweistext (`hostname -I` am Dev-PC), oder einen
   Platzhalter-Wert vorbelegen? (Empfehlung: leer + Hinweis, damit nichts Falsches „scheinbar
   stimmt".)
3. **Cleartext:** breites `usesCleartextTraffic=true` (Empfehlung, No-Internet-App) ok, oder
   doch engere `network-security-config`?
4. **Reconnect:** in P2 bewusst simpel — manuell Connect/Disconnect, kein Auto-Reconnect
   (das ist Phase 8). Einverstanden?
5. **`architecture.md`:** ziehe ich im Zuge der Umsetzung nach (Laufzeit-Kanal rosbridge +
   Code-Struktur), nicht jetzt. Ok?

---

## 7. Umsetzung + kritischer Self-Review (2026-07-16)

**Gebaute Dateien (neu):** `ControllerInput.kt`, `GamepadExtract.kt` (Extractor),
`JoyMapper.kt` (+ `JoyMessage`), `RosbridgeProtocol.kt` (org.json-Envelope),
`RosbridgeClient.kt` (+ `ConnState`), `ConnectionState.kt`, `ControlScreen.kt`,
`test/.../JoyMapperTest.kt`. **Geändert:** `MainActivity.kt` (Client + 30-Hz-Lifecycle-Schleife
+ `ControlScreen`), `GamepadReaderScreen.kt` (→ einbettbare `GamepadReaderSection`),
`AndroidManifest.xml`, `libs.versions.toml`, `app/build.gradle.kts`.

**Build:** `assembleDebug` ✅ · `testDebugUnitTest` ✅ **16 Tests / 0 Fehler** · keine Warnungen.

**Self-Review-Tabelle:**

| # | Punkt | Status |
|---|---|---|
| 1 | WebSocket-Callback-Parameter `ws` erzeugte Namens-Warnung (vs. Supertyp) | 🔴→✅ Feld in `socket` umbenannt, Params `webSocket` |
| 2 | Stale-Callback-Race: spätes `onClosed`/`onFailure` alter Socket überschreibt neue Verbindung | 🔴→✅ Identitäts-Guard `if (socket !== webSocket) return` |
| 3 | Crash bei ungültigem Host (`url()` wirft `IllegalArgumentException`) | 🔴→✅ try/catch → ERROR-Status statt Absturz |
| 4 | 30-Hz-Read von `lastJoy` in `ControlScreen` → ganzer Screen rekomponiert | 🟡→✅ Read in `JoyOutSection` verschoben (lokalisiert) |
| 5 | Sticks/Trigger/Positions-Buttons/Array-Längen | ✅ per `JoyMapperTest` gedeckt |
| 6 | NF1-Failsafe (Pause → Publish-Stop) | ✅ Schleife an Lifecycle; kein Foreground-Service |
| 7 | QoS RELIABLE | ✅ advertise ohne Override = rosbridge-Default (Contract §0) |
| 8 | org.json-Envelope + Extractor nicht unit-getestet | 🟡 bewusst → Integration (P2A.10) |
| 9 | Vorzeichen (Stick/D-Pad) + Trigger-Idle final | 🟡 Integration via `ros2 topic echo /joy` (P2A.10) |
| 10 | Host-Persistenz, Auto-Reconnect, OkHttp-Dispatcher-Shutdown | 🟢 später (Phase 8) |

**Offen = nur P2A.10 (Integration, User):** Sim-Walk + `app_teleop` starten, App öffnen,
Desktop-IP eintragen, verbinden → Sim fährt (R1 + Stick). Die **/joy-out-Anzeige** im Screen
zeigt die gesendeten Werte live → damit der Vorzeichen-Check (`ros2 topic echo /joy`) direkt
gegengeprüft werden kann.

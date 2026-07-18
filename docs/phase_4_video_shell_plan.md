# Phase 4 (App) — Fahr-Screen-Shell + Vollbild-Video — App-seitiger Plan

> **Status: 🟡 Plan (zur Freigabe).** App-Gegenstück zum ROS-Plan
> `~/hexapod_ws/project_finalization/app_control_requirements/phase_4_video_shell_plan.md` §5
> (App-Shell + Slot-Vertrag) + §0 (Abgrenzung).
> **Interface = SoT:** `interface_contract.md` **v0.8** (§5 Video/MJPEG :8080 `/camera/image_raw`,
> §0 Adressierung/Host, §7.4 Latched-über-rosbridge) — hier nur **referenziert**, nicht kopiert ([D10]).
>
> **Vorgehen (CLAUDE.md §3):** Plan → **User-Freigabe** → Implementierung (MJPEG-Prototyp zuerst) →
> Tests → Self-Review → Fertig-Meldung. Dieses Doc = der Plan.
>
> **Zwei User-Entscheidungen (Alignment vor diesem Plan):**
> 1. **MJPEG-Rendering = eigener OkHttp-Decoder** (nicht WebView, nicht Dritt-Lib) — [ADR-1].
> 2. **Kamera-Gate = vorhandenes Status-Polling (`StackState.RUNNING`)**, kein neuer
>    latched-Subscribe (Option B bleibt der spätere Nachzug) — [ADR-2].

---

## 0. Ziel + Abgrenzung

**Ziel:** Ein **eigener Fahr-Screen** (Querformat) mit **Vollbild-MJPEG-Video** als Center-View,
einem **3-Wege-Center-Toggle** (Nichts/Kamera/3D), **Kamera an/aus** (app-seitig = Stream laden/
entladen) und **allen Overlay-Slots aus dem §5-Layout positioniert, aber leer/Label** (Vertrag
für Phase 5). Navigation vom Lifecycle-Screen (Phase 3) rein und per Back/Geste zurück. Der
**Kishi fährt weiter über `/joy`** (Phase 2, unverändert).

**In Phase 4 (App) — P4.7–P4.9 (ws), Done T4.4–T4.7:**
- **Video-Pipeline app-seitig:** eigener MJPEG-Decoder über das **schon vorhandene OkHttp**
  (`multipart/x-mixed-replace` parsen → `Bitmap` → Compose, `ContentScale.Crop`).
- **Fahr-Screen** (neuer Screen) + **leichtgewichtige Compose-Navigation** Lifecycle ↔ Drive.
- **Center-Toggle** (Nichts=Default / Kamera / 3D=reserviert-disabled) + **cam-Toggle**.
- **Slot-Vertrag:** alle Slots aus §5 positioniert; `conn`=Anzeige, `cam`=Toggle,
  `config`/`alerts`/`show`=Buttons → **leere** Overlay-Panels, Rest leer/Label,
  `E-STOP`=reservierte Position (disabled).

**Bewusst NICHT in Phase 4 (App) — deckt sich mit ws-Plan §0/§5:**
- **Overlay-Inhalte / Live-Daten** (safety/tip/state/stance/gait/tempo/foot) → **Phase 5**
  (brauchen den Status-Publisher, Contract §6 `[TBD-Phase 5]`). Nur **leere Slots** positioniert.
- **Dropdown-Logik** (gait/tempo/stance), **3D-Roboter-Viz**, **Config-/Alerts-/Show-Inhalte**
  → Phase 5.
- **E-Stop scharf** (`/hexapod_safety_freeze`) → **Phase 6** (nur reservierte Position).
- **RTSP/H.264/WebRTC**, **Media3/ExoPlayer** → späteres Latenz-Upgrade (NF4), nicht jetzt ([ADR-1]).
- **Option B** (latched `/hexapod/bringup_running`-Live-Push, P3A.13) → weiterhin Nachzug, **kein
  Blocker** für Phase 4 ([ADR-2]).
- **Kein Auto-Reconnect** (Phase 8), **kein** GUI-Feinschliff — zweckmäßige, aber
  vertragstreu positionierte Shell.

---

## 1. Logik-Skizze / Vorgehen (+ Begründung)

### 1a. MJPEG-Decoder über OkHttp (Kern-Neubau) — [ADR-1]

Die App lädt die **rohe** MJPEG-URL (Contract §5, live gegen den Server verifiziert):

```
http://<host>:8080/stream?topic=/camera/image_raw&type=mjpeg
```

Ein `multipart/x-mixed-replace`-Stream ist eine endlose HTTP-Antwort, deren Body aus
aneinandergereihten JPEG-Teilen besteht, getrennt durch einen Boundary-Marker:

```
--<boundary>\r\n
Content-Type: image/jpeg\r\n
[Content-Length: <n>\r\n]        ← evtl. vorhanden (Fast-Path), evtl. nicht (Fallback)
\r\n
<JPEG-Bytes>\r\n
--<boundary>\r\n
...
```

**Zwei getrennte Schichten** (wie im Repo üblich: reine Logik testbar, Framework/Netz separat):

- **`MjpegParser` (rein, framework-frei → unit-testbar, [ADR-4]):** liest aus einem
  `InputStream` und ruft je vollständigem Bild einen Callback mit dem **JPEG-`ByteArray`** auf.
  Unterstützt **beide** Formen (weil am Live-Stream noch zu verifizieren, [offener Punkt 1]):
  - **Content-Length vorhanden → Fast-Path:** Header bis Leerzeile lesen, `Content-Length`
    ziehen, exakt `n` Bytes lesen → Frame.
  - **kein Content-Length → Boundary-Scan-Fallback:** Bytes puffern, bis der nächste
    Boundary-Marker auftaucht → alles davor (abzgl. Part-Header) = Frame.
  - **robust gegen Split-Reads:** ein `InputStream` liefert nicht garantiert eine ganze
    Nachricht pro `read()` → der Parser puffert über `read()`-Grenzen hinweg (genau das ist im
    Unit-Test mit einem drosselnden Fake-Stream abzusichern, U2).

  ```
  // Pseudocode (rein):
  fun parse(input, onFrame):
      boundary = readBoundaryFromFirstDelimiter(input)   // "--...."
      while (!closed):
          skipToAfter(boundary)
          headers = readHeadersUntilBlankLine(input)     // "Content-Type", ["Content-Length"]
          frame = if (headers.contentLength != null)
                      readExactly(input, headers.contentLength)
                  else
                      readUntilNextBoundary(input, boundary)
          onFrame(frame)                                  // ByteArray (ein JPEG)
  ```

- **`MjpegStream` (Android-Glue, integrationsverifiziert):** OkHttp-`GET` auf die URL,
  Response-Body-Stream in `MjpegParser` speisen, jedes Frame per `BitmapFactory.decodeByteArray`
  zu `Bitmap`, dann **auf den Main-Thread marshallen** und in einen Compose-State schreiben.
  - **Eigener OkHttpClient mit `readTimeout(0)`** (kein Timeout — der Stream endet nie) +
    kurzer `connectTimeout` (schnelles Fail, wenn Port 8080 zu ist). **Nicht** den
    `RosbridgeClient`-Client wiederverwenden (dessen `pingInterval`/Timeouts passen nicht zu
    einem Dauer-Stream).
  - **Threading:** OkHttp-Async-Call → Lese-/Decode-Schleife auf OkHttp-/BG-Thread;
    `bitmap`-Übergabe an Compose per `mainHandler.post` (wie `onState`/`callService` schon).
  - **Lifecycle:** `start(url)` / `stop()`. `stop()` cancelt den Call und schließt den Stream
    sauber (sonst leakt der Socket / der BG-Thread läuft weiter).
  - **Fehlerbild:** Connect-refused/Read-Fehler (z. B. Stack zwischen zwei Polls gestoppt) →
    `VideoState.error` setzen, **nicht crashen**; Overlay zeigt einen Hinweis + erlaubt
    Neu-Laden (cam aus/an). Wenige Auto-Retries mit Backoff (Detail in Impl).

**Begründung [ADR-1]:** reuse des schon vorhandenen OkHttp → **keine neue Abhängigkeit**;
`multipart/x-mixed-replace` ist nicht ExoPlayers Kerngebiet, ein WebView-Chromium rendert
multipart nur unzuverlässig (Support teils entfernt) und macht center-crop nur über
HTML/CSS-Wrapper; ein kleiner eigener Decoder gibt volle Kontrolle über Crop, An/Aus und
Fehlerbild und ist zur Hälfte (Parser) **rein unit-testbar**. Media3/ExoPlayer bleibt für das
spätere RTSP/H.264/WebRTC-Upgrade reserviert (architecture §4.3 nachziehen).

### 1b. Video-URL aus dem Host ableiten (rein, unit-testbar)

Der Host wird **einmal** eingegeben (Connect-Leiste). Contract §0/§5: **gleiche IP wie
rosbridge, nur Port 8080 statt 9090**.

```
fun videoStreamUrl(host: String): String =
    "http://${host.trim()}:8080/stream?topic=/camera/image_raw&type=mjpeg"
```

Rein → **unit-getestet** (U1: Port/Pfad/Trim korrekt). **Host hochziehen:** heute lebt `host`
lokal in `ConnectBar` (`rememberSaveable`) und geht nur an `ros.connect(host)`. Für die Video-URL
muss der Fahr-Screen ihn kennen → **`host` in `ConnectionState` hochziehen** (`var host by
mutableStateOf("")`); `ConnectBar` bindet dann an `connection.host`, `videoStreamUrl` liest ihn.
Reine App-interne Umstrukturierung, **kein Contract-Thema**.

### 1c. Kamera-Gate an `StackState.RUNNING` — [ADR-2]

Contract §5: Port 8080 ist **erst nach `/hexapod_bringup_start`** offen (Stream-Server im
On-Demand-Stack). Die App darf **keinen Stream laden, solange der Stack nicht läuft** (sonst
Connection-refused/Timeout).

- **Quelle = das vorhandene Phase-3-Polling:** `LifecycleState.stack == StackState.RUNNING`
  (aus `/hexapod_bringup_status`, wird nach Connect + nach Start/Stop + manuell gepollt).
- **Gate:** Stream nur laden, wenn `connected && stack == RUNNING && centerView == KAMERA`.
- **Con (bewusst akzeptiert, User-Entscheidung):** der Zustand kann **veralten** (kein
  periodischer Timer). Milderung: (a) beim **Betreten des Fahr-Screens einmal `pollStatus()`**
  auslösen; (b) das robuste `MjpegStream`-Fehlerbild (1a) fängt den Fall „Stack zwischendurch
  gestoppt" ab (Hinweis statt Crash). **Kein** neuer latched-Subscribe (Option B bleibt Nachzug).

### 1d. Navigation Lifecycle ↔ Drive (leichtgewichtig) — [ADR-3]

Zwei Screens, kein Navigations-Framework:

```
enum class Screen { LIFECYCLE, DRIVE }
// in setContent:
var screen by rememberSaveable { mutableStateOf(Screen.LIFECYCLE) }
when (screen) {
    LIFECYCLE -> ControlScreen(..., onDrive = { screen = Screen.DRIVE })
    DRIVE     -> DriveScreen(...);  BackHandler(enabled = true) { screen = Screen.LIFECYCLE }
}
```

- **„Fahren"-Button** im Lifecycle-Screen (aktiv **wenn verbunden**; der Kamera-Slot self-gated
  auf RUNNING → der Screen ist auch ohne laufenden Stack erreichbar, zeigt dann nur den dunklen
  Center + Hinweis). *(→ [offener Punkt 6])*
- **Zurück:** `BackHandler` fängt die System-Zurück-Geste im Drive-Screen → `LIFECYCLE`.
  (Der Kishi-Circle `BUTTON_B` löst **kein** Zurück aus — `dispatchKeyEvent` konsumiert
  Gamepad-Tasten; Back-Geste/On-Screen sind Nicht-Gamepad → funktionieren normal.)

### 1e. `/joy`-Kontinuität über den Screen-Wechsel (kritisch, aber ohne Eingriff)

Der Screen-Wechsel ist ein **Compose-State-Wechsel innerhalb derselben Activity** → **kein
`onPause`** → der **30-Hz-`/joy`-Publish-Loop läuft unverändert** weiter. Gamepad-Events werden
auf **Activity-`dispatch*`-Ebene** (nicht über Compose-Fokus) abgegriffen → sie kommen an,
**egal welcher Compose-Screen sichtbar** ist. **Ergebnis: der Kishi fährt auf dem Drive-Screen
genau wie auf dem Lifecycle-Screen — der Phase-2-Pfad wird nicht angefasst.** (Nur beim echten
Verlassen der App/Screen-Lock verstummt `/joy` → `cmd_vel_timeout`, wie gehabt, NF1.)

### 1f. Fahr-Screen-Layout + Slot-Vertrag (§5)

Ein `Box(fillMaxSize)` mit zwei Ebenen; Querformat ist bereits **global** gelockt
(`android:screenOrientation="landscape"` auf der Activity) → kein Manifest-Change.

```
Ebene 0 (Center-View, füllt den Screen):
  NOTHING → dunkler Hintergrund
  KAMERA  → Image(bitmap, ContentScale.Crop, fillMaxSize)   (nur wenn RUNNING, sonst Hinweis)
  ROBOT3D → dunkel + „3D — Phase 5"-Platzhalter (disabled)

Ebene 1 (Overlay, Modifier.align):
  ┌───────────────────────────────────────────────────────────┐
  │ [conn ●]  [safety] [tip]     (3-Wege-Toggle)  [⚠][⚙][show] │  Top (align Top)
  │                                                             │
  │                   CENTER-VIEW (Ebene 0)                     │
  │                                                             │
  │ 1 4  [state][stance][gait][tempo]            [⛔ E-STOP]    │  Bottom (align Bottom)
  │ 2 5                                          [📷 cam]       │
  │ 3 6  (foot-Raster)                                          │
  └───────────────────────────────────────────────────────────┘
```

| Slot | in P4 (dieser Plan) | Quelle/Interaktion |
|---|---|---|
| `conn` | **Anzeige** (verbunden/getrennt + Stack-Kurzstatus) | `ConnectionState`/`LifecycleState` (vorhanden) |
| `safety`,`tip`,`state`,`stance`,`gait`,`tempo` | **leer/Label** | Phase 5 (Status-Publisher) |
| `foot 1–6` | **leeres 2×3-Raster** `1 4 / 2 5 / 3 6` (Label) | Phase 5 (`/foot_contacts`) |
| `⚙ config` | **Button → leeres Overlay-Panel** | Phase 5 |
| `⚠ alerts` | **Button → leere Overlay-View** | Phase 5 (`/hexapod/alerts`) |
| `show` | **Button → leeres Menü** | Phase 5 |
| `📷 cam` | **Toggle** (Kamera an/aus = Stream laden/entladen) | 1a/1c |
| `⛔ E-STOP` | **reservierte Position, disabled** | Phase 6 (`/hexapod_safety_freeze`) |
| 3-Wege-Toggle | **funktional** (Nichts/Kamera; 3D disabled) | 1g |

- **Leere Slots** = schlichtes `OverlaySlot(label)`-Composable (Label-Chip, halbtransparent),
  nur positioniert.
- **`config`/`alerts`/`show`** öffnen ein **dismissbares Overlay-Panel** (Scrim + Panel mit Titel
  + „Phase 5 — noch leer" + Schließen), State `var openPanel by remember { mutableStateOf<Panel?>(null) }`.
  Kein eigener Screen ([ADR-5]).

### 1g. Center-Toggle + cam-Toggle — eine Wahrheit, keine zwei Gates — [ADR-6]

```
enum class CenterView { NOTHING, KAMERA, ROBOT3D }   // Default NOTHING
```

- **`centerView`** ist die **einzige** Wahrheit fürs Center-Inhalt.
- Das **3-Wege-Toggle** setzt `centerView` direkt (`ROBOT3D` disabled → Phase 5).
- Der **`📷 cam`-Slot** ist ein **Shortcut**, der zwischen `KAMERA ↔ NOTHING` umschaltet und den
  cam-An/Aus-Zustand als `centerView == KAMERA` **spiegelt** → **keine widersprüchlichen
  Zustände**. (Optional als reine Funktion `toggleCam(centerView): CenterView`, mini-unit-testbar U3.)
- **Stream lädt** genau dann, wenn `centerView == KAMERA && stack == RUNNING && connected`;
  wechselt der Toggle weg (oder Stack fällt weg) → `MjpegStream.stop()` (Stream entladen).

---

### Verworfene Alternativen (ADRs)

- **[ADR-1] MJPEG via WebView / Media3-ExoPlayer / Dritt-Lib** — verworfen zugunsten eines
  **eigenen OkHttp-Decoders** *(User-Entscheidung)*. WebView: Chromium-Support für
  `multipart/x-mixed-replace` unzuverlässig/teils entfernt, center-crop nur per HTML-Wrapper.
  ExoPlayer: kein natives MJPEG-multipart. Dritt-Lib: neue Abhängigkeit + Wartungs-/Offline-Risiko.
  Eigener Decoder: reuse OkHttp (0 Deps), voller Crop-/Lifecycle-Griff, Parser rein testbar.
  Media3/ExoPlayer **bleibt reserviert** für das RTSP/H.264/WebRTC-Upgrade.
- **[ADR-2] Latched-Subscribe `/hexapod/bringup_running` (Option B) als Kamera-Gate jetzt** —
  verworfen für Phase 4 *(User-Entscheidung)*: das vorhandene **Status-Polling** (`StackState.
  RUNNING`) reicht als Gate; Option B (P3A.13) bleibt der spätere Live-Push-Nachzug. Con
  (Veralten) mit Betreten-Poll + robustem Stream-Fehlerbild abgefedert (1c).
- **[ADR-3] Navigation-Compose-Bibliothek** — verworfen: nur **2 Screens** → leichtgewichtige
  Compose-State-Navigation (`Screen`-Enum + `BackHandler`), **keine neue Abhängigkeit**
  („erst bei Bedarf", spiegelt Phase-3-[ADR-1]).
- **[ADR-4] MJPEG-Parsing nicht testen** — abgelehnt: das **Framing** (Boundary/Content-Length/
  Split-Reads) ist die fehleranfällige Kern-Logik → als **reiner `MjpegParser`** herausgezogen
  und unit-getestet (U2); `BitmapFactory`/OkHttp/Compose bleiben Integration (wie org.json in P2/P3).
- **[ADR-5] `config`/`alerts`/`show` als eigene Screens** — verworfen: passt nicht zum
  Overlay-Slot-Modell (§5) → **In-Screen-Overlay-Panels** (dismissbar), leerer Phase-5-Platzhalter.
- **[ADR-6] cam-Toggle und Center-Toggle als zwei unabhängige Gates** — verworfen (Gefahr
  widersprüchlicher Zustände): **`centerView` = einzige Wahrheit**, `📷 cam` spiegelt/schaltet
  `KAMERA↔NOTHING`.

---

## 2. Tests-Liste (+ was bewusst NICHT)

**Unit (JUnit, kein Gerät/Netz):**
| Test | Prüft |
|---|---|
| **U1** `videoStreamUrl(host)` | Port 8080 + Pfad + Query korrekt; Trim; leerer/mit-Leerzeichen-Host |
| **U2** `MjpegParser` | 2 synthetische Parts **mit** Content-Length → 2 korrekte JPEG-`ByteArray`s · 1 Part **ohne** Content-Length (Boundary-Scan) · **Split-Reads** (drosselnder Fake-`InputStream`, 1–3 Bytes/`read`) → Frames trotzdem korrekt zusammengesetzt · Boundary-String aus dem Header korrekt gelesen |
| **U3** `toggleCam(centerView)` | `NOTHING→KAMERA`, `KAMERA→NOTHING`, `ROBOT3D→KAMERA` (falls als reine Funktion herausgezogen) |

**Bewusst NICHT unit-getestet (→ Integration/manuell am Live-Stream):**
- `BitmapFactory.decodeByteArray`, OkHttp-Streaming, `readTimeout(0)`-Verhalten, Threading/Marshaling.
- Compose-Rendering + `ContentScale.Crop` (center-crop, keine Balken), reale **~11 Hz**, **Latenz** (T4.7).
- **multipart-Format am echten Server** (Content-Length vorhanden? Boundary-String?) → das ist der
  **Prototyp-Schritt P4A.1** (empirisch, nicht raten — [offener Punkt 1]).
- Navigation/BackHandler, Overlay-Panels, cam-/center-Toggle-UI.

**Regression:** `assembleDebug` + `testDebugUnitTest` grün; **Phase-2-`/joy`- und
Phase-3-Lifecycle-Pfad unverändert** (keine Änderung an `dispatch*`, Publish-Loop, `callService`).

---

## 3. Progress-Checkliste (Done-Vertrag)

Rollt in die ws-Progress **P4.7–P4.9** (+ Integration T4.4–T4.7) hoch.

```
Phase 4 (App — Fahr-Screen-Shell + Vollbild-Video):
- [~] P4A.1  MJPEG-Decoder gebaut + unit-verifiziert (beide Formate, self-correcting). LIVE-
             Verifikation gegen den echten Stream (Content-Length? Boundary?) STEHT NOCH AUS ->
             in P4A.13 gefaltet (kein Live-Stream/Geraet in dieser Session). Reihenfolge-Abweichung
             bewusst: robust gebaut statt geraten; Prototyp = erste Live-Sekunde von P4A.13.
- [x] P4A.2  MjpegParser (rein): InputStream->JPEG-ByteArrays; Length-Fast-Path + Boundary-Scan-
             Fallback + Split-Reads + Hint-Selbstkorrektur — unit-getestet (U2, 7 Faelle)
- [x] P4A.3  MjpegStream (Glue): OkHttp-GET readTimeout=0, Daemon-Thread, BitmapFactory->VideoState
             (Main-marshalled); start/stop-Lifecycle + Fehlerbild + Auto-Retry (kein Crash)
- [x] P4A.4  videoStreamUrl(host) (rein, unit-getestet U1); host aus ConnectionState hochgezogen
- [x] P4A.5  Navigation: Screen-Enum LIFECYCLE/DRIVE + "Fahren"-Button + BackHandler zurueck;
             /joy laeuft ueber den Wechsel weiter (kein onPause; dispatch*-Ebene unveraendert)
- [x] P4A.6  DriveScreen-Layout: Box (Center-View Ebene0 + Overlay Ebene1), Querformat; 3-Wege-Toggle
- [x] P4A.7  Center-View: NOTHING=dunkel · KAMERA=Vollbild-MJPEG ContentScale.Crop (Gate: RUNNING) ·
             ROBOT3D=Platzhalter/disabled
- [x] P4A.8  cam-Toggle (Slot 📷) an centerView gekoppelt (KAMERA<->NOTHING); Kamera an/aus =
             Stream laden/entladen
- [x] P4A.9  alle Overlay-Slots positioniert (leer/Label) gemaess §5; conn=Anzeige,
             E-STOP=reservierte Position (disabled), foot-Raster 2x3 leer
- [x] P4A.10 config/alerts/show-Buttons oeffnen leere, dismissbare Overlay-Panels (Phase-5-Platzhalter)
- [x] P4A.11 assembleDebug + testDebugUnitTest gruen (42/0); Phase-2-/joy- + Phase-3-Lifecycle-Pfad unveraendert
- [x] P4A.12 Kritischer Self-Review + Doku-Nachzug (NEXT.md, architecture.md §4.3, CLAUDE §7 + Nav-Zeile)
- [ ] P4A.13 [Integration, User+App] End-to-End am Sim: Handy zeigt Gazebo-Video VOLLFLAECHIG
             (center-crop, keine Balken); Toggle Nichts<->Kamera + cam an/aus; Navigation Drive<->Lifecycle;
             config/alerts/show oeffnen leere Views; Latenz "folgbar" (T4.4-T4.7). Enthaelt die
             Live-Verifikation des multipart-Formats (P4A.1).
```

**Mapping auf ws-Plan:** P4A.1–4 → **P4.7** · P4A.5–8 → **P4.8** · P4A.9–10 → **P4.9** ·
P4A.13 → **P4.11 / T4.4–T4.7**.

---

## 4. Offene Punkte / Risiken (User-Review)

1. **multipart-Format am Live-Stream — Prototyp klärt (P4A.1):** ob `web_video_server` je Frame
   **Content-Length** sendet und wie der **Boundary-String** heißt. Der Parser unterstützt **beide**
   Pfade → kein Blocker, aber **nicht raten**: erst gegen den echten Stream verifizieren, dann Shell.
2. **Kamera-Gate = Polling (`StackState.RUNNING`), User-Entscheidung [ADR-2]:** Con = Zustand kann
   veralten (kein periodischer Poll). Abgefedert durch **Betreten-Poll** (User-Entscheidung: **ja,
   eingebaut** — beim Öffnen des Fahr-Screens feuert einmal `pollStatus()`) + **robustes
   Stream-Fehlerbild** (Connection-refused → Hinweis + begrenzter Auto-Retry statt Absturz).
3. **center-crop 16:9 → Phone ~19,5:9:** `ContentScale.Crop` skaliert auf Breite → beschneidet
   **oben/unten minimal**, kein schwarzer Balken (Contract §5). Späterer In-App-Zoom optional (P4-out).
4. **~11 Hz + Render-Jitter:** kein Frame-Buffering/Interpolation in P4 (NF4 „folgbar" reicht).
   Falls es „ruckelig" wirkt: erst am Live-Blick bewerten, nicht vorab optimieren.
5. **Media3/ExoPlayer wird NICHT genutzt** (architecture §4.3 nannte es geplant) — durch [ADR-1]
   ersetzt; ExoPlayer bleibt für das spätere RTSP/H.264/WebRTC-Upgrade reserviert. → architecture
   nachziehen (Doku-Nachzug §5).
6. **„Fahren"-Button-Gate = verbunden** (nicht RUNNING): der Fahr-Screen ist auch ohne laufenden
   Stack erreichbar (Center dann dunkel + Hinweis, Kamera-Slot self-gated). OK so, oder erst bei
   RUNNING freigeben?
7. **Kein Manifest-/Permission-Change nötig:** `INTERNET` + `usesCleartextTraffic="true"` +
   Querformat-Lock + `FLAG_KEEP_SCREEN_ON` sind **schon** gesetzt (gelten für beide Screens).
8. **D-Pad-X Contract-Sync** (Altlast aus P2/P3) — unabhängig von Phase 4, kein Blocker.

---

## 5. Doku-Nachzug (nach Umsetzung)
- `docs/NEXT.md` auf **Phase 4** umstellen (Rolling-Doc: Stand + Wiedereinstieg/Resume-Prompt).
- `docs/architecture.md`: **Video-Kanal 2 = eigener OkHttp-MJPEG-Decoder** (Ist, statt geplant
  Media3, §4.3); **Screen-Struktur** (2 Screens + Navigation); Datenfluss Video.
- `CLAUDE.md §7`: diese Datei in den Nav-Index eintragen; **„Aktuell: Phase 4"**-Zeile bestätigt
  (ws-Plan §7).
- **ws (ROS/Contract-Session, durch den User):** `phase_4_..._progress.md` P4.7–P4.9 abhaken;
  `phase_4_..._test_commands.md` um die App-Test-Schritte ergänzen. Contract §5 ist bereits v0.8
  (kein weiterer Bump durch die App nötig — reiner Konsument).

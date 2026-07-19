# Phase-6-App-Plan — E-Stop (scharf) + Recover + frozen-Anzeige

> **App-Seite von Phase 6** (P6.8 + P6.9). Die ROS-Seite ist fertig + Sim-verifiziert; hier wird
> **nur die App-UI** gegen den festgezurrten Contract gebaut — **kein ROS-Code, kein neues
> Interface, kein neuer Transport**.
>
> **Interface (read-only, referenziert, nicht kopiert — [D10]):**
> `interface_contract.md` **v0.10** — §2 (`/hexapod_estop`, `/hexapod_recover`, beide
> `std_srvs/srv/Trigger`, leerer Request) + §6a (`/hexapod/status`-JSON, Feld `safety_frozen`).
> Grenze: `decisions.md` **[D6]** (Recovery richtet keine Kipplage auf).
> ROS-Brief: `hexapod_ws/.../phase_6_estop_recovery_plan.md §5`.

---

## 0. Ziel + Abgrenzung

**P6.8 — E-STOP-Button (scharf):** der in Phase 4 reservierte Slot unten rechts
(`DriveScreen.kt:286`, aktuell disabled) wird scharf: groß, rot, **immer sichtbar**. Tap →
`call_service` auf **`/hexapod_estop`** (`std_srvs/srv/Trigger`, leer). Wirkt Sim **und** HW.
**Kein** Dead-Man, **kein** Bestätigungsdialog — ein einzelner, bewusster Tap. **Visuelle
Tap-Rückmeldung** (User-Wunsch).

**P6.9 — Recover-Button + frozen-Anzeige:** frozen-Zustand **ausschließlich** aus
`/hexapod/status.safety_frozen` (bool, `HmiModels.kt:22`, schon geparst) ableiten — **nicht** aus
der Service-Response. Bei `safety_frozen == true`: prominentes Banner **„FROZEN — E-STOP"** (englisch) +
Recover-Button (mit D6-Hinweistext). Recover-Tap → **`/hexapod_recover`** (`Trigger`). Danach
`status.state` `STARTUP_RAMP` → `STANDING`, `safety_frozen` → false → Banner/Button weg. Optional
**„recovering …"** während STARTUP_RAMP.

**Nebenwunsch (nicht Phase-6-Kern):** die drei Center-Toggle-Labels auf **Englisch**
(„Nichts/Kamera/3D" → „None/Video/3D"). **Alles andere bleibt Deutsch.**

**Bewusst NICHT (Phase 6):** Kipp-/Recovery-Automatik, Hardware-Taster, Recovery-Physik/-Ramp
(ROS-Seite, dort erledigt).

**Zielziele (App-Ziel `/hexapod_estop`, NICHT `/hexapod_safety_freeze`):** `/hexapod_estop` ist der
gait_node-Service, der Sim+HW wirkt und den Plugin-Freeze intern triggert. `/hexapod_safety_freeze`
ist der reine HW-Plugin-Service (wirkt nicht in Sim) → **nicht** direkt aus der App (Contract §2).

---

## 1. Betroffene Dateien

| Datei | Änderung |
|---|---|
| **NEU** `SafetyLogic.kt` | reine Logik: `SafetyMode`-Enum + `safetyMode(…)` + Service-Const `ESTOP_SERVICE`/`RECOVER_SERVICE` (Muster = `VideoLogic.kt`/`LifecycleLogic.kt`) |
| **NEU** `SafetyLogicTest.kt` | Unit-Tests für `safetyMode` + Const-Pin (Muster = `VideoLogicTest.kt`) |
| `DriveScreen.kt` | E-STOP-Slot scharf + Tap-Feedback; Frozen-Banner + Recover-Button + D6-Hinweis; Center-Labels englisch; Signatur `+onEstop`/`+onRecover` |
| `MainActivity.kt` | `onEstop`/`onRecover` an `ros.callService(…)` hängen, Response auf Main marshallen |

Kein Interface/Transport-Change: der Trigger-Pfad `ros.callService(service, onResult)`
(`RosbridgeClient.kt:122`) existiert und wird von den Lifecycle-Services genauso genutzt
(`MainActivity.kt:281`).

---

## 2. Logik-Skizze / Pseudocode + Begründung je Entscheidung

### 2a. `SafetyLogic.kt` (rein, unit-getestet)
```kotlin
const val ESTOP_SERVICE = "/hexapod_estop"
const val RECOVER_SERVICE = "/hexapod_recover"

/** UI-Modus der Safety-Anzeige, abgeleitet aus /hexapod/status (+ „Recover getappt?"). */
enum class SafetyMode { NORMAL, FROZEN, RECOVERING }

fun safetyMode(frozen: Boolean, state: String?, recoverRequested: Boolean): SafetyMode = when {
    frozen -> SafetyMode.FROZEN
    recoverRequested && state != null && state != "STANDING" -> SafetyMode.RECOVERING
    else -> SafetyMode.NORMAL
}
```
**Begründung / Design-Entscheidungen:**
- **frozen NUR aus `status.safety_frozen`** (Contract-Vorgabe) — nie aus der Service-Response. FROZEN
  hat **Vorrang** vor RECOVERING (ein neuer E-Stop während Recovery zeigt sofort wieder frozen).
- **RECOVERING nur bei `recoverRequested && state != STANDING`** — verhindert, dass ein *normaler*
  Stand-up (der ebenfalls `STARTUP_RAMP` durchläuft) fälschlich als „recovering" erscheint. Der
  „recover getappt"-Kontext (`recoverRequested`) ist **nicht** aus dem Status ableitbar → dünnes
  Compose-Flag (s. 2b), die **Entscheidung** bleibt rein & testbar.
- **`state == STANDING` oder `recoverRequested == false` → NORMAL** → Banner/Button aus. `state==null`
  (kein Status / Stack aus) → NORMAL (keine Anzeige ohne Datenbasis).
- **Verworfen:** RECOVERING allein aus `state==STARTUP_RAMP` — kollidiert mit normalem Stand-up.

### 2b. `DriveScreen.kt`
- **Signatur:** `+ onEstop: () -> Unit, + onRecover: () -> Unit`.
- **Ableitung im Composable:**
  ```kotlin
  val frozen = hmi.status?.safetyFrozen == true
  val state  = hmi.status?.state
  var recoverRequested by remember { mutableStateOf(false) }
  val mode = safetyMode(frozen, state, recoverRequested)
  // Flag-Reset (dünne Glue, kein Unit-Test nötig):
  LaunchedEffect(frozen, state) {
      if (frozen || state == "STANDING") recoverRequested = false
  }
  ```
  `frozen==true` setzt `recoverRequested` zurück → ein Re-Freeze bricht die „recovering"-Anzeige ab,
  der nächste Recover-Tap setzt es sauber neu.
- **E-STOP-Slot scharf** (`EStopSlot(mode, onEstop)`): voll-deckendes Rot, fett, `clickable(onEstop)`,
  **immer sichtbar & tappbar** (Not-Halt nie ausgegraut). **Tap-Feedback:** lokales
  `var flash by remember`; Tap → `flash=true`; `LaunchedEffect(flash){ delay(~600ms); flash=false }`
  → kurzer Helligkeits-/Rahmen-Puls. Bei `mode==FROZEN` zusätzlich ein „latched/aktiv"-Look am Button.
- **Frozen-Banner** (neuer Overlay-Layer im äußeren `Box`, obenzentrierter roter Balken, damit Fahren
  sichtbar bleibt):
  - `mode==FROZEN`: großer roter Balken **„⛔ FROZEN — E-STOP"** (englisch) + Button **„Recover"**
    (`onClick = { recoverRequested = true; onRecover() }`) + Hinweistext (D6): *„Roboter grob aufrecht
    auf ebenen Boden stellen, dann Recover — kein Aufrichten aus Kipplage."*
  - `mode==RECOVERING`: Balken **„recovering …"** (kein Button), bis `STANDING` → NORMAL.
  - `mode==NORMAL`: kein Banner.
- **`SafetySlot`** (`DriveScreen.kt:344`, zeigt schon klein FROZEN/ok) bleibt als kompakte
  Statuszeile; das prominente Banner ist die „deutliche" Anzeige nach Brief.
- **Center-Labels englisch** (`CenterToggle`, `DriveScreen.kt:196-198`): `"Nichts"→"None"`,
  `"Kamera"→"Video"`, `"3D"` bleibt. Enum-Namen (`CenterView.NOTHING/KAMERA/ROBOT3D`) **unverändert**
  (intern). `📷 cam AN/AUS`, `CenterHint`-Texte etc. **bleiben Deutsch**.

### 2c. `MainActivity.kt`
```kotlin
onEstop   = { callSafety(ESTOP_SERVICE) },
onRecover = { callSafety(RECOVER_SERVICE) },
// …
private fun callSafety(service: String) {
    ros.callService(service) { result ->
        mainHandler.post {
            // frozen/recovered-Zustand kommt aus /hexapod/status (Topic), NICHT aus result.
            if (!result.ok) Log.w(TAG, "$service fehlgeschlagen: ${result.message}")
        }
    }
}
```
**Begründung:** exakt der bestehende `Trigger`-Pfad (leere Args) wie die Lifecycle-Services; die
Response dient **nicht** der Zustandsableitung (Contract: frozen aus dem Status-Topic). Fehlerfall in
v1 nur geloggt (s. offener Punkt §5).

---

## 3. Tests-Liste (+ was bewusst NICHT)

**Unit (`SafetyLogicTest.kt`, JUnit, ohne Gerät/Netz):**
- `safetyMode(frozen=true, …)` → `FROZEN` (auch bei `recoverRequested=true` / beliebigem `state`).
- `safetyMode(false, "STARTUP_RAMP", recoverRequested=true)` → `RECOVERING`.
- `safetyMode(false, "STANDING", true)` → `NORMAL`.
- `safetyMode(false, "STARTUP_RAMP", recoverRequested=false)` → `NORMAL` (normaler Stand-up ≠ recovering).
- `safetyMode(false, null, true)` → `NORMAL` (kein Status).
- `ESTOP_SERVICE == "/hexapod_estop"` und `RECOVER_SERVICE == "/hexapod_recover"` (Contract-Pin gegen
  Tippfehler-Regression).

**Bewusst NICHT (Unit):**
- Der echte `call_service` (org.json/Netz → Android-SDK-Stub, in reinem JUnit nicht lauffähig; wie
  Phase 3/5) → **Sim-E2E**.
- Composable-Rendering / Tap-Feedback-Animation (kein Compose-UI-Test-Setup im Repo) → visuell im Sim.
- Recovery-Ramp/Physik & Latch-Verhalten (ROS-Seite, T6.1–T6.7 dort erledigt).

**Sim-E2E (mit User — P6.11-Sim / T6.8-Vorstufe, Desktop-IP:9090):**
`/hexapod_bringup_start` → `/hexapod_stand_up` → laufen → **E-STOP tappen** → Banner „FROZEN — E-STOP" +
`ros2 topic echo /hexapod/status` zeigt `safety_frozen: true` → **Recover tappen** →
`STARTUP_RAMP → STANDING`, Banner weg.

---

## 4. Progress-Checkliste (Done-Vertrag, App-lokal; spiegelt hexapod_ws P6.8/P6.9)
```
Phase 6 App (E-Stop + Recover):
- [x] P6.8a  SafetyLogic.kt (SafetyMode + safetyMode() + ESTOP/RECOVER-Const) + SafetyLogicTest gruen
- [x] P6.8b  E-STOP-Slot scharf (rot, deutlich, immer sichtbar) -> onEstop; visuelle Tap-Rueckmeldung
- [x] P6.9a  Frozen-Banner „FROZEN — E-STOP" (englisch) prominent aus status.safety_frozen (mode=FROZEN)
- [x] P6.9b  Recover-Button (nur FROZEN) -> onRecover + D6-Hinweistext; „recovering…" (RECOVERING)
- [x] P6.9c  MainActivity: onEstop/onRecover -> ros.callService(/hexapod_estop | /hexapod_recover), auf Main
- [x] P6.9d  Center-Labels englisch (None/Video/3D); Rest bleibt Deutsch
- [x] P6.x   testDebugUnitTest gruen (85/0, +6 SafetyLogicTest) + kritischer Self-Review (§6)
- [ ] P6.11-Sim  E2E mit User (E-STOP -> frozen -> Recover -> STANDING) — offen (User testet); HW-T6.8 spaeter
```

---

## 6. Umsetzung + kritischer Self-Review (2026-07-19)

**Geändert:** `SafetyLogic.kt` (neu) · `SafetyLogicTest.kt` (neu, 6 Tests) · `DriveScreen.kt`
(E-STOP scharf + Tap-Puls, Safety-Banner, engl. Center-Labels, +2 Callbacks) · `MainActivity.kt`
(`callSafety` → `ros.callService`, `Log`/`TAG`). `testDebugUnitTest` **85/0**, Main kompiliert.

| # | Punkt | Status |
|---|---|---|
| 1 | frozen NUR aus `status.safety_frozen` (nicht Response) — Contract §6a | OK |
| 2 | E-Stop-Ziel `/hexapod_estop` (nicht `/hexapod_safety_freeze`) — Const gepinnt + Test | OK |
| 3 | `recovering` zeigt nicht beim **normalen** Stand-up (`recoverRequested`-Gate) — Unit-getestet | OK |
| 4 | E-Stop-Tap bei **getrennt/kein Stack**: `callService` → sofort `ok=false`, nur Log, kein Crash | OK |
| 5 | Kein bestehender Pfad verändert (`/joy`/Trigger/Video/Status unberührt; nur additive Callbacks) | OK |
| 6 | Regression: alle 79 Alt-Tests weiter grün | OK |
| 7 | **Banner vs. offenes Overlay-Panel:** Banner wird **ausgeblendet, solange ein Panel (config/alerts) offen ist** (sonst verdeckt es dessen Inhalt), und erscheint beim Schließen wieder, solange noch frozen (User-Wunsch nach Test). E-STOP-Slot bleibt vom Panel-Scrim verdeckt (erst Panel schließen). | OK (Banner) / 🟡 (E-Stop-Slot, Phase 8) |
| 8 | Sichtbarer Fehlerhinweis bei fehlgeschlagenem E-Stop-Call (v1 nur Log) | 🟡 vormerken (User-Freigabe: v1 = Log; Banner ist Erfolgsbeleg) |
| 9 | Banner verdeckt Video-Mitte während frozen — Roboter hält ohnehin; nach Recover→STANDING wieder frei | OK (bewusst) |

**Keine 🔴.** Die 🟡 sind bewusste v1-Grenzen (User-bestätigt). Offen bleibt der Sim-E2E (P6.11-Sim).

**Nach-Test-Anpassung (2026-07-19, nach erfolgreichem Sim-Test):** (a) Banner-Text auf **Englisch**
(„FROZEN — E-STOP" + „Place the robot roughly upright …" / „recovering …" / „Joint-space ramp to
stand …"). (b) Banner **ausgeblendet, solange ein Overlay-Panel offen ist** (`openPanel != null`) →
Alerts/Config wieder lesbar; beim Schließen kommt der Banner zurück, solange `safety != NORMAL`.
`testDebugUnitTest` weiter **85/0** (reine Compose-Glue, keine neuen Unit-Tests nötig).

---

## 5. Offene Punkte / User-Review — **ENTSCHIEDEN (User-Freigabe 2026-07-19)**

1. **Center-Labels:** „**None / Video / 3D**" — bestätigt.
2. **Banner-Platzierung:** zentrierter roter **Balken/Karte** ohne Vollbild-Scrim (Fahren/E-Stop
   bleiben bedienbar) — bestätigt.
3. **Fehler bei fehlgeschlagenem E-STOP-Call:** v1 nur **Log** (Frozen-Banner ist die
   Erfolgsbestätigung); sichtbarer Hinweis als 🟡 vorgemerkt (§6.8) — bestätigt.
4. **„recovering …"-Zustand:** **mitgenommen** (via `RECOVERING`) — bestätigt.

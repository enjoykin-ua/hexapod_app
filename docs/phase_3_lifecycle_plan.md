# Phase 3 (App) — Connect-/Lifecycle-Screen — App-seitiger Plan

> **Status: 🟡 Plan (zur Freigabe).** App-Gegenstück zum ROS-Plan
> `~/hexapod_ws/project_finalization/app_control_requirements/phase_3_lifecycle_plan.md` §5.
> **Interface = SoT:** `interface_contract.md` **v0.6** (§2a Launcher-Services, §3
> `/hexapod/bringup_running`, §7.4 Latched-über-rosbridge) — hier nur referenziert, nicht
> kopiert ([D10]).
>
> **Vorgehen (CLAUDE.md §3):** Plan → **User-Freigabe** → Implementierung → Tests →
> Self-Review → Fertig-Meldung. Dieses Doc = der Plan.

---

## 0. Ziel + Abgrenzung

**Ziel:** Aus der App den schweren Gait-/Sim-Stack **on demand** starten/stoppen, den Roboter
**aufstehen/hinsetzen** lassen und den Pi **kontrolliert** (guarded) herunterfahren — kein
Terminal mehr. Die App fernbedient die Always-On-Schicht (rosbridge + supervisor +
`bringup_launcher`) über rosbridge `call_service`.

**In Phase 3 (App):**
- rosbridge-Client um **`call_service`** erweitern (id-Korrelation, Response-Routing, Timeout).
- **Lifecycle-Bereich** im UI: 6 Buttons + Stack-Status-Anzeige + 2-stufiger Shutdown-Dialog.
- **Stack-State** zweistufig (siehe Reihenfolge): **A** Polling **jetzt**, **B** Live-Push als Nachzug.

**Reihenfolge — A zuerst, B als Nachzug ([ADR-3]):**
1. **Schritt 1 (jetzt): Option A — Polling** von `/hexapod_bringup_status`. Verlässlich, kein
   Latch-Risiko. Das ist der Umfang der Checkliste **P3A.1–P3A.12** (inkl. Sim-Integrationstest).
2. **Schritt 2 (Nachzug, direkt nach dem Sim-Test): Option B — latched Live-Subscribe** von
   `/hexapod/bringup_running` **zusätzlich** zum Polling, mit dem expliziten `qos`-Frame (§4.1).
   Erst **nachdem** die Integration (P3A.12) den Latch **einmal empirisch bestätigt** hat →
   **P3A.13**. So bauen wir nicht auf einem ungetesteten Pfad auf; Polling bleibt Primärquelle.

**Bewusst NICHT in Phase 3 (App):**
- **Kein Auto-Reconnect** (bleibt Phase 8, [ADR-2]) — nur sauberes Disconnect-Handling +
  manuelles Wieder-Verbinden.
- **Kein** „Roboter steht/sitzt"-Wissen — die App kennt nur „Stack läuft/aus"; die Pose-States
  kommen erst mit dem Status-Topic in **Phase 5** (deckt sich mit ROS-Plan §4.3).
- Kein Video (Phase 4), kein Status-Overlay (Phase 5), kein Recovery/Not-Halt (Phase 6).
- Kein GUI-Feinschliff — der Lifecycle-Bereich ist zweckmäßig für den **generellen Test**;
  die eigentliche Cockpit-GUI (Video-Vollbild + Overlay) kommt später.

---

## 1. Logik-Skizze / Vorgehen (+ Begründung)

### 1a. `RosbridgeClient` — `call_service` (der Kern-Neubau)

Der Client kann heute nur `advertise`/`publish` (`/joy`). Neu: generisches `call_service` mit
Korrelation über eine `id` (rosbridge-v2).

```
Anfrage:  { "op":"call_service", "id":"call-<n>", "service":"<srv>", "args":{} }
Antwort:  { "op":"service_response", "id":"call-<n>", "result":true,
            "values":{ "success":true, "message":"..." } }
```

Pseudocode (im Client):
```
val pending = ConcurrentHashMap<String, (ServiceResult)->Unit>()   // thread-safe: OkHttp- vs Main-Thread
val counter = AtomicInteger()

fun callService(service, onResult):
    if (!isOpen) { onResult(Fehler "nicht verbunden"); return }
    id = "call-" + counter.incrementAndGet()
    pending[id] = onResult
    socket.send(rosbridgeCallService(id, service))     // org.json-Frame (1b)
    scope.launch { delay(CALL_TIMEOUT_MS); pending.remove(id)?.invoke(Fehler "Timeout") }

onMessage(text):                                       // OkHttp-Thread
    parseServiceResponse(text)?.let { r ->             // null, wenn kein service_response
        pending.remove(r.id)?.invoke(r.toResult())     // Callback nur einmal (remove = idempotent)
    }
```

**Begründungen:**
- **Generisch statt pro Service hartcodiert:** eine `callService`-Funktion deckt alle 6 Services
  ab → weniger Code, wiederverwendbar auch in Phase 5/6 ([ADR-4]).
- **`ConcurrentHashMap` + `AtomicInteger`:** `onMessage` läuft auf dem OkHttp-Thread, der
  Timeout auf Main → gemeinsamer Zugriff auf `pending` muss thread-safe sein. `remove(id)` als
  atomarer „genau-einmal"-Trigger (Response ODER Timeout, nie beides).
- **Threading wie bestehend:** der `onResult`-Callback feuert vom OkHttp-Thread; die **Activity
  marshallt auf Main** (wie schon bei `onState` → `mainHandler.post`), bevor Compose-State
  geschrieben wird.
- **Timeout Pflicht:** ein hängender/fehlender Service darf den Button nicht ewig „läuft…"
  lassen. Dauer siehe [offener Punkt 3].

### 1b. Protokoll-Layer (`RosbridgeProtocol`) — org.json, integrationsverifiziert

- `rosbridgeCallService(id, service, args="{}"): String` — baut den Frame (org.json).
- `parseServiceResponse(text): RawServiceResponse?` — zieht `op`/`id`/`result`/`values.success`/
  `values.message` roh heraus; `null`, wenn `op != "service_response"`.

**Begründung Testbarkeit:** org.json ist in Android der SDK-Stub → **in reinem JUnit nicht
lauffähig** (wie in `RosbridgeProtocol.kt` schon dokumentiert). Diese Frames werden daher — wie
der Phase-2-`/joy`-Envelope — **über die Integration** verifiziert, **nicht** unit-getestet. Die
*Interpretation* der Rohdaten liegt bewusst in einer **reinen** Funktion (1c) → die ist testbar.

### 1c. Reine Interpretation + State (unit-testbar)

```
enum StackState { UNKNOWN, STOPPED, RUNNING }

fun interpretStatus(message: String?): StackState        // rein, JUnit-testbar
    message==null            -> UNKNOWN
    "running" in message.lc  -> RUNNING                   // "running (pid=1234)"
    "stopped" in message.lc  -> STOPPED
    else                     -> UNKNOWN
```

`LifecycleState` (Compose-Snapshot-Holder, analog zu `ConnectionState`):
- `stack: StackState` (Default UNKNOWN)
- `statusMessage: String?` (Roh, für die Anzeige „läuft (pid=…)")
- `pendingAction: LifecycleAction?` (welcher Call gerade läuft → Button-Sperre + Spinner; **nur
  einer gleichzeitig**, [ADR-5])
- `shuttingDown: Boolean` (Pi-Shutdown ausgelöst → nachfolgender Verbindungsabbruch ist gewollt)

### 1d. UI — Lifecycle-Bereich (Q1: alles in `ControlScreen`, Debug einklappbar)

Reihenfolge im (weiterhin einen) `ControlScreen`:
1. **Connect-Leiste** (bestehend, Phase 2).
2. **Lifecycle-Karte** (neu): Status-Zeile + 6 Buttons.
3. **Dev/Debug** (bestehend `/joy`-out + Roh-Reader) → **einklappbar**, **default zu**.

Button → Service + Aktivierung (reine Funktion, unit-testbar):
```
fun enablement(conn, stack, pending): Map<Action,Boolean>
  Basis: conn==CONNECTED && pending==null
  Starten   (bringup_start): Basis && stack != RUNNING
  Stoppen   (bringup_stop) : Basis && stack == RUNNING
  Aufstehen (stand_up)     : Basis && stack == RUNNING     // Pose unbekannt → nur „Stack läuft"-gated
  Hinsetzen (sit_down)     : Basis && stack == RUNNING
  Pi aus    (pi_shutdown)  : Basis                          // immer, wenn verbunden & idle
```
Nach `bringup_start`/`_stop`-Erfolg → **Status neu pollen** (1e) → `stack`/`statusMessage`
aktualisieren.

**2-stufiger Shutdown-Dialog** (Contract §2a Pflicht):
- Stufe 1: „Pi ausschalten?" [Abbrechen] [Weiter]
- Stufe 2: „Wirklich herunterfahren? Die Verbindung wird getrennt." [Abbrechen] [Ja, ausschalten]
- Bei Bestätigung: `shuttingDown=true` → `callService("/hexapod_pi_shutdown")`. **Antwort ggf.
  nie** (Pi geht aus → Socket bricht ab) → das ist **Erfolg**, nicht Fehler ([offener Punkt 4]).
  Nachfolgender `onClosed`/`onFailure` bei `shuttingDown` → UI-Zustand **„heruntergefahren"**
  statt generisch „getrennt".

### 1e. Status-Polling (Option A)

`/hexapod_bringup_status` aufrufen: **(1)** direkt nach `CONNECTED`, **(2)** nach jedem
erfolgreichen `bringup_start`/`_stop`. `message` → `interpretStatus` → `stack`. **Kein**
periodischer Timer in Phase 3 (ein unerwarteter Stack-Crash wird erst beim nächsten Poll/Klick
sichtbar — akzeptiert, [ADR-3]).

### 1f. Verbindung / Reconnect (Q2: graceful + manuell)

- `onClosed`/`onFailure` → `state=DISCONNECTED/ERROR`, **`stack=UNKNOWN`**, alle `pending`-Calls
  mit Fehler abschließen, `pendingAction=null`. Nutzer kann erneut „Verbinden".
- **Kein** Auto-Reconnect ([ADR-2]).
- `/joy`-Pfad (Phase 2) bleibt **unverändert**: `advertise` + 30-Hz-Publish laufen wie gehabt.
  Advertise vor laufendem Stack ist harmlos (kein Subscriber → no-op); sobald der Stack
  `joy_to_twist(app)` startet, greift DDS-Discovery ([offener Punkt 5]).

---

### Verworfene Alternativen (ADRs)

- **[ADR-1] Eigener Lifecycle-Screen mit Navigation** — verworfen für Phase 3: Nav-Bibliothek +
  mehr Struktur, unnötig für den „generellen Test"; die GUI wird später ohnehin neu gestaltet.
  → Ein `ControlScreen`, Lifecycle als Sektion, Debug einklappbar. *(User Q1)*
- **[ADR-2] Auto-Reconnect jetzt** — verworfen: NF8-Auto-Reconnect ist als **Phase 8** gesetzt;
  Phase 3 braucht nur graceful Disconnect + manuelles Verbinden. *(User Q2)*
- **[ADR-3] Latched-Subscribe (`bringup_running`) als Live-Push jetzt** — verschoben auf
  **Schritt 2 (P3A.13, direkt nach dem Sim-Test)**. Contract **§7.4 (v0.6)** bestätigt:
  rosbridge (2.7.0) liefert den `transient_local`-Latch an die App. **Polling** reicht für den
  Test und trägt kein Latch-/Timing-Risiko. Option B kommt als **Zusatz** obendrauf — mit
  **explizitem `qos`-subscribe-Frame** (§4.1), nicht per Auto-Match; die erste Integration
  bestätigt den Latch empirisch. *(User Q3)*
- **[ADR-4] Pro Service hartcodierte Call-Methoden** — verworfen zugunsten eines generischen
  `callService(service, onResult)` (weniger Code, in Phase 5/6 wiederverwendbar).
- **[ADR-5] Mehrere parallele Service-Calls** — verworfen: ein Steuerpult braucht kein Nebenläufig;
  `pendingAction` erlaubt **genau einen** Call → sperrt Buttons, verhindert Doppelklick-Rennen.
- **[ADR-6] org.json-Frames unit-testen** — nicht möglich (SDK-Stub); stattdessen reine
  Interpretation (`interpretStatus`, `enablement`) testen, Frames über Integration (wie Phase 2).

---

## 2. Tests-Liste (+ was bewusst NICHT)

**Unit (JUnit, kein Gerät/Netz):**
| Test | Prüft |
|---|---|
| U1 `interpretStatus` | `"running (pid=1)"`→RUNNING · `"stopped"`→STOPPED · `null`/Müll→UNKNOWN |
| U2 `enablement` | je (ConnState × StackState × pending) die 6 Button-Flags (inkl. „Starten aus, wenn schon RUNNING", „alles aus, wenn pending", „Pi-aus nur wenn verbunden") |
| U3 Shutdown-Übergang | `shuttingDown=true` + Socket-Close → Zustand „heruntergefahren" (falls rein abbildbar) |

**Bewusst NICHT unit-getestet (→ Integration/manuell am Sim):**
- `call_service`-Frame-Bau + `service_response`-Parsen (org.json = SDK-Stub, wie Phase-2-Envelope).
- WebSocket-Transport, Timeout-Timing, Threading/Marshaling.
- Shutdown-Dialog-UI, Disconnect-/Error-Handling, manuelles Reconnect.
- **T3.13 (ROS-Plan):** der End-to-End-Click-Through am Sim (User).

**Regression:** `assembleDebug` + `testDebugUnitTest` grün; Phase-2-`/joy`-Pfad unverändert.

---

## 3. Progress-Checkliste (Done-Vertrag)

```
Phase 3 (App — Connect-/Lifecycle-Screen):
- [ ] P3A.1  RosbridgeClient.callService: id-Korrelation, Response-Routing, Timeout, thread-safe pending
- [ ] P3A.2  RosbridgeProtocol: rosbridgeCallService(frame) + parseServiceResponse(raw)  [org.json]
- [ ] P3A.3  interpretStatus(message)->StackState  (rein, unit-getestet U1)
- [ ] P3A.4  LifecycleState (stack, statusMessage, pendingAction, shuttingDown)
- [ ] P3A.5  enablement(conn,stack,pending) Button-FSM  (rein, unit-getestet U2)
- [ ] P3A.6  UI: Lifecycle-Karte (Status + 6 Buttons); Dev/Debug einklappbar (default zu)
- [ ] P3A.7  2-stufiger Shutdown-Dialog -> pi_shutdown; „heruntergefahren" nach gewolltem Drop
- [ ] P3A.8  Status-Polling: nach Connect + nach Start/Stop
- [ ] P3A.9  Graceful Disconnect/Error: stack->UNKNOWN, pending-Calls failen, manuell wieder verbindbar
- [ ] P3A.10 assembleDebug + testDebugUnitTest gruen; Phase-2-/joy-Pfad unveraendert
- [ ] P3A.11 Kritischer Self-Review (Tabelle OK/fixen/vormerken/spaeter) + Doku-Nachzug
- [ ] P3A.12 [Integration, User] Click-Through am Sim: Start/Aufstehen/Fahren/Hinsetzen/Stop/Shutdown-Dry-Run
--- Schritt 2 (Nachzug, erst nach P3A.12) ---
- [ ] P3A.13 Option B: latched-Subscribe /hexapod/bringup_running (expliziter qos-Frame §4.1) als Live-Push ZUSAETZLICH zum Polling; Latch in der Integration empirisch bestaetigt
```

---

## 4. Offene Punkte / Risiken (User-Review)

1. **Latched `bringup_running` (Option B) — geklärt (Contract §7.4, v0.6):** rosbridge liefert
   den Latch an die App. Für Phase 3 **kein Change** (Polling bleibt Primärquelle). Für die
   spätere Option B ist der deterministische Weg der **explizite `qos`-subscribe-Frame** (nicht
   Auto-Match — der fixiert die QoS nur einmalig beim ersten Subscriber):
   `{"op":"subscribe","topic":"/hexapod/bringup_running","type":"std_msgs/msg/Bool","qos":{"history":"keep_last","depth":1,"durability":"transient_local","reliability":"reliable"}}`
   Empirische Bestätigung in der ersten Integration.
2. **D-Pad-X Contract-Sync** (aus voriger Session) — App sendet `-input.dpadX`, Contract **v0.6**
   §1 listet weiterhin pass-through. User zieht den Contract nach (nicht Teil dieser Phase, kein
   Blocker).
3. **`call_service`-Timeout-Dauer — 8 s** *(User-Entscheidung; später ggf. feinjustieren)*. Deckt
   die Trigger-Services ab (Launcher meldet „running", sobald der Subprozess **lebt** — nicht wenn
   Gazebo fertig ist, ROS-Plan §4.3 → Antwort kommt schnell). `pi_shutdown` gibt seine
   Trigger-Antwort nach dem **Auslösen** der Shutdown-Kette zügig (das Hinsetzen läuft danach) →
   8 s decken auch das ab.
4. **`pi_shutdown` ohne Antwort — bestätigt (User Q2):** beim echten Poweroff bricht der Socket
   ab, **bevor** eine `service_response` kommt. Behandlung: „gesendet + Drop bei `shuttingDown`" =
   **Erfolg** („heruntergefahren"), nicht Timeout-Fehler. Am Dev-Host (Dry-Run) bleibt die
   Verbindung bestehen → normale Antwort. Beide Fälle behandelt.
5. **`/joy`-Advertise vor laufendem Stack** — bleibt an (harmlos). Falls unerwünscht, könnten wir
   Advertise/Publish an „Stack RUNNING" koppeln — m. E. **nicht** nötig in Phase 3.

---

## 5. Doku-Nachzug (nach Umsetzung)
- `docs/NEXT.md` auf Phase 3 umstellen (Rolling-Doc: Stand + Wiedereinstieg).
- `docs/architecture.md`: Lifecycle-/`call_service`-Pfad + Screen-Struktur nachtragen.
- Nav-Index in `CLAUDE.md §7`: diese Datei eintragen.
- Bei Option-B-Freigabe später: Subscribe-Pfad ergänzen (expliziter `qos`-Frame aus §4.1) +
  empirische §7.4-Bestätigung notieren.

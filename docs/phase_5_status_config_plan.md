bei # Phase 5 (App) — Status-Overlay + Config-Panel + Dropdowns + 3D-Viz — §4-Plan

> **Ziel:** Die in Phase 4 positionierten **leeren** Overlay-Slots/Panels mit **Live-Daten**
> füllen und ein **rqt-artiges Config-Panel** bauen. Reine App-Arbeit gegen den **fertigen**
> Contract — **kein ROS-Change** (die ROS-Seite der Phase 5 ist live-verifiziert grün).
>
> **Seite:** nur App. **Status: 🟡 Plan (wartet auf Freigabe).**
> **Interface (SoT, read-only):** `~/hexapod_ws/.../interface_contract.md` **v0.9.1** — §6a
> (Phase-5-Topics + Manifest + Set-Stance/Set-Tempo), §2 (Services), §4 (Params via rosbridge),
> §5/§0 (Fahr-Screen/Adressierung), §7.4 (latched-QoS-Subscribe-Frame). **Nie kopieren** ([D10]).
> **App-Brief:** `~/hexapod_ws/.../phase_5_status_config_plan.md` §5 + Whitelist §1c.
>
> Aufgabe = Progress **P5.10–P5.13** (Done = die App-Tests + Integration T5.15 mit User).

---

## 0. Ziel + Abgrenzung

**Enthalten (P5.10–P5.13):**
1. **P5.10 Overlay-Live-Daten:** `state`/`stance`/`gait`/`safety`/`tip` ← `/hexapod/status`;
   `tempo` ← `/hexapod/tempo`; `foot 1–6` (2×3-Raster, grün=Kontakt) ← `/foot_contacts`.
2. **P5.11 Config-Panel** generisch aus `/hexapod/config_manifest` (39 Params): gruppierte
   Abschnitte, pro Param Slider mit **± UND Eintipp-Feld** (min/max/step/default/hint/unit),
   Toggles, Dropdowns. Gating (`standing`), Dynamic-Cap, Reject-`reason`. „IMU / Balance —
   Erweitert" (16 Gains, `advanced`) **eingeklappt**. Werte lesen/setzen = native rosbridge
   `get_parameters`/`set_parameters`.
3. **P5.12 Dropdowns** gait/tempo/stance aus `/hexapod/capabilities` (standing-gated) +
   **Alerts-View** aus `/hexapod/alerts` (Liste + „Alles kopieren").
4. **P5.13 3D-Roboter-Viz** (Center-View-Option aus Phase 4) aus `/joint_states` — **leichtgewichtig,
   zero-dependency** (Compose-Canvas + eigene 3D-Projektion, URDF-Links als Primitive).

**Bewusst NICHT in Phase 5 (unverändert):**
- **Config-Persistenz** — nur Laufzeit (User-Entscheidung, ws-Plan §0). Kein Speichern-Button.
- **PWMs / Roh-Winkel / Cal-Werte** — nie verstellbar.
- **E-Stop scharf / Recovery** — Phase 6 (E-STOP-Slot bleibt disabled).
- **Audio** — Phase 7.
- **Voll-3D mit Meshes / neue 3D-Engine** — bewusst zero-dep-Primitive (Q1-Entscheidung).
- **Numerischer Roll/Pitch-Readout** (aus `/imu/monitor`) — vorerst weggelassen (User-Entscheidung);
  der `tip`-Zustand (`none`/`warn`/`crit`) reicht. Bei Bedarf später ergänzbar.
- **Neue Dependencies** — nichts Neues im Version-Catalog; JSON via `org.json` (schon da),
  Transport via vorhandenem `OkHttp`, Rendering via Compose-Canvas.

**Statusänderung ggü. Vorüberlegung:** Die App-Session hatte gemeldet „kein direkter
Tempo-Setz-Weg". Die ROS-Session hat das mit **`/hexapod_cycle_tempo`** (Contract **v0.9.1**)
geschlossen → Tempo-Dropdown ist jetzt **voll funktional** (cycle-to-target, symmetrisch zu
Set-Stance), **nicht** read-only.

---

## 1. Logik-Skizze / Design (mit Begründung je Entscheidung)

### 1.0 [ADR-P5-1] Transport-Erweiterung — der `RosbridgeClient` kann heute zu wenig

**Problem:** Der bestehende `RosbridgeClient` (Phase 2/3) kann nur (a) `/joy` advertisen +
publishen und (b) leere-Args-**`Trigger`**-Services rufen + **Trigger**-Antworten parsen.
Eingehende `publish`-Frames werden **nur geloggt**. Phase 5 braucht fundamental:

1. **Subscribe/Unsubscribe** + **Routing eingehender `publish`-Frames** an typisierte Handler
   (6 Topics: `status`, `tempo`, `foot_contacts`, `capabilities`, `config_manifest`, `alerts`;
   + `joint_states` für 3D).
2. **Generisches `call_service` mit Args** + generische Antwort-Werte (nicht nur Trigger):
   `get_parameters {names:[…]}`, `set_parameters {parameters:[…]}` → `results[].successful/reason`,
   `SetBool {data:bool}` → `success/message`.

**Entscheidung:** `RosbridgeClient` + `RosbridgeProtocol` **erweitern** (nicht neu schreiben) —
additiv, der Phase-2/3-Pfad (`/joy` + Lifecycle-Trigger) bleibt unverändert.

- **Inbound-Routing:** `onMessage` unterscheidet `op`:
  - `service_response` → bestehende id-Korrelation (jetzt: **rohe `values`-JSON** an den Callback,
    damit get/set/SetBool ihre Felder selbst ziehen — Trigger-Spezialfall bleibt als Helfer).
  - `publish` → `topicHandlers[topic]?.invoke(msgJson)` (ConcurrentHashMap, thread-safe wie `pending`).
- **Subscribe-Frames** (Contract §7.4): latched Topics **mit explizitem `qos`**
  (`transient_local`+`reliable`), nicht-latched (`status`, `foot_contacts`, `joint_states`) mit
  rosbridge-Default. Beispiel latched:
  `{"op":"subscribe","topic":"/hexapod/capabilities","type":"std_msgs/msg/String","qos":{"history":"keep_last","depth":1,"durability":"transient_local","reliability":"reliable"}}`
  (`alerts` → `depth:50`, damit die Historie ankommt).
- **Subscribe-Zeitpunkt:** direkt bei `CONNECTED` alle 6 (+joint_states) subscriben. `capabilities`/
  `manifest`/`alerts` (Always-On, latched) liefern **sofort**; `status`/`tempo`/`foot_contacts`
  (On-Demand-Stack) liefern **erst nach `bringup_start`** — das ist ok, die Handler feuern dann.
  Bei `DISCONNECTED`/`ERROR` werden die Handler geleert (kein Re-Subscribe nötig — Connect ist manuell).

**Threading:** Handler feuern vom OkHttp-Thread → die Activity marshallt auf Main (wie `onState`),
bevor sie Compose-State schreibt (exakt das etablierte Muster).

**Verworfen:** (a) roslibjs-Kotlin-Port / fertige rosbridge-Lib → neue Dependency, Overkill für 7
Topics + 3 Service-Formen; (b) zweiter WebSocket nur für Subscriptions → unnötig, ein Socket
multiplext problemlos.

### 1.1 P5.10 — Overlay-Live-Daten

- **Zwei Status-Quellen mergen** (Contract §6a): `/hexapod/status` (State/Stance/Gait/Safety/Tip +
  H1/H2-Caps, ~5 Hz) **und** `/hexapod/tempo` (latched) sind **getrennte** Topics → die App hält
  beide und liest sie zusammen ins Overlay.
- **Slots** (bestehende leere Slots in `DriveScreen` füllen):
  | Slot | Feld | Anzeige |
  |---|---|---|
  | `state` | `status.state` | Text (z. B. `STANDING`/`WALKING`) |
  | `stance` | `status.stance` | Text (`tief`/`mittel`/`hoch`) |
  | `gait` | `status.gait` | Text (`tripod`/…) |
  | `tempo` | `tempo.tempo` | Text (`schnell`/…) |
  | `safety` | `status.safety_frozen` | grau/rot („⚠ FROZEN") |
  | `tip` | `status.tip` | `none`=grau, `warn`=gelb, `crit`=rot (nur Zustand, **kein** roll/pitch — User-Entscheidung) |
  | `foot 1–6` | `/foot_contacts.data[i] ≥ 0.5` | grün=Kontakt, sonst grau |
- **Kein Wert da** (vor `bringup_start` bzw. vor erstem Frame): Slot zeigt neutralen Platzhalter
  („—"), **kein Absturz** — `status`/`tempo`/`foot` sind nullable im State.
- **Foot-Mapping:** `data`-Index `0..5` → Raster-Position „1 4 / 2 5 / 3 6" (Index 0→„1" … 5→„6").
  Der exakte Bein→Index-Bezug ist Anzeige-Detail (Contract garantiert nur „6× 0/1"); wird beim
  Live-Test verifiziert (offener Punkt §4).

### 1.2 P5.11 — Config-Panel (generisch aus dem Manifest)

- **Platzierung (Q3):** **Vollbild-scrollbares Overlay** aus dem **`⚙ config`**-Slot des
  Fahr-Screens (der Slot-Text wird von „⚙" auf **„⚙ config"** erweitert). Der 30-Hz-`/joy`-Loop
  läuft unverändert weiter (Compose-State-Overlay = **kein** `onPause`). Nutzt die vorhandene
  `OverlayPanel.CONFIG`-Struktur (jetzt mit Inhalt statt „Phase 5 — noch leer").
- **Generisches Rendering** aus `/hexapod/config_manifest` (`version` + `params[]`):
  - **Gruppierung** nach `group` in **Manifest-Reihenfolge** (5 Gruppen). Gruppe
    `"IMU / Balance — Erweitert"` (`advanced:true` je Param) → **eingeklappt** (Toggle-Header) +
    Warnhinweis („falsche Gains → Aufschwingen").
  - **Widgets:** `slider` (± Buttons + Eintipp-Feld + min/max/step/default/hint/unit), `toggle`
    (Switch), `dropdown` (`options`).
- **Werte lesen/setzen** = native rosbridge-Param-Services:
  - **Lesen:** beim Öffnen des Panels **und** wenn der Stack (neu) läuft → `get_parameters`
    je Node gebündelt (`{names:[…]}` pro `node`, ein Call je Node statt je Param).
  - **Setzen:** `set_parameters` mit typisiertem Wert (`type`: bool=1/int=2/double=3/string=4).
    Antwort `results[0].successful` → `false` ⇒ `reason`-String am Param anzeigen (rot), Wert
    zurück auf den zuletzt bestätigten (kein optimistisches Übernehmen).
- **App-Pflichten (Contract §6a):**
  1. **Gating** `gating:"standing"` → Widget **disabled** bei **exakt `status.state != STANDING`**
     (Hinweis „nur im Stand"; User-Entscheidung). Das disabled auch während `WALKING` + den
     Übergangs-States (`STARTUP_RAMP`/`REPOSITION`/`SAT`/…) und **spiegelt genau das Server-Reject**
     (der gait_node lehnt `standing_only` sonst ab) → keine sinnlosen Fehlversuche. Betrifft
     `cycle_time` + die 3 Overlay-Dropdowns (gait/tempo/stance).
  2. **Dynamic-Cap** `dynamic_cap:"step_height_cap"`/`"step_length_cap"` → effektives
     `max = min(manifest.max, status[cap])`; Slider klemmt live mit dem Stance-abhängigen Cap.
     Fehlt der Status (Stack aus) → nur `manifest.max`.
  3. **Reject** `successful=false` → `reason` zeigen (der gait_node liefert Klartext, z. B.
     Cap-/Standing-Reject).
- **Verfügbarkeit (Contract §6a):** Manifest ist **Always-On** → Panel rendert **schon vor**
  `bringup_start` (aus dem Manifest, Defaults sichtbar). Get/Set gehen aber erst, wenn der Stack
  läuft (Nodes existieren) → vorher Widgets „lesend deaktiviert" mit Hinweis „Stack starten".
- **Nebenwirkung Tempo↔Scales (Contract §4.1):** ein Tempo-Wechsel (D-Pad **oder** Dropdown)
  überschreibt in `joy_to_twist` die 3 Scale-Params → bei jedem `/hexapod/tempo`-Update liest das
  Panel `linear_x_scale`/`linear_y_scale`/`angular_z_scale` **neu** (re-`get_parameters`), damit die
  Slider den echten Wert zeigen.

**Reine Logik (unit-getestet), getrennt vom org.json-Glue:**
- `effectiveMax(spec, status)` = `min(spec.max, status[dynamic_cap] ?: +∞)`.
- `isEnabled(spec, state, stackRunning)` = Gating- + Stack-Regel.
- `stepUp/stepDown(value, spec)` + `clamp(value, min, effectiveMax)` (auf `step` gerundet).
- `parseTypedInput(text, spec)` → gültiger Wert oder null (Eintipp-Validierung).

### 1.3 P5.12 — Dropdowns (gait/tempo/stance) + Alerts

**Dropdown-Quelle:** `/hexapod/capabilities` (latched, Always-On) → `gaits`/`stance_modes`/
`tempo_presets`. Alle drei **standing-gated** (disabled außerhalb STANDING).

**Bedienung (Q1 = a):** **keine** zusätzliche Overlay-Fläche — die schon positionierten Read-out-Slots
`stance`/`gait`/`tempo` werden **antippbar** und öffnen ein **Popup-Menü** (`DropdownMenu`) mit den
Optionen aus `capabilities`. Der `state`-Slot bleibt reine Anzeige. Antippen außerhalb STANDING →
Slot wirkt disabled (Hinweis). So bleiben die Slots kompakt und kontextnah beim Fahren.

**Setz-Mechanismus:**
- **stance** — **cycle-to-target** via `/hexapod_cycle_stance` (`SetBool`, `true`=höher/`false`=tiefer),
  Ist-Index = `status.stance_idx`.
- **tempo** — **cycle-to-target** via `/hexapod_cycle_tempo` (`SetBool`, `true`=schneller/`false`=langsamer),
  Ist-Index = `tempo.tempo_idx` (Contract v0.9.1 §6a).
- **gait** — **`set_parameters(/gait_node, gait_pattern=<name>)`** (namensbasiert, ein Call).

**[ADR-P5-2] Warum gait anders (Param statt Cycle):** `status` liefert `stance_idx`/`tempo_idx`
direkt (Ist-Index bekannt → cycle-to-target sicher), aber **keinen `gait_idx`** — nur `gait` (Name).
Ein cycle-to-target für gait müsste den Ist-Index aus `capabilities.gaits.indexOf(name)` ableiten
**und** annehmen, dass `/hexapod_cycle_gait` **in genau dieser Reihenfolge** cyclet — das ist im
Contract **nicht garantiert**. `set_parameters(gait_pattern=name)` ist **namensbasiert,
reihenfolge-unabhängig, ein Call** (Contract §4: `gait_pattern` ist `standing_only`-Param) und der
Reject-`reason`-Pfad greift identisch. Für stance/tempo ist die Index-Ausrichtung dagegen belegt
(`stance_idx:1`↔`stance_modes[1]="mittel"`, `tempo_idx:2`↔`tempo_presets[2]="schnell"`).
> **Am Code verifiziert:** `gait_pattern` ist ein deklarierter `standing_only`-Param und läuft durch
> `_on_param_change` (`gait_node.py:187` + `:2722/2726`, löst `_load_gait_pattern`, prüft
> `∈ GAIT_PRESETS`) → live-setzbar via `set_parameters`, Reject mit Klartext-`reason`.

**[ADR-P5-3] cycle-to-target-Orchestrierung (async, ein generischer Controller für stance+tempo):**
- **Reine Logik** `nextCycleStep(currentIdx, targetIdx)` → `Boolean?`: `true` wenn `target>current`
  (einen Schritt hoch), `false` wenn `target<current`, `null` wenn erreicht (fertig).
- **Glue** (Activity/Controller): Ziel setzen → `nextCycleStep` rufen → SetBool mit der Richtung →
  auf **Antwort** warten:
  - `success=true` → auf das **nächste `/hexapod/status`- bzw. `/hexapod/tempo`-Update** warten
    (Ist-Index ändert sich) → erneut `nextCycleStep`; erreicht → fertig.
  - `success=false` (blockiert/nicht STANDING) → **nicht** sofort nachfeuern; auf ein Topic-Update
    warten, dann neu bewerten. **Cap:** ≤ (Preset-Anzahl−1) Schritte **und** ein Timeout → gegen
    Endlosschleife (Ziel nie erreichbar, z. B. Zustand verlassen).
- Nur **ein** Cycle „in flight" je Art; UI zeigt „…" bis erreicht/abgebrochen.

**Alerts-View** ← `/hexapod/alerts` (latched, Historie 50). Ein Alert je Nachricht
(`{stamp,level,name,msg}`) → die App **akkumuliert** eine Liste (neueste oben, Cap 50 wie ROS).
Panel aus dem `⚠ alerts`-Slot: Liste (Level-Farbe WARN/ERROR/FATAL) + **„Alles kopieren"**
(Android `ClipboardManager`, formatiert als Text) + **lokaler „Löschen"-Button** (User-Wunsch):
leert nur die **App-Liste** (nicht ROS — die latched Historie liefert beim Reconnect neu). **Reset +
Dedup beim (Re-)Subscribe** (Key `stamp+name+msg`), damit der latched-Batch keine Dopplungen erzeugt.

### 1.4 P5.13 — 3D-Roboter-Viz (leichtgewichtig, zero-dep)

- **Center-View `ROBOT3D`** (in Phase 4 reserviert/disabled) → **aktivieren**. Quelle:
  `/joint_states` (`sensor_msgs/JointState`, name[]→position[] in rad) über rosbridge. **Kein**
  Status-Topic nötig (Contract §5 App-Brief).
- **[ADR-P5-4] Kinematik-Modell statt Mesh-Rendering:** zero-dep-Ansatz — kein 3D-Engine, kein
  Mesh-Loading. Ein **statisches, parametrisiertes Kinematik-Modell** (6 Bein-Basis-Transforms am
  Hex-Körper + 3 Link-Längen coxa/femur/tibia) rechnet **Forward-Kinematics** aus den 18
  Joint-Winkeln → 4 Punkte je Bein (Basis→Coxa→Femur→Tibia→Fuß). **Isometrische Projektion** auf
  2D → Zeichnen als Linien/Punkte auf einem **Compose `Canvas`**. Körper als Hexagon.
  - **Geometrie-Konstanten** werden **einmalig** aus der `hexapod_description`-URDF übernommen
    (statische Roboter-Geometrie, **kein** Laufzeit-Contract) und als Kotlin-Konstanten hinterlegt;
    Quelle im Code dokumentiert. **Am Code verifiziert vorhanden:** Link-Längen
    `coxa_length=0.0436`, `femur_length=0.060`, `tibia_length=0.134` (m) in
    `hexapod_physical_properties.xacro`; Joint-Origins + 6 Bein-Mounts in `leg.xacro` — die Links
    sind dort **Boxen** (`<box>`) → 1:1 als 3D-Box-Segmente renderbar. Die 6 Mount-(x,y,yaw)
    übernehme ich beim Impl 1:1. **Verworfen:** URDF/`/robot_description` zur Laufzeit XML-parsen
    (XmlPullParser, schwerer, für statische Geometrie unnötig) — als spätere Option notiert, falls
    sich die Geometrie ändert.
- **Reine Logik** (unit-getestet): `legForwardKinematics(base, angles, lengths)` → 3D-Punkte;
  `project(point3d, camera)` → 2D. Das Canvas-Zeichnen + der rosbridge-Glue sind Integration.
- **Fallback:** kommt kein `/joint_states` (Stack aus) → statische Default-Pose + Hinweis.

---

## 2. Tests-Liste (mit Begründung) + was bewusst NICHT

**Reine Logik (JUnit, ohne Gerät/Netz — wie `JoyMapperTest`/`VideoLogicTest`):**
| Test | Prüft | Warum |
|---|---|---|
| `ConfigLogicTest` `effectiveMax` | `min(manifest.max, cap)`, Cap fehlt → manifest.max | Slider-Klemmung (§6a Pflicht 2) |
| `ConfigLogicTest` `isEnabled` | Gating standing × Stack-Zustand | Widget-Disable (§6a Pflicht 1) |
| `ConfigLogicTest` `stepUp/Down`+`clamp` | Runden auf `step`, Grenzen | ±-Buttons korrekt |
| `ConfigLogicTest` `parseTypedInput` | double/int/bool/string-Validierung + Range | Eintipp-Feld |
| `CycleLogicTest` `nextCycleStep` | Richtung hoch/runter/fertig, Grenzen | stance/tempo-Dropdown |
| `FootLogicTest` | `data[i]≥0.5` → Kontakt, Länge≠6 tolerant | Foot-Raster |
| `AlertLogicTest` | Akkumulation+Cap 50, Clipboard-Format | Alerts-Liste |
| `Robot3dLogicTest` | FK-Punkte für bekannte Winkel, Projektion | 3D-Geometrie |

**Bewusst NICHT unit-getestet (wie bisher → Integration T5.15):**
- **org.json-Glue** (`RosbridgeProtocol`-Parser/Frames, `RosbridgeClient`-Routing) — `org.json` ist
  ein Android-SDK-Stub, in reinem JUnit nicht lauffähig (identisch zu Phase 2/3/4). Die *Zahlen*
  kommen aus der getesteten reinen Schicht.
- **Compose-Rendering** (Panel/Slots/Canvas) — visuell, per Live-Test.
- **rosbridge-Roundtrips** (subscribe/latched-Delivery/get-set) — nur echt gegen die ROS-Seite prüfbar.

**Bewusst offen / später:** Config-Persistenz (nie); E-Stop/Recovery (P6); Audio (P7);
Voll-3D-Meshes (nicht geplant).

---

## 3. Progress-Checkliste (Done-Vertrag) — gestuft mit Checkpoints (Q4)

> Ein Plan-Doc, Umsetzung **gestuft** P5.10→P5.13; nach **jeder** Stufe kurzer Self-Review +
> `testDebugUnitTest` grün + Checkpoint an den User, bevor die nächste Stufe startet.

```
Phase 5 App (P5.10–P5.13):
- [x] P5.10a [Transport] RosbridgeClient: subscribe/unsubscribe + publish-Routing; callServiceArgs + rohe RawResponse (ADR-P5-1) ✅
- [x] P5.10b [Transport] RosbridgeProtocol: subscribe-Frames (latched-QoS §7.4), callServiceArgs, parsePublish/parseRawResponse; HmiProtocol-Parser status/tempo/foot ✅ (get/set_parameters+SetBool+caps/manifest/alerts/joints → mit ihrer Stufe, s. Abweichung ↓)
- [x] P5.10c [Overlay] HmiState-Halter + Slots gefüllt (state/stance/gait/tempo/safety/tip) + Foot-Raster grün; Merge status+tempo; Platzhalter wenn leer; Stale-Clear bei Stack-Stopp ✅
- [x] P5.10  [Checkpoint] testDebugUnitTest 47/0 grün + assembleDebug ok + Self-Review; **User-Sicht offen**
- [x] P5.11a [Config] Manifest-Parse + generisches Panel (Gruppen, Slider ±/Eintipp/Drag, Toggle, Dropdown), advanced eingeklappt + Warnung; Slot-Text „⚙ config" ✅
- [x] P5.11b [Config] get/set_parameters-Glue; Gating + Dynamic-Cap + Reject-reason; Tempo↔Scales-Reload; Slider-Revert bei Reject ✅
- [x] P5.11  [Checkpoint] ConfigLogicTest 18 grün (Summe 65/0) + assembleDebug ok + Self-Review; **User-Sicht offen**
- [x] P5.12a [Dropdowns] antippbare Slots stance/gait/tempo -> Popup (capabilities); gait (set_parameters) + stance/tempo (cycle-to-target, ein Controller), standing-gated, „…"-Indikator ✅
- [x] P5.12b [Alerts] Alerts-Liste (Level-Farbe) + „Alles kopieren" + lokaler „Löschen"-Button; Reset bei Disconnect + Dedup ✅
- [x] P5.12  [Checkpoint] CycleLogicTest 3 + AlertLogicTest 4 grün (Summe 72/0) + assembleDebug ok + Self-Review; **User-Sicht offen**
- [x] P5.13  [3D-Viz] ROBOT3D aktiviert: FK+Projektion (Canvas) aus /joint_states (gated subscribe); Robot3dLogicTest 5 grün (Summe 77/0); Self-Review ✅
- [x] P5.14  [Doku] architecture.md §4.5 + NEXT.md + CLAUDE.md „Aktuell: Phase 5" nachgezogen ✅
- [ ] T5.15  [Integration, User+App] End-to-End gegen die laufende ROS-Seite (Done-Kriterium) — **offen (Live-Test mit User)**
```

**Integration T5.15 (Done-Kriterium, mit User):** Overlay zeigt Live-State/Stance/Gangart/Tempo/
Foot-Raster · Config-Panel rendert Gruppen/Slider/Toggles/Dropdowns aus dem Manifest, ± und
Eintipp setzen Params live · standing-gated Widgets disabled außerhalb STANDING · Reject-`reason`
sichtbar · Dropdowns gait/tempo/stance funktionieren · Alerts-Liste + Kopieren · 3D-Viz animiert.

---

## 4. Offene Punkte / Risiken (für User-Review)

1. **Foot-Index→Bein-Zuordnung:** Contract garantiert nur „6× 0/1". Welcher `data`-Index welches
   physische Bein ist, verifiziere ich beim Live-Test (Bein anheben → welcher Slot wird grau);
   Raster-Reihenfolge ggf. justieren. **Unkritisch.**
2. **3D-Geometrie-Konstanten:** ✅ Quelle bestätigt (`hexapod_physical_properties.xacro` Link-Längen,
   `leg.xacro` Mounts/Box-Geometrie). Ich übernehme die 6 Bein-Mount-(x,y,yaw) beim Impl 1:1 und lege
   sie kurz vor; falls die Geometrie später abweicht, Option „`/robot_description` parsen".
3. **Dropdown-Bedienung:** ✅ entschieden (Q1=a) — antippbare Slots `stance`/`gait`/`tempo` → Popup.
   Nur das **Feinlayout** (Popup-Breite/Position) beim Impl, User-Sicht am Checkpoint P5.12.
4. **Gating-Schärfe:** ✅ entschieden — exakt `state == STANDING` (spiegelt Server-Reject; auch in
   `WALKING`/Übergangs-States disabled).
5. **Manifest-Ranges:** min/max/step sind ROS-seitig „app-sichere" Vorschläge (ws-Plan §4.4) — die
   App zeigt sie 1:1; falls beim Live-Tuning zu eng/weit, meldet der User es an die ROS-Session
   (Manifest-Änderung dort, **kein** App-Change).
6. **`success=false`-Semantik bei cycle-to-target:** laut Contract = blockiert/nicht STANDING →
   nicht sofort nachfeuern (auf Topic-Update warten). Timeout/Step-Cap als Sicherung — Wert (z. B.
   3 s) beim Impl, User-Sicht.

---

## 5. Modul-Karte (Ist → neu)

**Erweitert (bestehend):**
| Datei | Änderung |
|---|---|
| `RosbridgeClient.kt` | + `subscribe`/`unsubscribe` + `publish`-Routing; generisches `callService` mit Args + rohen `values` |
| `RosbridgeProtocol.kt` | + subscribe-Frames (latched-QoS), get/set_parameters, SetBool; Parser der 7 Topics + Param-Antworten |
| `DriveScreen.kt` | Slots mit Live-Daten füllen; Foot-Raster grün; `⚙`→`⚙ config`; Dropdowns; ROBOT3D aktiv; Panels mit Inhalt |
| `MainActivity.kt` | Subscribe bei Connect; Handler→HmiState (Main-marshallt); Param-get/set; cycle-to-target-Orchestrierung |

**Neu — reine Logik (unit-getestet):**
`HmiModels.kt` (Datenklassen + Enums) · `ConfigLogic.kt` · `CycleLogic.kt` · `FootLogic.kt` ·
`AlertLogic.kt` · `Robot3dLogic.kt`

**Neu — Glue (org.json/Compose, integrationsverifiziert):**
`HmiProtocol.kt` (Topic-Parser, org.json) · `HmiState.kt` (Compose-Snapshot-Halter) · `ConfigPanel.kt` ·
`AlertsPanel.kt` · `Robot3dView.kt` · Dropdown-Composables (in `DriveScreen.kt` oder eigene Datei)

> **Umsetzungsnotizen P5.10 (bewusste Abweichungen vom Plan):** (1) Die Topic-Parser liegen in einer
> **eigenen** `HmiProtocol.kt` statt in `RosbridgeProtocol.kt` (Kohäsion: /joy+generischer Transport
> vs. Phase-5-HMI-Parsen). (2) Parser werden **inkrementell mit ihrer Stufe** gebaut (P5.10: status/
> tempo/foot; caps/manifest/alerts/joints folgen P5.11–P5.13) statt alle vorab — vermeidet spekulativen
> toten Code. Der **Transport** (subscribe/route/callServiceArgs) ist bereits vollständig.

**Neu — Tests:** `ConfigLogicTest.kt` · `CycleLogicTest.kt` · `FootLogicTest.kt` ·
`AlertLogicTest.kt` · `Robot3dLogicTest.kt`

---

## 6. Doku-Nachzug (nach Umsetzung, P5.14)
- `docs/architecture.md` §4.5 (neuer Abschnitt „Phase 5 — Status-Overlay + Config"): die 7
  rosbridge-Subscriptions, Param-Services, reine-Logik-vs-Glue-Schichtung, 3D-Kinematik.
- `docs/NEXT.md` überschreiben (Stand Phase 5, Resume-Prompt).
- `CLAUDE.md` §7 „Aktuell: Phase 5" + Navigations-Index-Zeile für dieses Doc.
- **Kein** Contract-Change (App-Seite baut nur dagegen).

---

## 7. Post-Review-Nachbesserungen (nach P5.14, vor T5.15)

Aus einer kritischen Architektur-Durchsicht + erstem User-Test:

- **B3 — `HmiController` extrahiert:** die Phase-5-HMI-Orchestrierung (Subscriptions, Param get/set,
  cycle-to-target) ist aus der 549-Zeilen-`MainActivity` in einen framework-leichten `HmiController`
  gewandert (Activity schlank, HMI zentralisiert; verhaltensgleich).
- **B4 — Subs an den Vordergrund gekoppelt:** HMI-Topics werden bei `onPause` abbestellt / bei
  `onResume` (+verbunden) neu abonniert → kein status/foot-Verkehr im Hintergrund (wie `/joy`/Video).
- **C5 — `onDestroy`-Cleanup:** anstehende Cycle-Timeouts + Video-Retry-Callbacks werden abgeräumt.
- **A1/A2 — Doku:** `architecture.md` §4.4 (veraltete Phase-5-Zeile) + §4.5 (Always-On/On-Demand-
  Verfügbarkeit, Subscription-Lifecycle, `HmiController`) nachgezogen.
- **GUI:** Status-Chips (verbunden/Stack/safety/tip) links **vertikal** gestapelt → oben Platz, `show`
  wird nicht mehr abgeschnitten. Center-Toggle + alerts/config/show unverändert.
- **3D-Ansicht:** **feste Kamera** (kein „Schwingen" — feste Skalierung/Zentrierung statt Auto-Fit);
  **1 Finger = orbitieren, 2 Finger = zoomen** (Blickwinkel/Zoom bleiben über Pose-Änderungen erhalten,
  nicht persistiert); **Füße 2× größer**; **Bein-1-Femur grün**.

### 7a. Live-Test-Fixes (2. User-Runde)

- **3D war spiegelverkehrt → behoben:** die alte Iso-Projektion enthielt (mit screen-y-down) eine
  Reflexion (Bein-Reihenfolge CCW statt CW). Ersetzt durch eine **rechtshändig konstruierte
  `cameraProject`** (Azimuth/Elevation, `right = forward × worldUp`) → spiegelfrei, Bein 1 oben-rechts,
  Uhrzeigersinn wie in der Sim. `rotateView`+`project` entfernt; Tests angepasst (Top-down-Chiralität).
- **Foot-Raster** auf **„4 1 / 5 2 / 6 3"** umgestellt (rechte Beine 1–3 rechts). Bein-Reihenfolge
  `data[i]`→`leg_(i+1)` **ROS-bestätigt** (keine Index-Justage nötig).
- **3D-Füße kontaktabhängig gefärbt:** grün bei Bodenkontakt / grau ohne (aus `/foot_contacts`), statt
  immer grün.
- **`queue_length:1`** in den Subscribe-Frames von `status`/`foot_contacts`/`joint_states` (ROS-Agent-
  Tipp) → rosbridge hält nur den neuesten Frame, weniger Latenz bei High-Rate.

**Verbleibend für T5.15 (Live):** Config-Panel-Feinprüfung (nicht alle Params verifiziert);
3D **femur/tibia-Vorzeichen** (knicken die Beine nach unten?) am echten Bild — Chiralität/Spiegelung
ist jetzt gefixt.
# NEXT — Stand & nahtloser Wiedereinstieg

> **Rolling-Doc.** Wo wir stehen + wie es weitergeht. Überschreiben/aktualisieren beim
> Phasenwechsel. Der ausführliche Stand liegt in
> [`phase_6_estop_recovery_plan.md`](phase_6_estop_recovery_plan.md).
>
> **Letzter Stand:** 2026-07-19.

---

## Wo wir stehen

**Phase 6 — E-Stop (scharf) + Recover + frozen-Anzeige.** App-Code **fertig & grün**
(P6.8/P6.9): `testDebugUnitTest` **85/0** (neu: `SafetyLogicTest` 6), Main kompiliert.
**Offen:** **P6.11-Sim** = End-to-End gegen den laufenden Sim-Stack (User + Handy), danach HW-**T6.8**.

Was die App jetzt zusätzlich kann (Interface = `interface_contract.md` **v0.10 §2/§6a**):
- **E-STOP scharf** (reservierter Slot unten rechts, voll-rot, immer sichtbar, Tap-Puls) →
  `call_service /hexapod_estop` (`Trigger`, wirkt Sim+HW). Kein Dead-Man, kein Dialog.
- **frozen-Anzeige:** prominenter zentraler **Banner** „FROZEN — E-STOP" (englisch) sobald
  `/hexapod/status.safety_frozen == true` (nicht aus der Service-Response abgeleitet); **ausgeblendet,
  solange ein Overlay-Panel (config/alerts) offen ist**, kommt beim Schließen zurück (falls noch frozen).
- **Recover-Button** (nur bei frozen) → `call_service /hexapod_recover` (`Trigger`) + D6-Hinweistext;
  danach `STARTUP_RAMP → STANDING`, Banner „recovering …" → weg.
- **Center-Toggle-Labels** jetzt englisch: **None / Video / 3D** (Rest der UI bleibt Deutsch).
- Reine Logik in `SafetyLogic.kt` (`safetyMode()` + Service-Const), unit-getestet.

**Phase 5** (Status-Overlay + Config-Panel + Dropdowns + 3D-Viz, `interface_contract.md` **v0.9.1 §6a**)
war davor fertig & grün (P5.10–P5.14). Was die App aus Phase 5 kann:
- **Overlay-Live-Daten:** `state`/`stance`/`gait`/`tempo`/`safety`/`tip`-Slots aus
  `/hexapod/status` (+ `/hexapod/tempo` gemergt); **Foot-Raster grün** aus `/foot_contacts`.
- **Config-Panel** (`⚙ config`-Slot): generisch aus `/hexapod/config_manifest` (39 Params) —
  Gruppen, Slider (Drag/±/Eintipp), Toggles, Dropdown; „— Erweitert" (16 Gains) eingeklappt;
  get/set via native `get/set_parameters`; **Gating** (nur STANDING), **Dynamic-Cap**, **Reject-`reason`**.
- **Dropdowns** (antippbare Slots `stance`/`gait`/`tempo`) aus `/hexapod/capabilities`,
  standing-gated: gait = `set_parameters(gait_pattern)`, stance/tempo = cycle-to-target
  (`/hexapod_cycle_stance`/`_tempo`).
- **Alerts** (`⚠ alerts`-Slot): Liste aus `/hexapod/alerts` (Level-farbig) + „Kopieren" + „Löschen".
- **3D-Viz** (Center-Toggle **3D**): zero-dep Strichmodell aus `/joint_states` (FK + Iso-Projektion).

Transport-Kern neu: `RosbridgeClient` kann jetzt **subscribe + publish-Routing** und **`call_service`
mit Args** (get/set_parameters, SetBool) — der Phase-2/3-`/joy`-/Trigger-Pfad ist unverändert.

## Die zwei „Test"-Ebenen

1. **App-Logik ohne Hardware** (`testDebugUnitTest`, **85/0**): `SafetyLogic`/`ConfigLogic`/`CycleLogic`/
   `FootLogic`/`AlertLogic`/`Robot3dLogic`. Der org.json-Glue (`HmiProtocol`, `RosbridgeClient`-Routing)
   + Compose-Rendering (inkl. E-STOP-Tap/Banner) sind bewusst **nicht** unit-getestet (SDK/Netz) → Integration.
2. **Echtes Ausprobieren (P6.11-Sim / T5.15):** braucht die laufende ROS-Seite (always_on → bringup_start →
   stand_up). Die App *ist* der Client.

## Nächster Schritt: Live-Integration (P6.11-Sim + T5.15) — Schritt für Schritt

**▶ ROS (hexapod_ws):** `always_on.launch.py` → (App) verbinden → `bringup_start` → `stand_up`.
Ab `always_on` sind `capabilities`/`config_manifest`/`alerts` da; nach `bringup_start` fließen
`status`/`tempo`/`foot_contacts`/`joint_states`.

**▶ Phase-6-Check (E-Stop + Recover), am laufenden Sim-Stack:** aufstehen → laufen → **E-STOP
tappen** (Slot unten rechts) → Banner „FROZEN — E-STOP"; gegenprüfen mit
`ros2 topic echo /hexapod/status` → `safety_frozen: true`. → **Recover tappen** → Banner „recovering…",
`state` `STARTUP_RAMP → STANDING`, Banner verschwindet, Roboter steht. (E-Stop-Ziel ist
`/hexapod_estop`, NICHT `/hexapod_safety_freeze`.)

**▶ App (dieses Repo):** `./gradlew installDebug` aufs S22+ (Wireless ADB → `docs/build_and_deploy.md`).

**▶ Handy prüfen:**
- **Overlay:** nach `stand_up` zeigen `state=STANDING`, `stance`/`gait`/`tempo`, `safety`, `tip`
  Live-Werte; **Foot-Raster** wird grün bei Bodenkontakt (Bein anheben → Slot wird grau; ggf.
  Index→Bein-Zuordnung justieren, §4.1 des Plans).
- **Config-Panel** (`⚙ config`): Gruppen/Slider/Toggles/Dropdown rendern; ± und Eintipp **setzen
  Params live** (Wirkung am Roboter sichtbar); außerhalb STANDING sind die gegateten Widgets
  disabled; ein Cap-/Standing-Reject zeigt die **`reason`** rot; Slider springt bei Reject zurück.
- **Dropdowns:** `stance`/`gait`/`tempo` antippen → Popup → Auswahl greift (stance/tempo cyclen
  schrittweise zum Ziel). Nur im Stand aktiv.
- **Alerts** (`⚠ alerts`): ein WARN am Roboter erscheint in der Liste; „Kopieren"/„Löschen".
- **3D** (Center-Toggle): animiert aus `/joint_states`, **feste Kamera** (kein Schwingen),
  **spiegelfrei** (rechtshändige `cameraProject` — Bein 1 vorne-rechts/oben-rechts, grüner Femur).
  **1 Finger = orbitieren** (horiz.=Azimuth, vert.=Elevation), **2 Finger = zoomen**; große Füße,
  je Fuß **grün bei Kontakt / grau ohne** (aus `/foot_contacts`). **Restcheck:** knicken die Beine
  nach oben statt unten → Sign-Flip femur/tibia (`Robot3dLogic.legPoints`, `-z`).
- **Foot-Raster** (unten links): Layout **„4 1 / 5 2 / 6 3"** (rechte Beine 1–3 rechts); `data[i]`→`leg_(i+1)`
  ROS-bestätigt.
- **GUI:** Status-Chips (verbunden/Stack/safety/tip) stehen jetzt **links vertikal**; `show` rechts
  wird nicht mehr abgeschnitten.

## Resume-Prompt (copy-paste in die App-Session)

```
Wir sind in hexapod_app, Phase 6 (E-Stop scharf + Recover + frozen-Anzeige). App-Code ist fertig
und grün (P6.8/P6.9, 85/0), offen ist der Sim-E2E (P6.11-Sim). Kontext:
docs/phase_6_estop_recovery_plan.md + interface_contract.md v0.10 (§2/§6a).

Ich habe den Live-Test gemacht (ROS: always_on -> connect -> bringup_start -> stand_up -> laufen).
Ergebnis:
- E-STOP tappen -> Banner "FROZEN — E-STOP", /hexapod/status.safety_frozen=true: <ok / Fehler: …>
- Recover tappen -> "recovering…" -> STARTUP_RAMP -> STANDING, Banner weg, steht: <…>
- Center-Labels None/Video/3D + Tap-Puls am E-STOP: <…>
(Phase-5-Overlay/Config/Dropdowns/Alerts/3D am selben Stack ggf. mitprüfen, falls T5.15 noch offen.)

Hake P6.11-Sim ab -> Phase 6 App-Seite komplett (HW-T6.8 dann am echten Roboter).
```

## Danach
- Stimmt alles → **P6.11-Sim abhaken**, **Phase 6 App-Seite komplett** → HW-**T6.8** am echten
  Roboter (mit User); ROS-Seite ist bereits implementiert + Sim-verifiziert.
- ROS-Seite (User): `phase_6_..._progress.md` App-Bullets P6.8/P6.9 abhaken.
- Falls **T5.15** (Phase-5-Live) noch offen war: am selben Stack mitprüfen und abhaken.

## Offene Altlasten / bewusst später
- **3D-Vorzeichen** (femur/tibia/coxa) + **Foot-Index→Bein** erst am Live-Bild final justieren.
- **`/joint_states`** nur abonniert, solange die 3D-Ansicht aktiv ist (Bandbreite).
- **Phase-3-Option B** (latched Live-Push `/hexapod/bringup_running`): weiterhin offen, nicht nötig.
- **Kein Auto-Reconnect** (Phase 8); Snapshot-State überlebt keinen Prozess-Tod (Querformat gelockt).

# NEXT — Stand & nahtloser Wiedereinstieg

> **Rolling-Doc.** Wo wir stehen + wie es weitergeht. Überschreiben/aktualisieren beim
> Phasenwechsel. Der ausführliche Stand liegt in
> [`phase_5_status_config_plan.md`](phase_5_status_config_plan.md).
>
> **Letzter Stand:** 2026-07-18.

---

## Wo wir stehen

**Phase 5 — Status-Overlay + Config-Panel + Dropdowns + 3D-Viz.** App-Code **fertig & grün**
(P5.10–P5.14): `assembleDebug` ✅, `testDebugUnitTest` **79/0** (neu: `FootLogicTest` 5,
`ConfigLogicTest` 18, `CycleLogicTest` 3, `AlertLogicTest` 4, `Robot3dLogicTest` 5).
**Offen:** **T5.15** = End-to-End-Live-Test (User + Handy) gegen die laufende ROS-Seite.

Was die App jetzt zusätzlich kann (Interface = `interface_contract.md` **v0.9.1 §6a**):
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

1. **App-Logik ohne Hardware** (`testDebugUnitTest`, 79/0): `ConfigLogic`/`CycleLogic`/`FootLogic`/
   `AlertLogic`/`Robot3dLogic`. Der org.json-Glue (`HmiProtocol`, `RosbridgeClient`-Routing) +
   Compose-Rendering sind bewusst **nicht** unit-getestet (SDK/Netz) → Integration.
2. **Echtes Ausprobieren (T5.15):** braucht die laufende ROS-Seite (always_on → bringup_start →
   stand_up). Die App *ist* der Client.

## Nächster Schritt: Live-Integration T5.15 — Schritt für Schritt

**▶ ROS (hexapod_ws):** `always_on.launch.py` → (App) verbinden → `bringup_start` → `stand_up`.
Ab `always_on` sind `capabilities`/`config_manifest`/`alerts` da; nach `bringup_start` fließen
`status`/`tempo`/`foot_contacts`/`joint_states`.

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
Wir sind in hexapod_app, Phase 5 (Status-Overlay + Config + Dropdowns + 3D). App-Code ist fertig
und grün (P5.10–P5.14, 79/0), offen ist nur T5.15 (End-to-End-Live-Test). Kontext:
docs/phase_5_status_config_plan.md + interface_contract.md v0.9.1 (§6a).

Ich habe den Live-Test gemacht (ROS: always_on -> connect -> bringup_start -> stand_up). Ergebnis:
- Overlay state/stance/gait/tempo/safety/tip + Foot-Raster: <ok / Fehler: …>
- Config-Panel rendert + ± / Eintipp setzen live + Gating + Reject-reason: <…>
- Dropdowns stance/gait/tempo: <…>
- Alerts-Liste + Kopieren/Löschen: <…>
- 3D-Viz animiert (Vorzeichen ok?): <…>

Justiere falls nötig (Foot-Index, 3D-Vorzeichen), hake T5.15 ab -> Phase 5 komplett.
```

## Danach
- Stimmt alles → **T5.15 abhaken**, **Phase 5 komplett** → Planung **Phase 6** (E-Stop scharf +
  Recovery; Contract `[TBD-Phase 6]`).
- ROS-Seite (User): `phase_5_..._progress.md` App-Bullets P5.10–P5.13 abhaken.

## Offene Altlasten / bewusst später
- **3D-Vorzeichen** (femur/tibia/coxa) + **Foot-Index→Bein** erst am Live-Bild final justieren.
- **`/joint_states`** nur abonniert, solange die 3D-Ansicht aktiv ist (Bandbreite).
- **Phase-3-Option B** (latched Live-Push `/hexapod/bringup_running`): weiterhin offen, nicht nötig.
- **Kein Auto-Reconnect** (Phase 8); Snapshot-State überlebt keinen Prozess-Tod (Querformat gelockt).

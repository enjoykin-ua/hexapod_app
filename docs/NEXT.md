# NEXT — Stand & nahtloser Wiedereinstieg

> **Rolling-Doc.** Wo wir stehen + wie es weitergeht. Überschreiben/aktualisieren beim
> Phasenwechsel. Der ausführliche Stand liegt in
> [`phase_7_audio_video_plan.md`](phase_7_audio_video_plan.md).
>
> **Letzter Stand:** 2026-07-19.

---

## Wo wir stehen

**Phase 7A/7B — Audio + Video-`type` je Host.** App-Code **fertig & grün** (P7.1–P7.8):
`testDebugUnitTest` **89/0** (neu: `AudioLogicTest` 2, +2 `VideoLogicTest`), Main kompiliert.
**Offen:** **P7.9-Sim** = End-to-End gegen den laufenden Sim-Stack (User + Handy), danach HW (Ton/rpicam).

Was die App jetzt zusätzlich kann (Interface = `interface_contract.md` **v0.12.1 §5/§6b**):
- **Audio (7A):** rechte **Audio-Spalte** im Fahr-Modus (nur bei laufendem Stack) — **ein An/Aus-Toggle**
  (spiegelt latched `/hexapod/sound_enabled`, mutet nur Auto-Sounds) + **3 Soundboard-Buttons**
  „Sound 1/2/3" → publishen `/hexapod/play_sound` (`sound_01/02/03`, spielen immer). `sound_enable`
  wird via `set_parameters` auf `/hexapod_audio` gesetzt. **Kein** „mit/ohne Audio"-Doppelbutton
  (User-Entscheidung: ein Toggle) — „Fahren →" bleibt einzeln.
- **Video (7B):** **Sim/HW-Schalter** in der Connect-Bar → Video-`type` (**Sim=`mjpeg`**, **HW=`ros_compressed`**);
  Default Sim = wie bisher. **`camera_enable`** an die Center-Ansicht gekoppelt (Video→Kamera an,
  None/3D→aus), **nur HW** (in Sim kein `/hexapod_camera`).
- Neu im Transport: `RosbridgeClient` kann jetzt **generisch advertisen + publishen** (Soundboard);
  reine Logik in `AudioLogic.kt` + `VideoLogic` (streamType/wantCameraEnable), unit-getestet.

**Phase 6** (E-Stop scharf + Recover + frozen-Anzeige, `interface_contract.md` **v0.10 §2/§6a**) war
davor fertig & grün (P6.8/P6.9); Sim-E2E (P6.11) lief. **Phase 5** (Status-Overlay + Config-Panel +
Dropdowns + 3D-Viz, **v0.9.1 §6a**) davor fertig & grün. Was die App aus Phase 5 kann:
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

1. **App-Logik ohne Hardware** (`testDebugUnitTest`, **89/0**): `AudioLogic`/`SafetyLogic`/`ConfigLogic`/
   `CycleLogic`/`FootLogic`/`AlertLogic`/`Robot3dLogic` + `VideoLogic` (streamType/wantCameraEnable). Der
   org.json-Glue (`HmiProtocol`, `RosbridgeClient`-Routing) + Compose-Rendering (Audio-Spalte, Sim/HW-Schalter)
   sind bewusst **nicht** unit-getestet (SDK/Netz) → Integration.
2. **Echtes Ausprobieren (P7.9-Sim):** braucht die laufende ROS-Seite (always_on → bringup_start →
   stand_up). Die App *ist* der Client.

## Nächster Schritt: Live-Integration (P7.9-Sim) — Schritt für Schritt

**▶ ROS (hexapod_ws):** `always_on.launch.py` → (App) verbinden → `bringup_start` → `stand_up`.
Ab `always_on` sind `capabilities`/`config_manifest`/`alerts` da; nach `bringup_start` fließen
`status`/`tempo`/`foot_contacts`/`joint_states` **+ `/hexapod/sound_enabled`**.

**▶ Phase-7-Check am laufenden Sim-Stack:**
- **Audio (7A):** in den Fahr-Modus → rechte Audio-Spalte erscheint (nur bei laufendem Stack). **Toggle**
  antippen → `ros2 param get /hexapod_audio sound_enable` folgt + `ros2 topic echo /hexapod/sound_enabled`
  spiegelt live (Toggle „🔊 AN" ↔ „🔇 AUS"). **3 Sound-Buttons** → Roboter-Log „would play sound_0X"
  (Sim = log-only, Ton nur HW). Toggle mutet **nur** Auto-Sounds; Soundboard spielt immer.
- **Video (7B) Sim:** Modus **Sim** (Default) → Stream lädt wie bisher (`type=mjpeg`).
- **Video (7B) HW-type:** Schalter auf **HW** + `camera.launch.py source:=test` → App baut
  `type=ros_compressed` → **Bild kommt**. (In Sim keine `camera_enable`-Calls; auf HW folgt die Kamera
  der Ansicht: Video→an, None/3D→aus.)

**▶ App (dieses Repo):** `./gradlew installDebug` aufs S22+ (Wireless ADB → `docs/build_and_deploy.md`).

**▶ Handy prüfen (Phase-5-Regression am selben Stack):**
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
Wir sind in hexapod_app, Phase 7A/7B (Audio + Video-type je Host). App-Code ist fertig und grün
(P7.1–P7.8, 89/0), offen ist der Sim-E2E (P7.9-Sim). Kontext:
docs/phase_7_audio_video_plan.md + interface_contract.md v0.12.1 (§5/§6b).

Ich habe den Live-Test gemacht (ROS: always_on -> connect -> bringup_start -> stand_up -> Fahren).
Ergebnis:
- Audio-Toggle: sound_enable + /hexapod/sound_enabled folgen live (AN/AUS): <ok / Fehler: …>
- 3 Soundboard-Buttons -> Roboter-Log "would play sound_0X": <…>
- Video Sim (mjpeg) lädt wie bisher: <…>
- Video HW-type: Schalter HW + camera.launch.py source:=test -> ros_compressed, Bild kommt: <…>

Hake P7.9-Sim ab -> Phase 7 App-Seite komplett (HW-Ton/rpicam dann am echten Roboter).
```

## Danach
- Stimmt alles → **P7.9-Sim abhaken**, **Phase 7 App-Seite komplett** → HW (Ton hörbar, rpicam-
  Kamerabild + Strom/Wärme via camera_enable) am echten Roboter (mit User).
- ROS-Seite (User): `phase_7a_..._progress.md` / `phase_7b_..._progress.md` App-Bullets abhaken.

## Offene Altlasten / bewusst später
- **Video-Latenz-Fix (Frame-Drop):** `MjpegStream` rendert jetzt immer nur das **neueste** Frame
  (Reader leert den Socket → Single-Slot; Decoder-Thread dekodiert den neuesten) + `videoStreamUrl`
  drosselt für `mjpeg` die Bandbreite (`quality=70&width=1120&height=630`, ~¾ der 720p-Pixel). Ziel: Handy-Latenz < 1–2 s
  statt 10–15 s wachsend. **Code fertig & grün (89/0); Handy-Verifikation am Sim-Stack offen.**
- **3D-Vorzeichen** (femur/tibia/coxa) + **Foot-Index→Bein** erst am Live-Bild final justieren.
- **`/joint_states`** nur abonniert, solange die 3D-Ansicht aktiv ist (Bandbreite).
- **Phase-3-Option B** (latched Live-Push `/hexapod/bringup_running`): weiterhin offen, nicht nötig.
- **Kein Auto-Reconnect** (Phase 8); Snapshot-State überlebt keinen Prozess-Tod (Querformat gelockt).

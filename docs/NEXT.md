# NEXT — Stand & nahtloser Wiedereinstieg

> **Rolling-Doc.** Wo wir stehen + wie es weitergeht. Überschreiben/aktualisieren beim
> Phasenwechsel. Der ausführliche Stand liegt in
> [`phase_4_video_shell_plan.md`](phase_4_video_shell_plan.md).
>
> **Letzter Stand:** 2026-07-18.

---

## Wo wir stehen

**Phase 4 — Fahr-Screen-Shell + Vollbild-Video.** App-Code **fertig & grün** (P4A.2–P4A.12):
`assembleDebug` ✅, `testDebugUnitTest` ✅ **42/0** (neu: `MjpegParserTest` 7, `VideoLogicTest` 9).
**Offen:** **P4A.1-Live** (multipart-Format am echten Stream) + **P4A.13** = End-to-End-Sim-Test
(User + Handy) — beide brauchen die laufende ROS-Seite + ein Gerät.

Was die App jetzt kann: eigener **Fahr-Screen** (Querformat), erreichbar per **„Fahren →"** vom
Lifecycle-Screen (aktiv wenn verbunden), zurück per Back/Geste. **Center-View-Toggle** (Nichts=
Default / Kamera / 3D=reserviert-disabled). **Kamera = Vollbild-MJPEG** (`ContentScale.Crop`,
center-crop) über einen **eigenen OkHttp-Decoder** (`multipart/x-mixed-replace`), **gated** auf
laufenden Stack (`StackState.RUNNING`, Contract §5) + Betreten-Poll. **Alle Overlay-Slots** aus
§5 positioniert (leer/Label); `config`/`alerts`/`show` öffnen leere Panels; `📷 cam`-Toggle;
`E-STOP`-Position reserviert (disabled → P6). Der 30-Hz-`/joy`-Pfad läuft über den Screen-Wechsel
**unverändert** weiter (kein `onPause`).

## Die zwei „Test"-Ebenen

1. **App-Logik ohne Hardware:** `VideoLogicTest.kt` (`videoStreamUrl`, `toggleCam`, `shouldStream`,
   `extractBoundary`) + `MjpegParserTest.kt` (beide Framing-Formen, Split-Reads, Hint-Selbst-
   korrektur) → `./gradlew testDebugUnitTest`. Der OkHttp/Bitmap-Glue (`MjpegStream`) + Compose-
   Rendering sind bewusst NICHT unit-getestet (SDK/Netz) → Integration.
2. **Echtes Ausprobieren (P4A.1-Live + P4A.13):** braucht die **ROS-Video-Pipeline** (Gazebo-Cam →
   `web_video_server` :8080, kommt mit `bringup_start`) + das S22+. Die App *ist* der Client.

## Nächster Schritt: Live-Integration (P4A.1-Live + P4A.13) — Schritt für Schritt

**▶ ROS (hexapod_ws):** `always_on.launch.py` → (App) `bringup_start` → `stand_up`. Dann ist
`/camera/image_raw` da und `http://<Desktop-IP>:8080/stream?topic=/camera/image_raw&type=mjpeg`
zeigt das Gazebo-Bild. Desktop-IP notieren (`hostname -I`).

**▶ (optional, spart Rate-Arbeit) multipart-Kopf prüfen:**
`curl -sN -D - "http://localhost:8080/stream?topic=/camera/image_raw&type=mjpeg" -o - | head -c 1200 | cat -v`
→ zeigt Boundary-String + ob je Frame ein `Content-Length` kommt. Der Parser kann beides; das ist
nur die Bestätigung von P4A.1.

**▶ App (dieses Repo):** `./gradlew installDebug` aufs S22+ (Wireless ADB → `docs/build_and_deploy.md`).

**▶ Handy:** verbinden → **„Hexapod starten"** → **„Aufstehen"** → **„Fahren →"** → Center-Toggle
**Kamera** → Bild sollte **vollflächig** kommen (keine Balken). Prüfen: Toggle Nichts↔Kamera +
`📷 cam` an/aus laden/entladen den Stream; **Latenz „folgbar"** (~100–300 ms) beim Fahren (R1 +
Stick); `config`/`alerts`/`show` öffnen leere Panels; Back/Geste zurück zum Lifecycle-Screen.

## Resume-Prompt (copy-paste in die App-Session)

```
Wir sind in hexapod_app, Phase 4 (Fahr-Screen-Shell + Video). App-Code ist fertig und grün
(P4A.2–P4A.12, 42/0), offen sind P4A.1-Live (multipart-Format am echten Stream) + P4A.13
(End-to-End-Sim-Test). Kontext: docs/phase_4_video_shell_plan.md + interface_contract.md v0.8 (§5/§0).

Ich habe den Live-Test gemacht (ROS hoch: always_on -> bringup_start -> stand_up). Ergebnis:
- Video vollflächig im Fahr-Screen (center-crop, keine Balken): <ok / Fehler: …>
- Center-Toggle Nichts<->Kamera + 📷 cam an/aus: <…>
- Navigation Fahren<->Lifecycle, config/alerts/show-Panels: <…>
- Latenz „folgbar" beim Fahren: <…>
- (optional) curl-Kopf: Boundary=<…>, Content-Length pro Frame=<ja/nein>

Justiere falls nötig, hake P4A.1-Live + P4A.13 ab, dann Phase 4 komplett -> Planung Phase 5 (Overlay-Inhalte).
```

## Danach
- Stimmt alles → **P4A.1-Live + P4A.13 abhaken**, **Phase 4 komplett** → Planung **Phase 5**
  (Status-Publisher + Overlay-Inhalte + Dropdowns + 3D-Viz + Config/Alerts/Show-Inhalte;
  Contract §6 `[TBD-Phase 5]` festzurren in der ROS/Contract-Session).
- Contract-Nachzug ROS-Seite (User): `phase_4_..._progress.md` P4.7–P4.9 abhaken.

## Offene Altlasten (nicht Phase 4)
- **Phase-3-Option B** (P3A.13, latched Live-Push `/hexapod/bringup_running`): weiterhin offen —
  in Phase 4 bewusst nicht nötig (Kamera-Gate über das vorhandene Polling, [ADR-2]).
- **D-Pad-X Contract-Sync:** App sendet `-input.dpadX`; Contract seit v0.7 nachgezogen — kein
  App-Change nötig.
- **host + screen + Video-State überleben keinen Prozess-Tod** (Activity-Snapshot-State, nicht
  `rememberSaveable`): akzeptiert für v1 (Querformat gelockt → kaum Recreation). Bei Bedarf später
  in einen ViewModel/SavedState heben.

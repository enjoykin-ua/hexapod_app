# NEXT — Stand & nahtloser Wiedereinstieg

> **Rolling-Doc.** Wo wir stehen + wie es weitergeht. Überschreiben/aktualisieren beim
> Phasenwechsel. Der ausführliche Stand liegt in
> [`phase_2_joy_client_plan.md`](phase_2_joy_client_plan.md) §7.
>
> **Letzter Stand:** 2026-07-16 (Ende Session).

---

## Wo wir stehen

**Phase 2 — `/joy`-WebSocket-Client.** App-Code **fertig & grün** (P2A.1–P2A.9):
`assembleDebug` ✅, `testDebugUnitTest` ✅ **16/0**, keine Warnungen. **Offen ist nur
P2A.10 = der Integrationstest** am echten Gerät (Handy → Desktop-rosbridge → Sim).

Was die App jetzt kann: Kishi roh lesen → auf PS4-`/joy` normalisieren (Contract v0.3 §1:
Sticks negiert, Trigger `1−2t`, positionsbasierte Buttons, 8 Achsen/15 Buttons) → per OkHttp-
WebSocket an rosbridge `/joy` **@ ~30 Hz** publishen. Connect-Leiste + „/joy ausgehend"-Debug im
Screen. Publish an den Activity-Lifecycle gekoppelt (Pause → Stopp → NF1-Failsafe).

## Die zwei „Test"-Ebenen

1. **App-Logik ohne Hardware:** `app/src/test/java/io/github/enjoykinua/hexapod/JoyMapperTest.kt`
   → `./gradlew testDebugUnitTest`. Bestätigt die Mapping-Logik jederzeit, ohne Roboter.
2. **Echtes Ausprobieren (P2A.10):** geht **nur mit ROS2 zuerst** — rosbridge + Sim müssen
   laufen, dann verbindet die App. Die App *ist* der Client; es gibt keine „App-allein"-Testdatei.

## Morgen: Integrationstest (P2A.10) — Schritt für Schritt

**▶ ROS (hexapod_ws):**
1. Prüfen, ob die **ROS-Seite von Phase 2** steht (rosbridge-Launch + `joy_source:=app`). Falls
   nicht → zuerst in der ROS-Session bauen (`phase_2_control_baseline_plan.md`).
2. Sim-Walk + `ros2 launch hexapod_bringup app_teleop.launch.py` (rosbridge :9090).
3. **Vorabtest ohne Handy** (beweist die Kette): `python3 tools/joy_ws_test_client.py --host 127.0.0.1`
   → Sim muss fahren. Erst dann lohnt der App-Test.
4. Desktop-IP notieren: `hostname -I`.

**▶ App (dieses Repo):** `./gradlew installDebug` aufs S22+ (Wireless ADB → `docs/build_and_deploy.md`),
oder Run in Android Studio.

**▶ Handy:** App öffnen → **Desktop-IP** ins Host-Feld → **Verbinden** → **R1 halten + linker
Stick vor** → Sim fährt. Dann **Vorzeichen-Check** via `ros2 topic echo /joy` gegen die
„/joy ausgehend"-Anzeige im Screen (hoch=vorwärts, links=links, Trigger idle=+1, D-Pad-Richtung).

## Resume-Prompt (copy-paste in die App-Session)

```
Wir sind in hexapod_app, Phase 2 (/joy-WebSocket-Client). App-Code ist fertig und grün
(P2A.1–P2A.9), offen ist nur P2A.10 (Integration). Kontext: docs/phase_2_joy_client_plan.md §7
+ interface_contract.md v0.3 §1.

Ich habe den Integrationstest gemacht. Ergebnis:
- Verbindung/Fahren: <ok / Fehler: …>
- Vorzeichen (ros2 topic echo /joy): Stick hoch=<vorwärts?>, links=<links?>, rechter Stick=<…>,
  D-Pad-Richtung=<…>, Trigger idle=+1=<ja?>

Justiere Transform bzw. sign_* falls nötig, hake dann P2A.10 ab und skizziere Phase 3.
```

## Danach
- Stimmt alles → **Phase 2 komplett**, Planung **Phase 3** (Bringup-/Shutdown-Lifecycle).
- Kippt ein Vorzeichen → `echo`-Beobachtung liefern; Korrektur ist ein Einzeiler im `JoyMapper`
  (oder `sign_*` in `ps4_usb.yaml`).

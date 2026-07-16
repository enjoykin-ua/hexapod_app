# NEXT — Stand & nahtloser Wiedereinstieg

> **Rolling-Doc.** Wo wir stehen + wie es weitergeht. Überschreiben/aktualisieren beim
> Phasenwechsel. Der ausführliche Stand liegt in
> [`phase_3_lifecycle_plan.md`](phase_3_lifecycle_plan.md).
>
> **Letzter Stand:** 2026-07-16.

---

## Wo wir stehen

**Phase 3 — Connect-/Lifecycle-Screen (Option A).** App-Code **fertig & grün**
(P3A.1–P3A.11): `assembleDebug` ✅, `testDebugUnitTest` ✅ **26/0**. **Offen:** P3A.12 =
Sim-Integrationstest (User), danach P3A.13 = Option B (Live-Push, Nachzug).

Was die App jetzt kann: rosbridge-`call_service` (mit id-Korrelation + 8-s-Timeout) → die
Launcher-Services **Starten/Stoppen/Aufstehen/Hinsetzen/Pi-ausschalten** (Contract v0.6 §2a) +
**Status-Polling** (`/hexapod_bringup_status`, Option A). UI: **Lifecycle-Karte** (Stack-Status
+ 5 Aktions-Buttons + „Status aktualisieren") + **2-stufiger Shutdown-Dialog**; der Phase-2-
`/joy`-Debug liegt als **einklappbarer** Dev-Bereich darunter (default zu). Der 30-Hz-`/joy`-Pfad
läuft unverändert.

## Die zwei „Test"-Ebenen

1. **App-Logik ohne Hardware:** `LifecycleLogicTest.kt` (`interpretStatus`, `buttonEnablement`)
   + `JoyMapperTest.kt` → `./gradlew testDebugUnitTest`. Der org.json-`call_service`-Rahmen und
   der WebSocket-Transport sind bewusst NICHT unit-getestet (SDK-Stub) → Integration.
2. **Echtes Ausprobieren (P3A.12):** geht nur mit der **ROS-Always-On-Schicht** (rosbridge +
   `shutdown_supervisor` + `bringup_launcher`). Die App *ist* der Client.

## Nächster Schritt: Integrationstest (P3A.12) — Schritt für Schritt

**▶ ROS (hexapod_ws):**
1. Prüfen, ob die **ROS-Seite von Phase 3** steht (`always_on.launch.py` + `bringup_launcher`).
   Falls nicht → zuerst in der ROS-Session (`phase_3_lifecycle_plan.md`).
2. Always-On starten: `ros2 launch hexapod_bringup always_on.launch.py` (rosbridge :9090).
3. Desktop-IP notieren: `hostname -I`.

**▶ App (dieses Repo):** `./gradlew installDebug` aufs S22+ (Wireless ADB → `docs/build_and_deploy.md`).

**▶ Handy:** App öffnen → **Desktop-IP** ins Host-Feld → **Verbinden** (Status sollte gepollt
werden) → **„Hexapod starten"** → Roboter kommt auf dem Bauch → **„Aufstehen"** → **R1 halten +
linker Stick** fahren → **„Hinsetzen"** → **„Stoppen"** → **„Pi ausschalten"** (2-stufiger Dialog)
→ **Dry-Run** (Desktop bleibt an, Meldung „would shut down"). Prüfen: Buttons aktiv/inaktiv je
Stack-State, Status-Zeile stimmig, `notice` zeigt die Service-`message`.

## Resume-Prompt (copy-paste in die App-Session)

```
Wir sind in hexapod_app, Phase 3 (Connect-/Lifecycle-Screen). App-Code Option A ist fertig und
grün (P3A.1–P3A.11), offen ist P3A.12 (Sim-Integration) + P3A.13 (Option B Live-Push, Nachzug).
Kontext: docs/phase_3_lifecycle_plan.md + interface_contract.md v0.6 (§2a, §3, §7.4).

Ich habe den Integrationstest gemacht. Ergebnis:
- Verbinden/Status-Polling: <ok / Fehler: …>
- Starten/Aufstehen/Fahren/Hinsetzen/Stoppen: <…>
- Pi ausschalten (Dry-Run, Desktop bleibt an): <…>

Justiere falls nötig, hake P3A.12 ab, dann Option B (P3A.13) planen/bauen.
```

## Danach
- Stimmt alles → **P3A.12 abhaken**, dann **P3A.13 = Option B** (latched-Subscribe
  `/hexapod/bringup_running`, expliziter `qos`-Frame aus Contract §7.4) als Live-Push-Nachzug.
- Danach **Phase 3 komplett** → Planung **Phase 4** (Video).

## Offene Altlasten (nicht Phase 3)
- **D-Pad-X Contract-Sync:** App sendet `-input.dpadX` (negiert, Sim-Test bestätigt), Contract
  v0.6 §1 listet noch pass-through. User zieht den Contract nach (v0.7) — kein App-Change nötig.

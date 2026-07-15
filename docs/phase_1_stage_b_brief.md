# Phase 1 · Stufe B — Kishi-Gamepad-Hello-World (App-seitige Aufgabe)

> **Was jetzt zu bauen ist.** Minimale native App, die den Razer Kishi V2 über
> `InputManager` liest und je Achse/Button **Live-Wert + Android-Konstante** anzeigt.
> **Kein ROS, kein Netz, keine Steuerung** — reine Input-Validierung.
>
> **Kanonischer Plan (read-only, Single Source):**
> `~/hexapod_ws/project_finalization/app_control_requirements/phase_1_controller_validation_plan.md` (§1 Stufe B)
> **Done-Vertrag:** `.../phase_1_controller_validation_progress.md` (Bullets P1.2–P1.8)
> **Ziel-Layout der Normalisierung:** `~/hexapod_ws/src/hexapod_teleop/config/ps4_usb.yaml`

---

> **Stand (Umsetzung):** App gebaut, `assembleDebug` + Unit-Tests grün. **Code-Teil erledigt**,
> Geräte-Verifikation (P1.2–P1.7) offen. Details:
> [`phase_1_stage_b_implementation.md`](phase_1_stage_b_implementation.md) · Durchführung:
> [`testing_guide.md`](testing_guide.md).

## 0. Warum diese Stufe

Die gesamte Architektur steht/fällt damit, dass Android den Kishi als **Standard-HID-Gamepad**
sieht → dann trägt `InputManager` und später die `/joy`-Emulation ([D3]). Stufe A
(Web-Vorcheck) hat das auf OS-Ebene schon bestätigt (Vendor `0x1532`/Product `0x071b`
erkannt, Sticks ±1 analog, L2/R2 analog 0..1). Stufe B misst jetzt die **echten
Android-Konstanten** — das **Deliverable** für die Contract-Index-Tabelle (Phase 2).

> **Wichtig:** Der Web-Check gab `mapping: n/a` → die Chrome-B-Nummern **nicht** übernehmen.
> Die App **misst** `AXIS_*` / `KEYCODE_*` selbst.

## 1. Logik-Skizze

**Eine Activity (`MainActivity`, `ComponentActivity` + Compose):**

1. **Gamepad auflisten** — beim Start + auf Connect/Disconnect:
   - `InputManager` (`getSystemService`) → `inputDeviceIds` → je `InputDevice` prüfen, ob
     `sources` `SOURCE_GAMEPAD` bzw. `SOURCE_JOYSTICK` enthält.
   - Anzeige: „Kishi gefunden: ja/nein" + `device.name` + `vendorId`/`productId`
     (Erwartung 0x1532 / 0x071b).
   - Optional: `InputManager.InputDeviceListener` für Hot-Plug.

2. **Achsen lesen** — `onGenericMotionEvent(MotionEvent)` auf **Activity-Ebene** überschreiben
   (funktioniert mit Compose; Werte in Compose-`State` schieben → UI rekomponiert):
   - Für jede relevante Achse `event.getAxisValue(AXIS_…)`:
     `AXIS_X`, `AXIS_Y` (li. Stick), `AXIS_Z`, `AXIS_RZ` (re. Stick — **vermutet, messen!**),
     `AXIS_HAT_X`, `AXIS_HAT_Y` (D-Pad als Achse), `AXIS_LTRIGGER`, `AXIS_RTRIGGER`,
     ggf. `AXIS_BRAKE`/`AXIS_GAS` (manche Firmwares).
   - Robust: über `device.motionRanges` iterieren und **alle** vorhandenen Achsen dynamisch
     anzeigen (statt eine feste Liste anzunehmen) — so entgeht keine.

3. **Buttons lesen** — `onKeyDown/onKeyUp(KeyEvent)` überschreiben:
   - `KEYCODE_BUTTON_A/B/X/Y`, `KEYCODE_BUTTON_L1/R1/L2/R2`, `KEYCODE_BUTTON_THUMBL/THUMBR`
     (L3/R3), `KEYCODE_BUTTON_START/SELECT`, D-Pad als Keys
     (`KEYCODE_DPAD_UP/DOWN/LEFT/RIGHT`, falls nicht als HAT-Achse).
   - Jeden empfangenen `keyCode` **mit `KeyEvent.keyCodeToString()`** beschriften — so werden
     auch unerwartete Codes sichtbar.

4. **UI (Compose, simpel):** je Achse eine Zeile „Konstante : Wert (Balken)", je Button ein
   Indikator „Konstante : gedrückt". Keine Schönheit — nur Sichtbarkeit + die Konstante daneben.

**Design-Begründungen:**
- *Activity-Level-Override statt Compose-Focus-Handling:* Gamepad-Events kommen zuverlässig an
  der Activity an, unabhängig vom Compose-Fokus — einfachster robuster Weg fürs Hello-World.
- *Dynamisch über `motionRanges`/`keyCodeToString` statt feste Liste:* wir **wissen** die
  Kishi-Belegung nicht (mapping n/a) — dynamisch = nichts wird übersehen.

## 2. Tests-Liste (mit Begründung)

| Test | Prüft | Warum |
|---|---|---|
| App baut + startet auf S22+ (Wireless ADB) | Toolchain/Deploy | Grundlage |
| App listet den Kishi als Gamepad-`InputDevice` (Name + Vendor/Product) | native Erkennung | `InputManager`-Weg trägt |
| beide Sticks → Live-Achswerte (~0 zentriert, ~±1 voll) | Analog-Achsen | Fahren/Drehen später |
| alle Buttons + D-Pad + L1/R1 + L2/R2 lösen sichtbar aus, mit Konstante | Vollständigkeit | Dead-Man/Sit-Stand/Stance/Gait brauchen sie |
| **Roh-Index-Tabelle** (physisch → `AXIS_*`/`KEYCODE_*`) gefüllt | Phase-2-Input | die `/joy`-Normalisierung braucht sie |

**Bewusst NICHT:** kein rosbridge/`/joy`/Netz/Steuerung; keine Video-/Touch-UI; kein
Controller-Profil-System (nur Roh-Indizes notieren); keine Latenzmessung (Phase 2).

## 3. Done-Vertrag (spiegelt hexapod_ws `phase_1_..._progress.md` P1.2–P1.8)

```
- [ ] P1.2 Projekt baut + startet auf dem S22+ (Wireless ADB verifiziert)
- [ ] P1.3 native App listet den Kishi als Gamepad-InputDevice (Name + Vendor/Product)
- [ ] P1.4 beide Analog-Sticks liefern Live-Achswerte (zentriert ~0, Voll ~±1)
- [ ] P1.5 alle Buttons + D-Pad + L1/R1 + L2/R2 lösen sichtbar aus (mit Konstante)
- [ ] P1.6 Roh-Index-Tabelle (Achse/Button -> Android-Konstante) gefüllt
- [ ] P1.7 Trigger analog/digital bestätigt (Stufe A: analog); Passform mit Hülle geprüft
- [ ] P1.8 kurze Self-Review + Eignungs-Fazit
```

## 4. Deliverable — Roh-Index-Tabelle (an die ROS/Contract-Session zurückgeben)

| Physisch (Kishi) | Android-Konstante | Wertebereich (gemessen) |
|---|---|---|
| Linker Stick X | `AXIS_?` | |
| Linker Stick Y | `AXIS_?` | |
| Rechter Stick X | `AXIS_?` | |
| Rechter Stick Y | `AXIS_?` | |
| D-Pad X | `AXIS_HAT_X` oder Keys? | |
| D-Pad Y | `AXIS_HAT_Y` oder Keys? | |
| A / B / X / Y | `KEYCODE_BUTTON_?` | |
| L1 / R1 | `KEYCODE_BUTTON_?` | |
| L2 / R2 | `KEYCODE_?` bzw. `AXIS_?TRIGGER` | 0..1 (analog, Stufe A) |
| L3 / R3 (Stick-Klick) | `KEYCODE_BUTTON_THUMB?` | |
| Start / Select / Menü | `KEYCODE_BUTTON_?` | |

> Diese Tabelle wandert (durch die ROS-Session) in `interface_contract.md §1` als Basis der
> Kishi→PS4-`/joy`-Abbildung (Phase 2). **Der Contract wird dort festgezurrt, nicht hier.**

## 5. Nach Umsetzung

- Progress-Bullets abhaken, Self-Review-Tabelle, Eignungs-Fazit (Kishi tauglich ja/nein).
- Roh-Index-Tabelle + Fazit an die ROS/Contract-Session (User) übergeben.
- Erst danach ist Phase 1 vollständig und Phase 2 (Steuer-Grundstrecke) plan-reif.

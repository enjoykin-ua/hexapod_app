# Phase-1-Deliverable — Kishi-V2 → PS4-`/joy`-Mapping (Übergabe an ROS/Contract-Session)

> **Zweck:** Ergebnis von Phase 1 (Controller-Validierung), fertig zum Eintragen in
> `~/hexapod_ws/project_finalization/app_control_requirements/interface_contract.md §1`
> (dort steht heute `[TBD-Phase 2]`). **Dieser App-Repo-File ist nur der Record/die Vorlage** —
> festgezurrt wird der Contract auf der ROS-Seite ([D9]/[D10]): Block eintragen → Version
> **v0.1 → v0.2** + Changelog-Zeile.
>
> **Quellen:** Roh-Indizes gemessen am S22+ (2026-07-15, siehe `phase_1_stage_b_implementation.md §6a`);
> PS4-Ziel-Layout aus `~/hexapod_ws/src/hexapod_teleop/config/ps4_usb.yaml`.

---

## Achsen — `axes[]` (PS4-USB-Reihenfolge)

| PS4-Index | PS4-Rolle (`ps4_usb.yaml`) | Kishi-Quelle | App-Transform |
|---|---|---|---|
| `axes[0]` | `axis_lx` (linker Stick X) | `AXIS_X` (links=−1) | **`−AXIS_X`** |
| `axes[1]` | `axis_ly` (linker Stick Y) | `AXIS_Y` (hoch=−1) | **`−AXIS_Y`** |
| `axes[2]` | `axis_l2` (L2, idle=+1 / gedrückt=−1) | `AXIS_LTRIGGER` (0..1) | **`1 − 2·AXIS_LTRIGGER`** |
| `axes[3]` | `axis_rx` (rechter Stick X) | `AXIS_Z` (links=−1) | **`−AXIS_Z`** |
| `axes[4]` | `axis_ry` (rechter Stick Y, Show) | `AXIS_RZ` (hoch=−1) | **`−AXIS_RZ`** |
| `axes[5]` | `axis_r2` (R2) | `AXIS_RTRIGGER` (0..1) | **`1 − 2·AXIS_RTRIGGER`** |
| `axes[6]` | `axis_dpad_x` (D-Pad ←/→) | `AXIS_HAT_X` (−1/0/+1) | `AXIS_HAT_X` — Vorzeichen in Phase 2 prüfen |
| `axes[7]` | `axis_dpad_y` (D-Pad ↑/↓) | `AXIS_HAT_Y` (−1/0/+1) | `AXIS_HAT_Y` — Vorzeichen in Phase 2 prüfen |

**Wichtige Details:**
- **Sticks negiert:** Kishi liefert hoch=−1/links=−1, PS4-`/joy` erwartet hoch=+1/links=+1 → alle vier
  Stick-Achsen mit `−1` multiplizieren. Endgültig gegen `ros2 topic echo /joy` verifizieren;
  Fallback bleiben die `sign_*`-Params in `ps4_usb.yaml`.
- **Trigger-Umrechnung + Idle:** `AXIS_LTRIGGER/RTRIGGER` liefern 0..1 (idle 0), PS4 erwartet
  idle=+1 / voll=−1. Formel `1 − 2·t` **jeden Frame** anwenden (auch bei t=0 → +1), sonst steht L2/R2
  beim Start fälschlich auf „gedrückt". Kishi meldet die Trigger doppelt (`AXIS_BRAKE`/`AXIS_GAS`) →
  ignorieren, nur `LTRIGGER`/`RTRIGGER` verwenden.
- **Keine App-seitige Deadzone** — `joy_to_twist` filtert (`deadzone: 0.10`), App publisht roh (D3).

## Buttons — `buttons[]` (PS4-USB-Reihenfolge, **positionsbasiert**)

| PS4-Index | PS4-Taste | Roboter-Funktion (`joy_to_twist`) | Kishi-Quelle (Position) |
|---|---|---|---|
| `buttons[0]` | Cross | Show-Pose-Toggle (`button_cross`) | `KEYCODE_BUTTON_A` (unten) |
| `buttons[1]` | Circle | Shutdown lang (`button_circle`) | `KEYCODE_BUTTON_B` (rechts) |
| `buttons[2]` | Triangle | Sit/Stand-Toggle (`button_triangle`) | `KEYCODE_BUTTON_Y` (oben) |
| `buttons[3]` | Square | — (ungenutzt) | `KEYCODE_BUTTON_X` (links) |
| `buttons[4]` | L1 | Slow/präzise (`slow_button`) | `KEYCODE_BUTTON_L1` |
| `buttons[5]` | R1 | **Dead-Man** (`deadman_button`) | `KEYCODE_BUTTON_R1` |
| `buttons[6]` | L2 (digital) | — (Höhe läuft über `axes[2]`) | `KEYCODE_BUTTON_L2` (optional) |
| `buttons[7]` | R2 (digital) | — (läuft über `axes[5]`) | `KEYCODE_BUTTON_R2` (optional) |
| `buttons[8]` | Share | — | `KEYCODE_BUTTON_SELECT` („3 Punkte" links) |
| `buttons[9]` | Options | — | `KEYCODE_BUTTON_START` („3 Striche" rechts) |
| `buttons[10]` | PS/Guide | — | `KEYCODE_BUTTON_MODE` (Kreis-mit-Pfeil) |
| `buttons[11]` | L3 | — | `KEYCODE_BUTTON_THUMBL` (linker Stick-Klick) |
| `buttons[12]` | R3 | — | `KEYCODE_BUTTON_THUMBR` (rechter Stick-Klick) |
| `buttons[13]` | *(Kishi-Extra)* | — | `KEYCODE_BUTTON_C` = **L4** |
| `buttons[14]` | *(Kishi-Extra)* | — | `KEYCODE_BUTTON_Z` = **R4** |

**Wichtige Details:**
- **Positionsbasiert** (nicht labelbasiert): Kishi ist Xbox-beschriftet (A unten, B rechts, X links,
  Y oben); die Zuordnung folgt der **physischen Position**, damit „oben drücken = Sit/Stand" am
  selben Knopf sitzt wie beim PS4 (Triangle oben). Daher Kishi-**Y → `buttons[2]`** (Triangle) und
  Kishi-**A → `buttons[0]`** (Cross).
- **Array-Länge:** volle Länge emittieren (**8 Achsen, 15 Buttons** = 13 PS4 + L4/R4), alle Slots
  belegt. `joy_to_twist` liest 0/1/2/4/5 → die müssen belegt sein.
- **Bonus-Buttons L4/R4** = `KEYCODE_BUTTON_C` / `KEYCODE_BUTTON_Z` (HID, entgegen Stufe-A-Annahme)
  → im Contract als `buttons[13]`/`[14]` gesendet, von `joy_to_twist` (noch) unkonsumiert,
  ROS-seitig jederzeit bindbar ohne App-Änderung.
- **Screenshot-Taste** = Android-System-Taste, in der App nicht sichtbar → out of scope.

## Offene Punkte (Phase 2, per `ros2 topic echo /joy`)

1. Stick-Vorzeichen final bestätigen (Erwartung: alle vier negiert → hoch=vorwärts, links=links).
2. D-Pad-Vorzeichen (`HAT_X/Y`) bestätigen; ggf. `sign_dpad_x/y` justieren.
3. Trigger idle=+1 verifizieren (kein Fehl-Auslösen von Stance beim Start).

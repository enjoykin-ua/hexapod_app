# Phase 1 · Stufe B — Implementierung & Stand

> **Was gebaut wurde** für den Kishi-Gamepad-Hello-World (reine Input-Validierung, **kein ROS,
> kein Netz, keine Steuerung**). Aufgabe/Vertrag: [`phase_1_stage_b_brief.md`](phase_1_stage_b_brief.md).
> Architektur-Einordnung: [`architecture.md`](architecture.md) §4.1.

---

## 1. Ergebnis

- `./gradlew assembleDebug` → **grün**, APK: `app/build/outputs/apk/debug/app-debug.apk`.
- `./gradlew testDebugUnitTest` → **grün** (`GamepadFormatTest`: 6 Tests, 0 skipped, 0 failures).
- Geräte-Verifikation am S22+ **abgeschlossen** (User, 2026-07-15): Kishi gelistet, alle
  Achsen/Buttons live vermessen **inkl. Vorzeichen**, Roh-Index-Tabelle gefüllt (§6a),
  Passform mit Hülle ok, L3/R3 (Stick-Klick) vorhanden. **Phase 1 fertig — Kishi tauglich: ja.**

## 2. Dateien

| Datei | Rolle |
|---|---|
| `app/src/main/java/.../MainActivity.kt` | `InputManager`-Erkennung + Hot-Plug-Listener; `dispatchGenericMotionEvent`/`dispatchKeyEvent` greifen Achsen/Buttons ab; Querformat + keep-screen-on |
| `app/src/main/java/.../GamepadState.kt` | Beobachtbarer State-Halter (`mutableStateOf`/`mutableStateMapOf`) |
| `app/src/main/java/.../GamepadReaderScreen.kt` | Compose-UI (Header, Achsen-Balken, Button-Liste, Highlight) |
| `app/src/main/java/.../GamepadFormat.kt` | Reine Helfer: `hex4`, `normalize`, `matchesKishi` + Kishi-IDs |
| `app/src/test/java/.../GamepadFormatTest.kt` | Unit-Tests der reinen Helfer |
| `app/src/main/AndroidManifest.xml` | `android:screenOrientation="landscape"` ergänzt |

## 3. Funktionsweise (kurz)

1. **Erkennung:** `InputManager` listet `inputDeviceIds`; erstes Gerät mit `SOURCE_GAMEPAD`/
   `SOURCE_JOYSTICK` wird übernommen (Name, Vendor/Product hex, Abgleich `0x1532`/`0x071b`).
   `InputDeviceListener` (an `onResume`/`onPause` gekoppelt) fängt Hot-Plug ab.
2. **Achsen:** In `dispatchGenericMotionEvent` werden **alle** Achsen des Geräts aus
   `device.motionRanges` gelesen (`getAxisValue` + `min`/`max`) — keine feste Liste, nichts wird
   übersehen. Label je Achse via `MotionEvent.axisToString`.
3. **Buttons:** In `dispatchKeyEvent` werden Gamepad-Tasten (Source Gamepad/Joystick bzw.
   `KeyEvent.isGamepadButton`) auf gedrückt/frei gesetzt; Label via `KeyEvent.keyCodeToString`.
   Ein Button erscheint, sobald er einmal gedrückt wurde (auch unerwartete Codes).
4. **Anzeige:** `GamepadState` (Compose-Snapshot-State) → jede Änderung rekomponiert die UI.
   Aktive Zeilen (Achse ausgelenkt / Button gedrückt) werden hervorgehoben, damit beim „eine
   Eingabe nach der anderen"-Vorgehen eindeutig ist, welche Konstante reagiert.

## 4. Design-Entscheidungen (ADRs, mit verworfenen Alternativen)

**ADR-B1 — Events auf `dispatch*`-Ebene der Activity.**
Gewählt: `dispatchGenericMotionEvent` / `dispatchKeyEvent` überschreiben.
Verworfen: (a) `onGenericMotionEvent`/`onKeyDown` — funktioniert nur, wenn kein View das Event
vorher konsumiert; (b) Compose-Fokus-Handling (`onKeyEvent`-Modifier) — erfordert Fokus-Management
und ist für ein Hello-World fragil. `dispatch*` wird **garantiert vor der View-Zustellung**
aufgerufen → robust, unabhängig vom Compose-Fokus.

**ADR-B2 — Gamepad-Tasten konsumieren (`return true`).**
Gamepad-`KeyEvent`s werden geschluckt statt an `super` weitergereicht. Grund: `BUTTON_B` ist in
manchen Kontexten als „Zurück" gemappt (würde die App schließen), D-Pad-Keys verschieben sonst
den Fokus. **Nicht-Gamepad-Events** (Zurück, Home, Lautstärke) gehen normal an `super` → System-
Navigation bleibt intakt.

**ADR-B3 — Dynamische Erkennung statt fester Achsen-/Button-Liste.**
Über `motionRanges` + `axisToString`/`keyCodeToString`. Grund: Die Kishi-Belegung ist die
**Unbekannte, die wir messen** (Web-Check gab `mapping: n/a`; rechter Stick `AXIS_Z/RZ` nur
vermutet). Fest verdrahten würde genau die Messung verfälschen. Deckt zugleich D-Pad-als-HAT
**und** D-Pad-als-Keys sowie Trigger-als-Achse **und** -als-Button ab.

**ADR-B4 — Schlichter State-Halter statt `ViewModel`.**
Für ein Hello-World ohne Netz/Persistenz genügt eine Klasse mit Compose-State; einfacher zu
erklären. Trade-off: State überlebt keinen Activity-Recreate — durch die Querformat-Sperre
(kein Rotation-Recreate) praktisch entschärft. `ViewModel`/`rememberSaveable` bei Bedarf später.

**ADR-B5 — Querformat gesperrt + Bildschirm wach.**
`screenOrientation="landscape"` (Handy steckt quer im Kishi; verhindert Rotation-Recreate) +
`FLAG_KEEP_SCREEN_ON` (kein Schlaf mitten im Test). **Kein** Immersive-/Kiosk-Modus → App
normal verlassbar.

## 5. Tests

**Automatisiert (ohne Gerät):** `GamepadFormatTest` prüft `hex4` (4-stellig, lowercase),
`normalize` (bipolar/unipolar/Clamping/leerer Bereich), `matchesKishi`.

**Bewusst NICHT getestet:** kein rosbridge/`/joy`/Netz/Steuerung; keine Video-/Touch-UI; kein
Profil-System; keine Latenzmessung; keine Instrumented-/UI-Tests in dieser Stufe (die
Geräte-Prüfung ist manuell über den Testing-Guide).

## 6. Done-Vertrag — Stand

| Bullet | Stand |
|---|---|
| Code komplett, `assembleDebug` grün, Unit-Tests grün, Self-Review | ✅ erledigt |
| P1.8 Self-Review + Eignungs-Fazit | ✅ Self-Review + **Fazit: Kishi V2 @ S22+ tauglich (ja)** |
| P1.2 startet auf S22+ (Wireless ADB) | ✅ am Gerät (User, 2026-07-15) |
| P1.3 Kishi als Gamepad gelistet (Name + Vendor/Product) | ✅ am Gerät |
| P1.4 beide Sticks liefern Live-Werte (~0 / ~±1) | ✅ analog −1..0..+1 (`AXIS_X/Y`, `AXIS_Z/RZ`) |
| P1.5 alle Buttons + D-Pad + L1/R1 + L2/R2 sichtbar (mit Konstante) | ✅ inkl. Bonus L4/R4 (§6a) |
| P1.6 Roh-Index-Tabelle gefüllt | ✅ §6a |
| P1.7 Trigger analog/digital + Passform mit Hülle | ✅ Trigger analog 0..1; Passform mit Hülle ok; L3/R3 (THUMBL/THUMBR) vorhanden |

## 6a. Roh-Index-Tabelle (P1.6 — gemessen am S22+, 2026-07-15)

> **Deliverable an die ROS/Contract-Session.** Gemessene Android-Konstanten + vorgeschlagenes
> PS4-Ziel (aus `ps4_usb.yaml`). **Die PS4-Zielspalte ist ein Vorschlag** — festgezurrt wird
> sie in `interface_contract.md §1` (`[TBD-Phase 2]`), nicht hier ([D10]).

| Physisch (Kishi) | Android-Konstante (gemessen) | Bereich | Art | PS4-Ziel (Vorschlag) |
|---|---|---|---|---|
| Linker Stick X | `AXIS_X` | −1..0..+1 (links=−1) | analog | `axes[0]` (axis_lx) — **negieren** |
| Linker Stick Y | `AXIS_Y` | −1..0..+1 (hoch=−1) | analog | `axes[1]` (axis_ly) — **negieren** |
| Rechter Stick X | `AXIS_Z` | −1..0..+1 (links=−1) | analog | `axes[3]` (axis_rx) — **negieren** |
| Rechter Stick Y | `AXIS_RZ` | −1..0..+1 (hoch=−1) | analog | `axes[4]` (axis_ry) — **negieren** |
| D-Pad X (←/→) | `AXIS_HAT_X` | −1/0/+1 | digital (HAT) | `axes[6]` (axis_dpad_x) |
| D-Pad Y (↑/↓) | `AXIS_HAT_Y` | −1/0/+1 | digital (HAT) | `axes[7]` (axis_dpad_y) |
| A (unten) | `KEYCODE_BUTTON_A` | 0/1 | digital | `buttons[0]` (Cross, unten) |
| B (rechts) | `KEYCODE_BUTTON_B` | 0/1 | digital | `buttons[1]` (Circle, rechts) |
| Y (oben) | `KEYCODE_BUTTON_Y` | 0/1 | digital | `buttons[2]` (Triangle, oben) |
| X (links) | `KEYCODE_BUTTON_X` | 0/1 | digital | `buttons[3]` (Square, links) |
| L1 | `KEYCODE_BUTTON_L1` | 0/1 | digital | `buttons[4]` (slow) |
| R1 | `KEYCODE_BUTTON_R1` | 0/1 | digital | `buttons[5]` (Dead-Man) |
| L2 | `AXIS_LTRIGGER` (+ `AXIS_BRAKE` Dublette; `KEYCODE_BUTTON_L2` ab ~0.16) | 0..1 | analog+digital | `axes[2]` (axis_l2) — **0..1 → +1..−1 umrechnen** |
| R2 | `AXIS_RTRIGGER` (+ `AXIS_GAS` Dublette; `KEYCODE_BUTTON_R2` ab ~0.16) | 0..1 | analog+digital | `axes[5]` (axis_r2) — **0..1 → +1..−1 umrechnen** |
| L4 (Zusatz) | `KEYCODE_BUTTON_C` | 0/1 | digital | frei — **Bonus** (Stufe A hatte L4/R4 als Nicht-HID erwartet) |
| R4 (Zusatz) | `KEYCODE_BUTTON_Z` | 0/1 | digital | frei — **Bonus** |
| „3 Punkte" (links) | `KEYCODE_BUTTON_SELECT` | 0/1 | digital | `buttons[8]` (Share) |
| „3 Striche" (rechts) | `KEYCODE_BUTTON_START` | 0/1 | digital | `buttons[9]` (Options) |
| Kreis-mit-Pfeil (unten rechts) | `KEYCODE_BUTTON_MODE` | 0/1 | digital | `buttons[10]` (PS/Guide) |
| L3/R3 (Stick-Klick) | `KEYCODE_BUTTON_THUMBL` / `_THUMBR` | 0/1 | digital | `buttons[11]` / `buttons[12]` |
| Screenshot-Taste | nicht erkannt (Android-System-Taste) | — | — | — (out of scope, wie erwartet) |

**Befunde / Auffälligkeiten (an Contract-Session):**
1. **Face-Buttons Label ≠ Position → positionsbasiert (entschieden 2026-07-15).** Kishi ist
   Xbox-beschriftet (A unten, B rechts, X links, Y oben), PS4 ist `0=Cross unten, 1=Circle rechts,
   2=Triangle oben, 3=Square links`. Abbildung **positionsbasiert**: Kishi-A→`buttons[0]`,
   B→`buttons[1]`, Y→`buttons[2]`, X→`buttons[3]` — so sitzt die Roboter-Funktion am selben
   physischen Knopf wie beim PS4. In Phase 2 per `ros2 topic echo /joy` gegenprüfen.
2. **L4/R4 sind HID-Buttons** (`BUTTON_C`/`BUTTON_Z`) — anders als in Stufe A angenommen
   (dort „Nicht-HID → ignorieren"). Zwei **Bonus-Buttons** verfügbar; ob/wofür nutzen = Contract.
3. **Trigger doppelt gemeldet** (`LTRIGGER`+`BRAKE`, `RTRIGGER`+`GAS`) → in Phase 2 **eine**
   Achse wählen (`LTRIGGER`/`RTRIGGER`), die andere ignorieren. PS4 hat Trigger als Achse
   *idle=+1, gedrückt=−1*; Kishi liefert *0..1* → **Normalisierung** `axis_l2 = 1 − 2·ltrigger`.
4. **Stick-Vorzeichen — gemessen (2026-07-15):** Kishi hoch=−1, links=−1; PS4-`/joy` erwartet
   hoch=+1, links=+1 (`ps4_usb.yaml`). ⇒ App **negiert alle vier Stick-Achsen**
   (`axes[0/1/3/4] = −AXIS_X/Y/Z/RZ`). Endgültige Bestätigung Phase 2 per `ros2 topic echo /joy`;
   Fallback-Knopf bleiben die `sign_*`-Params. D-Pad-Vorzeichen (`HAT_X/Y`) analog in Phase 2
   fixieren (digital, unkritisch, per `sign_dpad_*` justierbar).
5. **L3/R3 vorhanden** (Stick-Klick = `THUMBL`/`THUMBR`) — beim ersten Durchgang übersehen, die
   Events sind da. Im Contract `buttons[11]`/`[12]`; `joy_to_twist` konsumiert sie (noch) nicht,
   ROS-seitig später bindbar.

## 7. Bekannte Grenzen (Self-Review 🟡/🟢)

- 🟡 **Achsen-Highlight** nutzt eine Ruhelage-Heuristik (bipolar=mittig, unipolar=0). Bei
  exotischer Firmware evtl. kosmetisch daneben; der **Rohwert bleibt korrekt**.
- 🟡 **Batched/historische MotionEvent-Samples** werden ignoriert (nur aktueller Wert) — für
  Validierung ausreichend.
- 🟢 **Gehaltener Button beim App-Pause** kann „stuck pressed" bleiben, bis er erneut gedrückt
  wird.
- 🟢 **State-Verlust bei Activity-Recreate** (kein `rememberSaveable`/`ViewModel`) — durch
  Querformat-Sperre entschärft.

## 8. Nächster Schritt

Geräte-Verifikation P1.2–P1.7 nach [`testing_guide.md`](testing_guide.md) durchgehen, dabei die
**Roh-Index-Tabelle** (Brief §4) füllen und samt Eignungs-Fazit an die ROS/Contract-Session
zurückgeben.

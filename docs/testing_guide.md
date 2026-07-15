# Test-Anleitung — App aufs S22+ bringen & Kishi vermessen

> **Zum Durchgehen gedacht.** Bringt die Stufe-B-App aufs Handy (Wireless ADB, Handy bleibt im
> Kishi) und misst systematisch die Kishi-Belegung, um die **Roh-Index-Tabelle** zu füllen.
> Mechanik-Referenz: [`build_and_deploy.md`](build_and_deploy.md). Was die App tut:
> [`phase_1_stage_b_implementation.md`](phase_1_stage_b_implementation.md).

**`adb`-Ort auf diesem Rechner:** `~/Android/Sdk/platform-tools/adb` (nicht im PATH).
In allen Befehlen unten diesen vollen Pfad nutzen — oder einmalig `export PATH="$HOME/Android/Sdk/platform-tools:$PATH"` setzen, dann reicht `adb`.

> **Hinweis:** Die `adb pair/connect/install`-Schritte (Teil B/C) kann **Claude für dich
> ausführen** — du musst dann nur die drei Werte aus Teil A liefern. Wer es selbst macht, nutzt
> die Befehle wie angegeben.

---

## Teil A — Handy vorbereiten (einmalig, nur du am Handy)

1. **Einstellungen → Telefoninfo → Softwareinformationen → 7× auf „Build-Nummer"** tippen.
   → „Sie sind jetzt Entwickler" erscheint.
2. **Einstellungen → Entwickleroptionen:**
   - **„USB-Debugging"** an.
   - **„Drahtloses Debugging"** an (Nachfrage im WLAN bestätigen).
3. **Handy + dieser Rechner im selben WLAN.** (Dein Handy-Hotspot geht auch — Hauptsache der
   Dev-Rechner ist im selben Netz.)
4. **Drahtloses Debugging** öffnen. Zwei Angaben liegen hier:
   - **Haupt-Screen:** „IP-Adresse und Port" → das ist die **Verbindungs**-`IP:PORT`.
   - **„Gerät mit Kopplungscode koppeln"** antippen → zeigt eine **Kopplungs**-`IP:PORT`
     **und** einen **6-stelligen Code**.

   > ⚠️ **Kopplungs-Port ≠ Verbindungs-Port.** Der Kopplungs-Port gilt nur einmalig; der
   > Verbindungs-Port steht auf dem Haupt-Screen.

**Für Claude bereithalten (3 Werte):**
`Kopplungs-IP:PORT` · `6-stelliger Code` · `Verbindungs-IP:PORT`.

## Teil B — Koppeln & verbinden

Der Kopplungs-Dialog muss beim `pair` **offen** sein (der Code ist kurzlebig).

```bash
cd ~/AndroidStudioProjects/hexapod_app
ADB=~/Android/Sdk/platform-tools/adb

$ADB pair    <KOPPLUNGS-IP>:<KOPPLUNGS-PORT> <6-STELLIGER-CODE>
$ADB connect <VERBINDUNGS-IP>:<VERBINDUNGS-PORT>
$ADB devices          # S22+ muss als "device" gelistet sein
```

Erwartete Ausgabe von `adb devices`: eine Zeile `…:<port>   device`.

## Teil C — App aufspielen

```bash
./gradlew installDebug          # baut (falls nötig) + installiert drahtlos
# Alternative, wenn die APK schon gebaut ist:
# $ADB install -r app/build/outputs/apk/debug/app-debug.apk
```

Dann am Handy die App **hexapod_app** öffnen. Sie startet im **Querformat** und bleibt wach.
Oben steht **„Gamepad gefunden: ja/nein"** samt Name + Vendor/Product.

**Erwartung mit eingestecktem Kishi:** „ja", Vendor/Product **`0x1532 / 0x071b` (✓ Kishi V2)**.
Steht „nein" oder falsche IDs → Teil E.

## Teil D — Kishi systematisch vermessen (Roh-Index-Tabelle füllen)

**Prinzip:** Die App zeigt nur die **Android-Konstante** (`AXIS_*`/`KEYCODE_*`) — welcher
physische Bedienpunkt das ist, ordnest **du** zu, indem du **genau einen** bewegst und abliest,
welche Zeile reagiert (aktive Zeile ist hervorgehoben). So entsteht die Zuordnung physisch → Konstante.

Geh diese Reihenfolge durch und trag jeweils die angezeigte Konstante + gemessenen Bereich ein:

1. **Linker Stick** ganz **rechts/links** → notiere Achse (X) + Wertebereich; dann **hoch/runter** → Achse (Y).
2. **Rechter Stick** rechts/links, hoch/runter → Achsen (Erwartung `AXIS_Z`/`AXIS_RZ` — **prüfen!**).
3. **D-Pad** L/R/U/D → erscheint als **HAT-Achse** (`AXIS_HAT_X/Y`) **oder** als **Keys**
   (`KEYCODE_DPAD_*`) — notiere, was tatsächlich kommt.
4. **A / B / X / Y** einzeln drücken → je `KEYCODE_BUTTON_?`.
5. **L1 / R1** → `KEYCODE_BUTTON_?`.
6. **L2 / R2** langsam durchdrücken → analog als `AXIS_?TRIGGER`/`AXIS_BRAKE|GAS` (Wert 0→1)
   und/oder digital als Button; **beides notieren** (bestätigt P1.7: analog).
7. **L3 / R3** (Stick klicken) → `KEYCODE_BUTTON_THUMB?`.
8. **Start / Select / Menü** → `KEYCODE_BUTTON_?`.

Sticks zentriert → Wert ~0; voll → ~±1. Nebenbei P1.4/P1.5 abhaken.

**Roh-Index-Tabelle (ausfüllen, dann an die ROS/Contract-Session):**

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
| L2 / R2 | `KEYCODE_?` bzw. `AXIS_?TRIGGER` | 0..1 (analog) |
| L3 / R3 | `KEYCODE_BUTTON_THUMB?` | |
| Start / Select / Menü | `KEYCODE_BUTTON_?` | |

Zum Schluss **Passform mit Schutzhülle** prüfen (P1.7) und ein **Eignungs-Fazit** notieren
(Kishi tauglich ja/nein).

## Teil E — Fehlersuche

| Symptom | Vorgehen |
|---|---|
| `adb devices` leer / offline | `$ADB connect <VERBINDUNGS-IP>:<PORT>` erneut. Hilft nichts: `$ADB kill-server && $ADB start-server`, dann neu `connect`. |
| `pair` schlägt fehl | Kopplungs-Dialog war zu / Code abgelaufen → Dialog neu öffnen, neue Kopplungs-`IP:PORT` + Code nehmen. |
| Nach WLAN-Wechsel weg | Ports ändern sich. `connect` mit aktueller `IP:PORT` vom Haupt-Screen wiederholen. |
| „Gamepad gefunden: nein" | Kishi richtig eingerastet? Handy kurz raus/rein. App aus/an (Hot-Plug wird zwar erkannt). |
| Falsche Vendor/Product-IDs | Kein Kishi bzw. anderes Gerät aktiv — nicht das erwartete `0x1532/0x071b`. |
| Live-Logs gewünscht | `$ADB logcat --pid=$($ADB shell pidof -s io.github.enjoykinua.hexapod)` (für Stufe B **nicht nötig**, alles steht am Screen). |

## Alternative — einmaliger Kabel-Weg (ohne Wireless ADB)

Reicht für **einen** Testdurchlauf, da die App alles am Screen zeigt (kein logcat nötig):

1. Handy **aus** dem Kishi, per USB-C-Kabel an den Rechner (am Handy „USB-Debugging zulassen").
2. `./gradlew installDebug`
3. Kabel ab, Handy **in** den Kishi, App öffnen → Teil D.

Nachteil: bei jeder Code-Änderung Handy erneut aus-/einbauen. Ab dem zweiten Durchlauf lohnt
Wireless ADB.

# Build & Deploy — hexapod_app

> Mechanische Anleitung: Umgebung, **Wireless ADB** (damit das Handy im Kishi bleiben kann),
> Gradle-Build, Install auf das S22+. App-Arbeitsweise: [`../CLAUDE.md`](../CLAUDE.md).

---

## 0. Der zentrale Deploy-Haken

Steckt das S22+ im **Kishi V2**, ist der **USB-C-Port belegt** → kein gleichzeitiges
ADB-Kabel zum Dev-Rechner. Zwei Wege:

- **Kabel:** Handy **ohne** Kishi anstecken → installieren → dann in den Kishi einsetzen und
  Gamepad testen. Umständlich pro Iteration.
- **Wireless ADB (empfohlen):** einmal koppeln, danach deployt Android Studio drahtlos über
  WLAN → Handy **bleibt im Kishi** während des Testens. Siehe §2.

---

## 1. Umgebung (einmalig, Dev-Rechner Ubuntu 24.04)

- **Android Studio** installiert (bringt SDK-Manager, Gradle, JBR-JDK, `adb` in
  `~/Android/Sdk/platform-tools/`).
- Über den **SDK-Manager**: Android SDK Platform **API 36** + Build-Tools + Platform-Tools.
- Für ADB-USB-Zugriff auf Samsung: udev-Regeln (`sudo apt install
  android-sdk-platform-tools-common`) — **User führt sudo-Installs aus**, nicht der Agent.
- `adb` im PATH prüfen: `adb version`. Falls nicht: `~/Android/Sdk/platform-tools` zum PATH
  hinzufügen.

## 2. S22+ vorbereiten + Wireless ADB koppeln

**Am Handy:**
1. Einstellungen → Telefoninfo → **7× auf „Build-Nummer"** → Entwickleroptionen an.
2. Entwickleroptionen → **USB-Debugging** an **und Drahtloses Debugging** an.

**Koppeln (Android 11+ / S22+ Android 15):**
3. Handy + Dev-Rechner im **selben WLAN**.
4. Handy: Drahtloses Debugging → **„Gerät mit Kopplungscode koppeln"** → zeigt
   `IP:PORT` + 6-stelligen Code.
5. Dev-Rechner:
   ```bash
   adb pair <handy-ip>:<pairing-port>      # dann den 6-stelligen Code eingeben
   adb connect <handy-ip>:<connect-port>   # der andere Port aus dem Drahtlos-Debugging-Screen
   adb devices                             # S22+ muss als "device" gelistet sein
   ```
   > Kopplungs-Port (einmalig) ≠ Verbindungs-Port (im Drahtlos-Debugging-Hauptscreen).
6. Danach erscheint das Gerät auch in Android Studios Geräte-Dropdown → „Run" deployt drahtlos.

> Bei WLAN-Wechsel/Reconnect ggf. `adb connect <ip>:<port>` erneut. Verbindung weg:
> `adb kill-server && adb start-server`, dann neu `connect`.

## 3. Build & Install

```bash
cd ~/AndroidStudioProjects/hexapod_app

./gradlew assembleDebug     # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug      # baut + installiert auf das verbundene (drahtlose) Gerät
# oder direkt:
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternativ komplett in Android Studio: Zielgerät wählen → **Run** (Shift+F10).

## 4. Testen / Diagnose

- Gamepad-Test: App starten, S22+ **im Kishi**, Eingaben durchgehen.
- Logs live: `adb logcat --pid=$(adb shell pidof -s io.github.enjoykinua.hexapod)`
  (oder in Android Studio → Logcat, nach Tag/Package filtern).
- Unit-Tests: `./gradlew test` · Instrumented (Gerät nötig): `./gradlew connectedAndroidTest`.

## 5. APK zum Verteilen (später, Phase 8)

Signierte Release-APK an ein **GitHub Release** hängen, damit man ohne Build-Toolchain
sideloaden kann. Kein Thema für Phase 1 — nur vorgemerkt.

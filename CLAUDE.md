# hexapod_app — Arbeitsanweisung für Claude (App-Seite)

> **Lies diese Datei zu Beginn jeder Session.** Sie ist das App-seitige Gegenstück zu
> `~/hexapod_ws/CLAUDE.md` (ROS-Seite). Dieses Repo enthält **nur die Android-App**; die
> Roboter-/ROS-Seite und die **verbindliche Schnittstelle** leben in `hexapod_ws`.
>
> **Single Source of Truth für die Schnittstelle:** `interface_contract.md` in
> `~/hexapod_ws/project_finalization/app_control_requirements/`. **Nie hierher kopieren —
> nur referenzieren** ([D10]). Ändert sich der Contract dort (Version + Changelog), zieht
> die App nach.

---

## 0. Zwei-Repo-/Zwei-Sessions-Modell (WARUM dieses Repo getrennt ist)

Das Gesamtprojekt hat zwei Codebasen mit getrennten Toolchains:

| Seite | Repo | Toolchain | Session |
|---|---|---|---|
| **ROS / Roboter** | `~/hexapod_ws` | colcon / ROS 2 Jazzy / Python+C++ | „Session 1" (ROS + Contract-Autor) |
| **App / Handy** | `~/AndroidStudioProjects/hexapod_app` (dieses Repo) | Android Studio / Gradle / Kotlin | „Session 2" (**du hier**) |

**Kopplung = der versionierte `interface_contract.md`, NICHT Live-Absprache** ([D9]). Einmal
Contract einigen → beide Seiten bauen unabhängig dagegen → der User integriert am Ende. Du
(diese Session) baust **ausschließlich die App** und **liest den Contract read-only**.

**Zugriff auf die ROS-Seite:** `~/hexapod_ws` sollte dieser Session als **read-only
Zusatz-Verzeichnis** beiliegen. Die relevanten Dateien (absolute Pfade):

- Master/Übersicht: `~/hexapod_ws/project_finalization/app_control_requirements/00_overview.md`
- **Schnittstelle (SoT):** `.../interface_contract.md`
- Anforderungen: `.../requirements.md`
- Architektur-Entscheidungen: `.../decisions.md`  (referenziert als `[D1]`…`[D10]`)
- Phasen-Pläne: `.../phase_<n>_..._plan.md` / `_progress.md` / `_test_commands.md`
- PS4-`/joy`-Layout (Ziel der Normalisierung): `~/hexapod_ws/src/hexapod_teleop/config/ps4_usb.yaml`

> Wenn `hexapod_ws` **nicht** erreichbar ist: nicht raten. Den User bitten, das Verzeichnis
> beizulegen oder die betreffende Contract-Stelle zu zitieren.

---

## 1. Projekt-Kontext (was diese App ist)

Ein **Handy im Gamepad-Halter (Razer Kishi V2, USB-C)** ersetzt die bisherige
PS4-Bluetooth-Steuerung des Hexapod. Vorbild: DJI-Drohnen-Controller — physische
Sticks/Tasten fahren den Roboter, der Handy-Screen zeigt Video-Vollbild + Overlay + Menüs.

**Die tragenden Architektur-Entscheidungen (Details in `decisions.md`):**

- **Native Kotlin-App**, keine Web-PWA ([D1]).
- **rosbridge als Naht:** die App spricht **WebSocket + JSON** mit `rosbridge_server` auf dem
  Pi (Port 9090). **Kein ROS auf dem Handy** ([D2]).
- **`/joy`-Reuse:** die App liest den Kishi, **normalisiert auf das PS4-Achsen-/Button-Layout**
  und publisht `sensor_msgs/Joy`. Damit läuft die komplette bestehende Fahr-Logik am Roboter
  (`joy_to_twist`: Deadzone, Scaling, Dead-Man, Sit/Stand, Stance, Gait, Tempo, Show)
  **unverändert** ([D3]). **Kein Steuer-Code am Roboter neu.**
- **Video getrennt** von rosbridge (eigener Stream-Server, Kanal 2) ([D2]).
- **Touch-Aktionen** (Parameter, Menüs, Buttons) gehen als **direkte rosbridge-Service-/
  Param-Calls**, NICHT über `/joy` ([D3]).

**Hardware-Kontext:**
- Controller: **Razer Kishi V2** (USB-C, HID-Gamepad; Vendor `0x1532`, Product `0x071b`).
  L2/R2 analog (0..1). Zusatztasten L4/R4 + Screenshot = Nicht-HID → ignorieren.
- Handy: **Samsung S22+** (Android 15), SIM-los. Ersatz: S26+.
- Netz: Handy-Hotspot (Variante A) zuerst, Pi-AP (Variante B) als Reserve ([D4]) — **kein
  Internet**.

---

## 2. Tech-Stack (verbindlich, nicht ohne Rücksprache ändern)

| Komponente | Wert | Quelle |
|---|---|---|
| Sprache | Kotlin | Scaffold |
| UI-Toolkit | **Jetpack Compose** (Material3) | `build.gradle.kts` |
| Activity | `ComponentActivity` (Compose via `setContent`) | `MainActivity.kt` |
| minSdk | **31** (Android 12) | `app/build.gradle.kts` |
| targetSdk / compileSdk | **36** | `app/build.gradle.kts` |
| Java-Kompat | 11 | `build.gradle.kts` |
| Build | Gradle, **Kotlin DSL** (`.kts`) + Version-Catalog (`gradle/libs.versions.toml`) | Scaffold |
| AGP / Kotlin / Compose BOM | 9.2.1 / 2.2.10 / 2026.02.01 | `libs.versions.toml` |
| Package / applicationId | `io.github.enjoykinua.hexapod` | Scaffold |

**Geplante Bibliotheken (erst bei Bedarf ab Phase 2 hinzufügen, nicht vorab):**
- **WebSocket → rosbridge:** `OkHttp` (WebSocket-Client, rosbridge-Protokoll `op: publish/
  subscribe/call_service`, roslibjs-artig).
- **Video (Phase 4):** `Media3`/ExoPlayer (MJPEG-Erstwurf, später RTSP/H.264 oder WebRTC).

> Neue Dependencies **immer über den Version-Catalog** (`libs.versions.toml`), nicht als
> nackte Koordinaten in `build.gradle.kts`.

---

## 3. Arbeitsweise (Pflicht — spiegelt hexapod_ws §4)

- **Phasenweise.** Die Roadmap (Phasen 1–8) steht in `~/hexapod_ws/.../00_overview.md §2`.
  Phasen werden **nicht übersprungen**. Aktuelle Phase erfragen/aus dem progress-File ablesen.
- **Pro Sub-Stage:** erst **Plan dokumentieren** → **User-Freigabe** → dann Implementierung →
  dann Tests → **dann kritischer Self-Review, BEVOR „fertig" gemeldet wird.** Plan-Inhalt
  (alle vier Pflicht): (1) Logik-Skizze/Pseudocode + Begründung je Design-Entscheidung,
  (2) Tests-Liste **und was bewusst NICHT getestet wird**, (3) Progress-Checkliste als
  Done-Vertrag, (4) offene Punkte für User-Review.
- **Kritischer Self-Review** ist Pflicht, nicht optional: Tabelle mit Punkten + Status
  (`OK` / `🔴 fixen` / `🟡 vormerken` / `🟢 später`). Fixe zuerst, dann Fertig-Meldung.
- **Design-Entscheidungen mit verworfenen Alternativen** festhalten (damit späteres Re-Design
  ohne Erinnerung geht) — App-seitige ADRs in `docs/` bzw. je Phasen-Doku.
- **Tests grün vor Commit.** Progress-File **pro erledigtem Bullet sofort** abhaken, nicht
  batchen.
- **Bei Unsicherheit nachfragen statt raten.** „Ich weiß es nicht" ist eine valide Antwort.
  Keine erfundenen APIs/Konstanten/Parameter.
- **Contract-Disziplin:** Braucht die App ein Topic/Service/Feld, das im `interface_contract.md`
  noch `[TBD-Phase N]` ist → **nicht selbst erfinden**, sondern als offener Punkt an die
  ROS/Contract-Session (den User) zurückgeben. Der Contract wird **dort** festgezurrt
  (Version + Changelog), dann baut die App dagegen.

---

## 4. Git — macht ausschließlich der User (ausnahmslos, wie in hexapod_ws §5)

> **Absolut:** Der Agent/Assistant führt **keine** Git-Operation aus — **niemals**, auch nicht
> read-only, nicht „nur kurz", nicht via Skript/Alias/Tool/IDE-VCS-Integration.

- **Verboten (Agent-Ausführung):** jedes `git …` — `add`, `commit`, `push`, `pull`, `fetch`,
  `clone`, `checkout`, `switch`, `branch`, `merge`, `rebase`, `reset`, `revert`, `stash`,
  `tag`, `restore`, `clean` **und** read-only (`status`, `log`, `diff`, `show`).
- **Erlaubt:** Git-Befehle als **Vorschlag/Anleitung** ausgeben, die **der User** ausführt.
  Braucht der Agent Git-Zustand → den User bitten, ihn zu liefern.

---

## 5. Shell-/System-Sicherheit

- **Erlaubt ohne Rückfrage:** alles unter diesem Repo (außer Git), `./gradlew …`
  (`assembleDebug`, `installDebug`, `test`, `lint`), `adb …` (devices/install/connect/pair/
  logcat), Android-Studio-interne Builds.
- **Niemals ohne explizites „GENEHMIGT":** System-Upgrades (`apt full-upgrade`,
  `do-release-upgrade`), PPAs (`add-apt-repository`), Pakete mit `nvidia`/`mesa`/`xorg`/
  `wayland`/`libgl`/`linux-image`/`kernel`/`grub` im Namen, Änderungen unter `/etc`, `/usr`,
  `/opt`, Display-Manager, `reboot`/`shutdown`, `rm -rf` außerhalb dieses Repos.
- **SDK/Tooling-Installs** (Android-SDK-Komponenten, `gh`, `adb`) als **Vorschlag** ausgeben;
  der User führt sie aus.
- **Golden Rule:** Tritt ein Fehler auf, ist ein System-Update **nie** der erste Reflex. Erst
  Diagnose (Logcat, Gradle-Output, `adb`-Status), dann kleinster Eingriff.

---

## 6. Konventionen

- **Sprache:** alle Antworten/Erklärungen/Doku auf **Deutsch** (Fachbegriffe dürfen englisch
  bleiben). User ist erfahrener SW-Ingenieur (8+ J. Fullstack, Embedded-C), aber
  **Android/Kotlin/Compose-Neuling** → neue Android-Idiome (Lifecycle, Compose-State,
  `InputManager`, Foreground-Service, Permissions) ausführlich erklären; Grundlagen (OOP,
  Build-Systeme, Git, CLI) nicht.
- **Package:** `io.github.enjoykinua.hexapod` (Segmente ohne Bindestriche).
- **Eingabe-Abstraktion ([D8]):** Controller-Eingaben laufen über ein **abstraktes Action-Set**
  (`translate`, `rotate`, `sit_stand`, `stance_up`, …), entkoppelt von physischen Tasten. Pro
  Controller ein **Profil (JSON)** Achsen-/Button-Index → Action. Die App normalisiert die
  aktive Belegung auf das **PS4-`/joy`-Layout** ([D3]). Kein fixes Kishi-Hardcoding.
- **Doku:** App-Doku (Arbeitsweise/Build/Phasen-Briefs) in diesem Repo (`CLAUDE.md`, `docs/`).
  **Spec/Requirements NICHT duplizieren** — auf `hexapod_ws` zeigen.
- **README dünn:** nur Build/Run + Zeiger auf diese Doku und den Contract ([D10]).

---

## 7. Navigations-Index (dieses Repo)

| Datei | Zweck |
|---|---|
| `CLAUDE.md` | diese Datei (App-Arbeitsanweisung) |
| `README.md` | dünn: Build/Run + Zeiger |
| `docs/NEXT.md` | **➜ zuerst lesen:** aktueller Stand + nahtloser Wiedereinstieg (Rolling-Doc) |
| `docs/architecture.md` | App-Architektur: Laufzeit-Kanäle, Eingabe-Pipeline, Code-Struktur (Ist + geplant) |
| `docs/build_and_deploy.md` | Android-Umgebung, **Wireless ADB**, Gradle-Build, Install auf S22+ |
| `docs/testing_guide.md` | Schritt-für-Schritt: App aufspielen + Kishi systematisch vermessen |
| `docs/phase_1_stage_b_brief.md` | Phase 1 (erledigt): Kishi-Gamepad-Hello-World + Roh-Index-Tabelle |
| `docs/phase_1_stage_b_implementation.md` | Stufe B: was gebaut wurde, ADRs, Done-Vertrag-Stand |
| `docs/phase_1_deliverable_joy_mapping.md` | Phase-1-Deliverable: fertiges Kishi→PS4-`/joy`-Mapping (Übergabe an ROS/Contract-Session) |
| `docs/phase_2_joy_client_plan.md` | Phase-2-App-Plan: `/joy`-WebSocket-Client (Logik/ADRs, Tests, Done-Vertrag) |
| `docs/phase_3_lifecycle_plan.md` | Phase-3-App-Plan: Connect-/Lifecycle-Screen (`call_service`, Option A/B, ADRs, Tests, Done-Vertrag) |

**Roboter-Seite (read-only, `~/hexapod_ws/project_finalization/app_control_requirements/`):**
`00_overview.md` · `requirements.md` · `decisions.md` · **`interface_contract.md` (Interface, SoT)** ·
`phase_<n>_*` (plan/progress/test_commands je Phase).

> **➜ Aktuelle Phasen-Aufgabe (immer zuerst lesen):** Das *Interface* (Topics/Services/QoS)
> steht im **`interface_contract.md`** (versioniert). Die *App-Aufgabe der laufenden Phase*
> steht im **App-Seite-Abschnitt des jeweiligen `phase_<n>_..._plan.md` in hexapod_ws** —
> **nicht im Chat** (D9/D10: Kopplung über den versionierten Contract/Plan, nicht mündlich).
> Phase 1 (Hello-World) war app-lokal (`docs/phase_1_stage_b_brief.md`); **ab Phase 2** =
> hexapod_ws-Phasenplan.
> **Aktuell: Phase 3** — Connect-/Start-Screen + Lifecycle-Buttons. Brief:
> `~/hexapod_ws/project_finalization/app_control_requirements/phase_3_lifecycle_plan.md` §5
> + Interface `interface_contract.md` v0.5 (§2a Launcher-Services, §3 `/hexapod/bringup_running`).
> (Phase 2 `/joy`-Client = erledigt, `phase_2_control_baseline_plan.md` §5.)

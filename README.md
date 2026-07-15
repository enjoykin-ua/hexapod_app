# hexapod_app

Android-Teleop-/HMI-App für einen 6-beinigen Hexapod-Roboter. Ein Handy im **Razer Kishi V2**
fährt den Roboter (physische Sticks/Tasten wie ein PS4-Controller) und zeigt Kamera-Live-Bild,
Status-Overlay und On-Screen-Konfiguration — Vorbild: DJI-Drohnen-Controller.

Die App spricht **WebSocket + JSON** mit `rosbridge_server` auf dem Roboter (kein ROS auf dem
Handy) und publisht `sensor_msgs/Joy`, normalisiert auf das PS4-Layout — damit läuft die
bestehende Fahr-Logik am Roboter unverändert.

## Stack

- Kotlin + Jetpack Compose (Material3), `ComponentActivity`
- minSdk 31 (Android 12) · targetSdk/compileSdk 36
- Gradle (Kotlin DSL) + Version-Catalog (`gradle/libs.versions.toml`)

## Build & Run

```bash
./gradlew assembleDebug        # Debug-APK bauen
./gradlew installDebug         # auf angeschlossenes Gerät installieren
./gradlew test                 # Unit-Tests
```

Deployment auf das Samsung S22+ (im Kishi → USB-C belegt) läuft über **Wireless ADB**.
Vollständige Anleitung: [`docs/build_and_deploy.md`](docs/build_and_deploy.md).

## Doku

- **App-Arbeitsweise / Konventionen:** [`CLAUDE.md`](CLAUDE.md)
- **Architektur (Kanäle, Eingabe-Pipeline, Code-Struktur):** [`docs/architecture.md`](docs/architecture.md)
- **Build & Deploy (Wireless ADB, Gradle):** [`docs/build_and_deploy.md`](docs/build_and_deploy.md)
- **Test-Anleitung (aufspielen + Kishi vermessen):** [`docs/testing_guide.md`](docs/testing_guide.md)
- **Aktuelle Aufgabe (Phase 1 Stufe B):** [`docs/phase_1_stage_b_brief.md`](docs/phase_1_stage_b_brief.md) · [Umsetzung/Stand](docs/phase_1_stage_b_implementation.md)

## Schnittstelle zum Roboter (Single Source of Truth)

Die App↔Roboter-API (rosbridge-Topics/-Services/-Message-Felder) ist **versioniert** und liegt
**nicht in diesem Repo**, sondern in der ROS-Codebasis:
`~/hexapod_ws/project_finalization/app_control_requirements/interface_contract.md`.
Dieses Repo **referenziert** ihn, dupliziert ihn nie.

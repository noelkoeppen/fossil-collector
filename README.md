# Fossil Collector

Grabungsspiel für Android: Fossilien von Hand ausgraben, freilegen, bergen, am 3D-Skelett
zusammensetzen und im eigenen Museum ausstellen. Hobbyprojekt, umgesetzt mit Claude-Code-Agenten.

- Design und Aufgabenliste: [`docs/GAME_DESIGN.md`](docs/GAME_DESIGN.md) (Version 2.0)
- Regeln für Agenten: [`CLAUDE.md`](CLAUDE.md)
- 3D-Modelle: `art/models3d/<speciesId>/` (eine `.glb` pro Knochen plus `bones.json`)

> **Umbau läuft.** Das Projekt war bis Version 1.1 ein Incremental-/Idle-Spiel. Dieser Stand liegt auf
> dem Branch `archive/incremental-v1.1`. Der Code auf `main` wird schrittweise auf das neue Design
> umgestellt (Arbeitspakete `AP2-XX`). Seit AP2-02 sind die Idle-Systeme entfernt; die App startet
> bis zu einem leeren Hauptbildschirm mit Einstellungen.

## Projektstruktur

| Modul | Inhalt |
|---|---|
| `core` | Reines Kotlin/JVM. Gesamte Spiellogik, Daten (`src/main/resources/data/`), JUnit-5-Tests. Keine Android-Abhängigkeiten. |
| `app` | Android-App (Jetpack Compose, Material 3). UI, ViewModel, Plattformanbindung. |
| `tools` | Hilfsskripte (`tools/models3d`: Aufbereitung der 3D-Modelle). |
| `art/models3d` | 3D-Modelle der Skelette als Einzelknochen (glTF). |

Abhängigkeiten und Versionen stehen im Version Catalog `gradle/libs.versions.toml`.

## Voraussetzungen

- JDK 17 oder neuer (Android Studio bringt ein passendes JDK mit)
- Android SDK mit Platform 36 und aktuellen Build-Tools
  (über Android Studio oder `sdkmanager "platforms;android-36"`)
- Der Pfad zum SDK steht entweder in `ANDROID_HOME` oder in `local.properties`
  (`sdk.dir=/pfad/zum/sdk`). Android Studio legt die Datei automatisch an.

Gradle selbst muss nicht installiert sein, der Wrapper (`gradlew`) lädt die passende Version.

## Bauen und testen

```bash
# Tests des Spiellogik-Moduls (laufen ohne Android SDK)
./gradlew :core:test

# JVM-Unit-Tests der App (SaveStorage, Export/Import u. a., brauchen das Android SDK)
./gradlew :app:testDebugUnitTest

# Debug-APK bauen
./gradlew :app:assembleDebug
# Ergebnis: app/build/outputs/apk/debug/app-debug.apk

# Auf ein verbundenes Gerät bzw. einen Emulator installieren
./gradlew :app:installDebug
```

Unter Windows `gradlew.bat` statt `./gradlew` verwenden.

## Datenvalidierung

Die Spieldaten in `core/src/main/resources/data/` werden beim App-Start und in den Tests von
`DataValidator` geprüft (Referenzen, IDs, Farben, Qualitätsstufen, Texte in de/en). Lokal und in der
CI läuft die Prüfung als Teil von `./gradlew :core:test` (`DataValidationTest`).

## CI

GitHub Actions (`.github/workflows/ci.yml`) führt bei jedem Push und Pull Request die `core`-Tests
(einschließlich Datenvalidierung) und die App-Unit-Tests aus und baut die Debug-APK. Die APK liegt danach im Workflow-Lauf unter
**Artifacts** (`fossil-collector-debug-apk`) zum Download bereit.

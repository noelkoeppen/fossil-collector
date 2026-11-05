# Fossil Collector

Incremental-/Idle-Spiel für Android: Fossilien ausgraben, präparieren, erforschen und im
eigenen Museum ausstellen. Hobbyprojekt, umgesetzt mit Claude-Code-Agenten.

- Design und Aufgabenliste: [`docs/GAME_DESIGN.md`](docs/GAME_DESIGN.md)
- Regeln für Agenten: [`CLAUDE.md`](CLAUDE.md)
- Balancing-Simulation: `tools/sim/fossil_sim.py`

## Projektstruktur

| Modul | Inhalt |
|---|---|
| `core` | Reines Kotlin/JVM. Gesamte Spiellogik, Daten (`src/main/resources/data/`), JUnit-5-Tests. Keine Android-Abhängigkeiten. |
| `app` | Android-App (Jetpack Compose, Material 3). UI, ViewModel, Plattformanbindung. |
| `tools` | Hilfsskripte (Balancing-Simulation, später SVG-Konvertierung und Datenvalidierung). |

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

# Debug-APK bauen
./gradlew :app:assembleDebug
# Ergebnis: app/build/outputs/apk/debug/app-debug.apk

# Auf ein verbundenes Gerät bzw. einen Emulator installieren
./gradlew :app:installDebug
```

Unter Windows `gradlew.bat` statt `./gradlew` verwenden.

## CI

GitHub Actions (`.github/workflows/ci.yml`) führt bei jedem Push und Pull Request die
`core`-Tests aus und baut die Debug-APK. Die APK liegt danach im Workflow-Lauf unter
**Artifacts** (`fossil-collector-debug-apk`) zum Download bereit.

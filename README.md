# Fossil Collector

Grabungsspiel für Android: Fossilien von Hand ausgraben, freilegen, bergen, am 3D-Skelett
zusammensetzen und im eigenen Museum ausstellen. Hobbyprojekt, umgesetzt mit Claude-Code-Agenten.

- Design und Aufgabenliste: [`docs/GAME_DESIGN.md`](docs/GAME_DESIGN.md) (Version 2.0)
- Regeln für Agenten: [`CLAUDE.md`](CLAUDE.md)
- 3D-Modelle: `art/models3d/<speciesId>/` (eine `.glb` pro Knochen plus `bones.json`)

> **Umbau läuft.** Das Projekt war bis Version 1.1 ein Incremental-/Idle-Spiel. Dieser Stand liegt auf
> dem Branch `archive/incremental-v1.1`. Der Code auf `main` wird schrittweise auf das neue Design
> umgestellt (Arbeitspakete `AP2-XX`); bis AP2-02 beschreiben die Abschnitte unten noch den alten Stand.

## Projektstruktur

| Modul | Inhalt |
|---|---|
| `core` | Reines Kotlin/JVM. Gesamte Spiellogik, Daten (`src/main/resources/data/`), JUnit-5-Tests. Keine Android-Abhängigkeiten. |
| `app` | Android-App (Jetpack Compose, Material 3). UI, ViewModel, Plattformanbindung. |
| `tools` | Hilfsskripte (Balancing-Simulation, SVG-Konvertierung der Skelette, später Datenvalidierung). |
| `art/skeletons` | Quell-SVGs der alten 2D-Skelett-Grafiken (entfallen mit dem Umbau). |
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

# JVM-Unit-Tests der App (SaveStorage, Tab-Regeln u. a., brauchen das Android SDK)
./gradlew :app:testDebugUnitTest

# Debug-APK bauen
./gradlew :app:assembleDebug
# Ergebnis: app/build/outputs/apk/debug/app-debug.apk

# Auf ein verbundenes Gerät bzw. einen Emulator installieren
./gradlew :app:installDebug
```

Unter Windows `gradlew.bat` statt `./gradlew` verwenden.

## Tools

### Skelett-Grafiken (`tools/svg2json.py`)

Die App zeichnet Skelette aus JSON-Dateien in `core/src/main/resources/data/skeletons/`
(`skeleton_<name>.json`). Sie werden aus den SVGs in `art/skeletons/<name>.svg` erzeugt und
**mit eingecheckt**, damit der Gradle-Build kein Python braucht. Nach jeder Änderung an einem SVG:

```bash
python3 tools/svg2json.py            # alle SVGs nach JSON umwandeln
python3 tools/svg2json.py --check    # nur prüfen, ob die JSONs aktuell sind (läuft in der CI)
python3 tools/test_svg2json.py       # Tests des Skripts
./gradlew :core:test                 # DataValidationTest prüft Knochen/Zonen gegen bones.json
```

Nur Python 3 mit Standardbibliothek. Konvention für die SVGs (Kapitel 8.5):

- `viewBox` am `<svg>`-Element; jeder Knochen eine Gruppe `<g id="<boneId>">`, jede Zone darin
  ein Pfad `<path id="<boneId>__<zoneId>" d="...">`. Zonen in der Reihenfolge aus `bones.json`.
- In Knochengruppen nur Zonen-Pfade (Formen vorher in Pfade umwandeln), keine verschachtelten
  Knochengruppen. **`transform`-Attribute sind verboten** (vor dem Export anwenden).
- Alles außerhalb von Knochengruppen (z. B. Hintergrund) wird ignoriert.
- Die Gruppenreihenfolge ist die Zeichenreihenfolge in der App.

Dateinamen: `<speciesId>.svg` für die Grafik einer Art (AP-27), `quadruped.svg` und `biped.svg`
für die Platzhalter. Arten ohne eigene Datei nutzen den Platzhalter ihres `bodyPlan`.

## CI

GitHub Actions (`.github/workflows/ci.yml`) führt bei jedem Push und Pull Request die Tests von
`tools/svg2json.py` samt Aktualitätsprüfung der Skelett-JSONs, die `core`-Tests und die App-Unit-Tests aus und baut die Debug-APK. Die APK liegt danach im Workflow-Lauf unter
**Artifacts** (`fossil-collector-debug-apk`) zum Download bereit.

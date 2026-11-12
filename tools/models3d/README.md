# tools/models3d

Skripte rund um die 3D-Modelle in `art/models3d/<speciesId>/`.

| Skript | Zweck |
|---|---|
| `build_extract.py`, `extract.py` | Erzeugen die Einzelknochen-Meshes des Höhlenbären aus den Shader-Seiten (siehe `art/models3d/cave_bear/README.md`). |
| `neighbors.py` | Leitet Knochenliste, Nachbarschaftsgraph und Knochennamen einer Art aus ihrem Modell ab und schreibt sie in die Spieldaten. |

## neighbors.py

Braucht Python 3 mit `numpy` und `scipy` (`pip install numpy scipy`).

```bash
python3 tools/models3d/neighbors.py              # alle Arten mit Modellordner aktualisieren
python3 tools/models3d/neighbors.py cave_bear    # nur eine Art
python3 tools/models3d/neighbors.py --check      # nur prüfen (Exit-Code 1, wenn nicht aktuell)
python3 tools/models3d/neighbors.py --threshold 0.04   # andere Schwelle ausprobieren
```

Ablauf pro Art:

1. Knochenliste und Namen (de/en) aus `art/models3d/<speciesId>/bones.json`, Meshes aus `bones/<id>.glb`.
2. Zwei Knochen sind Nachbarn, wenn der kleinste Abstand ihrer Oberflächen höchstens die Schwelle
   beträgt (Standard 0.03 Modelleinheiten, 1 Einheit = Schädellänge). Mit diesem Wert hängt der
   Graph des Höhlenbären gerade zusammen (Atlas–Hinterhaupt 0.026, Brustbein–Rippe 1 0.029).
3. Korrekturen aus `art/models3d/<speciesId>/neighbors_overrides.json` anwenden:
   `remove` entfernt abgeleitete Kanten, die anatomisch nicht stimmen, `add` ergänzt fehlende.
   Jeder Eintrag hat `bones` (zwei IDs) und `reason`. Ein Eintrag, der nichts mehr bewirkt (Kante
   fehlt bzw. existiert schon), ist ein Fehler, damit veraltete Korrekturen auffallen.
4. Der Graph muss zusammenhängend sein, sonst bricht das Skript ab (Exit-Code 2).
5. Ergebnis schreiben:
   - `core/src/main/resources/data/species.json`: `bones` und `neighbors` der Art (die übrigen
     Felder wie `layer` und `rarityWeight` bleiben unverändert),
   - `core/src/main/resources/data/locale/{de,en}.json`: `bone.<speciesId>.<boneId>.name`.

## Neue Art hinzufügen

1. Modellordner `art/models3d/<speciesId>/` mit `bones.json` und `bones/<id>.glb` anlegen.
2. In `species.json` einen Eintrag mit `id`, `layer` und `rarityWeight` anlegen (`bones` und
   `neighbors` ergänzt das Skript) und `species.<speciesId>.name` in beiden Locale-Dateien eintragen.
3. `python3 tools/models3d/neighbors.py <speciesId>` ausführen, Graph prüfen, bei Bedarf
   `neighbors_overrides.json` anlegen und das Skript erneut ausführen.
4. `./gradlew :core:test` (Datenvalidierung) muss grün sein.

## Tests

```bash
python3 -m unittest discover -s tools/models3d -p 'test_*.py'
```

Die Tests prüfen das Skript an kleinen synthetischen Modellen und, dass die eingecheckten Daten
dem Skript-Ergebnis entsprechen. Die CI führt sie zusammen mit `neighbors.py --check` aus.

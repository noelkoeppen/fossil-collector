# tools/models3d

Skripte rund um die 3D-Modelle in `art/models3d/<speciesId>/`.

| Skript | Zweck |
|---|---|
| `build_extract.py`, `extract.py` | Erzeugen die Einzelknochen-Meshes des Höhlenbären aus den Shader-Seiten (siehe `art/models3d/cave_bear/README.md`). |
| `neighbors.py` | Leitet Knochenliste, Nachbarschaftsgraph und Knochennamen einer Art aus ihrem Modell ab und schreibt sie in die Spieldaten. |
| `bone_images.py` | Rendert pro Knochen ein Draufsicht-Bild mit Transparenz und eine Umrissmaske in die App-Assets (AP2-06). |

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
4. `python3 tools/models3d/bone_images.py <speciesId>` ausführen (Knochenbilder und Masken).
5. `./gradlew :core:test` (Datenvalidierung) und `./gradlew :app:testDebugUnitTest` müssen grün sein.

## bone_images.py

Braucht Python 3 mit `numpy` und `Pillow` (`pip install numpy pillow`). Keine GPU, kein OpenGL:
Der Renderer ist ein kleiner Software-Rasterizer mit numpy (orthografische Projektion, Z-Puffer,
4x4-Überabtastung für glatte Kanten, Licht von oben, Knochenfarbe aus dem Material der `.glb`).
Für den Höhlenbären (105 Knochen) dauert ein Lauf etwa 12 Sekunden.

```bash
python3 tools/models3d/bone_images.py                  # alle Arten mit Modellordner (wie --all)
python3 tools/models3d/bone_images.py cave_bear        # nur eine Art
python3 tools/models3d/bone_images.py --check          # nur prüfen (Exit-Code 1, wenn nicht aktuell)
python3 tools/models3d/bone_images.py --preview /tmp/sheet.png   # zusätzlich Kontaktbogen aller Knochen
python3 tools/models3d/bone_images.py cave_bear --px-per-unit 512  # anderer Maßstab
```

Ausgabe pro Art unter `app/src/main/assets/bones/<speciesId>/`:

| Datei | Inhalt |
|---|---|
| `images/<boneId>.png` | Draufsicht, RGBA, Hintergrund transparent, Kanten geglättet |
| `masks/<boneId>.png` | Umrissmaske, 1 Bit (weiß = Knochen, Alpha >= 50 %) |
| `manifest.json` | Render-Parameter und pro Knochen `width`, `height`, `left`, `top`, `maskPixels` |

Die App findet die Dateien nur über Art- und Knochen-ID (`BoneImageAssets` in `app`), eine neue Art
braucht keinen Code.

Parameter (Optionen, gespeichert unter `params` im Manifest):

- `view` (`--view`, Standard `left`): Blickrichtung, für alle Knochen einer Art gleich. `left`
  schaut von der linken Seite des Tieres (Bild-rechts = hinten, Bild-oben = oben): Das Tier liegt auf
  der rechten Seite in der Grabung. Paarige Knochen haben damit denselben Umriss; rechte Knochen
  zeigen ihre Innenseite. Weitere Werte: `right`, `top`.
- `pxPerUnit` (`--px-per-unit`, Standard 384): Pixel pro Modelleinheit (1 Einheit = Schädellänge),
  für alle Knochen einer Art gleich, damit die Größenverhältnisse stimmen. Der Oberschenkel wird
  damit etwa 236 x 384 Pixel groß, ein Schwanzwirbel etwa 30 x 30.
- `supersample` (`--supersample`, Standard 4) und `padding` (`--padding`, Standard 2 Pixel Rand).
- Licht und Schattierung (`light`, `ambient`, `diffuse`, `specular`, `depthShade`) und die
  Ersatzfarbe `color` stehen als Standardwerte im Skript.

Alle Bilder einer Art liegen im selben Pixelraster: `left`/`top` ist die linke obere Bildecke in
Pixeln der Ansicht (Modellkoordinate mal `pxPerUnit`, Zeilen nach unten). Setzt man alle Bilder an
diese Stellen, ergibt sich das Skelett in der Seitenansicht.

Ein erneuter Lauf ohne Optionen nimmt die Parameter aus dem vorhandenen Manifest und schreibt nur
Dateien, deren Pixel sich geändert haben. Bilder von Knochen, die es nicht mehr gibt, werden
gelöscht. `--check` rendert neu und vergleicht dekodierte Pixel statt Dateibytes (verschiedene
zlib-Versionen komprimieren dieselben Pixel unterschiedlich). Die Rechnung ist deterministisch;
damit Rundungsunterschiede zwischen Plattformen nicht stören, darf ein Bild in höchstens 0,2 %
seiner Pixel um mehr als 2 Stufen je Kanal abweichen. Eine echte Änderung an Modell oder Parametern
betrifft weit mehr Pixel.

Nach jeder Änderung an den Meshes einer Art das Skript erneut ausführen und die geänderten Dateien
mit einchecken. Die CI führt `bone_images.py --check` aus, die App-Unit-Tests
(`BoneImageAssetsTest`) prüfen, dass für jeden Knochen jeder Art Bild und Maske existieren.

## Tests

```bash
python3 -m unittest discover -s tools/models3d -p 'test_*.py'
```

Die Tests prüfen beide Skripte an kleinen synthetischen Modellen und, dass die eingecheckten Daten
dem Skript-Ergebnis von `neighbors.py` entsprechen. Die CI führt sie zusammen mit
`neighbors.py --check` und `bone_images.py --check` aus.

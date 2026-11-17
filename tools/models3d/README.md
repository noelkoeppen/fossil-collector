# tools/models3d

Skripte rund um die 3D-Modelle in `art/models3d/<speciesId>/`.

| Skript | Zweck |
|---|---|
| `build_extract.py`, `extract.py` | Erzeugen die Einzelknochen-Meshes des Höhlenbären aus den Shader-Seiten (siehe `art/models3d/cave_bear/README.md`). |
| `neighbors.py` | Leitet Knochenliste, Nachbarschaftsgraph und Knochennamen einer Art aus ihrem Modell ab und schreibt sie in die Spieldaten. |
| `bone_images.py` | Rendert pro Knochen ein Bild in Liegelage (Seite mit der größten Fläche) mit Transparenz und eine Umrissmaske in die App-Assets (AP2-06). |
| `bone_footprints.py` | Rechnet aus den Umrissmasken die Umrisse der Knochen im Raster der Grabungsfläche und schreibt sie in die Spieldaten (AP2-07). |

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
5. `python3 tools/models3d/bone_footprints.py <speciesId>` ausführen (Umrisse in der Grabungsfläche).
6. `./gradlew :core:test` (Datenvalidierung) und `./gradlew :app:testDebugUnitTest` müssen grün sein.

## bone_images.py

Braucht Python 3 mit `numpy` und `Pillow` (`pip install numpy pillow`). Keine GPU, kein OpenGL:
Der Renderer ist ein kleiner Software-Rasterizer mit numpy (orthografische Projektion, Z-Puffer,
4x4-Überabtastung für glatte Kanten, Licht von oben, Knochenfarbe aus dem Material der `.glb`).
Für den Höhlenbären (105 Knochen) dauert ein Lauf etwa 11 Sekunden.

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
| `images/<boneId>.png` | Knochen in Liegelage, RGBA, Hintergrund transparent, Kanten geglättet |
| `masks/<boneId>.png` | Umrissmaske, 1 Bit (weiß = Knochen, Alpha >= 50 %) |
| `manifest.json` | Render-Parameter und pro Knochen `rotation`, `width`, `height`, `left`, `top`, `maskPixels` |

Die App findet die Dateien nur über Art- und Knochen-ID (`BoneImageAssets` in `app`), eine neue Art
braucht keinen Code.

Parameter (Optionen, gespeichert unter `params` im Manifest):

- `view` (`--view`, Standard `lying`): Liegelage pro Knochen. Jeder Knochen wird so gezeigt, wie er
  flach im Boden läge: Blick senkrecht auf die Seite mit der größten Fläche. Bestimmt wird das per
  Hauptachsenanalyse der Oberfläche (exakte Flächenmomente der Dreiecke, Eigenvektoren mit einem
  Jacobi-Verfahren in reinem Python, damit es überall gleich rechnet): Blick entlang der Achse mit der
  kleinsten Ausdehnung, die Achse mit der größten Ausdehnung liegt waagerecht. Die Vorzeichen legt
  das dritte Moment fest (die schwerere Seite liegt rechts bzw. oben, bei nahezu symmetrischen Achsen
  entscheidet die erste deutliche Komponente in x, y, z). Die Bilder sind damit stabil, und linke und
  rechte Knochen haben denselben Umriss (der rechte zeigt die andere Seite). Rippen zeigen so ihre
  Krümmung, Wirbel sind von vorn zu sehen. Feste Ansichten für alle Knochen gibt es weiter:
  `left` (von der linken Seite des Tieres), `right`, `top`.
- `pxPerUnit` (`--px-per-unit`, Standard 384): Pixel pro Modelleinheit (1 Einheit = Schädellänge),
  für alle Knochen einer Art gleich, damit die Größenverhältnisse stimmen. Der Oberschenkel wird
  damit 426 x 104 Pixel groß, Rippe 9 341 x 163, der letzte Schwanzwirbel 25 x 17.
- `supersample` (`--supersample`, Standard 4) und `padding` (`--padding`, Standard 2 Pixel Rand).
- Licht und Schattierung (`light`, `ambient`, `diffuse`, `specular`, `depthShade`) und die
  Ersatzfarbe `color` stehen als Standardwerte im Skript.

`rotation` sind die Modellachsen für Bild-rechts, Bild-oben und zur Kamera hin (Zeilen einer
Drehmatrix, Determinante +1). `left`/`top` ist die linke obere Bildecke in Pixeln der gedrehten
Ansicht. Der Mittelpunkt von Pixel (Spalte c, Zeile r) liegt damit in der Modellebene bei
`((left + c + 0.5) * rechts - (top + r + 0.5) * oben) / pxPerUnit` (plus beliebige Tiefe zur Kamera).
Bei einer festen Ansicht teilen alle Knochen dieses Raster; setzt man die Bilder dann an ihre
Stellen, ergibt sich das Skelett.

Ein erneuter Lauf ohne Optionen nimmt die Parameter aus dem vorhandenen Manifest und schreibt nur
Dateien, deren Pixel sich geändert haben. Bilder von Knochen, die es nicht mehr gibt, werden
gelöscht. `--check` rendert neu und vergleicht dekodierte Pixel statt Dateibytes (verschiedene
zlib-Versionen komprimieren dieselben Pixel unterschiedlich). Die Rechnung ist deterministisch;
damit Rundungsunterschiede zwischen Plattformen nicht stören, darf ein Bild in höchstens 0,2 %
seiner Pixel um mehr als 2 Stufen je Kanal abweichen, Drehungen im Manifest um höchstens 1e-4. Eine
echte Änderung an Modell oder Parametern betrifft weit mehr Pixel.

Nach jeder Änderung an den Meshes einer Art das Skript erneut ausführen und die geänderten Dateien
mit einchecken. Die CI führt `bone_images.py --check` aus, die App-Unit-Tests
(`BoneImageAssetsTest`) prüfen, dass für jeden Knochen jeder Art Bild und Maske existieren.

## bone_footprints.py

Braucht Python 3 mit `numpy` und `Pillow`. Liest pro Art `manifest.json` und `masks/<boneId>.png`
von `bone_images.py`, die Knochenreihenfolge aus `species.json` und den Maßstab
`excavation.cellsPerUnit` aus `balance.json` (der Wert wird nur dort gepflegt, Start 8 Zellen pro
Modelleinheit) und die Unterteilung `preparation.subdivision` für das feine Freilegen (AP2-08,
Start 4). Ergebnis ist `core/src/main/resources/data/footprints/<speciesId>.json`, das `core`
lädt (`DataFootprints`) und der `DataValidator` prüft.

```bash
python3 tools/models3d/bone_footprints.py                 # alle Arten mit Knochenbildern
python3 tools/models3d/bone_footprints.py cave_bear       # nur eine Art
python3 tools/models3d/bone_footprints.py --check         # nur prüfen (Exit-Code 1, wenn nicht aktuell)
python3 tools/models3d/bone_footprints.py --stats         # Zellen und abgedeckter Pixelanteil je Knochen
python3 tools/models3d/bone_footprints.py --min-coverage 0.1   # andere Schwelle ausprobieren
python3 tools/models3d/bone_footprints.py --fine-coverage 0.4  # andere Schwelle fuer feine Zellen
```

Verfahren pro Knochen (ohne Drehung):

1. Eine Zelle ist `pxPerUnit / cellsPerUnit` Maskenpixel breit (Höhlenbär 384 / 8 = 48).
2. Das Zellraster wird in Schritten von `offsetStep` Pixeln (Standard 4) gegen die Maske verschoben.
   Eine Zelle ist belegt, wenn mindestens `minCoverage` (Standard 0,05) ihrer Fläche Knochen ist.
   Hat keine Zelle genug Anteil, ist die mit dem größten Anteil belegt (jeder Knochen hat mindestens
   eine Zelle).
3. Gewählt wird die Lage mit den wenigsten Zellen, bei Gleichstand die mit den meisten abgedeckten
   Knochenpixeln, dann die kleinste Verschiebung. Das Ergebnis ist deterministisch.
4. Gespeichert werden die belegten Zellen als Zeilen (`#` belegt, `.` frei) und die linke obere
   Bildecke relativ zum Umriss in Bildpixeln (`imageLeft`, `imageTop`). Damit zeichnet die App das
   Knochenbild deckungsgleich mit den Zellen.
5. Feiner Umriss für das feine Freilegen (AP2-08): Das Rechteck aus Schritt 4 wird in
   `subdivision` x `subdivision` feine Zellen je Zelle geteilt (Höhlenbär 48 / 4 = 12 Pixel je feiner
   Zelle). Eine feine Zelle ist Knochen, wenn mindestens `fineCoverage` (Standard 0,5) ihrer Fläche
   Knochen ist, mindestens aber eine. Gespeichert als `fineRows`; die Nahansicht nutzt sie als
   Knochenzellen (`PreparationGenerator`).

Höhlenbär mit den Startwerten: 105 Knochen, 604 Zellen, im Mittel 96 % der Knochenpixel auf
belegten Zellen, mindestens 81 %. Der Oberschenkel belegt 9 x 2 Zellen, das Becken 7 x 3, kleine
Pfotenknochen und Schwanzwirbel eine Zelle. Die Parameter stehen unter `params` in der Datei; ein
erneuter Lauf ohne Optionen nimmt sie von dort. Nach jeder Änderung an den Masken oder an
`cellsPerUnit` oder `subdivision` das Skript erneut ausführen; die CI prüft das mit `--check`, der
`DataValidator` prüft, dass `cellsPerUnit` und `subdivision` der Datei zu `balance.json` passen und
die feinen Umrisse die richtige Größe haben.

## Tests

```bash
python3 -m unittest discover -s tools/models3d -p 'test_*.py'
```

Die Tests prüfen die Skripte an kleinen synthetischen Modellen bzw. Masken und, dass die
eingecheckten Daten dem Skript-Ergebnis von `neighbors.py` und `bone_footprints.py` entsprechen. Die
CI führt sie zusammen mit `neighbors.py --check`, `bone_images.py --check` und
`bone_footprints.py --check` aus.

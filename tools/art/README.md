# Skelett-Generator (`tools/art/skeleton_gen.py`)

Zeichenhilfe für die Skelett-SVGs der Arten (AP-27, GAME_DESIGN.md Kapitel 8.5). **Die Quelle der
Grafik bleiben die SVGs in `art/skeletons/`**; Build und CI brauchen dieses Skript nicht. Es ist nur
ein Werkzeug, um die Pfade aus parametrisierten Grundformen zu erzeugen, statt jede Koordinate von
Hand zu setzen.

```bash
pip install shapely                                   # nur für dieses Werkzeug
python3 tools/art/skeleton_gen.py                     # alle Arten des Skripts neu zeichnen
python3 tools/art/skeleton_gen.py smilodon            # nur eine Art
python3 tools/art/skeleton_gen.py --list              # Zonenflächen ausgeben (zu kleine Zonen finden)
python3 tools/svg2json.py                             # danach wie immer: JSONs erzeugen
rsvg-convert -b '#222' -w 600 art/skeletons/smilodon.svg -o /tmp/smilodon.png   # Sichtkontrolle
```

## Arbeitsweise

1. Jede Art ist eine Funktion (`cave_bear()`, `giant_deer()`, ...), die Zonen aus Bausteinen
   zusammensetzt: `column` (Wirbelsäulenabschnitt: Wirbelkörper, Dornfortsätze, Querfortsätze),
   `tail`, `ribs`, `pelvis`, `bone` (Langknochen), `taper`/`curve_taper` (verjüngte Striche, z. B.
   Zehen, Krallen, Zähne), `blob` (weiche Umrisse) und `outlined_bone` (Umriss eines Schädels oder
   Unterkiefers, mit Schnittlinien in die Zonen geteilt; Zusätze wie Geweih, Hörner, Stoßzähne oder
   Säbelzähne werden einer Zone zugeschlagen, Löcher wie die Augenhöhle ausgeschnitten).
2. `fit()` passt die Figur in die viewBox `0 0 400 200` ein (Rand 8, Füße auf gleicher Grundlinie).
3. `knockout()` schneidet in Zeichenreihenfolge (Knochen wie in `DRAW_ORDER`, Zonen wie in
   `bones.json`) jede spätere Zone aus allen früheren aus, mit einer Fuge von `GAP` = 1,8 Einheiten.
   Dadurch überlappt keine Zone eine andere, und jede Zone ist auch ungefärbt als eigene Form
   sichtbar. Splitter unter 2,5 Flächeneinheiten fallen weg.
4. Ausgabe nach Konvention 8.5: Gruppe `id="<boneId>"`, Pfad `id="<boneId>__<zoneId>"`, nur `M`/`L`/`Z`,
   keine `transform`-Attribute, Füllung `#E8DFC8`, Hintergrund `#1C1A17`.

## Vereinfachungen (bewusst, schematisch)

- Zone Oberarm (`forelimb__humerus`) enthält auch das Schulterblatt.
- Hand-/Fußwurzel (`carpus`/`tarsus`) enthalten bei Huftieren den langen Mittelhand- bzw.
  Mittelfußknochen, Finger/Zehen das Fesselbein mit Huf.
- Schwanzwirbel gehören zur Zone Wirbelkörper der Rückenwirbel (`dorsal__centrum`).
- Querfortsätze erscheinen als Lappen unter dem Wirbelkörper, abwechselnd `transverse_l` (gerade
  Wirbel) und `transverse_r` (ungerade Wirbel).
- Kopfanhänge: Schaufelgeweih des Riesenhirschs in `skull__skull_roof`; beim Wollnashorn Nasenhorn in
  `skull__snout`, Stirnhorn in `skull__skull_roof`; Stoßzähne des Mammuts und Säbelzähne von
  Smilodon in `skull__snout`.
- Neogen (AP-27b): obere Stoßzähne (Gomphotherium) und Säbelzähne (Thylacosmilus) in
  `skull__snout`; Unterkiefer-Stoßzähne (Gomphotherium flach, Deinotherium nach unten gebogen) und
  der Kinnfortsatz von Thylacosmilus in `jaw__tip`. Hipparion trägt an jedem Fuß zwei kurze
  Seitenzehen (Zone Finger/Zehen), Chalicotherium läuft auf den Knöcheln (Krallen eingeschlagen).
- Vogel (Titanis, `bodyPlan` biped): Oberschnabel mit Haken = `skull__snout`, Unterschnabel = `jaw`,
  Hals = `cervical`, kurzer Rücken plus Schwanzwirbel = `dorsal`, kurzer angelegter Flügel =
  `forelimb` (Oberarm mit Schulterblatt, Unterarm, Handwurzel/Mittelhand als `carpus`,
  Flügelfinger mit Kralle als `digits`), Laufbein = `hindlimb` (Tibiotarsus als `tibia`,
  Tarsometatarsus als `tarsus`).

Wer eine SVG später von Hand nachbearbeitet, sollte die Art nicht erneut mit dem Generator
überschreiben (oder die Änderung in die Artfunktion übernehmen).

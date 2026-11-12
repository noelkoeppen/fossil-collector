# Modelling-Regeln für 3D-Skelette

Verbindlich für jeden Ordner unter `art/models3d/<speciesId>/`. Gedacht wie die Code-Regeln in
`CLAUDE.md`, nur für Modelle: erst lesen, dann bauen, am Ende gegen die Checkliste prüfen.
`tools/models3d/check.py <speciesId>` prüft alles, was sich messen lässt (Kennung in Klammern).

Referenzmodell ist der Höhlenbär (`cave_bear`). Erstes Modell nach diesen Regeln ist der
Riesenhirsch (`giant_deer`).

## 1. Erst messen, dann modellieren

1. **Maße vor Formen.** Vor der ersten Form stehen die Längen der großen Knochen, Schulterhöhe,
   Schädellänge und Wirbelformel fest, jeweils mit Quelle. Sie stehen als benannte Werte oben in
   der Artdatei (`MEASURES_MM`), nicht verstreut als Zahlen im Formcode.
2. **Keine magischen Zahlen für Proportionen.** Gelenkpositionen werden aus Knochenlängen und
   Winkeln berechnet, nicht von Hand gesetzt. Ändert sich ein Maß, wandert das Skelett mit.
3. **Ein Modell ist eine Hypothese.** Was geschätzt statt belegt ist, steht in der README der Art
   unter "Annahmen". Ziel ist "realistisch wirkend", nicht anatomisch exakt (Design Kapitel 8).
4. **Referenzansichten.** Seite, vorn, oben und schräg werden ohne Perspektive gerendert und mit
   Fotos montierter Skelette verglichen, bevor Details dazukommen.

## 2. Konventionen

1. **Achsen:** x zeigt nach hinten, y nach oben, z zur linken Seite des Tiers. Rechtshändig.
2. **Ursprung und Einheit:** Der Schädel liegt waagerecht im Ursprung, Nasenspitze bei x = -0.5,
   Hinterhaupt bei x = +0.5. Eine Einheit ist die Schädellänge. `bones.json` nennt den Faktor in
   Metern (`unitMeters`), damit sich die echte Größe ausrechnen lässt.
3. **Gemeinsames Koordinatensystem:** Jeder Knochen liegt an seinem Platz im stehenden Skelett.
   Keine Verschiebung, Drehung oder Skalierung an den glTF-Knoten (`node_transform`).
4. **Namen:** `id` und Dateiname nur aus `a-z`, `0-9` und `_`, mit Buchstabe am Anfang. Paarige
   Knochen enden auf `_l` und `_r`, Reihen werden von vorn nach hinten ab 1 gezählt
   (`thoracic_3`, `rib_7_l`) (`naming`).
5. **Gruppen:** `skull`, `cervical`, `thoracic`, `lumbar`, `pelvis`, `tail`, `ribs`, `sternum`,
   `forelimb`, `hindlimb`. Keine neuen Gruppen ohne Anpassung des Designs (`groups`).
6. **Gleiche Knochen heißen bei allen Arten gleich** (`femur_l`, `scapula_r`). Was es bei einer
   Art nicht gibt, fehlt einfach. Artspezifische Knochen bekommen einen sprechenden Namen
   (`antler_l`).

## 3. Aufbau

1. **Vom Großen zum Kleinen.** Reihenfolge: Maße, Gelenkpunkte, Silhouette aus groben Formen,
   Gelenkenden, zuletzt Details wie Zähne und Leisten. Nach jeder Stufe rendern.
2. **Ein Knochen, eine Funktion.** Jeder Knochen ist eine eigene Formfunktion mit eigenem
   Eintrag in der Knochenliste. Keine Funktion erzeugt nebenbei Teile eines Nachbarn.
3. **Bausteine wiederverwenden.** Wirbel, Röhrenknochen und Rippen kommen aus gemeinsamen,
   parametrisierten Bausteinen (`tools/models3d/sdf.py`, `parts.py`). Unterschiede zwischen
   Wirbel 3 und Wirbel 9 sind Parameter, kein kopierter Code.
4. **Symmetrie durch Spiegelung.** Modelliert wird nur die linke Seite (z >= 0). Rechte Knochen
   sind Spiegelungen, nie eigene Formen (`mirror`).
5. **Kleinknochen zusammenfassen.** Hand- und Fußwurzel sowie Zehen sind je Fuß ein Teil. Ein
   Teil, das kleiner als etwa 3 cm wäre, wird nicht einzeln ausgegeben.
6. **Lesbarkeit vor Vollständigkeit.** Ein Knochen muss allein, ohne Nachbarn, erkennbar sein:
   Gelenkenden dicker als der Schaft, Platten mit Rand, Fortsätze deutlich.

## 4. Formen als Abstandsfunktion (SDF)

Die Formen sind Abstandsfunktionen: negativ innen, positiv außen. `tools/models3d/build.py`
tastet sie auf einem Raster ab und vernetzt die Nullfläche.

1. **Exakte Bausteine zuerst.** Kugel, Kapsel, Kegelstumpf, gerundeter Quader, Vereinigung,
   Abrunden und Spiegeln behalten einen sauberen Abstand. Ellipsoid, weiche Vereinigung,
   Abziehen und Schneiden liefern nur eine Untergrenze und werden sparsam eingesetzt.
2. **Weiche Übergänge klein halten.** Der Radius `k` einer weichen Vereinigung ist eine Länge in
   Modelleinheiten und bleibt unter dem halben Radius des dünneren Partners. Sonst verschmieren
   Gelenke zu Klumpen.
3. **Nicht ungleichmäßig strecken**, ohne den Abstand zu korrigieren. Für flache Formen
   Ellipsoid oder gerundeten Quader nehmen statt einer gequetschten Kugel.
4. **Mindeststärke.** Keine Struktur dünner als das 2,5-fache der Rasterweite `h` des Knochens,
   sonst reißt sie beim Vernetzen. Dünne Platten (Schulterblatt, Geweihschaufel) bekommen ein
   feineres Raster statt einer dickeren Form.
5. **Oberflächenrauschen ist Sache des Materials.** Risse, Poren und Verfärbung kommen nicht in
   die Geometrie. Verschiebungen der Fläche bleiben unter 5 % des lokalen Radius.
6. **Löcher bewusst.** Öffnungen (Augenhöhle, Wirbelkanal, Hüftloch) werden als Abzug modelliert
   und müssen mindestens 3 `h` weit sein, sonst wachsen sie zu.

## 5. Zerlegung in Knochen

1. **Jeder Knochen ist ein geschlossener Körper** in genau einem Stück (`watertight`,
   `single_body`).
2. **Schädel:** eine gemeinsame Form, die über Regionen in Einzelknochen geteilt wird. Die
   Regionen decken die ganze Form ab, es bleibt nichts übrig und nichts doppelt. Grenzen
   schneiden die Oberfläche steil, nicht streifend, sonst enden Knochen in messerdünnen Kanten.
   Lose Krümel unter 1 % des Knochenvolumens verwirft der Build.
3. **Paarige Knochen, die sich in der Mitte treffen,** werden an z = 0 geschnitten.
4. **Nachbarn durchdringen sich nicht.** Zwischen Wirbeln und in Gelenken bleibt ein kleiner
   Spalt. Erlaubt ist eine Überlappung der Hüllquader, aber kein Volumen in beiden Knochen
   zugleich, das größer ist als 2 % des kleineren Knochens (`overlap`).
5. **Nichts schwebt, nichts versinkt.** Die tiefsten Punkte aller vier Füße liegen auf derselben
   Bodenhöhe (`floor`), kein Knochen reicht darunter.

## 6. Mesh und Export

1. **Format:** eine `.glb` pro Knochen unter `bones/`, dazu `bones.json` und `<speciesId>_all.glb`
   mit einem Knoten pro Knochen, benannt nach der `id` (`files`).
2. **Geometrie:** nur Dreiecke, geschlossen, Normalen nach außen, keine entarteten Dreiecke,
   keine losen Splitter, keine umgeklappten Dreiecke (`watertight`, `winding`, `degenerate`,
   `folds`).
3. **Schonend vereinfachen.** Die Dreieckszahl wird mit der sanftesten Einstellung gesenkt, die
   das Ziel erreicht. Eine aggressive Einstellung hinterlässt bei gleicher Dreieckszahl Facetten
   und Knicke, besonders auf dünnen Platten. Wirkt ein Knochen eckig, zuerst das rohe Mesh
   ansehen: Ist es glatt, liegt es an der Vereinfachung und nicht an der Form oder am Budget.
4. **Dreiecksbudget:** höchstens 6000 Dreiecke pro Knochen, höchstens 150 000 für die ganze Art.
   Richtwert der Khronos-Leitlinie für Mobilgeräte sind 100 000 pro gleichzeitig gezeigtem
   Modell (`budget`). Kleine Knochen bekommen weniger, nicht jeder das Maximum.
5. **Ein Material:** `bone`, einheitliche Knochenfarbe, kein Metall, keine Texturen. Die
   Qualitätsfarben setzt das Spiel.
6. **Dateigröße:** `<speciesId>_all.glb` bleibt unter 5 MB (`size`).
7. **`bones.json`** enthält je Knochen `id`, `file`, `group`, `side`, `name` (de und en), `min`,
   `max` und `triangles` und stimmt mit den Dateien überein (`manifest`).

## 7. Prüfen

Wie Tests beim Code. Ein Modell ist erst fertig, wenn alles hier grün ist.

1. **Automatisch:** `python3 tools/models3d/check.py <speciesId>` läuft ohne Fehler durch.
2. **Maßkontrolle:** Die Prüfung misst die großen Knochen am fertigen Mesh nach und vergleicht
   mit `MEASURES_MM`. Erlaubt sind 8 % Abweichung (`measures`).
3. **Sichtkontrolle:** `python3 tools/models3d/render.py <speciesId>` schreibt die Ansichten
   nach `preview/`. Ansehen und mit Referenzfotos vergleichen: Stimmt die Silhouette? Sitzen
   Gelenke aufeinander? Ist jeder Knochen einzeln erkennbar?
4. **Fehler am Ursprung beheben.** Stimmt etwas am Mesh nicht, wird die Formfunktion oder ein
   Parameter geändert und neu gebaut. Meshes werden nie von Hand nachbearbeitet.
5. **Reproduzierbar:** Ein Neuaufbau aus dem Repo ergibt dieselben Dateien. Kein Zufall ohne
   festen Startwert.

## 8. Fertig, wenn

- [ ] Maße mit Quellen stehen in der Artdatei, Annahmen in der README der Art
- [ ] `check.py` meldet 0 Fehler
- [ ] Ansichten in `preview/` sind aktuell und angesehen
- [ ] README der Art nennt Knochenzahl, Dateien, Besonderheiten und offene Punkte
- [ ] Keine Datei außerhalb von `art/models3d/<speciesId>/` und `tools/models3d/` geändert,
      außer das Design verlangt es

## Ablauf für eine neue Art

1. Maße und Wirbelformel recherchieren, Quellen notieren.
2. `tools/models3d/species/<speciesId>.py` anlegen: `MEASURES_MM`, Haltung, Formfunktionen,
   Knochenliste `bones()`. `giant_deer.py` ist die Vorlage.
3. `build.py`, `check.py` und `render.py` laufen lassen, Ansichten prüfen, nachbessern.
4. README der Art schreiben, `bones_viewer.html` aus einer vorhandenen Art ableiten.

## 9. Bekannte Abweichungen des Höhlenbären

Der Höhlenbär ist vor diesen Regeln entstanden und bleibt vorerst, wie er ist:

- Proportionen nach Augenmaß statt aus Messwerten (Regel 1.1, 1.2).
- Formen stehen im Fragment-Shader von `skeleton.html` und `skull.html`, nicht in einer Artdatei.
- 274 650 Dreiecke und damit über dem Budget aus Regel 6.4.
- `bones.json` ohne `unitMeters`.

## Quellen

- Inigo Quilez, Distance Functions: https://iquilezles.org/articles/distfunctions
- Khronos 3D Commerce, Real-time Asset Creation Guidelines:
  https://github.com/KhronosGroup/3DC-Asset-Creation
- Sullivan u. a. 2024, Skeletal reconstruction of fossil vertebrates as a process of hypothesis
  testing, Comptes Rendus Palevol 23 (5)
- Blender Studio, Asset Creation Guide, Modeling:
  https://studio.blender.org/tools/artist-guide/asset-creation/modeling

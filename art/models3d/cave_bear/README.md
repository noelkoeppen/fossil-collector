# 3D-Modell Höhlenbär (Prototyp)

Experimentelle, realistisch wirkende 3D-Darstellung des Höhlenbären als Alternative zur
flachen Vektorgrafik aus `docs/GAME_DESIGN.md` Kapitel 8.5. Noch nicht ins Spiel eingebunden
und kein Arbeitspaket aus Kapitel 14.

## Dateien

| Datei | Inhalt |
|---|---|
| `skeleton.html` | Vollständiges Skelett in 10 Teilen: Schädel, Halswirbel, Brustwirbel, Rippen, Brustbein, Lendenwirbel, Becken, Schwanzwirbel, Vorderbeine, Hinterbeine |
| `skull.html` | Schädel allein, zerlegt in 10 Einzelknochen: Hinterhaupt, Zwischenkiefer, Nasenbein, Oberkiefer, Jochbein, Keilbein, Schläfenbein, Stirnbein, Scheitelbein, Unterkiefer |

Beide Dateien sind eigenständige HTML-Seiten ohne Abhängigkeiten außer den Google Fonts.
Einfach im Browser öffnen (WebGL nötig).

## Einzelknochen als Meshes

Unter `bones/` liegt jeder Knochen als eigene glTF-Datei (`.glb`), insgesamt 105 Stück:
18 Schädelteile (inkl. beider Unterkieferhälften), 27 Wirbel, Kreuzbein, 8 Schwanzwirbel,
28 Rippen, Brustbein, 2 Hüftbeine sowie je Seite Schulterblatt, Oberarm, Speiche, Elle,
Vorderpfote, Oberschenkel, Kniescheibe, Schienbein, Wadenbein und Hinterpfote.
Die kleinen Pfotenknochen sind je Pfote ein Teil.

| Datei | Inhalt |
|---|---|
| `bones/<id>.glb` | Ein Knochen. Paarige Knochen enden auf `_l` bzw. `_r`. |
| `bones.json` | Liste aller Knochen: `id`, Datei, Gruppe, Seite, Name (de/en), Bounding-Box, Dreiecke |
| `cave_bear_all.glb` | Alle Knochen in einer Datei, jeder als eigener Knoten mit seiner `id` |
| `bones_viewer.html` | Betrachter für `cave_bear_all.glb` (über einen lokalen Webserver öffnen) |

- Alle Meshes liegen im gemeinsamen Skelett-Koordinatensystem. Lädt man alle Dateien ohne
  Verschiebung, ergibt sich das stehende Skelett.
- Die Meshes tragen nur die Form und eine einheitliche Knochenfarbe. Risse, Verfärbungen und
  Schaden aus den Shader-Seiten sind nicht enthalten.
- Die rechten Knochen sind Spiegelungen der linken.

Neu erzeugen: `python3 tools/models3d/build_extract.py && python3 tools/models3d/extract.py`
(braucht Google Chrome, `playwright`, `scikit-image`, `trimesh`, `fast-simplification`).
Die Skripte lesen die Formen aus `skeleton.html` und `skull.html`.

## Technik

- Das Modell ist kein Mesh. Die Form ist als Abstandsfunktion (SDF) im Fragment-Shader
  beschrieben und wird pro Pixel per Raymarching berechnet. Die Meshes unter `bones/` werden daraus per
  Marching Cubes abgeleitet.
- Jedes Teil hat eine Qualität 0..1 (Uniform `uQ`). Niedrige Qualität bricht Stücke aus der
  Oberfläche. Die Qualitätsfarben entsprechen Kapitel 8.4.
- "Auseinanderziehen" verschiebt die Teile über `uEx`, paarige Teile werden an der Mittelebene
  geteilt.
- Koordinaten: x zeigt nach hinten (Nasenspitze bei -0.5, Hinterhaupt bei +0.5), y nach oben,
  z zur Seite. Eine Einheit entspricht der Schädellänge. Alles ist an z = 0 gespiegelt.

## Bedienung

Ziehen dreht, zwei Finger zoomen. In `skeleton.html` verschieben zwei Finger zusätzlich die
Ansicht, und ein Tipp auf ein Teil in der Liste holt es heran.

Zum Testen gibt es feste Blickwinkel über den URL-Anker, z. B. `skeleton.html#side`,
`#q3`, `#scap`, `#paw`, `#ex` oder `skull.html#front`, `#rear`, `#ex`.

## Offene Punkte

- Abweichung vom Design: Der Schädel hat hier Einzelknochen mit je einer Qualität statt der
  4 Zonen aus Kapitel 4.3. Für eine Übernahme ins Spiel müsste das Design angepasst werden.
- Einbindung in Jetpack Compose ist ungeklärt (WebView, vorgerenderte Bilder oder echtes Mesh).
- Proportionen sind nach Augenmaß gebaut, nicht gegen Messwerte geprüft.

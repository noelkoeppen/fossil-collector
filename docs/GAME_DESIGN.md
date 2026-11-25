# GAME_DESIGN.md – Fossil Collector (Arbeitstitel)

> Neues Design nach dem Umbau vom Incremental zum aktiven Grabungsspiel.
> Stand: 2026-11-12 · Version 2.0
> Plattform: Android · Stack: Kotlin + Jetpack Compose · Sprachen: Deutsch (Standard) und Englisch
>
> **Rückfall-Stand:** Altes Incremental-Design und alter Code liegen auf dem Branch `archive/incremental-v1.1`.

---

## 0. Regeln für Agenten (zuerst lesen)

1. **Ein Arbeitspaket (AP) pro Session/PR.** Die Liste steht in Kapitel 12. Nur umsetzen, was im AP steht.
2. **Markierungen:** `[MVP]` wird jetzt gebaut. `[Später]` wird nicht gebaut, darf aber nicht verbaut werden.
3. **Keine hartcodierten Spielwerte.** Alle Balancing-Zahlen (Chancen, Schaden, Toleranzen, Preise,
   Belohnungen) kommen aus den JSON-Dateien in `core/src/main/resources/data/`.
4. **`core` hat keine Android-Abhängigkeiten** und enthält die gesamte Spiellogik, vollständig mit
   JVM-Unit-Tests testbar. Die UI liefert nur Eingaben (z. B. „Werkzeug X wirkt auf Bereich Y“) und
   zeichnet den Zustand.
5. **Zufall** nur über das injizierte `GameRandom`. Tests mit festem Seed.
6. **Alle Boni laufen über das Modifier-System.** Keine Sonderlogik im Code.
7. **Neue Art = neue Daten.** Eine Art hinzuzufügen darf keinen Code erfordern, nur Modellordner und
   Dateneinträge.
8. **Texte:** Keine Strings im Code. UI-Texte in `strings.xml` (de/en), Inhaltstexte in den Locale-JSONs.
9. **Es gibt keine Simulation über Zeit mehr.** Kein Tick, kein Offline-Fortschritt, kein `advance()`.
   Der Spielzustand ändert sich nur durch Spieleraktionen.
10. **Definition of Done:** Build grün, Tests grün, neue Logik in `core` hat Tests, Datenvalidierung grün.
    Weicht ein AP vom Design ab, wird dieses Dokument im selben PR angepasst und die Abweichung begründet.
11. Keine Emojis im Code.

---

## 1. Spielidee

Der Spieler geht auf Ausgrabungen, legt Fossilien von Hand frei, birgt sie und setzt die einzelnen Knochen
am 3D-Skelett zusammen. Kein Idle-Anteil: Fortschritt entsteht nur durch gespielte Ausgrabungen.
Ziel ist ein vollständiges Sammelbuch und ein Museum mit möglichst guten Skeletten.

Ton: entspannt, sammelorientiert, „noch eine Ausgrabung“.

## 2. Kern-Loop

```
Ausgrabung wählen (Schicht / Fundort)
  -> Grob buddeln auf freier Fläche, bis man auf etwas stößt
  -> Fein freilegen   (Pinsel und Kratzer, Fehler kosten Qualität)
  -> Bergen           (mit Gips sichern, ruhig herausheben, Fehler kosten Qualität)
  -> Knochen per Hand am 3D-Skelett zuordnen
  -> Museum: Skelette ausstellen -> Ansehen -> neue Schichten und Fundorte
  -> Duplikate verkaufen -> Geld -> Werkzeuge und Upgrades
  -> Aufträge des Museums -> Ansehen und Geld
```

## 3. Ausgrabung

- **Freie große Fläche.** Intern in Kacheln gerechnet, für den Spieler aber nicht sichtbar (kein Raster,
  keine Kachel-Optik). Bewusste Abgrenzung zu Raster-Grabungsspielen mit Schrittlimit.
- **Kein Schrittlimit, keine Energie.** Es wird gegraben, bis der Spieler aufgibt oder die Meldung kommt,
  dass keine Knochen mehr in der Fläche liegen. Eine Ausgrabung kostet nur Zeit und Aufwand, kein Geld.
- **Kostenpflichtige Ausgrabungsflächen:** [Später, optional].
- **Grobes Buddeln:** Wischen trägt Erde dort ab, wo der Finger ist. Mehrere Werkzeuge
  (z. B. Schaufel: schnell und grob; Kelle: langsam und sicher). Kurz bevor man auf Knochen stößt, zeigt
  sich ein Hinweis (Verfärbung, Knochenspitze). Wer dann grob weitermacht, verliert Qualität.
- **Die Entscheidung beim Buddeln ist „wie vorsichtig“, nicht „wo“.**
- **Sondieren als Upgrade [Später]:** z. B. Bodenradar, das Fundstellen grob anzeigt. Am Anfang gräbt man
  blind mit Hinweisen.

Umsetzung (AP2-05, `ExcavationGenerator` und `Excavator` in `core`, Parameter in
`balance.json: excavation` und `tools.json`, Startwerte vorläufig bis zum Playtest):
- **Raster:** `width` x `height` Zellen (Start 32 x 48). Jede Zelle hat eine Erdtiefe in Erdeinheiten,
  gleichverteilt zwischen `depthMin` und `depthMax` (Start 60 bis 100).
- **Funde:** Anzahl aus der gewichteten Tabelle `findCountWeights` (Start 2: 30, 3: 40, 4: 30), jeder
  Fund gewürfelt nach Kapitel 4 und platziert wie unter „Lage“ beschrieben.
- **Werkzeuge** (`tools.json`): Einsatzbereich `stage` (`dig` = grobes Buddeln, `prepare` = feines
  Freilegen nach 5.1, seit AP2-08), Abtrag pro Anwendung und Zelle (`removal`), Wirkradius in Zellen
  (`radius`), `rough` (grob ja/nein), `boneDamage` (Qualitätsverlust pro Treffer, nur bei groben
  Werkzeugen > 0), `strokeSpacing` (Abstand der Anwendungen beim Wischen in Zellen, seit AP2-07) und
  `removesHard` (löst harte Stellen der Restschicht, nur `prepare`, seit AP2-08). Startwerte grobes
  Buddeln: Schaufel 20 / 2.5 / grob / 0.08 / 2.5, Kelle 6 / 1.5 / nicht grob / 0 / 1.5.
- **Aktion „Werkzeug wirkt auf Bereich“:** Eingabe Werkzeug-ID und Ansatzpunkt in Zellkoordinaten.
  Betroffen sind alle Zellen, deren Mittelpunkt höchstens `radius` vom Ansatzpunkt entfernt ist; dort
  wird bis zu `removal` Erde abgetragen. Ergebnis als Daten: neue Fläche, abgetragene Erde, geänderte
  Zellen, neue Hinweise, Schaden je Knochen, neu entdeckte Knochen. Wie oft die UI die Aktion beim
  Wischen auslöst, steht unter „Screen“.
- **Hinweis:** Eine Knochenzelle zeigt einen Hinweis, sobald höchstens `hintDepth` Erde (Start 20)
  darüber liegt. `hintDepth` ist kleiner als `depthMin`, und kein Werkzeug trägt mehr als `hintDepth`
  pro Anwendung ab (Datenvalidierung). Damit zeigt sich immer mindestens eine Anwendung vorher ein
  Hinweis, bevor ein Werkzeug den Knochen trifft.
- **Schaden:** Ein grobes Werkzeug trifft einen Knochen, wenn es in einer seiner Zellen tiefer gräbt,
  als Erde darüber liegt (auch auf schon freiliegenden Knochen). Jeder getroffene Knochen verliert pro
  Anwendung einmal `boneDamage * (1 - damage_reduction)`, höchstens bis Qualität 0. Der Knochen bleibt
  immer erhalten (Kapitel 5). Nicht grobe Werkzeuge beschädigen nie.
- **Entdeckt und „nichts mehr zu finden“:** Ein Knochen gilt als entdeckt („man stößt auf ihn“),
  sobald eine seiner Zellen frei liegt. Sind alle Knochen der Fläche entdeckt, ist nichts mehr zu
  finden. Das Freilegen der Restschicht folgt in 5.1 (AP2-08): Ein entdeckter Knochen lässt sich in
  der Nahansicht öffnen (langes Drücken auf den Knochen oder „Freilegen“ in der Anzeige oben).
- **Geborgene Knochen** (seit AP2-09, Kapitel 5.2) liegen im Inventar und nicht mehr in der Fläche:
  Sie werden nicht mehr gezeichnet, zeigen keinen Hinweis, werden von der Schaufel nicht getroffen
  und lassen sich nicht mehr öffnen. Der Fund bleibt dabei unverändert gespeichert (sonst fielen die
  Knochen eines Fundes im Nachbarschaftsgraph auseinander); geborgene Knochen werden nur markiert.
- **Umrisse der Knochen** (seit AP2-07): aus den Umrissmasken der Knochenbilder (Kapitel 8) mit
  `excavation.cellsPerUnit` Zellen pro Modelleinheit (Start 8), ohne Drehung. Eine Zelle gilt als
  belegt, wenn mindestens 5 % ihrer Fläche Knochen ist, jeder Knochen hat mindestens eine Zelle.
  Der Höhlenbär belegt so 1 (Schwanzwirbel, Pfotenknochen) bis 18 Zellen (Oberschenkel 9 x 2).
  Mit den Startwerten der Fläche passt jeder gewürfelte Fund (2 000 Flächen mit 4 Funden zu je 6
  Knochen: kein Fund weggelassen).

Screen grobes Buddeln (AP2-07):
- **Start:** Auf dem Hauptbildschirm „Ausgrabung starten“; im ersten Stand immer in der obersten
  Schicht (Kapitel 9). Eine laufende Ausgrabung steht im Spielstand; die App öffnet sie nach dem Start
  direkt wieder.
- **Gesten:** Ein Finger gräbt, zwei Finger verschieben und zoomen. Die Anwendung startet erst, wenn
  sich der Finger um die Berührungstoleranz des Systems bewegt hat, oder beim Abheben ohne Bewegung
  (Tippen = eine Anwendung); setzt vorher ein zweiter Finger auf, wird nicht gegraben. Kommt
  während des Grabens ein zweiter Finger dazu, endet die Wischbewegung.
- **Wie oft eine Wischbewegung wirkt:** einmal am Ansatzpunkt und danach jedes Mal, wenn der Finger
  entlang seines Weges weitere `strokeSpacing` Zellen zurückgelegt hat (`DigStroke` in `core`). Die
  Ansatzpunkte liegen gleichmäßig auf dem Weg, unabhängig davon, wie schnell gewischt wird oder wie
  oft das Gerät Bewegungen meldet. Ohne Bewegung passiert nichts (keine Zeit-Simulation). Mit
  `strokeSpacing` = Radius trifft ein Wischzug eine Zelle im Mittel etwa 1,6-mal (höchstens 2-mal):
  Die Schaufel trägt pro Zug rund 30 Erdeinheiten ab (2 bis 4 Züge bis zum Grund), die Kelle rund 9.
- **Zoom:** von der ganzen Fläche (1) bis zum Vierfachen (UI-Konstanten, keine Spielwerte).
- **Darstellung:** Kapitel 8. Hinweise erscheinen als helle, körnige Verfärbung der Erde, dünne Erde
  über dem Grund wird leicht durchscheinend. Ein Treffer mit der Schaufel lässt die Zellen des
  Knochens kurz rot aufleuchten; oben zeigt eine Anzeige den zuletzt getroffenen oder entdeckten
  Knochen mit Name, Qualitätsstufe, Prozent und Stufenfarbe sowie die Zahl der entdeckten Knochen.
- **„Keine Knochen mehr“:** Sind alle Knochen entdeckt, erscheint die Meldung („Alle Knochen sind
  geborgen“, sobald auch alle geborgen sind). Seit AP2-09 wird „Ausgrabung beenden“ erst
  hervorgehoben, wenn alle Knochen der Fläche geborgen sind. Sonst fragt „Ausgrabung beenden“ vorher
  nach und nennt, was zurückbleibt: noch unentdeckte Knochen und die Zahl der entdeckten, aber nicht
  geborgenen Knochen. **Beenden:** Geborgene Knochen liegen schon im Inventar; nicht geborgene
  Knochen (entdeckt oder nicht) bleiben in der Fläche zurück und gehen mit ihr verloren. Das ist
  eine Entscheidung des Spielers („bis der Spieler aufgibt“), kein Fehler beim Graben; Kapitel 5
  („nie Totalverlust“) betrifft Fehler beim Freilegen und Bergen. Oben zählt die Anzeige zusätzlich
  die geborgenen Knochen.
- **Speichern:** nach jeder Wischbewegung (Finger hebt ab), beim Starten und Beenden und beim Wechsel
  in den Hintergrund, nicht nach jeder einzelnen Anwendung.

## 4. Fundsystem

- **Einzelknochen:** Jede Art besteht aus allen Einzelknochen des 3D-Modells (links/rechts getrennt,
  kleine Pfotenknochen je Pfote gruppiert). Der Schädel ist in seine echten Einzelknochen aufgeteilt.
  Keine Zonen mehr, jeder Knochen hat genau eine Qualität.
- **Würfeln eines Fundes:**
  1. Art (nach Seltenheitsgewicht der Schicht)
  2. Startknochen
  3. Anzahl zusammenhängender Knochen: Erweiterung entlang anatomischer Nachbarn
     (Nachbarschaftsgraph pro Art in den Daten)
  4. Grundqualität des Fundes

  Umsetzung (AP2-04, `FindRoller` in `core`, Parameter in `balance.json: finds`, Startwerte vorläufig
  bis zum Playtest):
  1. Art gewichtet nach `rarityWeight` unter den Arten der Schicht.
  2. Fundgröße aus der gewichteten Tabelle `sizeWeights` (Startwerte: 1 Knochen 35, 2: 25, 3: 17,
     4: 11, 5: 7, 6: 5), gekappt auf die Knochenanzahl der Art.
  3. Startknochen gleichverteilt unter allen Knochen der Art.
  4. Erweiterung: Bis die Fundgröße erreicht ist, kommt jeweils ein gleichverteilt gewählter Knochen
     vom Rand des Fundes dazu (Nachbar eines schon gewählten Knochens).
  5. Grundqualität gleichverteilt zwischen Untergrenze und `baseQualityMax` (Start 0.95). Die
     Untergrenze ist der Stat `quality_base_min` mit Basiswert `baseQualityMin` (Start 0.4), also über
     das Modifier-System anhebbar, und wird höchstens bis `baseQualityMax` angehoben.
  6. Jeder Knochen weicht gleichverteilt um höchstens `boneQualityDeviation` (Start 0.05) nach oben
     oder unten ab; das Ergebnis wird auf 0 bis 1 begrenzt.
- **Nachbarschaftsgraph:** wird aus den Meshes des Modells abgeleitet (plus Korrekturdatei), nicht
  von Hand gepflegt. `tools/models3d/neighbors.py` verbindet zwei Knochen, wenn ihre Oberflächen
  höchstens einen Schwellwert auseinanderliegen (Skript-Parameter, Standard 0.03 Modelleinheiten),
  und wendet dann `art/models3d/<speciesId>/neighbors_overrides.json` an (`add`/`remove` mit
  Begründung, z. B. Brustbein an die sternalen Rippen, keine Kante zwischen linker und rechter
  Rippe). Ergebnis sind `bones` und `neighbors` der Art in `species.json` sowie die Knochennamen
  (`bone.<speciesId>.<boneId>.name`) in den Locale-Dateien. Der Graph jeder Art muss
  zusammenhängend sein.
- **Qualität zieht sich durch:** Alle Knochen eines Fundes teilen die Grundqualität, mit kleiner
  Abweichung pro Knochen. Schaden trifft jeden Knochen einzeln.
- **Lage:** Zusammenhängende Knochen liegen in der Fläche beieinander. Umsetzung (AP2-05): Die
  Knochen eines Fundes werden mit ihren Umrissen (Kapitel 3) in Breitensuche-Reihenfolge über seinen
  Nachbarschaftsgraph gesetzt, jeder
  weitere Knochen an eine zufällige freie Stelle, an der er über eine Zellkante an einen schon gesetzten
  Graph-Nachbarn grenzt. Knochen überlappen nie; Zellen verschiedener Funde haben mindestens
  `findSpacing` Zellen Abstand (Start 2). Findet ein Knochen keine Stelle, beginnt der Fund von vorn;
  nach `placementAttempts` Fehlversuchen (Start 20) wird er weggelassen.
- **Qualitätsstufen und -farben** wie bisher: Fragment, Beschädigt, Gut, Exzellent, Museumsreif.
- **Ereignisse [Später]:** Merkmale am Fund, z. B. „Bissspuren von Art X“. Verändern Qualität bzw. Wert
  und können Hinweise auf andere Arten geben.

## 5. Schaden und Minigames

- **Fehler kosten nur Qualität.** Schlimmstenfalls landet ein Knochen bei „Fragment“. Ein Knochen geht nie
  ganz verloren und zählt immer fürs Skelett.
- Schadensquellen: grobes Werkzeug auf Knochen, Fehler beim feinen Freilegen, Fehler beim Bergen.
- Upgrades machen alles leichter (größere Toleranz, weniger Schaden pro Fehler, bessere Hinweise).

### 5.1 Fein freilegen: Pinsel und Kratzer
- Nahansicht der Fundstelle. Der Knochen steckt in einer dünnen Restschicht.
- **Pinsel:** entfernt lose Erde, ungefährlich, langsam.
- **Kratzer:** löst harte Stellen, schnell; wer damit über den Knochen kratzt, verliert Qualität.
- Gleiche Wisch-Bedienung wie beim groben Buddeln, nur feiner.
- Upgrade-Ideen: weicherer Pinsel, präziserer Kratzer, Kratzer verzeiht kurze Berührungen.

Umsetzung (AP2-08, `PreparationGenerator` und `Preparer` in `core`, Parameter in
`balance.json: preparation` und `tools.json`, Startwerte vorläufig bis zum Playtest):
- **Nahansicht pro Knochen:** Die Nahansicht gehört zu genau einem entdeckten Knochen, nicht zum
  ganzen Fund. Begründung: Qualität, Freilegen (hier „Der Knochen steckt …“) und Bergen (5.2, AP2-09
  „danach liegt der Knochen im Inventar“) sind pro Knochen; Knochen eines Fundes werden einzeln
  entdeckt, ein Nachbar kann noch unter grober Erde liegen.
- **Öffnen:** auf der Fläche langes Drücken auf einen entdeckten Knochen (Systemzeit für langes
  Drücken, Bediengeste) oder „Freilegen“ beim zuletzt entdeckten bzw. getroffenen Knochen. Beim
  ersten Öffnen entsteht die Restschicht; die restliche grobe Erde über den Zellen dieses Knochens
  fällt dabei weg (ab dann liegt über ihm nur die Restschicht). Weitere Öffnungen zeigen den
  gespeicherten Stand. „Zur Fläche“ bzw. Zurück schließt die Nahansicht.
- **Raster:** das Rechteck der Zellen des Knochens plus `margin` Zellen Rand (Start 1), jede Zelle
  in `subdivision` x `subdivision` feine Zellen geteilt (Start 4, also 12 Bildpixel je feiner
  Zelle beim Höhlenbären). Der Oberschenkel hat so 44 x 16 feine Zellen.
- **Knochenzellen:** der feine Umriss `fineRows` aus `data/footprints/<speciesId>.json`
  (Kapitel 8), wenn die gespeicherten Zellen dem Umriss entsprechen; sonst (Spielstand mit
  Platzhalter-Zellen) alle feinen Zellen der belegten Zellen. Sie werden mit der Nahansicht
  gespeichert.
- **Restschicht:** jede feine Zelle trägt lose Erde mit Dicke gleichverteilt in
  `looseDepthMin..looseDepthMax` (Start 3 bis 6). Harte Stellen: im Mittel `hardSpotsPerCell`
  (Start 0,35) je Zelle des Knochens in der Fläche (Anzahl `floor(n * hardSpotsPerCell + u)`),
  jede ein Kreis um eine zufällige Knochenzelle mit Radius in `hardSpotRadiusMin..hardSpotRadiusMax`
  feinen Zellen (Start 1 bis 2,5) und Dicke in `hardDepthMin..hardDepthMax` (Start 4 bis 8).
  Zufall nur über `GameRandom`, gleiche Reihenfolge, gleicher Seed, gleiche Restschicht.
- **Aktion „Werkzeug wirkt auf Bereich“** wie beim groben Buddeln, in feinen Zellen: betroffen
  sind alle feinen Zellen, deren Mittelpunkt höchstens `radius` vom Ansatzpunkt entfernt ist. Lose
  Erde trägt jedes Werkzeug ab (`removal`), harte Stellen nur ein Werkzeug mit `removesHard`.
  Startwerte (`removal` / `radius` / grob / `boneDamage` / `strokeSpacing` / `removesHard`): Pinsel
  1 / 2.0 / nicht grob / 0 / 1.0 / nein, Kratzer 10 / 1.0 / grob / 0.005 / 1.0 / ja. Der Pinsel
  braucht für lose Erde ein bis zwei Wischzüge, der Kratzer löst jede Stelle mit einer Anwendung.
- **Schaden-Regel:** Der Kratzer trifft den Knochen, wenn in seinem Wirkbereich eine Knochenzelle
  liegt, auf der schon **vor dieser Wischbewegung** keine Restschicht mehr lag. Zellen, die erst im
  selben Zug frei geworden sind, zählen nicht (sonst würde jeder Zug über eine harte Stelle den
  Knochen treffen, weil sich die Anwendungen beim Wischen überlappen). Ein neuer Zug über eben
  freigelegten Knochen trifft. Jede Anwendung mit Treffer kostet einmal
  `boneDamage * (1 - damage_reduction)`, höchstens bis Qualität 0. Der Pinsel beschädigt nie.
  Tipp für Spieler: harte Stellen zuerst abkratzen, solange lose Erde drumherum liegt.
- **Fortschritt:** Anteil der Knochenzellen ohne Restschicht. **Fertig freigelegt** ist der
  Knochen, sobald der Anteil `finishedShare` (Start 0,95) erreicht; der Zustand wird gespeichert und
  bleibt (auch wenn sich die Schwelle ändert). Das Ergebnis des Freilegens ist die Qualität des
  Knochens im Fund und der Zustand „fertig“ seiner Nahansicht; daran schließt das Bergen an
  (AP2-09). Weiterarbeiten nach „fertig“ ist erlaubt.
- **Screen:** Nahansicht mit Boden der Grube, Knochenbild und Restschicht darüber (Kapitel 8),
  gleiche Gesten wie beim groben Buddeln (ein Finger arbeitet, zwei verschieben und zoomen, Zoom 1
  bis 4). Oben Name und Qualität des Knochens (rot mit „Getroffen!“ nach einem Treffer, dazu leuchtet
  die Stelle kurz rot auf), Fortschrittsbalken mit Prozent und eine kurze Bedienhilfe, bei „fertig“
  eine Meldung. Unten Pinsel/Kratzer und „Zur Fläche“. Gespeichert wird wie beim groben Buddeln nach
  jeder Wischbewegung sowie beim Öffnen und Schließen. Welche Nahansicht offen ist, ist Bedienzustand
  und wird nicht gespeichert (nach dem Neustart öffnet die App die Fläche).

### 5.2 Bergen: Sichern und heben
- Brüchige Stellen am Knochen leuchten auf und müssen mit Gips gesichert werden (antippen).
- Danach wird der Knochen mit ruhigem Ziehen herausgehoben. Ruckartige Bewegungen und ungesicherte
  Stellen kosten Qualität.

Umsetzung (AP2-09, `RecoveryGenerator` und `Recoverer` in `core`, Parameter in
`balance.json: recovery`, Startwerte vorläufig bis zum Playtest):
- **Einstieg:** Bergen gibt es pro Knochen, sobald er fertig freigelegt ist (5.1): In der
  Nahansicht erscheint dann „Bergen“. Beim ersten Öffnen entstehen die brüchigen Stellen, weitere
  Öffnungen zeigen den gespeicherten Stand. „Zurück“ führt vor dem Herausheben zur Nahansicht.
- **Brüchige Stellen:** Anzahl `floor(n * fragileSpotsPerCell + u)` mit `n` = Zellen des Knochens
  in der Fläche und `u` gleichverteilt in `[0, 1)`, begrenzt auf `fragileSpotsMin..fragileSpotsMax`
  (Start 0,25 je Zelle, 1 bis 5; der Oberschenkel mit 18 Zellen hat 4 oder 5, kleine Knochen 1).
  Jede Stelle liegt auf einer gleichverteilt gewählten Knochenzelle der Nahansicht (feiner Umriss),
  mindestens `fragileSpotSpacing` feine Zellen (Start 4, eine Zelle der Fläche) von den anderen
  entfernt; ist kein Platz mehr, bleibt es bei weniger Stellen. Zufall nur über `GameRandom`.
- **Sichern:** Antippen sichert die nächste ungesicherte Stelle höchstens `secureRadius` feine
  Zellen (Start 3) vom Tippunkt; sie wird als Gipsfleck gezeigt. Gips ist unbegrenzt (kein
  Verbrauchsgut).
- **Heben:** Ziehen mit einem Finger hebt den Knochen, das Bild folgt dem Finger. Die UI liefert die
  Bewegung als Samples (Zeitstempel des Eingabeereignisses in ms, Position in dp, also unabhängig von
  Zoom und Bildschirmdichte). Heraus ist der Knochen, sobald der Finger `liftDistance` dp (Start
  140) vom Startpunkt entfernt ist, auch vor dem Loslassen. Lässt der Finger vorher los, fällt der
  Knochen zurück.
- **Bewertung der Bewegung** (reine Funktion der Samples, keine Zeit-Simulation): Die Bewegung wird
  linear zwischen den Samples interpoliert und in Fenster von `sampleWindowMillis` (Start 50 ms) ab
  dem ersten Sample geteilt; ein angebrochenes letztes Fenster zählt nicht. Je Fenster ist `v` die
  mittlere Geschwindigkeit, vor dem ersten Fenster ruht der Knochen. Ein Fenster ist ruckartig, wenn
  sich `v` gegenüber dem vorigen Fenster um mehr als `jerkSpeedChange` dp/s ändert (Start 400) oder
  `|v|` über `maxSpeed` dp/s liegt (Start 700, Reißen). Aufeinanderfolgende ruckartige Fenster sind
  **ein** Ruck. Damit hängt die Bewertung nicht davon ab, wie oft das Gerät Bewegungen meldet.
- **Schaden:** jeder Ruck `jerkDamage` (Start 0,04), beim Herausheben jede ungesicherte Stelle
  `unsecuredDamage` (Start 0,05), beides mal `1 - damage_reduction`, zusammen höchstens bis
  Qualität 0. Rucke kosten auch bei einem Versuch, bei dem der Knochen zurückfällt (sonst könnte man
  nach jedem Ruck folgenlos neu ansetzen); ungesicherte Stellen nur beim Herausheben. Fehler kosten
  nur Qualität, der Knochen wird immer geborgen.
- **Ergebnis:** Der Knochen kommt mit seiner Qualität ins Inventar (`GameState.inventory`), das
  Bergen ist als „geborgen“ markiert. Die Anzeige zeigt Qualitätsstufe, Rucke, ungesicherte Stellen
  und den Verlust seit dem Öffnen des Bergens, danach „Zur Fläche“.
- **Screen:** gleiche Gesten wie beim Freilegen (Tippen sichert, ein Finger zieht, zwei verschieben
  und zoomen; kommt beim Ziehen ein zweiter Finger dazu, endet der Versuch). Oben Name und Qualität,
  „Brüchige Stellen gesichert: x von y“, Bedienhilfe und Rückmeldung (gesichert, daneben getippt,
  „Ruck!“ mit roter Markierung am Knochen, zurückgefallen mit Verlust). Ungesicherte Stellen
  leuchten pulsierend, beim Ziehen bleibt ein dunkler Abdruck im Boden. Gespeichert wird nach jeder
  Berührung, beim Herausheben und beim Öffnen und Schließen. Welcher Knochen gerade geborgen wird,
  ist Bedienzustand.
- **Inventar** (minimal bis AP2-11/14): Der Hauptbildschirm zeigt die Zahl der Knochen im Inventar
  und die Liste mit Name und Qualität, neueste zuerst.

## 6. Zuordnen am Skelett

- Geborgene Knochen werden per Hand dem 3D-Skelett der Art zugeordnet.
- **Hilfe wächst mit dem Wissen:** Bei einer neuen Art sieht man nur die Silhouette des Tiers und muss
  ausprobieren. Jeder einmal richtig platzierte Knochen gilt im Sammelbuch dauerhaft als „bekannt“:
  Beim nächsten Fund desselben Knochens gibt es Umriss und Einrasten.
- Upgrades (z. B. Bestimmungsbuch) können zusätzliche Hinweise geben.

## 7. Meta-Ebene

- **Zwei Währungen mit getrennten Aufgaben:**
  - **Ansehen** kommt aus dem Museum (ausgestellte Skelette, nach Vollständigkeit und Qualität) und
    schaltet neue Schichten und Fundorte frei.
  - **Geld** kommt aus dem Verkauf doppelter Knochen und kauft Werkzeuge und Upgrades.
- **Aufträge:** Das Museum stellt Wünsche (z. B. „Höhlenbär-Schädel in mindestens Gut“). Erfüllte
  Aufträge bringen Ansehen und Geld.
- **Sammelbuch:** bester Fund je Knochen, permanent; merkt sich außerdem, welche Knochen „bekannt“ sind.
- **Kein Prestige**, kein Skilltree, keine Einnahmen pro Sekunde.
- **Entfällt gegenüber 1.1:** Grabungsteams, Präparatoren, Offline-Fortschritt, Pacing-Simulation,
  Labor/Ersatzteile, Zonen, große Zahlen.

## 8. Darstellung

- **Graben in 2D, Skelett in 3D.**
  - Grabungsfläche, feines Freilegen und Bergen sind eine 2D-Draufsicht. Erde ist eine Maske, die mit dem
    Finger abgetragen wird. Die Knochen darin sind Bilder, die vorab automatisch aus den 3D-Modellen
    gerendert werden (Build-Skript), damit sie zum Modell passen. Jeder Knochen ist in seiner
    Liegelage zu sehen (Seite mit der größten Fläche nach oben, so wie er flach im Boden läge), alle
    Knochen einer Art im selben Maßstab.
  - Umsetzung (AP2-06): `tools/models3d/bone_images.py` rendert pro Knochen ein Bild (PNG mit
    Transparenz) und eine Umrissmaske (1-Bit-PNG) nach `app/src/main/assets/bones/<speciesId>/`, dazu
    ein `manifest.json` mit den Parametern und pro Knochen Drehung, Größe und Lage. Alle Knochen einer
    Art haben denselben Maßstab (Parameter `pxPerUnit`, Pixel pro Modelleinheit, Start 384), die
    Größenverhältnisse stimmen also. Liegelage (Parameter `view`, Standard `lying`): Hauptachsenanalyse
    der Knochenoberfläche, Blick entlang der Achse mit der kleinsten Ausdehnung, die Achse mit der
    größten Ausdehnung liegt waagerecht. Die Vorzeichen der Achsen legt das dritte Moment fest, damit
    sind die Bilder stabil, und linke und rechte Knochen haben denselben Umriss. Feste Ansichten für
    alle Knochen (`left`, `right`, `top`) bleiben als Option. Schattierung: Licht von oben (aus
    Blickrichtung, leicht schräg von links oben), Knochenfarbe aus dem Material der `.glb`. Renderer ist ein Software-Rasterizer mit numpy (läuft
    ohne GPU, auch in der CI, deterministisch; die CI prüft mit `--check`, ob die Bilder aktuell sind).
    Eine neue Art braucht nur einen erneuten Aufruf.
  - Umrisse in der Fläche (AP2-07): `tools/models3d/bone_footprints.py` rechnet aus den Masken die
    belegten Zellen je Knochen (Kapitel 3) nach `core/src/main/resources/data/footprints/<speciesId>.json`
    und dazu die Lage des Bilds relativ zu diesen Zellen (`imageLeft`, `imageTop` in Bildpixeln). Den
    Maßstab `cellsPerUnit` liest es aus `balance.json`; die CI prüft mit `--check`, der
    `DataValidator` prüft Vollständigkeit, Maßstab und dass jeder Umriss in die Fläche passt.
  - Feine Umrisse (AP2-08): `bone_footprints.py` schreibt je Knochen zusätzlich `fineRows`, das
    Rechteck des Umrisses in `preparation.subdivision` x `subdivision` feine Zellen geteilt, `#`
    dort, wo mindestens `fineCoverage` (Skript-Parameter, Standard 0,5) der feinen Zelle Knochen
    ist. Die Unterteilung liest es aus `balance.json`; `formatVersion` der Datei ist 2.
  - Nahansicht (AP2-08): Boden der Grube und Knochenbild wie in der Fläche, darüber die Restschicht
    als kleines Bild (8 Pixel je feiner Zelle, gestreckt und gefiltert, Kanten weich interpoliert
    und ausgefranst). Lose Erde ist heller, feinkörniger Sand und leicht durchscheinend (der Knochen
    zeichnet sich schwach ab), harte Stellen sind dunkle, rotbraune, fleckige Kruste und decken.
    Nach einer Aktion wird nur der geänderte Ausschnitt neu gerechnet.
  - Grabungsfläche (AP2-07): kein sichtbares Raster. Unten der Boden der Grube (Gesteinstextur der
    Schicht, abgedunkelt) mit den Knochenbildern an ihren Zellen, darüber die Erde als
    Gesteinstextur, ausgestanzt mit einer Erdmaske. Die Maske (8 Pixel je Zelle, gestreckt und
    gefiltert) entsteht aus den Erdtiefen: Werte je Zelle werden weich zwischen den Zellmitten
    interpoliert, Lochkanten mit festem Rauschen ausgefranst, die Tiefen-Abstufung (dunkler, je
    tiefer gegraben) über 3 x 3 Zellen gemittelt und erst unterhalb der kleinsten Anfangstiefe,
    damit die zufälligen Anfangstiefen kein Zellmuster zeigen. Nach einer Aktion wird nur der
    geänderte Ausschnitt neu gerechnet; Verschieben und Zoomen zeichnen nur neu.
  - Das Skelett in 3D zum Drehen gibt es beim Zuordnen, im Museum und im Sammelbuch.
- 3D-Meshes: eine `.glb`-Datei pro Knochen plus `bones.json` pro Art
  (`art/models3d/<speciesId>/`). Realistisch wirkender Look, nicht zwingend anatomisch exakt.
- **3D-Renderer (AP2-10): eigener kleiner OpenGL-ES-3.0-Renderer** in `app`
  (`ui/model3d`), keine 3D-Bibliothek. Geprüft wurden:
  - **SceneView** (Filament mit Compose-API, Apache 2.0, Maven Central): aktuelle Versionen (4.x)
    ziehen Kotlin-Stdlib 2.4 und Compose-BOM 2026.09 nach sich (Projekt: Kotlin 2.2, BOM 2025.08),
    also ein Toolchain-Upgrade, dazu Filament mit derselben APK-Größe wie unten.
  - **Filament mit gltfio** (Google, Apache 2.0, Maven Central, minSdk 21): schöne PBR-Darstellung
    und volle glTF-Unterstützung, aber native Bibliotheken für vier ABIs; Filament 1.70 macht die
    Debug-APK ohne die Modelle 21,9 MB größer (12,5 auf 34,4 MB), ab 1.77 braucht es compileSdk 37. Einfärben ginge
    über Material-Parameter, Ausblenden über die Szene, Treffertest über `View.pick`; eine Silhouette
    bräuchte eigene, vorab übersetzte Materialien (`matc`).
  - **Eigener Renderer:** Die Modelle kommen aus unserer eigenen Pipeline und nutzen nur eine kleine
    glTF-Teilmenge (ein Mesh je Datei, Positionen, Normalen, Indizes, eine Materialfarbe, keine
    Texturen, keine Transformationen). Ein Leser dafür (`GlbReader`) und ein Renderer mit einem
    Draw-Call je Knochen sind klein, ohne native Abhängigkeit und JVM-testbar (Leser, Kamera,
    Einfärbung). Einfärben, Ausblenden und später eine Silhouette sind je Knochen eine Farbe bzw.
    ein Schalter; für den Treffertest beim Zuordnen (AP2-11) liegen die Meshes auch auf der CPU
    (Strahl durch den Fingerpunkt gegen die Dreiecke oder eine ID-Farbe je Knochen in einem
    Offscreen-Bild). Die APK wächst nur um die Modelle selbst.

  Entscheidung für den eigenen Renderer: Er erfüllt alles, was Zuordnen, Sammelbuch und Museum
  brauchen, ohne Toolchain-Upgrade, und die Debug-APK wächst nur um 4,3 MB (fast nur die Modelle,
  12,5 auf 16,8 MB) statt zusätzlich um rund 22 MB für die Bibliothek. PBR-Materialien,
  Texturen, Animationen und Schatten gibt es damit nicht; die Beleuchtung ist dieselbe wie bei den
  2D-Knochenbildern (`bone_images.py`), Knochen sehen in Grabung und Skelett gleich aus. Sollte der
  Look später nicht reichen, ist der Renderer hinter `SkeletonView`/`BoneView` austauschbar.
- Umsetzung (AP2-10):
  - **Modelle in der App:** Die Gradle-Aufgabe `sync<Variante>ModelAssets` kopiert beim Build für
    jede Art aus `species.json` die Dateien `art/models3d/<speciesId>/bones/<boneId>.glb` nach
    `models3d/<speciesId>/bones/` in die Assets (nicht eingecheckt, keine Kopie im Repo, eine neue
    Art braucht keinen Code). Einzelne Dateien statt `cave_bear_all.glb`, weil jeder Knochen ein
    eigenes Netz mit eigener Farbe und Sichtbarkeit braucht, die Einzeldateien Normalen tragen (die
    Gesamtdatei nicht) und die Pipeline (`neighbors.py`, `bone_images.py`) ebenfalls von ihnen
    ausgeht. Höhlenbär: 105 Dateien, 6,7 MB, im APK komprimiert rund 4,1 MB; 274 650 Dreiecke, ein
    Draw-Call je Knochen. `ModelAssetsTest` prüft je Art Vollständigkeit und dass jede Datei in der
    unterstützten Teilmenge liegt.
  - **Compose-Elemente:** `SkeletonView(speciesId, boneIds, boneQualities, missingBones)` und
    `BoneView(speciesId, boneId, quality)`. Welcher Knochen welche Qualität hat, liefert der Aufrufer
    aus `core` (bis AP2-11 `BoneQualities`: beste Qualität je Knochen im Inventar oder
    Beispielqualitäten); Knochen ohne Eintrag werden je nach `missingBones` ausgeblendet oder
    neutral (Modellfarbe) gezeichnet.
  - **Einfärbung:** Stufe über `QualityTiers`, Farbe aus `balance.json: qualityTiers[].color`,
    gemischt wie im Referenz-Shader `skeleton.html`: `mix(knochen, stufe * (0.35 + 1.1 * helligkeit),
    0.62)`. Die Knochenfarbe bleibt erkennbar, die Stufe ist klar.
  - **Steuerung:** ein Finger dreht (0,4 Grad je dp, Nickwinkel auf ±80 Grad begrenzt, damit oben
    oben bleibt), zwei Finger zoomen (1 bis 4 wie beim Graben; 1 zeigt das ganze Modell). Die Kamera
    kreist um die Mitte der Bounding-Box aller Knochen der Ansicht (auch ausgeblendeter, damit nichts
    springt). Startansicht schräg von vorn links, leicht von oben. Gezeichnet wird nur bei
    Änderungen, nicht dauernd.
  - **Testansicht:** In Debug-Builds führt „3D-Ansicht (Test)“ auf dem Hauptbildschirm zu Skelett
    oder Einzelknochen mit Beispielqualitäten oder Inventar.

## 9. Umfang

- **Erster spielbarer Stand [MVP]:** eine Schicht (Quartär), eine Art (Höhlenbär). Ansehen wird schon
  gezählt, schaltet aber noch nichts frei. Seit AP2-03 enthalten die Spieldaten nur diese Schicht und
  diese Art.
- **Weitere Arten** kommen einzeln nach, sobald ihr 3D-Modell fertig ist (Regel 0.7). Die früheren
  17 Arten sowie die Schichten Neogen und Kreide (mit Seltenheitsgewichten und Texten) liegen auf
  `archive/incremental-v1.1` und werden von dort mit ihrem Modell zurückgeholt.
- **[Später]:** weitere Schichten, Ereignisse an Funden, Sondieren, kostenpflichtige Flächen,
  Alter/Geschlecht, Ton.

## 10. Repo und vorhandener Code

- **`main` wird an Ort und Stelle umgebaut** (kein Neuaufsetzen, kein Force-Push).
- Übernommen werden: Schichten, Arten, Qualitätsstufen und -farben, Modifier-System (für Upgrades),
  Speichern (atomar, Backups, Export/Import), Lokalisierung DE/EN, Einstellungen, Startbildschirm,
  Trennung `core` und `app`.
- Das 3D-Modell liegt auf dem Branch `art-3d-cave-bear` und kommt mit AP-01 nach `main`.
- **Stand nach AP2-02:** Alle Idle-Systeme samt Screens, Daten und Texten sind entfernt. Mit ihnen
  entfallen auch Teile, die nur ihnen dienten: Meilensteine, Generatoren und Käufe des alten Designs,
  Buch-Boni und Completion, Rekonstruktion, die 2D-Skelettgrafiken (`art/skeletons`, `svg2json`,
  zonenbasiert) und die Einstellung „Zahlenformat“ (nur für große Zahlen nötig). Der Spielstand
  enthält bis AP2-03 nur `saveVersion` (weiter 1), `lastSavedAt` und die Einstellungen; ältere
  Spielstände laden weiter, ihre alten Felder werden ignoriert. `GameNumber` ist entfernt; Geld und
  Ansehen kommen als `Long` in den Spielstand, sobald ein AP sie braucht. Das Modifier-System behält nur die
  Stats `damage_reduction`, `quality_base_min`, `sell_price` und die Kategorien `upgrade` und
  `event`; die endgültigen Stats legt AP2-14 fest.
- **Stand nach AP2-03:** Übernommen aus dem alten Stand sind nur noch die Schicht Quartär und die Art
  Höhlenbär (Kapitel 9); die generische `bones.json` (8 Knochengruppen) ist durch die Einzelknochen
  der Art in `species.json` ersetzt. Ein `Piece` hat Art, Knochen, Qualität (0 bis 1) sowie `age` und
  `sex` (beide `null` bis [Später]); ein `Find` ist eine nicht leere Menge von `Piece`s einer Art,
  deren Knochen im Nachbarschaftsgraph zusammenhängen (geprüft gegen die Daten). Der Spielstand hat
  `saveVersion` 3 und enthält `lastSavedAt`, die Einstellungen und das Inventar (`inventory`,
  geborgene, noch nicht zugeordnete `Piece`s, gefüllt ab AP2-09). Funde kommen mit der
  Grabungsfläche (AP2-05) in den Spielstand. Version 2 wurde nie verwendet. Spielstände mit Version 1
  oder 2 werden nicht migriert, sondern verworfen: Die App startet ein neues Spiel und zeigt einen
  Hinweis; übernommen wird nur die Spracheinstellung (Bedienvorliebe, kein Fortschritt; wie beim
  „Spielstand löschen“). Beim Import werden alte Spielstände abgelehnt und importierte Spielstände
  zusätzlich gegen die Spieldaten geprüft (unbekannte Arten oder Knochen werden abgelehnt).
- **Stand nach AP2-04:** `FindRoller` würfelt einen Fund für eine Schicht nach Kapitel 4 (Eingabe
  Schicht-ID und `GameRandom`, Ausgabe der `Find` samt Grundqualität). Parameter in
  `balance.json: finds`, geprüft im Schema und im `DataValidator`. Der Stat `quality_base_min` hebt
  die Untergrenze der Grundqualität an. Platziert und gespeichert werden Funde erst mit AP2-05.
- **Stand nach AP2-05:** Die Grabungsfläche (`ExcavationSite`) mit Raster, Erdtiefen und platzierten
  Funden steht in `core` (Kapitel 3 und 4), ohne Screen (AP2-07). Werkzeuge liegen in `tools.json`
  (Namen `tool.<id>.name` in den Locale-Dateien), die Flächenparameter in `balance.json: excavation`.
  **Übergang bis AP2-06:** Jeder Knochen belegt als Platzhalter dasselbe Rechteck
  `excavation.placeholderFootprint` (Start 4 x 3 Zellen). Die Platzierung arbeitet über die
  Schnittstelle `BoneFootprints` mit beliebigen Zellmengen; AP2-06/07 übergibt dort Umrisse aus den
  Knochenmasken, ohne dass sich die Platzierung ändert. Die belegten Zellen werden pro Knochen
  gespeichert, laufende Ausgrabungen bleiben also gültig, wenn sich die Umrisse ändern. Der Spielstand
  hat `saveVersion` 4 mit dem Feld `excavation` (laufende Ausgrabung oder `null`); die Migration 3 -> 4
  setzt `null`. Die Tiefen werden kompakt als Base64 von 16-Bit-Werten gespeichert (daher
  `depthMax` höchstens 65535). Beim Import werden auch die Funde der Ausgrabung gegen die Spieldaten
  geprüft (Arten, Knochen, Zusammenhang, Schicht).
- **Stand nach AP2-06:** Knochenbilder (Liegelage) und Umrissmasken aller Knochen des Höhlenbären
  liegen in den App-Assets (Kapitel 8), Pfade über `BoneImageAssets` in `app`, ein App-Test prüft,
  dass jeder Knochen jeder Art Bild und Maske hat. Die Grabungsfläche nutzt noch den
  Platzhalter-Umriss. Entschieden ist: Umrisse kommen aus den Masken, mit
  `excavation.cellsPerUnit` = 8 Zellen pro Modelleinheit (vorläufiger Balancing-Wert) und ohne Drehung
  beim Platzieren; umgesetzt wird das in AP2-07.
- **Stand nach AP2-07:** Grobes Buddeln ist spielbar (Kapitel 3, „Screen“). Der Platzhalter-Umriss
  (`excavation.placeholderFootprint`) ist entfernt, die Umrisse kommen aus
  `data/footprints/<speciesId>.json` (`excavation.cellsPerUnit`, Kapitel 8). Spielstände mit
  Platzhalter-Zellen bleiben gültig (die Zellen sind gespeichert); deren Knochenbilder stehen mittig
  auf dem Rechteck. **Übergang bis AP2-08/09:** „Ausgrabung beenden“ setzt `excavation` auf `null`;
  gefundene Knochen werden noch nicht freigelegt, geborgen oder ins Inventar gelegt, sie entfallen mit
  der Fläche. Wie es nach dem groben Buddeln weitergeht, legen AP2-08 und AP2-09 fest.
- **Stand nach AP2-08:** Feines Freilegen ist spielbar (Kapitel 5.1). Werkzeuge haben ein Feld
  `stage` (`dig`/`prepare`) und `removesHard`; Schaufel und Kelle wirken nur auf der Fläche, Pinsel
  und Kratzer nur in der Nahansicht. Die Fläche speichert je begonnenem Knochen eine Nahansicht
  (`preparations`: Raster, Ursprung, Unterteilung, Restschicht als Base64 mit einem Byte je feiner
  Zelle, Bit 7 hart, Bits 0..6 Dicke, daher Dicke höchstens 127; Knochenzellen als Bitfeld; `finished`).
  Der Spielstand hat `saveVersion` 5; die Migration 4 -> 5 ergänzt in einer laufenden Ausgrabung
  `preparations: []`. **Übergang bis AP2-09:** „Ausgrabung beenden“ setzt `excavation` weiter auf
  `null`; freigelegte Knochen samt Qualität entfallen mit der Fläche, bis AP2-09 sie bergen und ins
  Inventar legen lässt.
- **Stand nach AP2-09:** Bergen ist spielbar (Kapitel 5.2), **Graben bis Bergen ist durchspielbar**.
  Die Fläche speichert je begonnenem Bergen `recoveries` (Fund, Knochen, brüchige Stellen als feine
  Zellen der Nahansicht, `secured` je Stelle, `recovered`); ein Bergen setzt eine fertige
  Nahansicht voraus, die Stellen müssen auf Knochenzellen liegen (geprüft beim Laden). Geborgene
  Knochen kommen ins Inventar (`inventory`), der Fund bleibt in der Fläche. Der Spielstand hat
  `saveVersion` 6; die Migration 5 -> 6 ergänzt in einer laufenden Ausgrabung `recoveries: []`.
  „Ausgrabung beenden“ setzt `excavation` auf `null`; nicht geborgene Knochen bleiben zurück
  (Kapitel 3). Beim Import werden Inventar und Funde wie bisher gegen die Spieldaten geprüft.
- **Stand nach AP2-10:** Der 3D-Renderer steht (Kapitel 8): eigener OpenGL-ES-3.0-Renderer mit
  `SkeletonView` und `BoneView` in `app/ui/model3d`, Meshes kommen per Gradle-Aufgabe aus
  `art/models3d` in die Assets. Die App verlangt OpenGL ES 3.0 (`uses-feature`). Zu sehen ist das
  Skelett bisher nur in der Testansicht der Debug-Builds; Zuordnen, Sammelbuch und Museum
  (AP2-11 bis AP2-13) nutzen die Elemente. Spielstand unverändert (Version 6).

## 11. Entscheidungen im Überblick

| Thema | Entscheidung |
|---|---|
| Meta-Ebene | Museum ja, Prestige nein |
| Repo | `main` an Ort und Stelle umbauen |
| Fehler | nur Qualitätsverlust, nie Totalverlust |
| Ausgrabung | freie Fläche, kein sichtbares Raster, kein Schrittlimit |
| Grobes Buddeln | Wischen mit Hinweisen, Sondieren später als Upgrade |
| Fein freilegen | Pinsel und Kratzer |
| Bergen | mit Gips sichern, ruhig herausheben; Bewegung als Samples (ms, dp), Rucke über Geschwindigkeitswechsel je 50-ms-Fenster, Rucke kosten auch bei Zurückfallen (ab AP2-09) |
| Ausgrabung beenden | erlaubt mit Rückfrage; nicht geborgene Knochen bleiben zurück (ab AP2-09) |
| Zuordnen | Hilfe wächst mit dem Wissen im Sammelbuch |
| Museum | Ansehen schaltet Fundorte frei, Geld aus Duplikaten, dazu Aufträge |
| Darstellung | Graben in 2D, Skelett in 3D |
| 3D-Renderer | eigener OpenGL-ES-3.0-Renderer, keine 3D-Bibliothek (Größe, Toolchain, Bedarf: Farbe/Sichtbarkeit je Knochen); Modelle per Gradle aus `art/models3d` (ab AP2-10) |
| Knochenbilder | Liegelage pro Knochen (Seite mit der größten Fläche), einheitlicher Maßstab pro Art |
| Knochenumrisse in der Fläche | aus den Masken, 8 Zellen pro Modelleinheit (vorläufig), ohne Drehung (ab AP2-07) |
| Wischen | eine Anwendung je `strokeSpacing` Zellen Wischweg, ein Finger gräbt, zwei verschieben/zoomen |
| Nahansicht | pro Knochen, feines Raster (4 x 4 je Zelle), Restschicht lose/hart, Kratzer trifft nur Knochen, der vor dem Zug schon frei lag (ab AP2-08) |
| Erster Stand | nur Höhlenbär, weitere Arten einzeln |

## 12. Arbeitspakete

Format: **ID – Titel** (Abhängigkeiten), darunter Akzeptanzkriterien. Branch `ap2-XX-kurzname`,
PR-Titel `AP2-XX: <Titel>` (Präfix `ap2`, damit es keine Verwechslung mit den alten AP-Branches gibt).

### Phase 1 – Umbau der Basis
- **AP2-01 – Design und Regeln auf 2.0** (–)
  `docs/GAME_DESIGN.md` durch dieses Dokument ersetzen, `CLAUDE.md` an Kapitel 0 anpassen, README
  aktualisieren. Inhalt von `art-3d-cave-bear` (`art/models3d/cave_bear/`) nach `main` holen. Kein Code.
- **AP2-02 – Idle-Systeme entfernen** (AP2-01)
  Entfernt: Teams, Präparatoren, Blöcke, `advance()` und Game-Loop, Offline, Prestige/Ruhm, Skilltree,
  Labor/Ersatzteile, Zonen, `GameNumber` (Geld und Ansehen als `Long`), Pacing-Test, `tools/sim`,
  zugehörige Screens, Daten und Texte. Bleibt und läuft weiter: Startbildschirm, Einstellungen,
  Speichern, Lokalisierung, Modifier-System, Datenvalidierung. Build und Tests grün, App startet bis zu
  einem leeren Hauptbildschirm.
- **AP2-03 – Datenmodell 2.0** (AP2-02)
  `species.json` mit Knochenliste und Nachbarschaftsgraph (abgeleitet aus den Meshes des Modells plus
  Korrekturdatei, siehe Kapitel 4),
  `Piece` (Art, Knochen, Qualität; `age`/`sex` nullable für später), `Find` (zusammenhängende Knochen),
  neuer Spielstand mit `saveVersion` 3 (alte Spielstände werden verworfen, mit Hinweis). Validierung:
  Graph ist zusammenhängend, jeder Knochen hat ein Modell, alle Texte in de/en. Round-Trip-Test.
- **AP2-04 – Fundsystem** (AP2-03)
  Würfeln nach Kapitel 4 mit Parametern aus `balance.json` (Verteilung der Fundgröße, Grundqualität,
  Abweichung pro Knochen). Tests mit festem Seed: Knochen eines Fundes sind im Graph zusammenhängend,
  Qualitäten liegen innerhalb der Abweichung, Verteilung über 10 000 Würfe plausibel.

### Phase 2 – Graben
- **AP2-05 – Grabungsfläche (core)** (AP2-04)
  Erzeugt eine Fläche mit internem Raster, Erdtiefe pro Zelle und platzierten Funden (zusammenhängende
  Knochen liegen beieinander, keine Überlappung). Aktion „Werkzeug wirkt auf Bereich“: trägt Erde ab,
  meldet Hinweise kurz vor einem Knochen, berechnet Schaden bei grobem Werkzeug auf Knochen. Zustand
  „nichts mehr zu finden“. Fläche ist speicherbar. Tests für alle Punkte.
- **AP2-06 – Knochenbilder aus 3D-Modellen** (AP2-01)
  Skript in `tools/`, das pro Knochen aus der `.glb` ein Bild in Liegelage (Seite mit der größten
  Fläche, einheitlicher Maßstab) mit Transparenz und eine Umrissmaske rendert. Läuft für den Höhlenbären durch, Ausgabe wird ins App-Modul eingebunden.
  Anleitung im README des Skripts. Für eine neue Art reicht ein erneuter Aufruf.
- **AP2-07 – Screen grobes Buddeln** (AP2-05, AP2-06)
  Freie Fläche ohne sichtbares Raster, Wischen trägt Erde ab, Werkzeugwahl (Schaufel, Kelle), Hinweise
  sichtbar, Schaden wird angezeigt, Verschieben/Zoomen der Fläche, Meldung „keine Knochen mehr“,
  Ausgrabung beenden. Echte Umrisse aus den Masken (`cellsPerUnit` 8, ohne Drehung) statt
  Platzhalter-Footprint. Läuft flüssig auf einem Mittelklasse-Gerät.
- **AP2-08 – Fein freilegen** (AP2-07)
  Nahansicht einer Fundstelle nach 5.1: Pinsel und Kratzer, Restschicht mit losen und harten Stellen,
  Schaden bei Kratzer auf Knochen, Fortschrittsanzeige. Logik in `core` mit Tests, Werte aus JSON.
- **AP2-09 – Bergen** (AP2-08)
  Nach 5.2: brüchige Stellen sichern, ruhiges Herausheben mit Bewertung der Bewegung, Schaden für
  ungesicherte Stellen und Rucke. Danach liegt der Knochen im Inventar. Logik in `core` mit Tests.
  **Ab hier ist Graben bis Bergen durchspielbar.**

### Phase 3 – Skelett
- **AP2-10 – 3D-Renderer** (AP2-01)
  Renderer für glTF in Compose auswählen (kurze Begründung in Kapitel 8 nachtragen), einen Knochen und
  das ganze Höhlenbär-Skelett drehbar und zoombar anzeigen, Einfärbung nach Qualitätsstufe.
- **AP2-11 – Zuordnen am Skelett** (AP2-09, AP2-10)
  Nach Kapitel 6: Knochen aus dem Inventar an die richtige Stelle ziehen, Silhouette bei unbekannten
  Knochen, Umriss und Einrasten bei bekannten, „bekannt“ wird dauerhaft gespeichert. Besserer Fund
  ersetzt den vorhandenen Knochen, der alte wird zum Duplikat. **Ab hier ist der Kern-Loop komplett.**
- **AP2-12 – Sammelbuch** (AP2-11)
  Pro Art: 3D-Skelett mit den besten Funden aller Zeiten, Liste der Knochen mit Qualität und
  Bekannt-Status, Fortschritt pro Art.

### Phase 4 – Meta
- **AP2-13 – Museum und Ansehen** (AP2-11)
  Ausgestellte Skelette, Ansehen aus Vollständigkeit und Qualität (Formel und Werte aus JSON),
  Freischalt-Schwellen für Schichten/Fundorte in den Daten (im ersten Stand ohne Inhalt dahinter).
- **AP2-14 – Verkauf, Werkzeuge, Upgrades** (AP2-11)
  Duplikate verkaufen (Preis nach Qualität), Shop für Werkzeuge und Upgrades über das Modifier-System
  (Toleranzen, Schaden, Tempo, Hinweise). Jede Upgrade-Wirkung hat einen Test.
- **AP2-15 – Aufträge** (AP2-13, AP2-14)
  Datengetriebene Aufträge (Art, Knochen oder Knochengruppe, Mindestqualität), Belohnung in Ansehen und
  Geld, Anzeige im Museum.

**Reihenfolge:** 01 → 02 → 03 → 04 → 05 → 06 → 07 → 08 → 09 → 10 → 11 → 12 → 13 → 14 → 15.
AP2-06 und AP2-10 hängen nur an AP2-01 und können vorgezogen werden.

## 13. Offene Punkte

- Endgültiger Name (Arbeitstitel „Fossil Collector“).
- Konkrete Balancing-Werte (Fundgrößen, Schaden, Preise, Ansehen) nach dem ersten Playtest.
- Größe der Grabungsfläche und Anzahl Funde pro Ausgrabung (Startwerte seit AP2-05 in
  `balance.json: excavation`, endgültig nach dem Playtest). Mit den echten Umrissen belegen Knochen
  im Mittel nur rund 44 von 1 536 Zellen (knapp 3 %).
- Tempo beim Wischen (`strokeSpacing`) und ob ein Hinweis bei der Schaufel lange genug sichtbar ist
  (ein Wischzug kann eine Zelle zweimal treffen), nach dem Playtest.
- Feines Freilegen (AP2-08): Schaden des Kratzers (`boneDamage` 0.005 je Anwendung, ein ganzer Zug
  über den freien Oberschenkel kostet rund 0,16 Qualität), Menge und Größe der harten Stellen, Tempo
  des Pinsels und die Schwelle `finishedShare`, nach dem Playtest. Ob langes Drücken zum Öffnen der
  Nahansicht gut auffindbar ist.
- Bergen (AP2-09): Schwellen der Bewegung (`jerkSpeedChange` 400 dp/s je 50 ms, `maxSpeed`
  700 dp/s, Zugweg 140 dp), Schaden je Ruck (0,04) und je ungesicherter Stelle (0,05), Anzahl und
  Abstand der brüchigen Stellen und der Trefferradius (3 feine Zellen; beim Oberschenkel ohne Zoom
  rund 20 dp) nach dem Playtest auf einem echten Gerät. Ob nicht geborgene Knochen beim Beenden
  verloren gehen sollen oder fertig freigelegte automatisch mit Abschlag geborgen werden
  (Entscheidung des Projektinhabers, siehe PR zu AP2-09).
- 3D-Ansicht (AP2-10): Test auf einem echten Gerät steht aus (Darstellung, Flüssigkeit mit 105
  Knochen auf einem Mittelklasse-Gerät, Gesten, Drehgeschwindigkeit 0,4 Grad je dp). Ob die
  Stufenfarben am Skelett gut unterscheidbar sind (Fragment grau vs. neutrale Knochenfarbe) und ob
  Verschieben beim Zoomen nötig ist (heute kreist die Kamera um die Mitte), mit AP2-11.
- Ob ein kurzes Tutorial nötig ist.

## 14. Änderungsprotokoll

- **2.0 (2026-11-12):** Neues Design als aktives Grabungsspiel. Alle Entscheidungen aus Kapitel 11,
  Umfang des ersten Stands, Agenten-Regeln und Arbeitspakete AP2-01 bis AP2-15.
- **AP2-02 (2026-11-12):** Kapitel 10 um den Stand nach dem Entfernen der Idle-Systeme ergänzt
  (mitentfernte Teile, minimaler Spielstand bis AP2-03, verbleibende Stats).
- **AP2-03 (2026-11-12):** Nachbarschaftsgraph wird aus den Meshes des Modells plus Korrekturdatei
  abgeleitet, nicht aus `bones.json` (Kapitel 4, 12). Spieldaten nur noch mit Quartär und Höhlenbär
  (Kapitel 9). Kapitel 10 um den Stand nach AP2-03 ergänzt (Datenmodell, Spielstand Version 3,
  Verwerfen alter Spielstände mit Übernahme der Sprache, Inventar, Import-Prüfung).
- **AP2-04 (2026-11-12):** Kapitel 4 um die konkrete Umsetzung des Würfelns ergänzt (gewichtete
  Fundgrößen-Tabelle, gleichverteilter Startknochen und Erweiterung über den Rand, gleichverteilte
  Grundqualität mit `quality_base_min` als Untergrenze, gleichverteilte Abweichung pro Knochen,
  Startwerte vorläufig). Kapitel 10 um den Stand nach AP2-04 ergänzt.
- **AP2-05 (2026-11-14):** Kapitel 3 um die Umsetzung der Grabungsfläche ergänzt (Raster, Werkzeuge in
  `tools.json`, Aktion „Werkzeug wirkt auf Bereich“, Hinweis-Schwelle, Schaden einmal pro Knochen und
  Anwendung, „entdeckt“ ab einer freiliegenden Zelle, „nichts mehr zu finden“). Kapitel 4 „Lage“ um die
  Platzierung ergänzt. Kapitel 10 um den Stand nach AP2-05 (Platzhalter-Umriss bis AP2-06, Spielstand
  Version 4). Kapitel 13 präzisiert.
- **AP2-06 (2026-11-16):** „Draufsicht“ der Knochenbilder präzisiert als Liegelage pro Knochen (Seite
  mit der größten Fläche) bei einheitlichem Maßstab pro Art (Kapitel 8, 11, 12), damit Rippen ihre
  Krümmung zeigen und Wirbel und Becken erkennbar sind. Kapitel 8 um die Umsetzung ergänzt (Skript,
  Ausgabeort, Hauptachsenanalyse, Renderer). Entscheidung zu den Knochenumrissen in der Fläche
  (8 Zellen pro Modelleinheit, ohne Drehung) in Kapitel 10 und 11 festgehalten, Umsetzung in AP2-07
  (Kapitel 12); der offene Punkt dazu in Kapitel 13 ist erledigt.
- **AP2-07 (2026-11-16):** Kapitel 3 um die echten Umrisse und den Screen grobes Buddeln ergänzt
  (Start, Gesten, `strokeSpacing` in `tools.json`, Zoom-Grenzen, Darstellung von Hinweisen und
  Schaden, „keine Knochen mehr“, Beenden mit Rückfrage, Speichern). Kapitel 4 „Lage“ auf die Umrisse
  bezogen. Kapitel 8 um `bone_footprints.py` und die Darstellung der Fläche ergänzt. Kapitel 10 um
  den Stand nach AP2-07 samt Übergang bis AP2-08/09, Kapitel 11 um das Wischen, Kapitel 13 um offene
  Balancing-Punkte.
- **AP2-08 (2026-11-16):** Kapitel 5.1 um die Umsetzung des feinen Freilegens ergänzt (Nahansicht
  pro Knochen mit Begründung, Öffnen, feines Raster, Restschicht mit losen und harten Stellen,
  Pinsel und Kratzer, Schaden-Regel „vor dem Zug schon frei“, Fortschritt und „fertig freigelegt“,
  Screen). Präzisierung: Beim ersten Öffnen fällt die restliche grobe Erde über dem Knochen weg.
  Kapitel 3 um `stage`/`removesHard` der Werkzeuge und das Öffnen, Kapitel 8 um `fineRows` und die
  Darstellung der Restschicht, Kapitel 10 um den Stand nach AP2-08 (Spielstand Version 5, Übergang
  bis AP2-09), Kapitel 11 und 13 ergänzt.
- **AP2-09 (2026-11-17):** Kapitel 5.2 um die Umsetzung des Bergens ergänzt (Einstieg aus der
  fertigen Nahansicht, brüchige Stellen, Sichern mit Trefferradius, Heben mit Zugweg, Bewertung der
  Bewegung in Zeitfenstern, Schaden für Rucke und ungesicherte Stellen, Ergebnis, Screen, minimales
  Inventar). Präzisierungen: Rucke kosten auch, wenn der Knochen zurückfällt; Beenden der Ausgrabung
  lässt nicht geborgene Knochen zurück (mit Rückfrage), hervorgehoben wird „Beenden“ erst, wenn alles
  geborgen ist; geborgene Knochen zählen in der Fläche nicht mehr (Kapitel 3). Kapitel 10 um den
  Stand nach AP2-09 (Spielstand Version 6), Kapitel 11 und 13 ergänzt.
- **AP2-10 (2026-11-25):** Renderer-Wahl in Kapitel 8 nachgetragen (eigener OpenGL-ES-3.0-Renderer
  statt SceneView/Filament, mit Vergleich und Begründung) samt Umsetzung (Modelle per Gradle in die
  Assets, `SkeletonView`/`BoneView`, Einfärbung, Steuerung, Testansicht). Kaputten Satzrest in
  Kapitel 8 („zum Drehen gibt es …“) als eigenen Punkt gefasst. Kapitel 10 um den Stand nach
  AP2-10, Kapitel 11 um den Renderer, Kapitel 13 um den offenen Gerätetest ergänzt.

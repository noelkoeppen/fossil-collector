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
- **Werkzeuge** (`tools.json`): Abtrag pro Anwendung und Zelle (`removal`), Wirkradius in Zellen
  (`radius`), `rough` (grob ja/nein) und `boneDamage` (Qualitätsverlust pro Treffer, nur bei groben
  Werkzeugen > 0). Startwerte: Schaufel 20 / 2.5 / grob / 0.08, Kelle 6 / 1.5 / nicht grob / 0.
- **Aktion „Werkzeug wirkt auf Bereich“:** Eingabe Werkzeug-ID und Ansatzpunkt in Zellkoordinaten.
  Betroffen sind alle Zellen, deren Mittelpunkt höchstens `radius` vom Ansatzpunkt entfernt ist; dort
  wird bis zu `removal` Erde abgetragen. Ergebnis als Daten: neue Fläche, abgetragene Erde, geänderte
  Zellen, neue Hinweise, Schaden je Knochen, neu entdeckte Knochen. Wie oft die UI die Aktion beim
  Wischen auslöst, legt AP2-07 fest.
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
  finden. Das Freilegen der Restschicht folgt in 5.1 (AP2-08).

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
  Knochen eines Fundes werden in Breitensuche-Reihenfolge über seinen Nachbarschaftsgraph gesetzt, jeder
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

### 5.2 Bergen: Sichern und heben
- Brüchige Stellen am Knochen leuchten auf und müssen mit Gips gesichert werden (antippen).
- Danach wird der Knochen mit ruhigem Ziehen herausgehoben. Ruckartige Bewegungen und ungesicherte
  Stellen kosten Qualität.

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
    gerendert werden (Build-Skript), damit sie zum Modell passen.
  - Echtes 3D zum Drehen gibt es beim Zuordnen, im Museum und im Sammelbuch.
- 3D-Meshes: eine `.glb`-Datei pro Knochen plus `bones.json` pro Art
  (`art/models3d/<speciesId>/`). Realistisch wirkender Look, nicht zwingend anatomisch exakt.
- Wahl des 3D-Renderers für Android/Compose: wird in AP-10 geprüft und hier nachgetragen.

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

## 11. Entscheidungen im Überblick

| Thema | Entscheidung |
|---|---|
| Meta-Ebene | Museum ja, Prestige nein |
| Repo | `main` an Ort und Stelle umbauen |
| Fehler | nur Qualitätsverlust, nie Totalverlust |
| Ausgrabung | freie Fläche, kein sichtbares Raster, kein Schrittlimit |
| Grobes Buddeln | Wischen mit Hinweisen, Sondieren später als Upgrade |
| Fein freilegen | Pinsel und Kratzer |
| Bergen | mit Gips sichern, ruhig herausheben |
| Zuordnen | Hilfe wächst mit dem Wissen im Sammelbuch |
| Museum | Ansehen schaltet Fundorte frei, Geld aus Duplikaten, dazu Aufträge |
| Darstellung | Graben in 2D, Skelett in 3D |
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
  Skript in `tools/`, das pro Knochen aus der `.glb` ein Draufsicht-Bild mit Transparenz und eine
  Umrissmaske rendert. Läuft für den Höhlenbären durch, Ausgabe wird ins App-Modul eingebunden.
  Anleitung im README des Skripts. Für eine neue Art reicht ein erneuter Aufruf.
- **AP2-07 – Screen grobes Buddeln** (AP2-05, AP2-06)
  Freie Fläche ohne sichtbares Raster, Wischen trägt Erde ab, Werkzeugwahl (Schaufel, Kelle), Hinweise
  sichtbar, Schaden wird angezeigt, Verschieben/Zoomen der Fläche, Meldung „keine Knochen mehr“,
  Ausgrabung beenden. Läuft flüssig auf einem Mittelklasse-Gerät.
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
  `balance.json: excavation`, endgültig nach dem Playtest).
- Knochengröße in Zellen, sobald die Umrisse aus AP2-06 kommen (Zellen pro Modelleinheit).
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

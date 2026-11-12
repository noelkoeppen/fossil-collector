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
- **Qualität zieht sich durch:** Alle Knochen eines Fundes teilen die Grundqualität, mit kleiner
  Abweichung pro Knochen. Schaden trifft jeden Knochen einzeln.
- **Lage:** Zusammenhängende Knochen liegen in der Fläche beieinander.
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
  gezählt, schaltet aber noch nichts frei.
- **Weitere Arten** kommen einzeln nach, sobald ihr 3D-Modell fertig ist (Regel 0.7).
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
  `species.json` mit Knochenliste und Nachbarschaftsgraph (abgeleitet aus `bones.json` des Modells),
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
- Größe der Grabungsfläche und Anzahl Funde pro Ausgrabung.
- Ob ein kurzes Tutorial nötig ist.

## 14. Änderungsprotokoll

- **2.0 (2026-11-12):** Neues Design als aktives Grabungsspiel. Alle Entscheidungen aus Kapitel 11,
  Umfang des ersten Stands, Agenten-Regeln und Arbeitspakete AP2-01 bis AP2-15.
- **AP2-02 (2026-11-12):** Kapitel 10 um den Stand nach dem Entfernen der Idle-Systeme ergänzt
  (mitentfernte Teile, minimaler Spielstand bis AP2-03, verbleibende Stats).

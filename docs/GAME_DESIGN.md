# GAME_DESIGN.md – Fossil Collector (Arbeitstitel)

> Zentrales Design-Dokument. Grundlage für die Umsetzung durch Claude-Code-Agenten.
> Stand: 2026-11-05 · Version 1.0
> Plattform: Android · Stack: Kotlin + Jetpack Compose · Sprachen: Deutsch (Standard) und Englisch

---

## 0. Regeln für Agenten (zuerst lesen)

1. **Ein Arbeitspaket (AP) pro Session/PR.** Die Aufgabenliste steht in Kapitel 14. Nur das umsetzen, was im AP steht.
2. **Markierungen:** `[MVP]` wird jetzt gebaut. `[Später]` wird **nicht** gebaut, aber Datenmodell und Schnittstellen dürfen es nicht verbauen.
3. **Keine hartcodierten Spielwerte.** Jede Zahl, die das Balancing betrifft (Kosten, Raten, Chancen, Dauern, Boni), kommt aus den JSON-Dateien in `core/src/main/resources/data/`. Im Code stehen nur Formeln.
4. **Das Modul `core` hat keine Android-Abhängigkeiten.** Es enthält die gesamte Spiellogik und ist mit JVM-Unit-Tests vollständig testbar.
5. **Ein einziger Simulationspfad:** Online-Tick und Offline-Fortschritt rufen dieselbe Funktion `advance()` auf (Kapitel 10.3).
6. **Zahlen:** Spielwerte nie als `Int`/`Long` speichern (Überlaufgefahr), sondern immer als `GameNumber` (Kapitel 6.6).
7. **Zufall:** `core` bekommt seinen Zufallsgenerator injiziert (`GameRandom`-Interface). Tests verwenden feste Seeds.
8. **Felder für spätere Features bleiben drin:** `age` und `sex` an Funden existieren ab dem MVP als nullable-Felder (Kapitel 4.8). Nicht entfernen, nicht „optimieren“.
9. **Alle Boni laufen über das Modifier-System** (Kapitel 6.2). Keine Sonderlogik à la `if (skillOwned) rate *= 1.1`.
10. **Texte:** Keine Strings im Code. UI-Texte stehen in `strings.xml` (de/en), Inhaltstexte in den Locale-JSONs (Kapitel 12).
11. **Definition of Done für jedes AP:** Build grün, Tests grün, neue Logik in `core` hat Tests, JSON-Validierung grün, keine neuen Warnungen. Wenn das AP vom Design abweichen muss, wird dieses Dokument im selben PR angepasst und die Abweichung kurz begründet.
12. Keine Emojis im Code.

---

## 1. Spielidee, Setting, Kern-Loop

### 1.1 Pitch
Der Spieler leitet eine paläontologische Ausgrabung. Er gräbt sich durch Erdschichten in immer ältere Erdzeitalter, präpariert Fossilien, deren Qualität vom Zufall und von seinen Werkzeugen abhängt, erforscht Duplikate im Labor und stellt die besten Stücke in seinem Museum aus. Langfristiges Ziel ist ein vollständiges **Sammelbuch** mit allen Arten in bestmöglicher Qualität.

### 1.2 Ton und Stil
Entspannt, sammelorientiert, „noch ein Fund“-Gefühl. Die Zahlen bleiben moderat (Millionen bis Billionen) und explodieren nicht. Der Reiz liegt in den Funden und der Qualität.

### 1.3 Kern-Loop [MVP]
```
Grabungsteams graben  ->  Rohblöcke (pro Schicht)
Präparatoren / Tippen ->  Block wird freigelegt
   ~92 %: Gestein     ->  etwas Geld
    ~8 %: Fossil      ->  Art + Knochen + Qualität pro Zone
        neu/besser    ->  Museum (Run) + Sammelbuch (permanent)
        Duplikat      ->  Labor (FP + Ersatzteile) oder Verkauf (Geld)
Museum                ->  Geld pro Sekunde
Geld                  ->  Teams, Präparatoren, Hallen, Laborplätze, neue Schichten
FP                    ->  bessere Werkzeuge (weniger Präparationsschaden)
Vollständige Skelette ->  Ruhm beim Prestige -> Skilltree
```

### 1.4 Verarbeitungskette (thematischer Rahmen)
Graben → Präparieren → (Labor) → Rekonstruieren → Ausstellen. Später kommen dazu: Alter/Geschlecht bestimmen, Kontinente, Wiederbelebung bzw. Reservat (Kapitel 13).

---

## 2. Glossar (Begriff DE / Code-Bezeichner)

| DE | Code | Bedeutung |
|---|---|---|
| Geld | `money` | Hauptwährung im Durchlauf |
| Forschungspunkte (FP) | `researchPoints` | Aus dem Labor, für Werkzeuge |
| Ruhm | `fame` | Prestige-Währung, permanent |
| Rohblock | `block` | Gegrabenes Gestein, pro Schicht |
| Schicht | `layer` | Erdzeitalter, Tiefe, Fundort von Arten |
| Art | `species` | z. B. Parasaurolophus |
| Knochen(typ) | `bone` | z. B. Rückenwirbel; jede Art hat 8 |
| Zone | `zone` | Teilbereich eines Knochens, 4 pro Knochen |
| Stück / Fund | `piece` | Konkretes gefundenes Exemplar eines Knochens einer Art |
| Ersatzteil | `spareZone` | Bestes gesichertes Zonen-Stück im Labor |
| Rekonstruktion | `reconstruction` | Knochen, aus Ersatzteilen zusammengesetzt |
| Skelett | `skeleton` | Alle 8 Knochen einer Art vorhanden |
| Durchlauf | `run` | Zeit zwischen zwei Prestiges |
| Sammelbuch | `book` | Permanenter Katalog, bester Fund je Knochen |
| Qualität | `quality` | 0.0–1.0 |
| Qualitätsstufe | `qualityTier` | Fragment … Museumsreif |
| Modifier | `modifier` | Datengetriebener Bonus |

---

## 3. Ressourcen

| Ressource | Quelle | Verwendung | Reset beim Prestige | Status |
|---|---|---|---|---|
| Geld | Museum, Verkauf von Gestein/Duplikaten | Generatoren, Schichten | ja | [MVP] |
| Rohblöcke | Grabungsteams, Tippen | Präparation | ja | [MVP] |
| FP | Labor | Werkzeugstufen | ja | [MVP] |
| Ersatzteile | Labor | Rekonstruktion | ja | [MVP] |
| Ruhm | Prestige | Skilltree | nein | [MVP] |
| DNA-Fragmente | Labor (Spätspiel) | Wiederbelebung | – | [Später] |

---

## 4. Spielsysteme

### 4.1 Schichten und Grabung [MVP]
- Schichten werden mit Geld freigeschaltet (`layers.json: unlockCost`). Die erste ist von Beginn an offen.
- Jede Schicht hat eigene Grabungsteams. Produktion pro Schicht:
  `blocksPerSec(L) = teams(L) * teamRate * milestoneMult(teams(L)) * mods(stat=dig_rate)`
- `milestoneMult(n) = balance.teamMilestoneFactor ^ (Anzahl erreichter Schwellen aus balance.teamMilestones)`, Standard: Faktor 2, Schwellen [25, 50, 100, 200].
- **Aktive Phase:** Ein Tipp auf die aktuelle Schicht erzeugt `balance.tapDigAmount` Blöcke (Start 0.25).
- Blöcke liegen pro Schicht in einem Lager. Die Präparation verarbeitet immer zuerst die **tiefste** Schicht mit verfügbaren Blöcken.
- Je tiefer die Schicht: höherer `value`, höhere `fragility`, höheres `fameWeight`.
- Präzisierungen (AP-07):
  - `dig_rate` ist die Rate **je Team** mit Basiswert `layer.teamRate`: `blocksPerSec(L) = teams(L) * mods(dig_rate, Basis teamRate) * milestoneMult(teams(L))`. Für `mult`-Modifier (Skill `dig_2`, Buch-Boni) ist das identisch mit der Formel oben; `add`-Modifier würden die Rate je Team erhöhen.
  - Eine Schwelle gilt ab `teams >= Schwelle` als erreicht (25 Teams → ×2, 50 → ×4).
  - **Tippen** (`tapDig(state, layerId)`) erzeugt genau `balance.tapDigAmount` Blöcke in einer beliebigen freigeschalteten Schicht. `dig_rate` wirkt **nicht** auf das Tippen (Begründung: der Skill heißt „Teamrate +15 %“, und die Referenzsimulation rechnet das Tippen ebenfalls ohne Bonus). Gesperrte oder unbekannte Schichten: keine Änderung.
  - Gesperrte Schichten produzieren nichts, auch wenn im Spielstand Teams für sie stehen.

### 4.2 Präparation und Qualität [MVP]
- Präparatoren verarbeiten `prepRate` Blöcke/s, Tippen auf „Freilegen“ verarbeitet `balance.tapPrepAmount` (Start 0.25).
- Pro freigelegtem Block:
  1. Mit Wahrscheinlichkeit `findChance` (Start 0.08, Modifier `find_chance`) ist es ein **Fossil**, sonst **Gestein** und bringt `layer.value * balance.rockSellFactor` Geld (Start 0.5).
  2. **Art** wird per `species.rarityWeight` aus der Schicht gezogen.
  3. **Knochen** wird gleichverteilt gezogen. Pech-Schutz siehe 4.9.
  4. **Pro Zone** (4 Stück):
     ```
     base   = uniform(qualityBaseMin, qualityBaseMax)        // Start 0.30 .. 1.00, Modifier quality_base_min
     damage = max(0, layer.fragility - toolReduction) * uniform(damageRollMin, damageRollMax)   // Start 0.5 .. 2.0
     zoneQ  = clamp(base - damage, 0, 1)
     ```
     `toolReduction` = Summe der Modifier `damage_reduction` (Werkzeugstufen, Skills).
  5. **Qualität des Stücks** = Mittelwert der 4 Zonen.
- **Qualitätsstufen** (Grenzen in `balance.json`):

| Stufe | Code | Qualität |
|---|---|---|
| Fragment | `FRAGMENT` | < 0.25 |
| Beschädigt | `DAMAGED` | 0.25 – < 0.50 |
| Gut | `GOOD` | 0.50 – < 0.70 |
| Exzellent | `EXCELLENT` | 0.70 – < 0.90 |
| Museumsreif | `PRISTINE` | ≥ 0.90 |

- Weil die Zerbrechlichkeit mit der Tiefe steigt, bleiben Funde aus tiefen Schichten auch mit guten Werkzeugen selten perfekt. Das ist **Absicht** und darf nicht „gefixt“ werden.
- Präzisierungen (AP-08), Umsetzung `core.engine.PrepSystem` (läuft in `GameEngine.defaultSystems` direkt nach der Grabung):
  - `prepRate` ist der Stat `prep_rate`, additiv mit Basis 0; die Basis kommt aus der Quelle `upgrade:preparator` (0.2 je Präparator), `mult`-Modifier (Skill `prep_2`, Buch-Boni) wirken darauf. `prep_rate` wirkt **nicht** auf das Tippen (`tapPrep(state, rng)`, analog zu `tapDig`).
  - **Bruchteile:** Gewürfelt werden nur **ganze** Blöcke. Die Verarbeitungsmenge eines Aufrufs (`prepRate * dt` bzw. `tapPrepAmount`) wird auf den globalen Fortschritt `run.prepProgress` addiert; daraus werden so viele ganze Blöcke freigelegt, wie Budget und Lager erlauben. Eine Schicht stellt nur ganze Blöcke bereit (`floor(blocks)`), Bruchteile im Lager warten auf weitere Grabung. Danach bleibt nur der angefangene Bruchteil im Fortschritt (immer `[0, 1)`); Kapazität ohne verfügbare Blöcke verfällt. Beispiel: 4 Tipps à 0.25 legen genau einen Block frei. `prepProgress` hat den Standardwert 0, alte Spielstände laden ohne Migration.
  - **Reihenfolge:** tiefste Schicht (höchste `order`) mit mindestens einem ganzen Block zuerst, dann die nächsthöhere.
  - **Fundchance:** `p = 1 - (1 - find_chance) * (1 - guaranteed_fossil_chance)`, ein Wurf pro Block. `find_chance` hat die Basis `balance.findChance`, `guaranteed_fossil_chance` (Capstone `dig_5`) die Basis 0. Das entspricht zwei unabhängigen Chancen („2 % der Blöcke enthalten garantiert ein Fossil“, sonst normaler Fundwurf); ohne Capstone ist `p = find_chance`.
  - **Gestein:** `layer.value * balance.rockSellFactor` wird sofort gutgeschrieben, ohne `sell_price` (der Stat gilt laut 4.4 für den Verkauf von Duplikaten).
  - **Schadensfreiheit** (`no_damage_chance`, Capstone `prep_5`): ein Wurf pro Fund (nur wenn die Chance > 0 ist); bei Erfolg entfällt der Schaden in **allen** Zonen, `zoneQ = base`.
  - `quality_base_min` hat die Basis `balance.qualityBaseMin` und wird auf `qualityBaseMax` begrenzt. `damage_reduction` ist additiv mit Basis 0. Die Zonenanzahl kommt aus `bones.json`.
  - Würfelreihenfolge je Block: Fund → Art → Knochen → (Schadensfreiheit) → je Zone Grundqualität und Schaden.
  - Stufen: `QualityTiers.tierOf(q)` bzw. `indexOf(q)` (Index passend zu `bookTierFactor`); eine Grenze gehört zur höheren Stufe (`q >= minQuality`).
  - **Ergebnis eines Fossils:** `Find(pieceKey, layerId, zones, undamaged)` (transient, nicht gespeichert). Die Präparation zählt `meta.stats.piecesFound` hoch, meldet `GameEvent.FossilFound(find, tierId)` und übergibt den Fund **sofort** an den `FindHandler` des `PrepSystem` (`onFind(state, find, context) -> GameState`). Standard des Konstruktors ist `FindHandler.NONE` (keine Einordnung). **AP-09** implementiert das Einordnen aus 4.4 als `FindClassifier` und trägt ihn in `GameEngine.defaultSystems` ein (`PrepSystem(data, modifiers, FindClassifier(data, modifiers))`); `GameEngine.tapPrep` nutzt automatisch dieselbe Instanz. Weil der Handler den Zustand pro Fund aktualisiert, sieht der nächste Wurf den neuen `run.pieces` (Pech-Schutz auch bei großem `dt`).
  - Je Schicht und Aufruf zusätzlich `GameEvent.BlocksPrepared(layerId, blocks, fossils, rockMoney)` nach den Fund-Ereignissen dieser Schicht.

### 4.3 Zonen [MVP]
- Jeder Knochentyp hat 4 benannte Zonen (`bones.json`). Die Anzahl ist pro Knochen konfigurierbar, der Code darf nicht fest von 4 ausgehen.
- Zonen sind die Grundlage für Ersatzteile, Rekonstruktion und die Einfärbung in der Skelett-Grafik.

| Knochen (`boneId`) | Zonen |
|---|---|
| Schädel `skull` | Schnauze, Augenhöhle, Schädeldach, Hinterhaupt |
| Unterkiefer `jaw` | Spitze, Zahnreihe, Unterrand, Gelenk |
| Halswirbel `cervical` | Wirbelkörper, Dornfortsatz, Querfortsatz links, Querfortsatz rechts |
| Rückenwirbel `dorsal` | Wirbelkörper, Dornfortsatz, Querfortsatz links, Querfortsatz rechts |
| Rippen `ribs` | Kopf, Hals, Schaft, Ende |
| Becken `pelvis` | Darmbein, Sitzbein, Schambein, Hüftpfanne |
| Vorderbein `forelimb` | Oberarm, Elle/Speiche, Handwurzel, Finger |
| Hinterbein `hindlimb` | Oberschenkel, Schienbein, Fußwurzel, Zehen |

### 4.4 Funde einordnen [MVP]
Für jeden Fossilfund mit Schlüssel `pieceKey = speciesId:boneId:age:sex` (im MVP sind `age` und `sex` = `null`, Schreibweise `_`):
1. **Sammelbuch:** Ist die Qualität höher als der Bucheintrag (oder gibt es keinen), wird das Buch aktualisiert (permanent).
2. **Run-Bestand:** Ist die Qualität höher als der beste Fund dieses Knochens im aktuellen Durchlauf, ersetzt der Fund diesen (er wird im Museum ausgestellt). Das ersetzte Stück zählt als Duplikat.
3. Sonst ist der Fund ein **Duplikat**: Er geht in die Laborwarteschlange, falls dort Platz ist (`labQueueMax = labSlots * balance.labQueuePerSlot`, Start 3), andernfalls wird er sofort verkauft:
   `sellValue = layer.value * (0.5 + q) * balance.duplicateSellFactor` (Start 20) `* mods(sell_price)`.
- **Speicher:** Es werden keine einzelnen Exemplare dauerhaft gespeichert, nur bester Run-Fund pro Knochen, bester Buch-Fund pro Knochen, bestes Ersatzteil pro Zone und die (gedeckelte) Laborwarteschlange.
- Präzisierungen (AP-09), Umsetzung `core.engine.FindClassifier` (der `FindHandler` des `PrepSystem` in `GameEngine.defaultSystems`):
  - „Höher“ heißt **echt größer**; gleiche Qualität ersetzt weder Buch noch Run-Fund (der Fund ist dann Duplikat).
  - Buch und Run-Bestand werden **unabhängig** verglichen: Ein Fund kann das Buch verbessern und trotzdem Duplikat sein (Run-Fund besser), oder den Run-Fund ersetzen, ohne das Buch zu ändern (Buch aus früherem Durchlauf besser). Ein neuer Bucheintrag aus einem echten Fund hat `reconstructed = false` und speichert die Zonen (`BookEntry.zones`, für die Zonen-Balken in 8.3).
  - Ein erster Fund eines Schlüssels im Durchlauf ist nie Duplikat. Bei Ersetzung ist das **ersetzte** Stück das Duplikat (mit seinen Zonen bzw. seiner Qualität für Warteschlange oder Verkaufspreis).
  - Warteschlange: Platz, solange `labQueue.size < labSlots * labQueuePerSlot`. Ohne Laborplatz wird jedes Duplikat verkauft.
  - `layer` im Verkaufspreis ist die Schicht der Art (`species.layer`). Verkaufserlöse werden sofort gutgeschrieben. `sell_price` ist ein Faktor-Stat (Skill `curator_4`: ×1.6).
  - Ereignisse in dieser Reihenfolge je Fund: `BookUpdated` (falls Buch verbessert), dann `NewPiece` (+ `SkeletonCompleted`, wenn damit erstmals alle Knochen der Art im Durchlauf vorhanden sind) **oder** `PieceImproved`, dann ggf. `DuplicateQueued` bzw. `DuplicateSold`.

### 4.5 Labor [MVP – einfache Version]
- `labSlots` parallele Forschungen (Start 1, kaufbar). Jede dauert `balance.labDuration` Sekunden (Start 30, Modifier `lab_speed`).
- Ergebnis einer abgeschlossenen Forschung:
  - FP: `(layerIndex + 1) * mods(research_points)`
  - Für jede Zone des Stücks: Ist `zoneQ` besser als das gespeicherte Ersatzteil (`spareZone[speciesId:boneId:age:sex:zoneId]`), wird es ersetzt.
  - Die Art gilt ab sofort als **erforscht** (Completion-Stufe 1, Kapitel 4.7).
- **Rekonstruktion:** Gibt es für jede Zone eines Knochens ein Ersatzteil, kann der Spieler den Knochen rekonstruieren (Button in Labor bzw. Buch; im MVP manuell, Auto-Rekonstruktion [Später]).
  - `reconQ = min(balance.reconstructionCap, mean(spareZones))`, Cap Start 0.89, also höchstens **Exzellent**.
  - Die Rekonstruktion ersetzt den Run-Fund bzw. Bucheintrag nur, wenn sie besser ist. Bucheinträge tragen das Flag `reconstructed = true`.
  - **Museumsreif ist nur durch echte Funde erreichbar.** Das ist eine Kernregel.
- Laborupgrades (schneller, mehr FP) gibt es nur über den Skilltree, nicht über Geld.
- Präzisierungen (AP-10), Umsetzung `core.engine.LabSystem` (`AdvanceSystem` nach dem `MuseumSystem`) und `core.engine.Reconstruction` (Spieleraktion `GameEngine.reconstruct`):
  - **Plätze:** `floor(labSlots)` Jobs (`run.labJobs`) laufen parallel; freie Plätze werden FIFO aus `run.labQueue` belegt. Das Labor läuft nach der Präparation (Duplikate dieses Ticks können sofort starten) und nach dem Museum (es ändert `run.pieces` nicht).
  - **Dauer:** `labDuration * mods(lab_speed)` (Faktor-Stat, Clamp `min 0.1`), festgelegt beim Start eines Jobs; später gekaufte Skills verkürzen laufende Jobs nicht. `lab_1` + `lab_4`: 0.65 × 30 s = 19.5 s. Seit AP-25 speichert der Job die Gesamtdauer (`LabJob.duration`), `GameEngine.lab.progress(job)` rechnet damit; Jobs aus älteren Spielständen (`duration = null`) nutzen die aktuelle Dauer bzw. die Restzeit, falls diese größer ist.
  - **Große `dt`:** ereignisweise Abarbeitung; die Restzeit nach einem Abschluss geht an das nächste Stück der Warteschlange auf dem frei gewordenen Platz. Ein großer Schritt ergibt dasselbe wie viele kleine Ticks.
  - **FP:** `layerIndex` ist `order` der Schicht der Art (Quartär 1, Neogen 2, Kreide 3 FP, jeweils mal `research_points`).
  - **Ersatzteile:** je Zone (`bones.json`) nur bei **echt höherer** Qualität ersetzt (wie 4.4). Unbekannte Arten/Knochen in einem Job bringen 0 FP bzw. keine Ersatzteile (kein Abbruch).
  - **Erforscht:** Art wird beim ersten Abschluss in `meta.researchedSpecies` aufgenommen.
  - **Rekonstruktion möglich**, wenn höchstens `floor(reconstruction_missing_zones)` Zonen ohne Ersatzteil sind (Basis 0, `lab_5`: 1; fehlende Zonen werden mit `reconstructionMissingZoneQuality` ergänzt) und mindestens ein Ersatzteil existiert.
  - **Qualität und Zonen:** Liegt `mean(Zonen)` über dem Cap, werden alle Zonen mit `cap / mean` skaliert. Run-Stück und Bucheintrag speichern diese skalierten Zonen, ihre Qualität ist deren Mittelwert (= `min(cap, mean)` bis auf Rundung). So stimmen `Piece.quality` (Mittelwert der Zonen) und Buchqualität überein, und eine erneute Rekonstruktion mit denselben Ersatzteilen gilt nicht als Verbesserung. Weil `DataValidator` `reconstructionCap < minQuality(Museumsreif)` verlangt, ist Museumsreif nie erreichbar.
  - **Ersetzen:** Run-Fund und Bucheintrag werden unabhängig und nur bei **echt höherer** Qualität ersetzt (oder neu angelegt); Bucheintrag mit `reconstructed = true` und den Zonen. Ist keins von beiden besser, wird die Aktion abgelehnt (`ReconstructionFailure.NO_IMPROVEMENT`). Ein ersetzter Run-Fund zählt wie in 4.4 als Duplikat (Warteschlange oder Verkauf).
  - **Ersatzteile werden nicht verbraucht** (Entscheidung AP-10; das Design ließ es offen): Sie sind laut Glossar das „beste gesicherte Zonen-Stück“ und bleiben bis zum Prestige erhalten.
  - **Abfragen für die UI:** `GameEngine.reconstructableBones(state)` liefert `ReconstructionPreview` (Schlüssel, Zonen, erwartete Qualität, fehlende Zonen, `improvesRun`, `improvesBook`, `isImprovement`) für alle Schlüssel mit mindestens einem Ersatzteil, sortiert nach Schlüsseltext; `GameEngine.lab` (`jobDuration`, `progress(job)`, `researchPointsFor`, `slots`).

### 4.6 Museum [MVP]
- Ausstellungsplätze = `halls * balance.slotsPerHall` (Start 1 Halle, 8 Plätze). Automatisch ausgestellt werden die wertvollsten Run-Funde.
- Wert pro Stück und Sekunde:
  `income(piece) = layer.value * (0.5 + q) * (skeletonComplete(species) ? skeletonMult : 1)`
  `skeletonMult` Start 2.0 (Modifier `skeleton_bonus`, additiv auf den Faktor).
- Gesamteinnahmen: `Σ income(piece) * mods(museum_income)`.
- Hallen werden mit Geld gekauft (Kapitel 6.3).
- Präzisierungen (AP-09), Umsetzung `core.engine.MuseumSystem` (`AdvanceSystem` direkt nach der Präparation, schreibt `incomePerSecond * dt` Geld gut):
  - Plätze `= floor(halls) * slotsPerHall`. Die „wertvollsten“ Run-Funde sind die mit dem höchsten `income(piece)` **inklusive** Skelett- und Sonderausstellungsfaktor (absteigend, Gleichstand nach Schlüsseltext). `layer` ist die Schicht der Art.
  - `skeletonComplete(species)`: Für jeden Knochen aus `species.bones` gibt es einen Run-Fund mit diesem `speciesId:boneId`, Alter/Geschlecht egal (`core.engine.Skeletons`). Ein vollständiges Skelett muss nicht vollständig ausgestellt sein.
  - Skelett-Faktor `= mods(skeleton_bonus, Basis skeletonMult)`; für `add`-Modifier (Skill `curator_3` +0.5, Buch-Bonus Velociraptor) ist das genau `skeletonMult + skeleton_bonus`.
  - **Sonderausstellung** (Capstone `curator_5`): `run.specialExhibit` enthält die gewählte Art-ID oder `null`. Ist die Art im Durchlauf vollständig und `special_exhibit_mult` (additiv, Basis 0) > 0, wird das Einkommen ihrer Stücke **zusätzlich** zum Skelett-Faktor mit `special_exhibit_mult` multipliziert (mit allen Kurator-Skills also ×2.5 ×5). Sonst wirkt die Auswahl nicht (kein Fehler). `MuseumSystem.isSpecialExhibitActive(state)` zeigt, ob sie wirkt. Die Auswahlaktion entstand in AP-18 (vorgezogen aus AP-13, weil der Museum-Screen sie braucht): `GameEngine.setSpecialExhibit(state, speciesId?)` gleicht die Modifier ab und gibt `SpecialExhibitResult` zurück (`Success(state)` bzw. `Failure(reason)`); `null` hebt die Auswahl immer auf, eine Art wird nur gesetzt, wenn sie bekannt ist (`UNKNOWN_SPECIES`), `special_exhibit_mult` > 0 ist (`NOT_UNLOCKED`) und ihr Skelett im Durchlauf vollständig ist (`SKELETON_INCOMPLETE`). AP-13 muss dafür nichts mehr bauen.
  - Abfragen für die UI: `incomePerSecond(state)`, `exhibitedPieces(state)` (Liste `ExhibitedPiece` mit Schlüssel, Stück, Einkommen/s inkl. `museum_income`, Flags Skelett und Sonderausstellung), `slots(run)`; auch über `GameEngine.incomePerSecond`/`exhibitedPieces`/`museum`.
  - Performance: Die Rangfolge wird nur neu berechnet, wenn sich `run.pieces` (Instanz), `halls`, `specialExhibit`, der Skelett- oder Sonderausstellungsfaktor ändern; `museum_income` wird bei jeder Abfrage angewandt. Eine Neuberechnung kostet `O(n log n)` und ist bei 10 Ticks/s und < 200 Stücken unkritisch.

### 4.7 Sammelbuch und Completion [MVP: Stufe 1–2]
- Das Buch listet pro Schicht alle Arten und pro Art alle 8 Knochen mit ihrer besten Qualität (Farbe der Stufe) sowie dem Flag „rekonstruiert“.
- **Completion-Stufen pro Art:**

| Stufe | Bedingung | Status |
|---|---|---|
| 1 Erforscht | Mindestens ein Stück der Art im Labor abgeschlossen | [MVP] |
| 2 Skelett vollständig | Alle Knochen im Buch vorhanden (egal welches Alter/Geschlecht) | [MVP] |
| 3 Konsistentes Skelett | Alle Knochen von derselben Kombination aus Alter und Geschlecht | [Später] |
| 4 Vollständige Population | Jede Kombination aus Alter und Geschlecht als konsistentes Skelett | [Später] |

- Hilfsfunktionen (AP-09, für AP-11): `Skeletons.isCompleteInBook(book, species)`, `completeSpeciesInBook(book, data)`, `missingBones(keys, species)` sowie die Run-Varianten `isCompleteInRun`/`completeSpeciesInRun`. „Vorhanden“ heißt: ein Eintrag mit `speciesId:boneId`, Alter/Geschlecht egal.
- **Buch-Boni:** Jede Art definiert in `species.json` einen Bonus (`bookBonus`: Stat und Basiswert). Er wirkt, sobald Stufe 2 erreicht ist, skaliert mit der **niedrigsten** Qualitätsstufe ihrer Knochen:
  `bookBonusValue = baseValue * balance.bookTierFactor[minTier]`, Standard-Faktoren [1, 2, 3, 5, 8] für Fragment … Museumsreif.
  Stufe 1 gibt pauschal `balance.researchedBonus` (+1 % `museum_income`).
- Buch-Boni haben die Modifier-Kategorie `book`, sind permanent und werden innerhalb der Kategorie **addiert**.
- Präzisierungen (AP-11), Umsetzung `core.engine.Completion` und `core.engine.BookSources`:
  - **Stufen:** Stufe 1 = Art in `meta.researchedSpecies` (setzt das `LabSystem`), Stufe 2 = `Skeletons.isCompleteInBook`. Die Bedingungen sind unabhängig (ein Buch kann allein aus Funden vollständig sein); `Completion.levelOf(state, speciesId)` liefert die höchste erfüllte Stufe (`CompletionLevel.NONE/RESEARCHED/SKELETON_COMPLETE`). Stufe 2 setzt Stufe 1 also **nicht** voraus.
  - **`minTier`:** je Knochen der Art zählt der beste Bucheintrag (über Alter/Geschlecht, relevant erst [Später]), `minTier` ist die niedrigste Stufe dieser Qualitäten (`QualityTiers.indexOf`, Index in `bookTierFactor`).
  - **Kumulativ (Entscheidung AP-11):** `researchedBonus` gilt für jede erforschte Art, auch ab Stufe 2, **zusätzlich** zum Buch-Bonus („Stufe 1 gibt pauschal“). Beispiel Höhlenbär erforscht und vollständig in Gut: `museum_income` × (1 + 0.01 · 3 + 0.01).
  - **Modifier-Quellen** (Provider `BookSources`, Prefix `book:`, in `ModifierSources.DEFAULT_PROVIDERS`): `book:<speciesId>` mit `bookBonus.stat/op` und Wert `bookBonus.value * bookTierFactor[minTier]`, Kategorie `book`; `book:researched:<speciesId>` mit `balance.researchedBonus`. Unbekannte Arten im Spielstand werden ignoriert. Die Boni wirken ab dem nächsten Abgleich der Quellen (Beginn des nächsten `advance` bzw. `syncModifiers`).
  - **Abfragen für die UI (AP-20):** `GameEngine.bookOverview(state)` bzw. `Completion.overview(state)` liefert je Art `SpeciesCompletion` (Stufe, Flags, Knochen im Buch/gesamt, `minTierIndex`, wirksamer Buch- und Pauschal-Bonus) in Datenreihenfolge; `GameEngine.completionLevel(state, speciesId)`.

### 4.8 Alter und Geschlecht [Später, Datenmodell schon im MVP]
- Altersstufen (konfigurierbar, Start 3): Jungtier, Halbwüchsig, Ausgewachsen. Geschlecht: weiblich, männlich.
- Freischaltung über ein Labor-Forschungs-Upgrade „Bestimmung“. Davor sind alle Funde unbestimmt (`null`) und zählen nur für Stufe 2.
- Ersatzteile sind an Alter und Geschlecht gebunden.
- Spätere Upgrades „Grabungsfokus“ erhöhen gezielt die Chance auf fehlende Varianten.
- **MVP-Anforderung:** `Piece`, `pieceKey`, `spareZone`-Schlüssel und Save-Format enthalten `age`/`sex` als nullable.

### 4.9 Pech-Schutz [MVP]
- Bei der Knochenwahl innerhalb einer Art bekommen Knochen, die im **aktuellen Durchlauf** noch fehlen, das Gewicht `balance.missingBoneWeight` (Start 3), vorhandene das Gewicht 1.
- Dazu kommen Ersatzteile und Rekonstruktion als zweiter Weg zu fehlenden bzw. besseren Knochen.
- Präzisierung (AP-08): „fehlend“ heißt, dass `run.pieces` keinen Eintrag mit dem `pieceKey` des neuen Fundes hat (im MVP `speciesId:boneId:_:_`). Mit Alter/Geschlecht [Später] ist die Regel neu zu prüfen.

---

## 5. Progression

### 5.1 Ziel-Pacing (Referenz für das Balancing)

| Zeit im 1. Durchlauf | Erwartung |
|---|---|
| 0–2 min | Erste Funde durch Tippen, erstes Team, Museum erscheint |
| ~10 min | Erste Skelette (häufige Quartär-Arten) vollständig |
| ~25–30 min | Schicht 2 (Neogen) freigeschaltet |
| ~40–50 min | Erstes Prestige sinnvoll (~10–15 Ruhm) |
| 2.–5. Durchlauf | Schicht 3 (Kreide) erreichbar, erste Capstones |
| langfristig | Buch in Museumsreif, voller Skilltree, später Kontinente |

Die Referenzsimulation der Design-Phase (ohne Pech-Schutz, Meilensteine und Buch-Boni) traf mit den Startwerten aus Kapitel 6.4 dieses Pacing: Neogen nach ca. 28 min, nach 45 min 11–14 Ruhm.

Messung mit der Kotlin-Engine (AP-15, `PacingTest`, Details in 6.7): Neogen nach 22–23 min, erstes Skelett nach ca. 3 min, nach 45 min 22–26 Ruhm, nach 60 min 31 Ruhm. Die Akzeptanzgrenzen aus AP-15 sind erfüllt, das Pacing ist aber schneller als die Tabelle; die Referenz der Design-Phase kannte Pech-Schutz, Meilenstein-Belohnungen und Buch-Boni nicht. Seit AP-28 bildet `tools/sim/fossil_sim.py` die Engine samt dieser Systeme nach und liefert für dieselben Seeds dieselben Werte wie der `PacingTest` (6.7). Die Anpassung ist Aufgabe von AP-29.

### 5.2 Meilensteine [MVP]
Datengetriebene Meilensteine (`milestones.json`) mit kleiner Belohnung (Geld oder FP) und Toast-Meldung, z. B.: erster Fund, erstes Skelett, erste Museumsreif-Qualität, 10 Teams, neue Schicht, erste Rekonstruktion, erstes Prestige. Sie dienen vor allem als Orientierung („Was mache ich als Nächstes?“).
- Präzisierungen (AP-11), Umsetzung `core.engine.MilestoneSystem` (letztes `AdvanceSystem` in `GameEngine.defaultSystems`) und `core.engine.MilestoneSources`:
  - **Bedingung:** erreicht, sobald der Zähler `>= condition.value` ist. Zähler: `pieces_found` = `meta.stats.piecesFound`; `skeletons_completed` = Arten mit Stufe 2 im **Sammelbuch** (permanent); `pristine_found` = `meta.stats.pristineFound` (Funde der **höchsten** Stufe aus `qualityTiers`, gezählt von der Präparation; Rekonstruktionen erreichen sie nie); `teams_total` = Summe aller `run.teams`; `layers_unlocked` = `run.unlockedLayers`; `research_completed` = `meta.stats.researchCompleted` (`LabSystem`, je abgeschlossenem Job); `reconstructions` = `meta.stats.reconstructions` (je erfolgreicher Rekonstruktion); `prestiges` = `meta.prestigeCount` (tritt erst mit AP-12 ein). `teams_total` und `layers_unlocked` beziehen sich auf den aktuellen Durchlauf, alle anderen Zähler sind permanent.
  - Die neuen Zähler in `meta.stats` haben den Standardwert 0; ältere Spielstände laden ohne Migration (bisherige Museumsreif-Funde, Forschungen und Rekonstruktionen werden nicht nachgezählt).
  - **Belohnung genau einmal:** ID in `meta.milestonesDone` (permanent), `reward.money` und `reward.researchPoints` sofort gutgeschrieben, Ereignis `GameEvent.MilestoneReached(milestoneId, money, researchPoints)`. `reward.effects` wirken dauerhaft über den Provider `MilestoneSources` als Quelle `milestone:<id>` ab dem nächsten Abgleich. Unbekannte IDs in `milestonesDone` werden ignoriert.
  - Auswertung in Datenreihenfolge in einem Durchgang; Belohnungen ändern keinen Zähler, ein Meilenstein löst also keinen weiteren aus. Mehrere Meilensteine können im selben Aufruf erreicht werden.
  - **Zeitpunkt:** am Ende jedes `advance` (nach allen anderen Systemen). Die Engine-Aktionen `tapPrep` und `reconstruct` prüfen selbst und hängen die Ereignisse an ihre Ereignisliste an. Für Aktionen außerhalb der Engine (Käufe über `Purchases`, später Prestige) gibt es `GameEngine.checkMilestones(state): AdvanceResult`; ohne Aufruf greift spätestens der nächste Tick (≤ 0,1 s). Das `GameViewModel` wurde dafür in AP-11 **nicht** geändert.
  - Abfrage für die UI: `GameEngine.milestones.progress(state)` (`MilestoneProgress`: Definition, aktueller Zähler, Ziel, erreicht).
  - **Toast (AP-25):** Das `GameViewModel` macht aus jedem `MilestoneReached` (Tick, Tippen, Rekonstruktion, Prestige) eine Snackbar `GameMessage.MilestoneReached`: „Meilenstein erreicht: <Name> (+50 $, +2 FP)“, Name über `TextProvider.milestoneName`, Belohnungsteile nur, wenn sie größer als 0 sind (`ui/feedback/FeedbackTexts`). Offline erreichte Meilensteine erzeugen keinen Toast (der Willkommen-Dialog fasst die Abwesenheit zusammen).

### 5.3 Prestige [MVP]
- Der Button sitzt im Ruhm-Tab und ist aktiv, sobald die Vorschau mindestens 1 Ruhm zeigt. Die Vorschau wird live angezeigt („Ruhm bei Neustart: +7“). Bestätigungsdialog mit Übersicht, was verloren geht und was bleibt.
- **Ruhm-Berechnung** über alle im aktuellen Durchlauf vollständigen Skelette:
  `fameGain = floor( Σ layer.fameWeight * (0.5 + avgQuality(skeleton)) * mods(fame_gain) )`
- **Reset-Tabelle:**

| Wird zurückgesetzt | Bleibt |
|---|---|
| Geld, FP, Rohblöcke | Ruhm (gesamt und unverteilt) |
| Teams, Präparatoren, Hallen, Laborplätze | Gekaufte Skills (Respec möglich, s. u.) |
| Werkzeugstufen | Sammelbuch inkl. Completion und Buch-Boni |
| Freigeschaltete Schichten (außer der ersten) | Statistiken, Meilensteine |
| Run-Funde, Ersatzteile, Laborwarteschlange | Einstellungen |
| Kontinente [Später] | Prestige-Zähler |

- **Kostenloser Respec bei jedem Prestige:** Alle Skills können zurückgegeben und neu verteilt werden (100 % Rückerstattung).
- Präzisierungen (AP-12), Umsetzung `core.engine.Prestige` (Instanz `GameEngine.prestigeSystem`):
  - **Skelette:** `Skeletons.completeSpeciesInRun`; Schicht = `species.layer`. `avgQuality` = Mittelwert über die Knochen der Art, je Knochen zählt das beste Run-Stück mit `speciesId:boneId` (Alter/Geschlecht egal; im MVP genau ein Stück je Knochen).
  - **`fame_gain`** ist ein Faktor-Stat (Basis 1.0) und wird **einmal auf die Summe** angewendet, vor dem Abrunden (mathematisch gleich der Anwendung je Skelett, ohne zusätzliche Rundungsverluste). Der Buch-Bonus des Tyrannosaurus (`mult`) wirkt also prozentual auf den Gesamtgewinn, z. B. Stufe Gut: × (1 + 0.03 · 3).
  - **Abfragen:** `GameEngine.fameGainPreview(state)`, `canPrestige(state)` (Vorschau ≥ 1), `prestigeSummary(state)` (`PrestigeSummary`: Gewinn, Faktor, Beiträge je Skelett `SkeletonFame`, Ruhm und Prestige-Zähler danach, verlorene Mengen wie Geld, FP, Blöcke, Teams, Präparatoren, Hallen, Laborplätze, Werkzeugstufe, gesperrte Schichten, Run-Funde, Ersatzteile, Laborstücke, Sonderausstellung, sowie bleibende Anzahlen). Alle drei gleichen vorher die Modifier-Quellen ab.
  - **Aktion** `GameEngine.prestige(state, metaStep = PrestigeMetaStep.NONE): PrestigeResult` (`Success(state, events, fameGained)` bzw. `Failure(NOT_ENOUGH_FAME | NON_FINITE_RESULT)`). Ablauf: Vorschau ≥ 1 prüfen → `fame`, `fameTotal` + Gewinn, `prestigeCount` + 1 → `metaStep` → Modifier-Quellen mit neuem Metazustand und leerem Durchlauf abgleichen (nur permanente Quellen wirken, `start_teams`/`start_lab_slots`) → `RunState.newRun(erste Schicht, RunStart.values)` ersetzt den kompletten Run-Zustand (inkl. `prepProgress`, Laborjobs und Sonderausstellung) → Quellen mit dem Ergebnis abgleichen → Meilensteine prüfen (`first_prestige`, Belohnung landet im neuen Durchlauf). Ereignisse: `GameEvent.Prestiged(fameGained)`, danach ggf. `MilestoneReached`. `settings` und `lastSavedAt` bleiben unverändert.
  - **Respec (AP-13):** `PrestigeMetaStep` ist die Erweiterungsstelle. Er erhält den Metazustand mit bereits gutgeschriebenem Ruhm und läuft **vor** der Berechnung der Startwerte, damit zurückgegebene Start-Skills (`dig_3`, `lab_3`) im neuen Durchlauf nicht mehr wirken. Umsetzung `RespecStep` (über `GameEngine.respecStep(rebuy)`): alle Skills zurück, Erstattung von `meta.fameSpent` (siehe 5.4), danach Neukauf der Liste `rebuy` in dieser Reihenfolge; nicht kaufbare Wünsche werden übersprungen. Neu gekaufte Start-Skills wirken sofort im neuen Durchlauf. Ohne Respec (Skills behalten) bleibt `PrestigeMetaStep.NONE`. Respec gibt es nur beim Prestige, nicht mitten im Durchlauf. `PrestigeSummary.respecRefund` zeigt die Erstattung, `GameEngine.respecPreview(state, rebuy)` (`RespecPlan`: Metazustand danach, Erstattung, gekaufte und abgelehnte Wünsche mit Grund) die Vorschau für den Dialog.
  - **Ruhm-Tab (8.2):** `TabUnlockRules` in der App bekommt die Vorschau als Funktion (`GameEngine::canPrestige`).

### 5.4 Skilltree [MVP: 4 Äste à 4 Knoten + Capstone]
- **Prinzip:** Alles ist kaufbar. Tiefere Knoten eines Astes sind stärker, Faustregel `Wirkung ≈ Basis * (1 + 0.5 * Tiefe)`. Jeder gekaufte Knoten verteuert alle weiteren:
  `nodeCost = node.baseCost * balance.skillCostGrowth ^ ownedNodeCount` (Start 1.25).
- Voraussetzung: der vorige Knoten im selben Ast. `baseCost` nach Tiefe: 1, 2, 3, 5, 8 Ruhm.
- Kategorie aller Skill-Modifier: `skill` (innerhalb addiert).
- Präzisierungen (AP-13), Umsetzung `core.engine.SkillTree` (Instanz `GameEngine.skillTree`):
  - **Preis:** `nodeCost = ceil( node.baseCost * skillCostGrowth ^ ownedNodeCount )`. `ownedNodeCount` zählt alle gekauften, in den Daten bekannten Skills über alle Äste (unbekannte IDs im Spielstand zählen nicht). **Abweichung:** Aufrunden auf ganze Ruhm-Punkte. Begründung: Ruhm entsteht nur ganzzahlig (der Gewinn wird abgerundet) und wird unter 1000 ganzzahlig angezeigt; mit gebrochenen Preisen (z. B. 1,5625) zeigte die UI „Ruhm 2, Preis 2“, obwohl der Kauf scheitert, und danach einen Rest von „0“. Mit Aufrunden bleibt Ruhm immer ganzzahlig. Folge: Der volle Baum (20 Knoten) kostet etwas mehr als ohne Rundung; das Balancing prüft AP-28/29.
  - **Kauf:** `GameEngine.buySkill(state, skillId): SkillPurchaseResult` (`Success(state, skillId, cost)` bzw. `Failure(reason)`), Prüfreihenfolge `UNKNOWN_SKILL` → `ALREADY_OWNED` → `REQUIREMENTS_MISSING` (alle `requires` müssen gekauft sein) → `NOT_ENOUGH_FAME`. Erfolg: `fame - cost`, `fameSpent + cost`, Skill in `meta.skills`; danach werden die Modifier-Quellen abgeglichen (`skill:<id>`), der Skill wirkt sofort. Start-Skills (`start_teams`, `start_lab_slots`) wirken ab dem nächsten Durchlauf. Skills sind jederzeit kaufbar, nicht nur beim Prestige.
  - **Abfragen für die UI:** `skillOverview(state)` (`SkillBranchView` je Ast mit Knoten, nur Äste mit Knoten, also ohne `expedition`), `skillNode(state, id)` (`SkillNodeView`: Status `OWNED`/`AVAILABLE`/`LOCKED`, Preis bei Kauf jetzt bzw. `null` wenn gekauft, `affordable`, fehlende Voraussetzungen), `skillPrice(state, id)`. Die Funktionen von `SkillTree` arbeiten auf dem `MetaState`, damit der Prestige-Dialog sie auf `RespecPlan.meta` anwenden kann.
  - **Erstattung beim Respec:** Bei globalem Wachstum hängt die Summe der gezahlten Preise von der Kaufreihenfolge ab. Deshalb speichert jeder Kauf den gezahlten Betrag in `meta.fameSpent` (Standardwert 0, ältere Spielstände laden ohne Migration; vor AP-13 gab es keinen Skillkauf). Der Respec erstattet genau `fameSpent` (100 % des tatsächlich ausgegebenen Ruhms) und setzt es auf 0. Damit ist die Erstattung eindeutig, exakt und nicht ausnutzbar; im regulären Spielverlauf gilt `fame + fameSpent = fameTotal`.
  - **Wirkung:** Alle 20 MVP-Knoten wirken ausschließlich über die Quellen `skill:<id>` in den vorhandenen Systemen (Grabung, Präparation, Labor, Rekonstruktion, Museum, Verkauf, Käufe, Startwerte); `SkillEffectsTest` prüft jeden Knoten, je Capstone mit eigener Spielaktion.

| Ast | T1 | T2 | T3 | T4 | Capstone (T5) |
|---|---|---|---|---|---|
| **Grabung** `dig` | Fundchance +5 % (mult) | Teamrate +15 % | Start jedes Durchlaufs mit 5 Teams in Schicht 1 | Schicht-Freischaltkosten −25 % | *Glückliche Hand*: 2 % der Blöcke enthalten garantiert ein Fossil |
| **Präparation** `prep` | Schaden −0.02 | Präparator-Tempo +20 % | Mindest-Grundqualität +0.05 | Schaden −0.05 | *Meisterpräparator*: 3 % Chance, dass ein Fund keinen Schaden nimmt |
| **Labor** `lab` | Laborzeit −10 % | FP +30 % | +1 Laborplatz zu Beginn | Laborzeit −25 % | *Restaurator*: Rekonstruktion möglich, wenn höchstens 1 Zone fehlt (wird mit 0.5 ergänzt) |
| **Kurator** `curator` | Museumseinnahmen +10 % | Hallenkosten −20 % | Skelett-Faktor +0.5 | Verkaufspreise +60 % | *Sonderausstellung*: ein gewähltes vollständiges Skelett bringt ×5 |
| **Expedition** `expedition` [Später] | Offline-Dauer +2 h | Offline-Effizienz +15 % | Kontinentkosten −25 % | … | … |

### 5.5 Kontinente [Später]
- Weitere Kontinente laufen **parallel** als zusätzliche Fundstätten mit eigenen Schichten und Arten.
- Freischaltung mit Geld, Reset beim Prestige, aber bewusst so bepreist, dass man sie in jedem Durchlauf schnell wieder erreicht.

### 5.6 Langzeitziele
Buch vollständig, Buch in Museumsreif, voller Skilltree. Später zusätzlich: Completion-Stufen 3 und 4, alle Kontinente, Wiederbelebung bzw. Reservat.

---

## 6. Zahlen und Balancing

### 6.1 Grundsätze
1. Innerhalb einer Modifier-Kategorie wird **addiert**, zwischen den Kategorien **multipliziert**. So verhindern wir eine Explosion durch viele kleine Multiplikatoren.
2. Kosten wachsen exponentiell, Produktion wächst über Mengen plus Meilenstein-Verdopplungen.
3. Die Sammlung ist endlich, deshalb kommt die Endlosigkeit aus Qualität, Prestige, Skilltree und später Alter/Geschlecht bzw. Kontinenten.
4. Jede Balancing-Änderung wird mit dem Simulationswerkzeug (6.7) gegen das Pacing in 5.1 geprüft.

### 6.2 Modifier-System [MVP]
```json
{ "stat": "museum_income", "op": "mult", "value": 0.10, "category": "skill" }
```
- `op`: `add` (addiert auf den Basiswert) oder `mult` (prozentual).
- `category`: `upgrade`, `skill`, `book`, `milestone`, `event` [Später].
- **Berechnung:**
  `final(stat) = (base + Σ add) * Π_über Kategorien (1 + Σ mult in dieser Kategorie)`
- Für Stats mit Untergrenze bzw. Obergrenze (z. B. `lab_speed`, Kosten-Rabatte) gelten Clamps aus `balance.statLimits` (z. B. Rabatt höchstens −90 %). Die Clamps gelten für den Endwert des Stats.
- **Faktor-Stats** haben die Basis 1.0 und wirken als Faktor auf einen Datenwert: `museum_income`, `sell_price`, `research_points`, `fame_gain`, `layer_unlock_cost`, `hall_cost`, `lab_speed`. `lab_speed` ist der Faktor auf `balance.labDuration`, „Laborzeit −10 %“ ist also `{ "stat": "lab_speed", "op": "mult", "value": -0.10 }`; der Clamp `min 0.1` begrenzt die Verkürzung auf −90 %.
- **Stat-IDs (MVP):** `dig_rate`, `prep_rate`, `find_chance`, `damage_reduction`, `quality_base_min`, `museum_income`, `skeleton_bonus`, `sell_price`, `lab_speed`, `research_points`, `fame_gain`, `layer_unlock_cost`, `hall_cost`, `start_teams`, `start_lab_slots`, `guaranteed_fossil_chance`, `no_damage_chance`, `reconstruction_missing_zones`, `special_exhibit_mult`, `offline_efficiency`, `offline_cap_hours`.
- `ModifierEngine` sammelt alle aktiven Modifier (Upgrades, Skills, Buch, Meilensteine) und cached das Ergebnis, bis sich die Quellen ändern.
  - Präzisierungen (AP-04): Die Engine kennt den Spielzustand nicht, sondern verwaltet benannte **Quellen** (`setSource(id, modifiers)` setzt oder ersetzt, `removeSource(id)`, `clearSources()`). Empfohlene IDs: `<art>:<definitionsId>`, z. B. `skill:lab_1`, `upgrade:tools`, `book:triceratops`, `milestone:first_find`. Spätere APs füttern die Quellen. Abfrage: `value(stat, base)` mit Basiswert vom Aufrufer, `factor(stat)` für Faktor-Stats (Basis 1.0), `additive(stat)` für rein additive Stats (Basis 0.0); reine Berechnung ohne Cache über `ModifierEngine.compute(stat, base, modifiers, limit)`. Die Clamps kommen über den Konstruktor (`ModifierEngine.fromGameData(data)` nutzt `balance.statLimits`). Cache pro (Stat, Basis), verworfen bei jeder Änderung der Quellen. Ist das Ergebnis vor dem Clamp `NaN` oder unendlich, wird eine `IllegalStateException` geworfen (6.6). Die Engine ist nicht thread-safe (Nutzung nur aus der Spielschleife).

### 6.3 Kostenkurven und Mehrfachkauf
- Preis des n-ten Kaufs: `price(n) = baseCost * growth ^ n`
- Kosten für k Stück ab Bestand n: `cost(n, k) = baseCost * growth^n * (growth^k - 1) / (growth - 1)`
- Maximal kaufbar mit Geld M: `k = floor( log( M * (growth - 1) / (baseCost * growth^n) + 1 ) / log(growth) )`
- Kaufmodi in der UI: ×1, ×10, Max [MVP].
- Präzisierungen (AP-06):
  - Umsetzung in `core.engine.purchase`: `CostCurve` (reine Formeln, `price`, `cost`, `maxAffordable`) und `Purchases(data, modifiers)` mit `buyTeams`, `buyPreparators`, `buyHalls`, `buyLabSlots`, `buyToolLevel` (jeweils mit `BuyMode` X1/X10/MAX), `unlockNextLayer`/`unlockLayer` sowie `quote` (Vorschau für die UI). Aktionen geben ein `PurchaseResult` zurück (`Success` mit neuem `GameState` oder `Failure` mit Grund) und verändern weder den Zustand noch die `ModifierEngine`.
  - **Preisindex:** `n` ist die Zahl der *bezahlten* Einheiten, also `max(0, Bestand - free)` mit `free` aus `generators.json`. Die erste kaufbare Halle kostet daher `baseCost` (50). Einheiten aus Start-Boni (`start_teams`, `start_lab_slots`) zählen wie gekaufte Einheiten und erhöhen den Preis. Begründung: Skills können mitten im Durchlauf gekauft werden; würden Start-Boni aus dem Preisindex herausgerechnet, senkte ein später gekaufter Start-Skill die Preise, ohne Einheiten zu geben.
  - **×10** kauft genau 10 Einheiten oder nichts (kein Teilkauf). **Max** kauft mindestens 1, sonst Fehlschlag. Bei einer Maximalstufe (`upgrades.json: maxLevel`) schlägt ×10 fehl, wenn weniger als 10 Stufen übrig sind; Max wird auf die Reststufen gedeckelt.
  - **Max-Kauf:** Das Ergebnis der Formel wird gegen Rundungsfehler korrigiert, sodass `cost(n, k) <= M` und `cost(n, k + 1) > M` gilt. `cost(n, 1)` ist exakt `price(n)`.
  - **Rabatte:** `hall_cost` wirkt als Faktor auf `baseCost` der Hallen, `layer_unlock_cost` auf `unlockCost` der Schichten (Faktor-Stats, Clamp aus `statLimits`).
  - **Schicht-Freischaltung:** nur die nächste Schicht in Reihenfolge (`unlockedLayers` zählt die offenen Schichten). Die neue Schicht bekommt Team- und Lagereinträge mit 0.
  - **Werkzeugstufen** werden mit FP bezahlt (Währung aus `upgrades.json`), Generatoren mit der Währung aus `generators.json`, Teams und Schichten mit Geld.
  - **Modifier-Quellen:** `ModifierSources.sync(engine, state, data)` gleicht die Quellen der Engine mit dem Spielstand ab (idempotent, unveränderte Quellen werden nicht neu gesetzt). Provider im MVP-Stand AP-06: `upgrade:<generatorId>` (Effekte je Einheit mal Bestand, z. B. `upgrade:preparator`), `upgrade:<upgradeId>` (`effectsPerLevel` mal Stufe, z. B. `upgrade:tools`) und `skill:<skillId>`. AP-11 ergänzt `BookSources` (`book:<speciesId>`, `book:researched:<speciesId>`, Kapitel 4.7) und `MilestoneSources` (`milestone:<id>`, Kapitel 5.2). Quellen mit Prefixen ohne Provider bleiben unberührt.
  - **Startwerte:** `RunStart.values(data, engine)` erzeugt `RunStartValues`: Teams in Schicht 1 = `start_teams`, Laborplätze = `lab_slot.free + start_lab_slots`, Hallen = `hall.free`, Präparatoren = `preparator.free`, Geld/FP/Werkzeugstufe 0.
  - Die festen Verknüpfungen zwischen Definitions-IDs und Feldern des Spielstands (`preparator`, `hall`, `lab_slot`, `tools`) stehen in `core.data.DefinitionIds`.

### 6.4 Startwerte (vorläufig, simulationsgeprüft)

**Schichten (MVP)**

| Schicht | unlockCost | teamBaseCost | value ($/s je Stück) | fragility | fameWeight |
|---|---|---|---|---|---|
| Quartär | 0 | 10 | 1 | 0.15 | 1 |
| Neogen | 6 000 | 2 000 | 8 | 0.30 | 3 |
| Kreide | 250 000 | 60 000 | 60 | 0.45 | 10 |

**Generatoren und Käufe**

| Kauf | baseCost | growth | Wirkung |
|---|---|---|---|
| Grabungsteam | je Schicht (oben) | 1.12 | +0.1 Blöcke/s, ×2 bei 25/50/100/200 |
| Präparator | 25 | 1.17 | +0.2 Blöcke/s Verarbeitung |
| Museumshalle | 50 | 3.0 | +8 Ausstellungsplätze (1 Halle gratis) |
| Laborplatz | 300 | 4.0 | +1 parallele Forschung (1 gratis) |
| Werkzeugstufe (FP) | 3 FP | 1.6 | `damage_reduction` +0.03 |

**Globale Werte (`balance.json`)**: `findChance 0.08`, `qualityBaseMin 0.30`, `qualityBaseMax 1.00`, `damageRollMin 0.5`, `damageRollMax 2.0`, `rockSellFactor 0.5`, `duplicateSellFactor 20`, `labDuration 30`, `labQueuePerSlot 3`, `reconstructionCap 0.89`, `reconstructionMissingZoneQuality 0.5`, `skeletonMult 2.0`, `slotsPerHall 8`, `teamMilestoneFactor 2`, `missingBoneWeight 3`, `tapDigAmount 0.25`, `tapPrepAmount 0.25`, `skillCostGrowth 1.25`, `offlineEfficiency 0.5`, `offlineCapHours 4`, `maxDetailedOfflineFinds 2000`.

**Arten (MVP, Seltenheitsgewicht in Klammern)**

| Quartär | Neogen | Kreide |
|---|---|---|
| Höhlenbär (40) | Hipparion (40) | Psittacosaurus (40) |
| Riesenhirsch (25) | Gomphotherium (25) | Parasaurolophus (25) |
| Wollnashorn (15) | Chalicotherium (15) | Triceratops (15) |
| Wollhaarmammut (10) | Deinotherium (10) | Velociraptor (10) |
| Säbelzahnkatze (Smilodon) (5) | Terrorvogel (Titanis) (5) | Ankylosaurus (5) |
| Riesenfaultier (Megatherium) (2) | Thylacosmilus (2) | Tyrannosaurus (2) |

**Buch-Boni pro Art** (`species.json: bookBonus`, Kategorie `book`, wirksamer Wert = Basiswert × `bookTierFactor[minTier]`). Häufige Arten 1 %, seltene bis 3 %; `add`-Stats mit absoluten Werten.

| Quartär | Neogen | Kreide |
|---|---|---|
| Höhlenbär: `museum_income` mult 0.01 | Hipparion: `dig_rate` mult 0.01 | Psittacosaurus: `quality_base_min` add 0.01 |
| Riesenhirsch: `prep_rate` mult 0.01 | Gomphotherium: `museum_income` mult 0.02 | Parasaurolophus: `prep_rate` mult 0.02 |
| Wollnashorn: `sell_price` mult 0.02 | Chalicotherium: `research_points` mult 0.02 | Triceratops: `damage_reduction` add 0.01 |
| Wollhaarmammut: `dig_rate` mult 0.02 | Deinotherium: `prep_rate` mult 0.02 | Velociraptor: `skeleton_bonus` add 0.02 |
| Smilodon: `find_chance` mult 0.02 | Titanis: `sell_price` mult 0.03 | Ankylosaurus: `museum_income` mult 0.03 |
| Megatherium: `research_points` mult 0.03 | Thylacosmilus: `find_chance` mult 0.03 | Tyrannosaurus: `fame_gain` mult 0.03 |

### 6.5 Bekannte Balancing-Risiken
- **Plateau in der Mitte des Durchlaufs:** Ist eine Schicht fast voll gesammelt, wächst das Museumseinkommen kaum noch. Gegenmittel: Sparziel-Anzeige für die nächste Schicht („Neogen: 62 %“), Prestige-Vorschau als Signal, Verkaufserlöse skalieren mit der Produktion.
- **Grind bei seltenen Arten:** Gewicht 2 von 97 heißt, nur etwa 2 % der Fossilien stammen von dieser Art. Gegenmittel: Pech-Schutz, Ersatzteile, Skill *Glückliche Hand*.
- **Buch-Boni:** Sie dürfen in Summe nicht so stark werden, dass frühe Schichten trivial werden. Deshalb Kategorie `book` additiv und kleine Basiswerte.

### 6.6 Große Zahlen [MVP]
- Eigener Typ `GameNumber` (Value-Class um `Double`) mit Operatoren, `compareTo`, `isFinite`-Prüfung und Serialisierung. Ein späterer Austausch gegen Mantisse plus Exponent betrifft nur diese Klasse.
- Formatierung `NumberFormatter`:
  - Standard: Kurzformen. DE: `1,5 K`, `2,3 Mio.`, `4,1 Mrd.`, `1,2 Bio.`; EN: `1.5K`, `2.3M`, `4.1B`, `1.2T`. Ab 10^15 automatisch wissenschaftlich.
  - Option in den Einstellungen: immer wissenschaftlich (`1,5e9`).
  - Unter 1000 ganzzahlig, darüber 1 bis 2 Nachkommastellen.
  - Präzisierungen (AP-03): Höchstens 2 Nachkommastellen, Nullen am Ende entfallen (`1 Mio.`, `1,5 K`). Gerundet wird kaufmännisch (HALF_UP) **vor** der Wahl der Größenklasse, daher `999,6 → 1 K`, `999 999 → 1 Mio.` und `999,999 Bio. → 1e15`. Auch im Modus „immer wissenschaftlich“ bleiben Beträge unter 1000 ganzzahlig (`42` statt `4,2e1`). Negative Zahlen erhalten ein `-` vor dem Betrag; was auf 0 rundet, wird als `0` ohne Vorzeichen angezeigt. `NaN`/`±Infinity` werden als `NaN` bzw. `∞`/`-∞` angezeigt. Die Sprache wird als eigener Enum `NumberLanguage` (DE/EN) übergeben, unabhängig von der System-Locale.
  - Serialisierung: `GameNumber` wird als JSON-Zahl geschrieben; das Standard-`Json` lehnt `NaN`/`Infinity` beim Schreiben ab.
- Werte nie als `Int`/`Long` speichern. `NaN` bzw. `Infinity` werden in `advance()` erkannt und als Fehler geloggt (Debug: Exception).

### 6.7 Balancing-Werkzeug [MVP]
- `tools/sim/fossil_sim.py` simuliert einen Durchlauf mit einer gierigen Kauf-KI und gibt den Verlauf (Geld, Einkommen, Funde, Skelette, Ruhm) pro Zeitpunkt aus.
- AP-28 stellt es so um, dass es die JSON-Dateien aus `core/src/main/resources/data/` liest. Zusätzlich gibt es einen Kotlin-Test `PacingTest`, der einen 60-Minuten-Durchlauf mit festem Seed simuliert und prüft, dass Schicht 2 zwischen 15 und 40 Minuten erreicht wird.
- Umsetzung (AP-15): `core/src/test/.../core/sim/PacingTest` und `GreedyBot` (mit `GreedyBotConfig`, `BotSnapshot`).
  - **Ablage im Testquellbaum** (Paket `core.sim`), nicht in `core/src/main`: Die KI ist ein Prüfwerkzeug und gehört nicht ins APK. AP-28 bindet das Python-Werkzeug an die JSON-Daten an und bildet die Kotlin-KI dort nach (siehe unten). Soll sie später außerhalb von Tests laufen (z. B. als Gradle-Task), wird sie in ein eigenes Source-Set verschoben.
  - **Nur öffentliche Aktionen:** `tapDig`, `tapPrep`, `advance` (Seed fest, `SeededRandom`), `Purchases` (Teams, Präparatoren, `unlockNextLayer`), `buyHalls`, `buyLabSlots`, `buyToolLevel` (Max mit allen FP) und `reconstruct` (nach jedem abgeschlossenen Laborjob alle Rekonstruktionen mit `isImprovement`). Kein Prestige, keine Skills (erster Durchlauf).
  - **Kauf-Logik wie die Referenz** (`Run.buy`): Optionen sind das nächste Team je offener Schicht, die nächste Schicht, ein Präparator (nur solange `prepRate < Grabung * 1.1`, Preis mit 0.7 gewichtet), eine Halle (ab `Run-Funde > Plätze * 0.8`) und ein Laborplatz (ab `Duplikate > 20 * Laborplätze`). Gekauft wird die billigste Option, solange sie bezahlbar ist; sonst wird gespart. Diese Strategie-Parameter sind Spielerverhalten, keine Spielwerte, und stehen deshalb in `GreedyBotConfig` statt in den JSON-Daten.
  - **Tippen:** 2 Tipps/s auf die tiefste offene Schicht und 2 Tipps/s auf „Freilegen“ in den ersten 10 min (= Referenz `MANUAL_DIG`/`MANUAL_PREP` 0.5 Blöcke/s, `ACTIVE_SECONDS` 600). Tippen nur in Minute 0–2 verschiebt Neogen um ca. 0,5 min (gemessen). Ganz ohne Tippen kommt der Durchlauf nicht in Gang (Startgeld 0, kein Team), das ist so gewollt (5.1: erste Funde durch Tippen).
  - **Tick 1 s** wie die Referenz und der Offline-Fortschritt: `advance` ist zeitschritt-tolerant (gemessen: Tick 0,1 s ergibt dieselben Neogen-Zeitpunkte, Ruhm ±4 durch andere Zufallsfolge), die Laufzeit ist zehnmal kürzer. Die Tipprate wird bei kleineren Ticks anteilig verteilt.
  - **Assertions** je Seed 1, 2, 3: Neogen zwischen 15 und 40 min, `fameGainPreview` nach 60 min zwischen 8 und 40. Verlauf alle 5 min (Minute, Geld, Museumseinkommen/s, Geldzufluss/s, Funde, Skelette, Ruhm-Vorschau, Bestände) in der Standardausgabe des Tests (Gradle-Testbericht). Laufzeit ca. 0,3–0,7 s je Seed.
  - **Messwerte** (Daten Stand AP-15, Seeds 1–5): Neogen nach 21,8–22,9 min, erstes Skelett nach 2,4–3,2 min, Ruhm 30 min 11–14, 45 min 22–26, 60 min 31 (alle 12 Skelette der Schichten 1–2 vollständig), Museum 60 min ca. 1 300 $/s. Python-Referenz der Design-Phase (vor AP-28) Seeds 1–3: Neogen 28,0–28,3 min, Ruhm 45 min 11–14, 60 min 19–22, Museum ca. 700 $/s.
  - **Ursachen der Abweichung** (Ablation, je eine Änderung, Mittel über Seeds 1–5): Ohne Buch-Boni (inkl. `researchedBonus`) Museum 60 min −27 %, Neogen +1,4 min; ohne Meilenstein-Belohnungen Neogen +1,2 min; `missingBoneWeight` 1 (Referenz hat keinen Pech-Schutz) Ruhm 45 min −5, erstes Skelett +1,4 min; Rekonstruktions-Cap 0,85 statt 0,89 und Verzicht auf Rekonstruktion je ca. −1 bis −2 Ruhm. Mit allen vier Referenz-Annahmen zusammen liefert die Kotlin-Engine Neogen nach 25–27 min und 13–15 Ruhm nach 45 min, also nahe an der Referenz; Engine und Referenz stimmen damit im Kern überein, die Beschleunigung kommt aus den später ergänzten Systemen (Kapitel 4.7, 4.9, 5.2).
  - **Vorschlag für AP-29** (nicht umgesetzt, weil die AP-15-Grenzen erfüllt sind): Neogen `unlockCost` 6 000 → 8 000 und `fameWeight` 3 → 2 ergibt Neogen nach 25,8–27,1 min, Ruhm 45 min 17–19, 60 min 21–23. Zusätzlich `bookTierFactor` [1, 1.5, 2, 3, 4] senkt den Ruhm nach 45 min auf 14–19. Das erste Skelett bleibt bei ca. 3 min (Pech-Schutz plus frühe Ökonomie); entweder die Erwartung „~10 min“ in 5.1 anpassen oder die frühe Phase (z. B. `findChance`, Präparatorpreis) gezielt verlangsamen.
- Umsetzung (AP-28): `tools/sim/fossil_sim.py` (nur Python-Standardbibliothek), Anleitung und Vergleichstabelle in `tools/sim/README.md`, Tests `tools/sim/test_fossil_sim.py` (CI).
  - **Daten:** liest `layers`, `species`, `bones`, `generators`, `upgrades`, `balance`, `milestones` und `skilltree` aus `core/src/main/resources/data/` (oder `--data`). Im Skript stehen nur Formeln und das Spielerverhalten (`BotConfig`, entspricht `GreedyBotConfig`). `--set pfad=wert` ändert einzelne Datenwerte nur für einen Lauf (z. B. `layers.neogene.unlockCost=8000`), damit Balancing-Ideen ohne Änderung der JSON-Dateien geprüft werden können.
  - **Nachbau der Engine statt der alten Referenz:** gleiche Systeme und Reihenfolge wie `GameEngine.advance` und `GreedyBot` (Präparation in ganzen Blöcken, Pech-Schutz, Funde einordnen, Museum, Labor FIFO, Rekonstruktion mit Cap, Buch-Boni, Meilensteine, Modifier-Quellen und Abgleichzeitpunkte, Kauf-KI). Der Zufall bildet `kotlin.random.Random(seed)` (XorWow) nach, Summen werden wie in Kotlin ohne Kompensation gebildet. **Ergebnis:** Für die Seeds 1–5 ist der Verlauf identisch mit dem `PacingTest` (Neogen, Ruhm 45/60 min, alle Verlaufszeilen; Ziel war ±15 %). Damit gelten Python-Ergebnisse direkt für die Engine.
  - **Parameter:** Seed(s) (`--seeds 1-5`), Dauer, Intervall, Datenordner, CSV (`--csv`), Schalter für Analysen (`--no-book-bonus`, `--no-milestones`, `--no-bad-luck-protection`, `--no-reconstruction`), gekaufte Skills (`--skills`), Tippdauer und Schrittweite.
  - **Grenzen:** nur erster Durchlauf mit KI (kein Prestige, keine Sonderausstellung, kein Offline). Ändert ein AP die Engine-Logik, muss `fossil_sim.py` nachgezogen werden; die Gleichheit mit dem `PacingTest` ist die Kontrolle (Vergleich in `tools/sim/README.md`). Die CI prüft nur Lauffähigkeit und Determinismus, nicht die Gleichheit (die Kotlin-Werte stehen nur im Testlog).

---

## 7. Offline-Fortschritt und Speichern

### 7.1 Offline [MVP: einfach]
- Beim App-Start bzw. bei der Rückkehr: `elapsed = now - lastSavedAt`. Negative Werte → 0.
- `effective = min(elapsed, offlineCapHours) * offlineEfficiency` (Start 4 h, 50 %).
- Aufruf von `advance(state, effective)` in 1-Sekunden-Schritten. Nach `maxDetailedOfflineFinds` einzeln gewürfelten Fossilien werden weitere Funde über den Erwartungswert direkt in Verkaufserlös bzw. FP umgerechnet.
- **Willkommen-zurück-Dialog:** Abwesenheitszeit, Geld, Anzahl Funde und die besten 3 neuen Funde (mit Qualitätsfarbe).
- Upgrades für Offline-Dauer und -Effizienz: [Später] (Ast Expedition).
- Keine Absicherung gegen das Verstellen der Uhr über die Obergrenze hinaus (Einzelspieler, keine Ranglisten).
- Präzisierungen (AP-14), Umsetzung `core.engine.OfflineProgress`, Aufruf `GameEngine.applyOffline(state, now, rng): OfflineResult(state, summary)`:
  - `elapsed = (now - lastSavedAt) / 1000` s, negativ → 0. `effective = min(elapsed, offline_cap_hours · 3600) · offline_efficiency`; beide Stats über die Modifier (Basis `balance.offlineCapHours` bzw. `balance.offlineEfficiency`, Clamps aus `statLimits`, Quellen werden vorher abgeglichen). Das Ergebnis hat immer `lastSavedAt = now`, auch bei negativer Zeit (dann sonst unverändert).
  - **Ein Simulationspfad:** `GameEngine.advance(state, 1.0, context)` je volle Sekunde, ein kleinerer Rest als letzter Schritt. Es laufen dieselben Systeme wie online. Offline-spezifisch ist nur der `AdvanceContext`: Ereignisse gehen an einen Empfänger, der sie sofort zusammenfasst (keine Ereignislisten über 14 400 Schritte), und eine `DetailedFindLimit` begrenzt die einzeln gewürfelten Fossilien. `advance(state, dt, rng)` ist unverändert `advance(state, dt, AdvanceContext(rng))`. Wird ein Schritt wegen `NaN`/`Infinity` verworfen (`AdvanceResult.rejected`, nur mit nicht werfendem Handler), bricht die Nachberechnung ab.
  - **Aggregation nach `maxDetailedOfflineFinds`:** Der Fundwurf je Block (Fossil oder Gestein) bleibt ein echter Wurf, Gestein bringt normal Geld. Ist die Grenze erschöpft, werden für Fossilien Art, Knochen und Zonen **nicht** mehr gewürfelt. Die Präparation zählt sie in `meta.stats.piecesFound` und `BlocksPrepared.fossils` und übergibt sie je Schicht und Aufruf gesammelt an `FindHandler.onAggregatedFinds(state, layer, count, expectedQuality, context)`. Der `FindClassifier` verkauft sie als Duplikate: `count · layer.value · (0.5 + E[q]) · duplicateSellFactor · sell_price`, Ereignis `GameEvent.FindsAggregated(layerId, count, expectedQuality, money)`.
  - **Näherung:** Aggregierte Funde ändern weder Buch noch Run-Bestand noch Laborwarteschlange und bringen keine FP (alle gelten als verkaufte Duplikate, kein `pristineFound`). Begründung: Nach 2 000 einzeln gewürfelten Funden ist der Durchlauf in aller Regel weitgehend gesammelt, die Warteschlange ist voll und Duplikate werden ohnehin verkauft; das Labor arbeitet seine Warteschlange offline normal weiter. Der Erlös selbst ist exakt der Erwartungswert eines verkauften Duplikats, weil `layer.value` für alle Arten einer Schicht gleich und der Preis linear in `q` ist.
  - **`E[q]`** (`PrepSystem.expectedQuality(layer)`): Erwartungswert einer Zone (= Stückqualität, da Mittelwert gleich verteilter Zonen) `(1 - no_damage_chance) · E[clamp(base - f · d, 0, 1)] + no_damage_chance · E[base]` mit `base ~ U(quality_base_min, qualityBaseMax)`, `d ~ U(damageRollMin, damageRollMax)`, `f = max(0, fragility - damage_reduction)`. Wegen des Clamps wird numerisch über ein 64×64-Mittelpunktgitter integriert (Fehler < 0.001; bei Kreide liegt `E[q]` deutlich über `E[base] - E[damage]`), je Schicht zwischengespeichert, solange sich die Eingaben nicht ändern.
  - **Zusammenfassung** `OfflineSummary`: `elapsedSeconds` (roh), `creditedSeconds` (nach Obergrenze), `effectiveSeconds`, `moneyGained`, `researchPointsGained` (Differenzen des Spielstands, also inkl. Meilenstein-Belohnungen), `detailedFinds`, `aggregatedFinds`, `totalFinds`, `bestFinds` (die besten 3 einzeln gewürfelten Funde als `OfflineFind(pieceKey, quality, tierId)`, höchstens einer je Schlüssel, Gleichstand nach Schlüsseltext) und `milestonesReached` (IDs). Grundlage für den Willkommen-Dialog (AP-23).
  - **Performance:** 4 h effektive Zeit (14 400 Schritte) mit spätem Durchlauf (alle Schichten, 400 Teams, 150 Präparatoren, volles Museum, ~68 000 Funde) dauern im JVM-Test etwa 0,7–0,9 s (`OfflineProgressTest`, Messwert im Log, kein harter Assert). Dafür wurden zwei Stellen beschleunigt, die auch online in jedem Tick laufen: `StateValidator` baut Pfadtexte nur noch im Fehlerfall, und `ModifierSources.sync` überspringt einen Provider, wenn er dieselbe Map-Instanz wie beim letzten Abgleich derselben Engine liefert und sich deren Quellen seitdem nicht geändert haben (`ModifierEngine.revision`). `BookSources` gibt dafür bei unveränderten `meta.book`/`meta.researchedSpecies` (gleiche Instanzen) das letzte Ergebnis zurück.
- Umsetzung (AP-23), Willkommen-zurück-Dialog in `app/ui/welcome`:
  - **Auslöser:** Jedes Mal, wenn die App in den Vordergrund kommt (erster Start nach dem Laden und jede Rückkehr), ruft `GameViewModel.catchUpOffline` `GameEngine.applyOffline(state, now, rng)` auf (Zeit aus `platform/TimeSource`). Es läuft im selben `collectLatest`-Block **vor** der Spielschleife, die Schleife ist also pausiert. Gerechnet wird auf dem Spiel-Dispatcher (`Dispatchers.Default` mit Parallelität 1, nie der Main-Thread), damit Engine und Spielstand nur von einem Thread benutzt werden; Spieleraktionen währenddessen werden danach ausgeführt. Danach wird der Zustand übernommen und sofort gespeichert. Die Berechnung ist nicht abbrechbar: Geht die App währenddessen wieder in den Hintergrund, wird das Ergebnis trotzdem übernommen (sonst zählte die Zeit doppelt). Die Rechenzeit selbst (bis etwa 1 s) wird nicht angerechnet.
  - **Referenz `lastSavedAt`:** Beim Übergang in den Hintergrund wird immer gespeichert und `lastSavedAt` neu gesetzt, auch wenn sich der Stand seit dem letzten Speichern nicht geändert hat; `tick` läuft im Hintergrund nicht. So zählt keine Vordergrund-Zeit als Offline-Zeit.
  - **Kurze Abwesenheit:** unter `WelcomeUiMapper.MIN_DIALOG_SECONDS` (60 s, reine UI-Konstante, kein Balancing-Wert) wird angerechnet, aber ohne Dialog und ohne Lade-Indikator. Ein noch offener Dialog bleibt dann stehen; eine neue lange Abwesenheit ersetzt ihn.
  - **Zustand:** `GameUiState.Ready.welcome: WelcomeUiState?` (`Calculating(elapsedSeconds)` mit Lade-Indikator, nicht schließbar, bzw. `Summary`). `WelcomeUiMapper.map(summary, engine.offline.capSeconds(), engine.offline.efficiency())` baut außerhalb der Composables: Abwesenheit roh, angerechnet und simuliert, Obergrenze, Effizienz in %, `capped`, Geld, FP, Anzahl aller Funde (`totalFinds`), die besten 3 Funde (`WelcomeFindUi`: Art, Knochen, Qualität, Stufe) und Meilenstein-IDs. Schließen über `GameViewModel.dismissWelcome`.
  - **Anzeige:** `WelcomeBackDialog` als Overlay in `GameShell`. Dauern über `formatDuration(seconds, DurationUnits)` mit den zwei größten Einheiten (z. B. „2 h 15 min“, „5 min 30 s“, „4 h“; Einheiten aus `strings_welcome.xml`). Funde mit Qualitätsfarbe (`QualityColors.forTier`), Namen über den `TextProvider`, Meilensteine über `LocaleKeys.milestoneName`.

### 7.2 Speichern [MVP]
- Eine JSON-Datei im App-internen Speicher (`files/save.json`), serialisiert mit kotlinx.serialization.
- Feld `saveVersion` (Start 1) und `SaveMigrator` mit Migrationsschritten `v(n) -> v(n+1)`.
- Autosave alle 30 s sowie in `onStop` bzw. wenn die App in den Hintergrund geht. Zusätzlich direkt nach dem Prestige.
- **Atomares Schreiben:** in `save.json.tmp` schreiben, dann umbenennen. Rotation von 3 Backups (`save.bak1..3`). Ist die Hauptdatei beim Laden defekt, wird das neueste lesbare Backup genommen und der Spieler informiert.
- **Export/Import** in den Einstellungen: Spielstand als Base64-Text (Zwischenablage). Der Import validiert und migriert, vorher kommt eine Bestätigung.
- Cloud-Save: [Später/optional].
- Umsetzung (AP-05): `core.model` (`GameState`, `MetaState`, `RunState`, `Settings`, `Piece`, `BookEntry`, `QueuedPiece`, `LabJob`, `PieceKey`, `SpareZoneKey`) und `core.save` (`SaveCodec`, `SaveMigrator`, `SaveMigration`, `SaveException`).
  - Laden: JSON parsen → `saveVersion` prüfen → Migrationskette auf `JsonObject`-Ebene → `GameState`. Fehler als `SaveException` (`InvalidJson`, `InvalidEncoding`, `MissingVersion`, `UnsupportedVersion` inkl. „zu neu“, `MigrationFailed`, `InvalidContent`, `NotEncodable`).
  - Unbekannte Felder werden beim Laden ignoriert (`ignoreUnknownKeys`), fehlende Pflichtfelder und falsche Typen sind Fehler. Beim Schreiben werden alle Felder inkl. Standardwerten geschrieben. `NaN`/`Infinity` sind weder beim Schreiben noch beim Lesen erlaubt.
  - Laborwarteschlange und Laborjobs speichern die Zonen-Qualitäten des Stücks mit, weil das Labor daraus Ersatzteile gewinnt (4.5).
  - Qualitäten (0.0–1.0) und die Restdauer von Laborjobs sind `Double`; alle Mengen, Währungen und Zähler sind `GameNumber`.
  - Der Startzustand (`GameState.newGame`, `RunState.newRun`) legt nur Struktur an; Balancing-Startwerte übergibt der Aufrufer als `RunStartValues`.
- Umsetzung (AP-16): `app/platform/SaveStorage` (reine `java.io.File`-Logik ohne Android, JVM-Tests in `app/src/test`).
  - Schreiben: `save.json.tmp` vollständig schreiben (`fsync`) → Backups rotieren (`bak2 → bak3`, `bak1 → bak2`, `save.json` nach `bak1` kopieren) → `save.json.tmp` atomar in `save.json` umbenennen. `save.json.tmp` wird beim Laden nie gelesen.
  - Laden: `save.json`, dann `bak1..3`; der erste lesbare Stand gewinnt (`SaveLoadResult.Loaded` mit `SaveSource`). Auch ein Spielstand aus einer neueren App-Version (`UnsupportedVersion`) zählt als unlesbar.
  - Kam der Stand aus einem Backup, wird die defekte Hauptdatei beim nächsten Speichern verworfen statt rotiert, damit sie keine lesbaren Backups verdrängt. Ist gar nichts lesbar (`Unreadable`), wird die Hauptdatei nach `save.corrupt.json` kopiert und ein neues Spiel gestartet; die UI zeigt jeweils einen Hinweis (Snackbar).
  - `GameViewModel` setzt `lastSavedAt` bei jedem Speichern auf die aktuelle Zeit. Ein neues Spiel wird sofort gespeichert. Seit AP-22 wird auch direkt nach dem Prestige gespeichert (`GameViewModel.confirmPrestige`). Beim Wechsel in den Hintergrund (`Activity.onStop` und `ProcessLifecycleOwner` `ON_STOP` über `platform/AppLifecycleObserver`) wird gespeichert und die Spielschleife angehalten; die Zeit im Hintergrund ist Offline-Zeit und wird bei der Rückkehr nachgerechnet (AP-23, Kapitel 7.1).
- Umsetzung (AP-24), Export/Import und Löschen im Einstellungs-Screen (Kapitel 8.3), Logik in `app/vm/SaveTransfer` (JVM-testbar):
  - **Export:** `SaveCodec.exportBase64` des aktuellen Stands (auf dem Spiel-Thread), die UI kopiert den Text in die Zwischenablage (`LocalClipboard`) und zeigt „kopiert“ als Snackbar.
  - **Import:** Text aus dem Eingabefeld oder per „Einfügen“ aus der Zwischenablage. `SaveTransfer.import` dekodiert, validiert und migriert über `SaveCodec.importBase64` und prüft danach alle IDs gegen die Spieldaten (`core.save.SaveReferenceValidator`: Schichten, Arten, Knochen, Zonen, Skills, Meilensteine, Sonderausstellung, `unlockedLayers` 1 bis Anzahl Schichten). Grund: Die Engine schlägt IDs über `GameData.layer(...)` usw. nach; ein importierter Stand mit unbekannten IDs würde sofort gespeichert und das Spiel bei jedem Start abstürzen lassen. Fehler werden als `ImportError` (je `SaveException`-Typ, `UnsupportedVersion` getrennt nach „zu neu“ und „zu alt“, plus `EMPTY` und `UNKNOWN_CONTENT`) mit eigenem Text angezeigt. Erst ein gültiger Text öffnet die Bestätigung („aktueller Spielstand wird überschrieben“); danach prüft das ViewModel erneut und ersetzt den Stand (`replaceState`: Modifier-Abgleich, Geldrate, Popups, Tabs, Dialoge zurücksetzen, sofort speichern; der alte Stand rotiert normal in `save.bak1`).
  - **Kein Offline-Fortschritt für importierte Stände:** `lastSavedAt` wird beim Import auf jetzt gesetzt. Ein Export kann beliebig alt sein; sonst brächte jeder Import bis zu `offline_cap_hours` Fortschritt ohne echte Abwesenheit. Die Einstellungen kommen mit dem importierten Stand.
  - **Spielstand löschen** (doppelte Bestätigung, Kapitel 8.3): neues Spiel über `GameState.newGame` + `RunStart.values` mit einer frischen `ModifierEngine` ohne Quellen (die laufende kennt noch Skills und Buch-Boni des alten Stands). Die Einstellungen bleiben erhalten (Bedienvorlieben, kein Fortschritt). **Backups werden mitgelöscht** (`SaveStorage.deleteAll`: `save.json`, `.tmp`, `bak1..3`, `save.corrupt.json`), danach wird der neue Stand sofort gespeichert. Grund: `SaveStorage.load` fällt bei defekter Hauptdatei auf die Backups zurück, ein gelöschter Stand könnte sonst später wieder auftauchen. Als Sicherheitsnetz dienen die doppelte Bestätigung und der Hinweis, vorher zu exportieren. Der neue Stand wird vor dem Löschen im Speicher gesetzt, ein Autosave währenddessen schreibt also nie den alten Stand.
  - Sprache und Zahlenformat werden bei jeder Änderung sofort gespeichert.

---

## 8. UI, Navigation, Grafik

### 8.1 Grundlayout [MVP]
- Hochformat, einhändig, Material 3, dunkles Thema als Standard (Museums- bzw. Gesteinsoptik).
- **Obere Leiste:** Geld (+ $/s), FP, Ruhm, Zahnrad (Einstellungen).
- **Untere Navigation:** Grabung · Labor · Museum · Sammelbuch · Ruhm.
- **Badges** an Tabs: Forschung fertig, neuer Bucheintrag, bezahlbares Upgrade, Prestige verfügbar.
  - Umsetzung (AP-25): `vm/TabBadges` (reine Funktionen) liefert `GameUiState.Ready.badges` (`Map<GameTab, TabBadge>`). Labor: `RESEARCH_DONE` (ein `LabJobCompleted`, während das Labor nicht offen war) vor `AFFORDABLE`; Sammelbuch: `NEW_BOOK_ENTRY` (`BookUpdated` seit dem letzten Besuch); Ruhm: `PRESTIGE` (`canPrestige`); Grabung, Museum: `AFFORDABLE`. „Bezahlbar“ zählt nur **×1-Käufe** der Haupt-Generatoren je Tab (Grabung: Teams einer offenen Schicht, Präparatoren oder Freischalten der nächsten Schicht; Museum: Halle; Labor: Laborplatz oder Werkzeugstufe), abgeleitet aus den Kaufvorschauen der UI-Zustände. Der gerade offene Tab hat nie ein Badge; die Shell meldet ihn über `GameViewModel.onTabShown` (Einstellungen offen = keiner). Darstellung: Ereignisse als Punkt in der Fehlerfarbe, „Kauf möglich“ dezent in der Akzentfarbe, Beschreibung für Bedienungshilfen.
  - **„Seit letztem Besuch“ wird nicht gespeichert** (`vm/UnseenUpdates`, nur im ViewModel): Es ist ein Hinweis auf Ereignisse der laufenden Sitzung; nach einem Neustart fasst der Willkommen-Dialog die Abwesenheit zusammen, und „bezahlbar“/„Prestige“ folgen ohnehin aus dem Spielstand. Ein Speicherfeld nur für zwei UI-Flags wäre Formatballast ohne Spielwirkung.
- **Zahlen-Animation (AP-25):** Geld, FP und Ruhm der oberen Leiste laufen dem Zielwert in 250 ms linear hinterher (`ui/feedback/NumberTween`, `animatedGameNumber`). Bei sinkendem Wert (Kauf), Sprung über Faktor 2 (Belohnung, Offline, Prestige) oder Startwert 0 springt die Anzeige sofort. Da jede Animation beim angezeigten Wert beginnt, läuft die Anzeige bei steigendem Wert nur vorwärts (kein Flackern bei 10 Ticks/s). Die $/s-Anzeige ist schon geglättet und wird nicht animiert.

### 8.2 Schrittweise Einführung der Tabs [MVP]

| Tab | Sichtbar ab |
|---|---|
| Grabung | Start |
| Museum | erstes Fossil |
| Sammelbuch | 5 verschiedene Funde |
| Labor | erstes Duplikat |
| Ruhm | Prestige-Vorschau ≥ 1 oder nach dem ersten Prestige |

Beim ersten Erscheinen eines Tabs gibt es einen kurzen Hinweistext (ein Satz).

Umsetzung (AP-16): `app/vm/TabUnlockRules`. Museum: `meta.stats.piecesFound > 0` oder ein Bucheintrag. Sammelbuch: mindestens 5 Einträge im Sammelbuch (`meta.book`). Labor: **Platzhalter bis AP-09/AP-10** (es gibt noch keinen Duplikat-Zähler): Laborwarteschlange, Laborjobs, Ersatzteile oder eine erforschte Art vorhanden. Ruhm: `prestigeCount > 0` oder `fameTotal > 0` oder Prestige-Vorschau ≥ 1 (**Platzhalter bis AP-12**, liefert `false`). Einmal gezeigte Tabs bleiben während der Sitzung sichtbar (`revealTabs`); dauerhaft gespeichert wird das noch nicht (offener Punkt für AP-25, das ohnehin „erstes Erscheinen“ für die Hinweistexte braucht).

Umsetzung (AP-25): Gezeigte Tabs stehen dauerhaft in `meta.revealedTabs` (IDs `dig`, `lab`, `museum`, `book`, `fame` aus `GameTab.id`; Standardwert leer, ohne Migration). **Metazustand statt Einstellungen**, weil die Menge zum Spielstand gehört: Sie bleibt beim Prestige erhalten, „Spielstand löschen“ (behält die Einstellungen) setzt sie zurück, und ein Import bringt die des importierten Stands mit. `vm/TabReveal.update` (reine Funktion) gleicht bei jedem Veröffentlichen mit `TabUnlockRules.visibleTabs` ab; neue Tabs kommen in den Spielstand (gespeichert mit dem nächsten Speichern) und lösen je einen Hinweis `GameMessage.TabHint` aus (Snackbar, lang, mit Schließen-Knopf; Texte in `strings_hints.xml`). Neues Spiel: Hinweis zur Grabung. **Ältere Spielstände** ohne gespeicherte Tabs übernehmen beim ersten Abgleich alle freigeschalteten Tabs still, wenn es mehr als einer ist (keine Hinweisflut für erfahrene Spieler).

### 8.3 Screens
- **Grabung:** Vertikaler Querschnitt mit Schichten als Farbbänder (oben jung, unten alt). Pro Schicht: Blöcke im Lager, Blöcke/s, Teams kaufen (×1/×10/Max). Gesperrte Schichten zeigen Kosten und Spar-Fortschritt. Darunter der Bereich Präparation: großer Freilege-Button (Tippen), Präparatoren kaufen, Verarbeitungsrate. **Fund-Popup:** Ein neuer oder besserer Fund erscheint kurz als Karte (Art, Knochen, Qualitätsstufe in Farbe). Bei vielen Funden pro Sekunde werden die Popups gebündelt (höchstens 1 pro Sekunde, Priorität: bessere Stufe).
  - Umsetzung (AP-17): `app/ui/dig/DigScreen`. Daten aus `GameUiState.Ready.dig` (`DigUiState`, abgeleitet in `vm/DigStateMapper` aus `GameEngine.blocksPerSecond`/`prepRate` und `Purchases.quote`/`unlockCost`/`nextLockedLayer`; Vorschauen für alle drei Kaufmodi). Aktionen über `GameViewModel` (implementiert `vm/DigActions`, intern `perform` bzw. `performWithEvents` für `tapPrep`, Käufe seit AP-25 über `purchase` mit `GameEngine.buyTeams`/`buyPreparators`/`unlockLayer`); nach jeder Aktion werden die Modifier-Quellen abgeglichen.
    - Tippen auf das Band einer offenen Schicht gräbt in **dieser** Schicht (`tapDig`). Gesperrte Schichten zeigen Kosten und Spar-Fortschritt `Geld / unlockCost` (0–100 %); nur die nächste hat einen Freischalten-Button, tiefere zeigen einen Hinweis.
    - „Freilegen“ ist nur aktiv, wenn mindestens ein **ganzer** Block in einer offenen Schicht liegt (sonst verfiele die Kapazität, 4.2); ein Balken zeigt `run.prepProgress`.
    - Kaufmodus ×1/×10/Max gilt für den ganzen Screen. Gemeinsame Bausteine in `app/ui/components/BuyMode.kt` (`rememberBuyMode`, `BuyModeSelector`, `BuyButton` aus einer `PurchaseQuote`) für AP-18/19.
    - **Fund-Popup:** aus `NewPiece` („Neuer Fund“) und `PieceImproved` („Besserer Fund“), Stufe über `QualityTiers`. Bündelung in `vm/FindPopupBundler`: ein Platz für den besten Kandidaten (höhere Stufe, dann höhere Qualität, bei Gleichstand der frühere), ausgegeben höchstens alle 1 s; schwächere Kandidaten derselben Sperrzeit verfallen. `GameViewModel.findPopups` liefert die gebündelten Popups, die Karte bleibt 2,5 s sichtbar bzw. bis das nächste kommt.
    - **Zahlen:** Kleine Raten und Lagerbestände (Blöcke, Blöcke/s) zeigt die UI unter 1000 mit bis zu 2 Nachkommastellen (`formatPrecise`, z. B. `0,25`), sonst stünde dort `0`. Ab 1000 gilt `NumberFormatter` unverändert (Abweichung von 6.6 nur für diese Anzeigen).
- **Labor:** Forschungsplätze mit Fortschrittsbalken, Warteschlange, Laborplatz kaufen, Werkzeugstufen (FP), Liste rekonstruierbarer Knochen mit Button „Rekonstruieren“.
  Umsetzung (AP-19): `app/ui/lab`. `LabUiMapper.map(engine, state, data)` baut außerhalb der Composables den `LabUiState` (Feld `lab` in `GameUiState.Ready`): Plätze (`lab.slots`, für die Anzeige auf 1000 gedeckelt), laufende Forschungen als `LabJobUi` (Art, Knochen, Stufe, Qualität, Fortschritt aus `lab.progress`, Restzeit, FP aus `lab.researchPointsFor`), freie Plätze, Warteschlange (`QueuedPieceUi`, FIFO) mit Kapazität `GameEngine.labQueueCapacity` (= `ceil(labSlots * labQueuePerSlot)`, passend zu `FindClassifier.hasQueueSpace`), Kaufvorschauen je Kaufmodus (`GameEngine.labSlotQuote`, `toolLevelQuote`; `null` bei Maximalstufe), Werkzeugstufe mit aktuellem `damage_reduction` (`GameEngine.damageReduction`, alle Quellen) und Zuwachs je Stufe aus `upgrades.json`, sowie `ReconstructionUi` aus `GameEngine.reconstructableBones`.
    - Neue Engine-Komfortfunktionen (wie `buyHalls`, gleichen vorher die Modifier ab): `buyLabSlots`, `buyToolLevel`; Abfragen `labSlotQuote`, `toolLevelQuote`, `damageReduction`, `labQueueCapacity`.
    - Der Fortschrittsbalken zeigt `lab.progress(job)` (seit AP-25 bezogen auf die beim Start gespeicherte Dauer) ohne Animation (kein Zurückschwingen, wenn ein Platz das nächste Stück beginnt). Listenschlüssel der Plätze sind Platznummern.
    - Ein Kaufmodus (×1/×10/Max) gilt für Laborplätze und Werkzeugstufen gemeinsam.
    - „Rekonstruieren“ ist nur aktiv bei `isImprovement`; die Aktion läuft über `GameViewModel.reconstruct` (`performWithEvents`, Ereignisse gehen an Events und Fund-Popups). Wird sie abgelehnt (z. B. weil sich der Stand seit der Anzeige geändert hat), bleibt der Zustand unverändert und es erscheint ein Hinweis (`GameMessage.ReconstructionFailed` mit `ReconstructionFailure`).
- **Museum:** Einnahmen/s, Hallen als Raster der ausgestellten Stücke, Halle kaufen, Hinweis auf Skelett-Boni. Capstone *Sonderausstellung*: Auswahl eines Skeletts.
  Umsetzung (AP-18): `app/ui/museum`. `MuseumUiMapper.map(engine, state, data)` baut außerhalb der Composables den `MuseumUiState` (Feld `museum` in `GameUiState.Ready`): Einnahmen/s, Plätze (`museum.slots`), ausgestellte Stücke als `ExhibitUi` (Art-, Knochen- und Stufen-ID, Qualität, $/s, Flags), Kaufvorschau je Kaufmodus (`GameEngine.hallQuote`), Skelett-Faktor, vollständige Skelette und `SpecialExhibitUi` (nur bei `special_exhibit_mult` > 0, sonst ausgeblendet). Halle `i` zeigt die Stücke `i * slotsPerHall …` in absteigendem Wert, freie Plätze bleiben sichtbar. Aktionen über `museumActions(viewModel)` → `purchase { … }` mit `GameEngine.buyHalls(state, mode)` (seit AP-25, vorher `perform`) und `perform { … }` mit `GameEngine.setSpecialExhibit`. Namen löst die UI über den gemeinsamen `TextProvider` auf (`rememberTextProvider(state.texts, state.settings)`), der Hallenkauf nutzt die gemeinsamen Bausteine aus `ui/components/BuyMode.kt` (`rememberBuyMode`, `BuyModeSelector`, `BuyButton` mit dem Generatornamen `hall`).
- **Sammelbuch:** Reiter pro Schicht, Raster der Arten (Silhouette; nicht gefunden = dunkel mit „?“), Completion-Stufe als Symbol. **Art-Detail:** Skelett-Grafik mit Knochen eingefärbt nach Stufe (fehlend = grau gestrichelt), Liste der Knochen mit Qualität in % und Zonen-Balken, Buch-Bonus der Art.
  Umsetzung (AP-20): `app/ui/book`. `BookUiMapper(data).map(engine, state)` baut außerhalb der Composables den `BookUiState` (Feld `book` in `GameUiState.Ready`) aus `GameEngine.bookOverview` und `Completion.tierFactor`: alle Schichten als `BookLayerUi` (auch gesperrte, `unlocked` aus `dig.unlockedLayers`), je Art `BookSpeciesUi` (entdeckt = Bucheintrag vorhanden oder erforscht, Stufe, Knochen im Buch/gesamt, `minTierIndex`/`minTierId`, Knochen als `BookBoneUi` mit bestem Eintrag über Alter/Geschlecht, Stufe, Flag „rekonstruiert“ und Zonen `BookZoneUi` aus `BookEntry.zones`, leere Zonenliste = unbekannt), `BookBonusUi` (wirksamer Wert und Faktor ab Stufe 2, sonst Vorschau von `value * bookTierFactor[0]` bis `value * bookTierFactor[letzte]`) und `ResearchedBonusUi`.
    - **Cache:** Der Mapper ist eine Instanz im `GameViewModel` und liefert dieselbe Instanz, solange `meta.book` und `meta.researchedSpecies` (Instanzvergleich) und die Zahl der offenen Schichten gleich sind; `publish` (10/s) kostet dann nur diesen Vergleich.
    - Raster: unbekannte Arten dunkel mit „?“ (nicht antippbar), sonst Name, `x/8 Knochen`, Symbol (Haken = Stufe 1, Stern in Farbe der schlechtesten Stufe = Stufe 2). Gesperrte Schichten haben ein Schloss im Reiter und einen Hinweis.
    - Art-Detail als Zustand im Screen (`rememberSaveable`), Zurück-Taste (`BackHandler`) und Pfeil führen zur Liste.
    - **Skelett-Grafik:** austauschbare Composable `SkeletonView(speciesId, bodyPlan, bones: Map<boneId, SkeletonBone(tierIndex?, zoneQualities?)>)`. AP-20 zeichnet einen schematischen Platzhalter (generischer Vier- bzw. Zweibeiner aus einfachen Formen, Farbe nach Stufe über `QualityColors.forTierIndex`, bei bekannten Zonen Streifen je Zone, fehlend grau gestrichelt, unbekannte Knochen-IDs als Kacheln am unteren Rand). AP-21 ersetzt nur den Inhalt dieser Funktion (siehe 8.5, Umsetzung AP-21).
    - Stat-Namen der Boni über `TextProvider.statName(StatId)` (Schlüssel `stat.<id>.name`, für alle Stats Pflicht in beiden Sprachen). Bonuszahlen: `mult` als Prozent, `add` als Zahl (`formatBonusNumber`). Seit AP-25 auch `add` auf Anteile bzw. Wahrscheinlichkeiten als Prozent (`StatId.isFraction`: `find_chance`, `guaranteed_fossil_chance`, `no_damage_chance`, `quality_base_min`, `damage_reduction`, `offline_efficiency`; `bonusShownAsPercent`), z. B. `guaranteed_fossil_chance +0.02` → „+2 %“. Gilt für Sammelbuch, Skilltree und die Schadensminderung im Labor.
- **Ruhm:** Ruhm verfügbar/gesamt, Skilltree als 4 vertikale Äste, Prestige-Button mit Vorschau, Dialog mit Reset-Übersicht, Respec-Option im Prestige-Dialog.
  Umsetzung (AP-22): `app/ui/fame`. `FameUiMapper.map(engine, state, dialog)` baut außerhalb der Composables den `FameUiState` (Feld **`fameScreen`** in `GameUiState.Ready`, weil `fame` dort schon der Ruhm der oberen Leiste ist): Ruhm verfügbar/gesamt, Prestige-Zähler, Vorschau `GameEngine.fameGainPreview` und `canPrestige` (Vorschau ≥ 1), Äste aus `GameEngine.skillOverview` als `SkillBranchUi`/`SkillNodeUi` (Status, Preis, `affordable`, fehlende Voraussetzungen, Wirkung als `Modifier` aus `skilltree.json`).
    - Skilltree: 4 Äste als Spalten nebeneinander, Knoten nach Tiefe untereinander. Name und Beschreibung aus der Locale (`LocaleKeys.skillName`/`skillDescription`), Wirkung als „Stat: Wert“ über `TextProvider.statName` und `formatBonusNumber` (`mult` als Prozent, `add` als Zahl, wie im Sammelbuch).
    - **Kauf:** Tippen auf einen Knoten öffnet die Details (Beschreibung, Wirkung, Preis, fehlende Voraussetzung) mit „Kaufen“; das ist die Bestätigung. Kauf über `perform` mit `GameEngine.buySkill`.
    - **Prestige-Dialog** (Vollbild): Zustand `PrestigeDialogState` (offen, Respec ja/nein, Auswahl) liegt im `GameViewModel` (übersteht Drehungen, Vorschau auf dem Spiel-Thread); der Mapper baut daraus `PrestigeDialogUi` mit `GameEngine.prestigeSummary` (Verlorenes nur mit Bestand, Bleibendes, Gewinn, Anzahl Skelette) und bei „Skills neu verteilen“ `RespecUi` aus `GameEngine.respecPreview(state, selection)` (Erstattung, Ruhm verfügbar/übrig, Baum im Zustand nach dem Neukauf, abgelehnte Wünsche mit Grund). Die Auswahl startet mit den gekauften Skills in Kaufreihenfolge (Neukauf kostet dann genau die Erstattung); Tippen wählt ab bzw. wählt an, wenn der Knoten in der Vorschau kaufbar ist. Abgewählte Voraussetzungen lassen abhängige Wünsche stehen; sie erscheinen als abgelehnt.
    - **Bestätigen:** `GameViewModel.confirmPrestige` → `engine.prestige(state, NONE)` bzw. `prestige(state, engine.respecStep(selection))`; Ereignisse an `events`, Snackbar `GameMessage.Prestiged` (bzw. `PrestigeFailed`), Geldrate zurückgesetzt, **sofort gespeichert** (7.2). Der Ruhm-Tab bleibt sichtbar (`fameTotal > 0` und `revealTabs`).
- **Einstellungen:** Sprache (System/DE/EN), Zahlenformat, Export/Import, Spielstand löschen (doppelte Bestätigung), Version.
  Umsetzung (AP-24): `app/ui/settings`. `SettingsScreen(settings, versionName, actions, onMessage)` ersetzt in `GameShell` den Inhalt, solange das Zahnrad geöffnet ist (Zurück-Taste und Pfeil schließen, die untere Navigation ist ausgeblendet). Aktionen über `SettingsActions`/`settingsActions(viewModel)` → `GameViewModel.setLanguage`, `setNumberFormat`, `exportSave`, `checkImport`, `importSave`, `deleteSave`; Rückmeldungen als `GameMessage` (`ImportSucceeded`, `ImportFailed`, `SaveDeleted`, `SaveDeletedIncomplete`) bzw. direkt als Snackbar (kopiert, Export fehlgeschlagen). Testbare Logik in `SettingsLogic` (Bestätigungswort, Texte je `ImportError`) und `vm/SaveTransfer` (Kapitel 7.2).
  - Sprache: Auswahl System/Deutsch/English (Sprachnamen in der eigenen Sprache), wirkt auf UI-Texte, Inhaltstexte und Zahlen (Kapitel 12).
  - Zahlenformat: Kurz/Wissenschaftlich mit Beispielzahl (1,5 Mio. bzw. 1,5e6), wirkt sofort.
  - Löschen: erster Dialog mit Hinweis auf den Export, zweiter Dialog mit Eintippen des Bestätigungsworts (`LÖSCHEN`/`DELETE`, Groß-/Kleinschreibung egal).
  - Version: `BuildConfig.VERSION_NAME` (`buildFeatures.buildConfig` ist dafür aktiviert).

### 8.4 Qualitätsfarben
Fragment Grau `#8A8A8A` · Beschädigt Braun `#A0703C` · Gut Grün `#5FA35F` · Exzellent Blau `#4A90D9` · Museumsreif Gold `#E2B93B`.
Umsetzung (AP-16): Die App liest die Farben aus `balance.json: qualityTiers[].color` (`ui/theme/QualityColors`, bereitgestellt über `LocalQualityColors`), damit sie nur an einer Stelle stehen.

### 8.5 Grafikstil
- Flache, **schematische Vektorgrafik** im Stil von Museumstafeln: knochenfarbene Formen (`#E8DFC8`) auf dunklem Grund, begrenzte Palette. Bewusst vereinfacht, nicht anatomisch exakt.
- Quelle: selbst erzeugte SVGs in `art/skeletons/<speciesId>.svg`, keine fremden Vorlagen, keine Anlehnung an bekannte Film- oder Markengrafik.
- **Konvention:** Jeder Knochen ist eine Gruppe `id="<boneId>"`, jede Zone ein Pfad `id="<boneId>__<zoneId>"`. Ein Build-Skript (`tools/svg2json.py`) wandelt die SVGs in `skeleton_<speciesId>.json` (Pfaddaten plus IDs) um. Die App zeichnet sie mit Compose `PathParser` und färbt sie zur Laufzeit ein.
- Schichthintergründe: Farbbänder mit einfacher Textur, ebenfalls per SVG oder Compose-Zeichnung.
- **MVP:** Platzhalter (ein generisches Vierbeiner- bzw. Zweibeiner-Skelett für alle Arten) ist zulässig. Die echten 18 Skelette entstehen in AP-27.
- Umsetzung (AP-21):
    - **SVG-Regeln** (`tools/svg2json.py`, nur Python-Standardbibliothek): `viewBox` am `<svg>` Pflicht; in Knochengruppen nur Zonen-Pfade, keine verschachtelten Knochengruppen; **`transform` ist an jedem Element verboten** (wird nicht aufgelöst, damit die Koordinaten im JSON denen im SVG entsprechen; vor dem Export anwenden); Elemente außerhalb von Knochengruppen (Hintergrund) werden ignoriert. Die Gruppenreihenfolge ist die Zeichenreihenfolge, die **Zonenreihenfolge muss der in `bones.json` entsprechen** (Index i der Grafik = `zoneQualities[i]`).
    - **Ausgabe:** `core/src/main/resources/data/skeletons/skeleton_<name>.json` (`name`, `viewBox {x,y,width,height}`, `bones[{id, zones[{id, d}]}]`, Schema `schema/skeleton.schema.json`). Die JSONs sind eingecheckt, der Build braucht kein Python; `svg2json.py --check` und `tools/test_svg2json.py` laufen in der CI. Anleitung im README (Abschnitt Tools).
    - **Platzhalter:** `art/skeletons/quadruped.svg` und `biped.svg` (8 Knochen × 4 Zonen, schematisch, `#E8DFC8` auf dunklem Grund).
    - **Fallback-Regel:** Eine Art nutzt `skeleton_<speciesId>.json` (= `species.art`, vom `DataValidator` erzwungen); fehlt die Datei, gilt `skeleton_<bodyPlan>.json`. Bis AP-27 nutzen alle Arten die Platzhalter. AP-27 legt nur `art/skeletons/<speciesId>.svg` an und erzeugt die JSONs.
    - **Core:** `core.data.SkeletonArt` (Modell), `SkeletonArtRepository` (`load`, `resolveFile`, `forSpecies`, `candidates`), `SkeletonArtValidator` (jeder Knochen/jede Zone aus `bones.json` vorhanden, Reihenfolge, keine unbekannten Einträge). `DataValidationTest` prüft beide Platzhalter und die aufgelöste Grafik jeder Art.
    - **Arten-Grafiken (AP-27):** Quartär (AP-27a) liegt vor: `cave_bear`, `giant_deer`, `woolly_rhino`, `woolly_mammoth`, `smilodon`, `megatherium` (alle Blick nach rechts, viewBox `0 0 400 200`, Megatherium halb aufgerichtet). Gezeichnet mit der Hilfe `tools/art/skeleton_gen.py` (Grundformen plus Freischneiden mit Fuge, sodass sich keine Zonen überlappen; Anleitung `tools/art/README.md`), Quelle bleiben die SVGs. Schematische Zuordnungen: Schulterblatt zur Zone Oberarm, Schwanzwirbel zu `dorsal__centrum`, Querfortsätze als Lappen unter den Wirbelkörpern (abwechselnd links/rechts), Geweih/Stirnhorn zu `skull_roof`, Nasenhorn/Stoßzähne/Säbelzähne zu `snout`.
      Neogen (AP-27b) liegt vor: `hipparion` (mit zwei kurzen Seitenzehen an jedem Fuß), `gomphotherium`, `chalicotherium` (abfallender Rücken, Knöchelgang mit eingeschlagenen Krallen), `deinotherium`, `titanis`, `thylacosmilus`, gleicher Generator und Stil. Zusätzliche Zuordnungen: obere Stoßzähne zu `snout`, Unterkiefer-Stoßzähne (Gomphotherium flach, Deinotherium nach unten gebogen) und der Kinnfortsatz von Thylacosmilus zu `jaw__tip`. **Vogel (Titanis, bodyPlan `biped`):** `skull` = Schädel mit Hakenschnabel (Oberschnabel = `snout`), `jaw` = Unterschnabel, `cervical` = S-förmiger Hals, `dorsal` = kurzer Rücken plus Schwanzwirbel, `pelvis` = Becken (Schambein nach hinten), `forelimb` = kurzer, nach hinten angelegter Flügel (`humerus` mit Schulterblatt, `radius_ulna`, `carpus` = Handwurzel/Mittelhand, `digits` = Flügelfinger mit Kralle), `hindlimb` = Laufbein (`tibia` = Tibiotarsus, `tarsus` = Tarsometatarsus, `toes` = drei Zehen vorn und eine hinten).
    - **App:** `ui/book/SkeletonArtCache` parst die Pfade einmal pro Datei mit Compose `PathParser` (Laden auf `Dispatchers.IO`), `SkeletonView` zeichnet in einer `Canvas` mit viewBox-Skalierung (zentriert). Farbe je Zone über `SkeletonColoring`: bekannte Zonenqualität → `QualityColors.forQuality`, sonst Knochenstufe `forTierIndex`, ohne Farben `#E8DFC8`; fehlende Knochen nur als grau gestrichelte Kontur. Knochen, die die Grafik nicht enthält, werden nicht gezeichnet (die Validierung stellt sicher, dass jede Grafik alle Knochen aus `bones.json` hat); die Kacheln für unbekannte Knochen aus AP-20 entfallen.

---

## 9. Monetarisierung
Keine. Das Spiel ist ein Hobbyprojekt. Keine Werbung, keine In-App-Käufe, keine Analytics-SDKs.

---

## 10. Technik

### 10.1 Stack
- Kotlin (aktuelle stabile Version), Jetpack Compose, Material 3, minSdk 26, targetSdk aktuell.
- kotlinx.serialization (JSON), kotlinx.coroutines. Keine Datenbank (der Spielstand ist eine JSON-Datei).
- JUnit 5 in `core`. Gradle mit Version Catalog.
- GitHub Actions: Bei jedem Push laufen `core`-Tests, die Datenvalidierung und der Build der Debug-APK (als Artefakt).

### 10.2 Module und Pakete
```
/core                      reines Kotlin/JVM
  data/       JSON-Modelle (Definitionen) + DataRepository (lädt und validiert)
  model/      GameState, RunState, MetaState, Piece, PieceKey ...
  engine/     advance(), Grabung, Präparation, Labor, Museum, Prestige
  modifier/   Modifier, ModifierEngine, StatId
  number/     GameNumber, NumberFormatter
  save/       SaveCodec, SaveMigrator
  random/     GameRandom (Interface), SeededRandom
/app                       Android
  ui/         Screens, Komponenten, Theme
  vm/         GameViewModel (Game-Loop, Aktionen)
  platform/   SaveStorage (Datei, atomar), Lifecycle, Zeitquelle
/tools
  sim/        fossil_sim.py, test_fossil_sim.py, README.md
  svg2json.py
  validate_data.py (optional, CI)
  test_svg2json.py
/art/skeletons/*.svg       -> core/src/main/resources/data/skeletons/skeleton_*.json
```

### 10.3 Spielschleife
- `GameViewModel` startet eine Coroutine mit 10 Ticks/s: `engine.advance(state, dt = 0.1, rng)`. Danach wird ein unveränderlicher UI-State per `StateFlow` veröffentlicht.
- `advance()` ist der **einzige** Ort, an dem Zeit Spielzustand verändert. Spieleraktionen (kaufen, tippen, rekonstruieren, Prestige) sind eigene Engine-Funktionen, die den State zurückgeben.
- `advance()` gibt eine Liste von `GameEvent` zurück (Fund, Forschung fertig, Meilenstein …), die die UI für Popups und Badges nutzt.
- Umsetzung (AP-07): `core.engine.GameEngine(data, modifiers, systems, errorHandler)` mit `advance(state, dt, rng): AdvanceResult(state, events)`. Ablauf: `dt` prüfen → Modifier-Quellen per `ModifierSources.sync` abgleichen → alle `AdvanceSystem`s nacheinander (jedes bekommt den Zustand des vorigen, Ereignisse über `AdvanceContext.emit`, Zufall über `AdvanceContext.rng`) → Ergebnis auf `NaN`/`Infinity` prüfen (`StateValidator`).
  - **Erweiterungsstelle:** Spätere APs implementieren ein `AdvanceSystem` und fügen es in `GameEngine.defaultSystems` ein (geplant: Grabung → Präparation → Funde/Museum → Labor → Meilensteine). `advance` selbst bleibt unverändert.
  - `dt <= 0`: keine Änderung (Eingabe-Instanz, keine Ereignisse). Große `dt` werden in einem Schritt gerechnet; die Aufteilung der Offline-Zeit (7.1) übernimmt `GameEngine.applyOffline` (AP-14) über die Variante `advance(state, dt, context)`.
  - `NaN`/`Infinity` (in `dt` oder im Ergebnis): Aufruf von `NumericErrorHandler`. Standard `THROW` wirft `NonFiniteValueException` (Tests, Debug). Ein nicht werfender Handler (Release: loggen) führt dazu, dass der Tick verworfen wird: `advance` gibt den unveränderten Eingabe-Zustand ohne Ereignisse zurück.
  - Ereignisse in AP-07: `GameEvent.BlocksDug(layerId, amount)` je produzierender Schicht und Aufruf. Weitere Ereignisse ergänzen die jeweiligen APs.
  - Ereignisse in AP-08: `GameEvent.FossilFound(find, tierId)` je Fund und `GameEvent.BlocksPrepared(layerId, blocks, fossils, rockMoney)` je Schicht und Aufruf (Kapitel 4.2). Spieleraktion `GameEngine.tapPrep(state, rng): AdvanceResult` gleicht die Modifier ab und prüft das Ergebnis wie `advance`.
  - Ereignisse in AP-09: `BookUpdated(pieceKey, quality, previousQuality?)`, `NewPiece(pieceKey, quality)`, `PieceImproved(pieceKey, quality, previousQuality)`, `DuplicateQueued(pieceKey, quality)`, `DuplicateSold(pieceKey, quality, money)`, `SkeletonCompleted(speciesId)` (Kapitel 4.4). `defaultSystems`: `DigSystem` → `PrepSystem` mit `FindClassifier` → `MuseumSystem`.
  - Ereignisse in AP-10: `LabJobStarted(pieceKey, duration)`, `LabJobCompleted(pieceKey, researchPoints)`, `SpeciesResearched(speciesId)` (erstmals, nach dem zugehörigen `LabJobCompleted`) aus `advance`; `Reconstructed(pieceKey, quality, improvedRun, improvedBook)` als erstes Ereignis von `GameEngine.reconstruct` (`ReconstructionResult.Success(state, events, preview)` bzw. `Failure(reason)`), gefolgt von `BookUpdated`, `NewPiece` (+ `SkeletonCompleted`) bzw. `PieceImproved` (+ `DuplicateQueued`/`DuplicateSold`). `defaultSystems`: `DigSystem` → `PrepSystem` mit `FindClassifier` → `MuseumSystem` → `LabSystem`.
  - Ereignisse in AP-11: `MilestoneReached(milestoneId, money, researchPoints)` aus `advance` (zuletzt), `tapPrep`, `reconstruct` und `checkMilestones` (Kapitel 5.2). `defaultSystems`: `DigSystem` → `PrepSystem` mit `FindClassifier` → `MuseumSystem` → `LabSystem` → `MilestoneSystem`.
  - Ereignisse in AP-14: `FindsAggregated(layerId, count, expectedQuality, money)` nur im Offline-Fortschritt (Kapitel 7.1). `AdvanceResult` hat zusätzlich `rejected` (`true`, wenn der Tick wegen `NaN`/`Infinity` verworfen wurde).
  - Zufall: `core.random.GameRandom` (`nextDouble()`, `nextDouble(from, until)` mit `from == until` → `from`, `nextInt(bound)`) und `SeededRandom(seed)` auf Basis von `kotlin.random.Random`.
- Umsetzung (AP-16): `app/vm/GameViewModel`.
  - Engine, Spielstand und `ModifierEngine` werden nur auf einem Dispatcher mit Parallelität 1 benutzt (nicht thread-safe). Dateizugriffe laufen auf `Dispatchers.IO` unter einem Mutex.
  - Schleife: fester Takt 100 ms, je Tick `advance(state, 0.1, rng)`. Ist ein Tick zu spät, werden verpasste Ticks nicht nachgeholt. Die Schleife läuft nur im Vordergrund.
  - Veröffentlichung: `uiState: StateFlow<GameUiState>` (`Loading`, `DataError`, `Ready` mit Rohwerten; formatiert wird in der UI über `NumberFormatter`), `events: SharedFlow<GameEvent>` (ohne Wiederholung, Puffer 64), `messages: Flow<GameMessage>` (Channel, genau einmal zugestellt).
  - „$/s“ in der oberen Leiste ist die über 5 s geglättete Geldänderung durch `advance` (`RateTracker`), nicht durch Käufe. So stimmt die Anzeige ohne eigene Formel, sobald weitere Systeme Geld erzeugen.
  - Spieleraktionen: `perform { engine, state -> engine.xyz(state, ...) }` als Erweiterungsstelle für AP-17 ff.
  - Spieldaten: `DataRepository` lädt aus den Java-Ressourcen von `core`; diese landen im APK (`data/*.json` im APK-Root) und sind dort über den ClassLoader erreichbar.
  - Release-Build: `NumericErrorHandler` loggt und verwirft den Tick; Debug-Build wirft.

### 10.4 Tests [MVP]
- Pro Engine-System Unit-Tests mit festem Seed.
- `DataValidationTest`: alle JSON-Dateien laden, Referenzen prüfen (Art → Schicht, Knochen existiert, Skill-Voraussetzung existiert, Stat-ID bekannt).
- `SaveRoundTripTest`: speichern → laden → identisch; Migration von Test-Fixtures.
- `PacingTest` (siehe 6.7).

---

## 11. Datenstruktur (JSON)

Ablage: `core/src/main/resources/data/`. Alle IDs in `snake_case`, Texte nur als Schlüssel.

**`layers.json`**
```json
[
  { "id": "quaternary", "order": 0, "unlockCost": 0, "teamBaseCost": 10, "teamGrowth": 1.12,
    "teamRate": 0.1, "value": 1, "fragility": 0.15, "fameWeight": 1, "color": "#B9A27A" }
]
```

**`species.json`**
```json
[
  { "id": "parasaurolophus", "layer": "cretaceous", "rarityWeight": 25, "bodyPlan": "biped",
    "bones": ["skull","jaw","cervical","dorsal","ribs","pelvis","forelimb","hindlimb"],
    "art": "skeleton_parasaurolophus.json",
    "bookBonus": { "stat": "prep_rate", "op": "mult", "value": 0.02 } }
]
```
`art` muss `skeleton_<id>.json` heißen; fehlt die Datei in `data/skeletons/`, nutzt die App den Platzhalter `skeleton_<bodyPlan>.json` (8.5).

**`bones.json`**
```json
[ { "id": "dorsal", "zones": ["centrum","spinous","transverse_l","transverse_r"] } ]
```

**`generators.json`**
```json
[
  { "id": "preparator", "baseCost": 25, "growth": 1.17, "currency": "money",
    "effects": [ { "stat": "prep_rate", "op": "add", "value": 0.2, "category": "upgrade" } ] },
  { "id": "hall", "baseCost": 50, "growth": 3.0, "currency": "money", "free": 1 },
  { "id": "lab_slot", "baseCost": 300, "growth": 4.0, "currency": "money", "free": 1 }
]
```

**`upgrades.json`**
```json
[ { "id": "tools", "currency": "research_points", "baseCost": 3, "growth": 1.6, "maxLevel": null,
    "effectsPerLevel": [ { "stat": "damage_reduction", "op": "add", "value": 0.03, "category": "upgrade" } ] } ]
```

**`skilltree.json`**
```json
[ { "id": "prep_5", "branch": "prep", "depth": 5, "baseCost": 8, "requires": ["prep_4"], "capstone": true,
    "effects": [ { "stat": "no_damage_chance", "op": "add", "value": 0.03, "category": "skill" } ] } ]
```

**`milestones.json`**: `{ "id", "condition": { "type": "pieces_found", "value": 1 }, "reward": { "money": 50 } }`. Bedingungstypen: `pieces_found`, `skeletons_completed`, `pristine_found`, `teams_total`, `layers_unlocked`, `research_completed`, `reconstructions`, `prestiges`. Belohnung: `money`, `researchPoints` und optional `effects` (Modifier der Kategorie `milestone`).

**`balance.json`**: alle globalen Konstanten aus Kapitel 6.4 sowie `qualityTiers` (`id`, `minQuality`, `color`), `bookTierFactor`, `teamMilestones`, `teamMilestoneFactor`, `researchedBonus` (Modifier), `slotsPerHall`, `skeletonMult`, `statLimits` (`{ "<statId>": { "min", "max" } }`).

**`locale/de.json`, `locale/en.json`**: `{ "species.parasaurolophus.name": "Parasaurolophus", "bone.dorsal.name": "Rückenwirbel", "zone.dorsal.centrum": "Wirbelkörper" }`. Weitere Schlüssel: `layer.<id>.name`, `generator.<id>.name`, `upgrade.<id>.name`, `skill_branch.<branch>.name`, `skill.<id>.name|desc`, `milestone.<id>.name|desc`, `quality.<tierId>.name` (gebildet in `LocaleKeys`). Beschreibungstexte enthalten keine Zahlenwerte; die UI zeigt Werte aus den Modifiern an.

**Schemas:** `core/src/main/resources/schema/*.schema.json`. Die Validierung läuft über `DataValidationTest`, optional zusätzlich `tools/validate_data.py` in der CI.

**Spielstand (Auszug)**

Seit AP-25: `meta.revealedTabs` (IDs der gezeigten Tabs, 8.2, Standardwert `[]`) und `labJobs[].duration` (Gesamtdauer beim Start, Standardwert `null`); ältere Spielstände laden ohne Migration.

`book`-Einträge enthalten seit AP-09 zusätzlich `zones` (Zonen-Qualitäten des Buch-Fundes für die Zonen-Balken im Art-Detail, 8.3). Das Feld hat den Standardwert `[]`, ältere Spielstände laden ohne Migration; leer heißt „unbekannt“, maßgeblich ist `quality`. Rekonstruierte Einträge (AP-10) speichern die (ggf. auf den Cap skalierten) Zonen der Rekonstruktion.

```json
{
  "saveVersion": 1,
  "lastSavedAt": 1762351200000,
  "meta": {
    "fame": 15, "fameTotal": 19, "prestigeCount": 2,
    "skills": ["dig_1","dig_2"], "fameSpent": 4,
    "book": { "parasaurolophus:dorsal:_:_": { "quality": 0.83, "reconstructed": false, "zones": [0.9,0.8,0.8,0.82] } },
    "researchedSpecies": ["cave_bear"],
    "milestonesDone": ["first_find"],
    "stats": { "piecesFound": 1520, "pristineFound": 3, "researchCompleted": 410, "reconstructions": 12 },
    "revealedTabs": ["dig","museum","book","lab","fame"]
  },
  "run": {
    "money": 1234.5, "researchPoints": 12, "unlockedLayers": 2,
    "teams": { "quaternary": 41, "neogene": 5 }, "preparators": 26, "halls": 4, "labSlots": 2,
    "toolLevel": 3, "blocks": { "quaternary": 2.4, "neogene": 0.7 }, "prepProgress": 0.25,
    "pieces": { "cave_bear:skull:_:_": { "zones": [0.9,0.7,0.8,0.6] } },
    "spareZones": { "cave_bear:skull:_:_:snout": 0.92 },
    "labQueue": [ { "piece": "hipparion:ribs:_:_", "zones": [0.1,0.2,0.3,0.4] } ],
    "labJobs": [ { "piece": "cave_bear:skull:_:_", "zones": [0.92,0.5,0.5,0.5], "remaining": 12.0, "duration": 30.0 } ],
    "specialExhibit": null
  },
  "settings": { "language": "system", "numberFormat": "short" }
}
```

---

## 12. Lokalisierung [MVP]
- Deutsch ist Standard, Englisch vollständig ab dem ersten Release.
- UI-Texte: `app/src/main/res/values/strings.xml` (de) und `values-en/strings.xml`.
- Inhaltstexte (Arten, Knochen, Zonen, Skills, Meilensteine): `core/.../data/locale/de.json` und `en.json`, aufgelöst über einen `TextProvider` in `core`.
  - Umsetzung (AP-17): `core.data.TextProvider(locales, language)` mit `text(key)` und Helfern (`layerName`, `speciesName`, `boneName`, `zoneName`, `generatorName`, `upgradeName`, `qualityTierName`, seit AP-20 `statName`; Schlüssel aus `LocaleKeys`). Suchreihenfolge: gewünschte Sprache → Deutsch → Schlüssel selbst (kein Absturz). Die App wählt die Sprache wie beim Zahlenformat (`resolveContentLanguage`: Einstellung DE/EN, bei „System“ Deutsch für `de`, sonst Englisch) über `rememberTextProvider(state.texts, state.settings)`.
- Sprache: Standard ist die Systemsprache, umstellbar in den Einstellungen.
  - Umsetzung (AP-24), ohne AppCompat: `MainActivity.attachBaseContext` überschreibt die Locale der Activity-Ressourcen per `createConfigurationContext` (`platform/AppLocale`). Dadurch sehen alle Composables einschließlich Dialogen (eigene Fenster, die `LocalConfiguration` neu aus dem Kontext lesen) dieselbe Sprache, und `rememberTextProvider`/`rememberGameNumberFormat` folgen automatisch, weil sie die Sprache aus `LocalConfiguration` lesen. Bei einer Änderung wird die Activity per `recreate()` neu aufgebaut (ViewModel und Spiel laufen weiter, `onStop` speichert dabei nicht). Weil die Activity die Sprache vor dem Laden des Spielstands braucht, spiegelt `platform/AppLanguageStore` die Einstellung in den SharedPreferences; maßgeblich bleibt der Spielstand (weicht er ab, z. B. nach einem Import, wird der Spiegel angepasst und neu aufgebaut). Der Ladebildschirm erscheint in der gespiegelten Sprache.
  - Verworfen: `AppCompatDelegate.setApplicationLocales` (bräuchte AppCompat), `LocaleManager` (erst ab API 33, minSdk 26 bräuchte ohnehin einen zweiten Weg; zwei Mechanismen und eine zweite, systemseitige Quelle der Wahrheit) und ein reines Überschreiben von `LocalContext`/`LocalConfiguration` in Compose (wirkt nicht in Dialogen, weil deren Fenster die Werte neu setzen).
  - Auch bei „System“ wird die Locale auf die aufgelöste Sprache (`de` bzw. `en`, Regel wie `resolveContentLanguage`) gesetzt. Bisher bekam z. B. ein französisches System die Standardressourcen (Deutsch), Inhaltstexte und Zahlen aber Englisch; jetzt ist alles Englisch (Abweichung von „Deutsch ist Standard“ nur für Systeme, die weder Deutsch noch Englisch sind, passend zur bisherigen Regel der Inhaltstexte).
- `DataValidationTest` prüft, dass jeder Schlüssel in beiden Sprachen existiert.
- Zahlenformat folgt der gewählten Sprache (Komma bzw. Punkt).

---

## 13. MVP vs. Später

| Feature | MVP | Später |
|---|---|---|
| 1 Kontinent, 3 Schichten, 18 Arten, 8 Knochen × 4 Zonen | x | |
| Grabung (aktiv + Teams), Präparation, Qualität, Pech-Schutz | x | |
| Museum, Hallen, Skelett-Bonus, Verkauf | x | |
| Labor (fixe Dauer), Ersatzteile, manuelle Rekonstruktion, FP-Werkzeuge | x | |
| Sammelbuch mit Completion 1–2 und Buch-Boni | x | |
| Prestige, Ruhm, Skilltree (4 Äste + Capstones), Respec | x | |
| Meilensteine | x | |
| Offline einfach (50 %, 4 h, Willkommen-Dialog) | x | |
| Speichern atomar, Backups, Export/Import | x | |
| DE/EN, Zahlenformatierung | x | |
| Platzhalter-Skelette, danach 18 echte SVGs | x | |
| Alter/Geschlecht, Completion 3–4, Grabungsfokus | | x (Datenfelder schon im MVP) |
| Kontinente (parallel), Ast Expedition | | x |
| Offline-Upgrades | | x |
| Freilege-Minispiel für seltene Funde | | x |
| Auto-Rekonstruktion, Laborupgrades für Geld | | x |
| Wiederbelebung / Reservat (DNA) | | x |
| Weitere Schichten (Paläogen, Jura, Trias, Perm, …, Kambrium) | | x |
| Cloud-Save | | optional |
| iOS | | eigenes Projekt |

---

## 14. Aufgabenliste (Arbeitspakete)

Jedes AP ist für eine Claude-Code-Session gedacht. Format: **ID – Titel** (Abhängigkeiten) und darunter Akzeptanzkriterien.

### Phase 0 – Fundament
- **AP-01 – Projektgerüst** (–)
  Gradle-Projekt mit Modulen `core` (JVM) und `app` (Android, Compose). Leerer Startscreen, Version Catalog, JUnit 5 in `core`, GitHub Action (Tests + Debug-APK als Artefakt). README mit Build-Anleitung. Die Action läuft grün.
- **AP-02 – Datenmodelle und JSON-Daten** (AP-01)
  Definitions-Klassen für alle Dateien aus Kapitel 11, `DataRepository`, alle MVP-Datendateien mit den Werten aus 6.4 und 18 Arten inkl. Buch-Boni, Locale-Dateien DE/EN, Schemas, `DataValidationTest`.
- **AP-03 – GameNumber und NumberFormatter** (AP-01)
  Value-Class, Operatoren, Serialisierung, Formatierung DE/EN kurz und wissenschaftlich. Tests für Grenzfälle (0, <1000, 999 999 → 1 Mio., 10^15, NaN).
- **AP-04 – Modifier-System** (AP-02)
  `Modifier`, `StatId`, `ModifierEngine` mit Formel aus 6.2, Clamps, Cache. Tests: Addition innerhalb einer Kategorie, Multiplikation zwischen Kategorien, Clamps.

### Phase 1 – Spiellogik (`core`)
- **AP-05 – GameState und Spielstand** (AP-03)
  `GameState`/`RunState`/`MetaState`, `PieceKey` mit nullable `age`/`sex`, `SaveCodec`, `SaveMigrator` (v1), `SaveRoundTripTest`.
- **AP-06 – Kaufen und Kostenkurven** (AP-04, AP-05)
  Preis, Mehrfachkauf, Max-Kauf (6.3) für Teams, Präparatoren, Hallen, Laborplätze, Schichten, Werkzeuge. Tests gegen von Hand berechnete Werte.
- **AP-07 – Grabung und advance()-Grundgerüst** (AP-06)
  `advance(state, dt, rng)` mit Blockproduktion, Meilenstein-Verdopplung, Tippen, `GameEvent`-Liste.
- **AP-08 – Präparation und Qualität** (AP-07)
  Fundchance, Gestein-Erlös, Arten- und Knochenwahl mit Pech-Schutz, Zonenqualität, Stufen. Statistischer Test (10 000 Würfe, fester Seed): Verteilung der Arten und Schadenswirkung plausibel.
- **AP-09 – Funde einordnen, Museum, Verkauf** (AP-08)
  Logik aus 4.4 und 4.6: Buch- und Run-Update, Duplikate, Verkauf, Ausstellungsplätze, Einkommen, Skelett-Erkennung.
- **AP-10 – Labor und Rekonstruktion** (AP-09)
  Warteschlange, Plätze, Dauer, FP, Ersatzteile, „erforscht“, Rekonstruktion mit Cap. Test: Rekonstruktion erreicht nie Museumsreif.
- **AP-11 – Sammelbuch, Completion, Buch-Boni, Meilensteine** (AP-10)
  Completion 1–2, Buch-Boni über die `ModifierEngine`, Meilensteine mit Belohnung.
- **AP-12 – Prestige und Ruhm** (AP-11)
  Vorschau, Berechnung, Reset gemäß Tabelle 5.3. Test: Was bleiben muss, bleibt.
- **AP-13 – Skilltree** (AP-12)
  Kauf mit globalem Kostenwachstum, Voraussetzungen, Respec beim Prestige, alle 20 MVP-Knoten wirksam (inkl. Capstones). Je Capstone ein Test.
- **AP-14 – Offline-Fortschritt** (AP-13)
  Berechnung gemäß 7.1, Grenze für einzeln gewürfelte Funde, Zusammenfassung als Rückgabeobjekt. Test: Offline über 1 h ≈ Online über 0,5 h (bei 50 %).
- **AP-15 – PacingTest** (AP-14)
  60-Minuten-Simulation mit Kauf-KI in Kotlin. Assertion: Schicht 2 wird zwischen 15 und 40 Minuten erreicht, nach 60 Minuten liegt der Ruhm zwischen 8 und 40.

### Phase 2 – App und UI
- **AP-16 – App-Shell** (AP-07)
  Theme, obere Leiste, untere Navigation mit schrittweiser Einführung der Tabs, `GameViewModel` mit Game-Loop, `SaveStorage` atomar mit Backups, Autosave und Lifecycle-Speichern.
- **AP-17 – Screen Grabung** (AP-16, AP-08)
  Schicht-Querschnitt, Teams kaufen (×1/×10/Max), Schicht freischalten mit Spar-Fortschritt, Freilege-Button, Präparatoren, Fund-Popups (gebündelt).
- **AP-18 – Screen Museum** (AP-16, AP-09)
- **AP-19 – Screen Labor** (AP-16, AP-10)
- **AP-20 – Screen Sammelbuch (Liste und Detail mit Platzhalter-Grafik)** (AP-16, AP-11)
- **AP-21 – Skelett-Renderer** (AP-20)
  `tools/svg2json.py`, ein Platzhalter-SVG (Vierbeiner und Zweibeiner) nach Konvention 8.5, Compose-Zeichnung mit Einfärbung pro Zone bzw. Knochen.
- **AP-22 – Screen Ruhm (Skilltree und Prestige-Dialog)** (AP-16, AP-13)
- **AP-23 – Willkommen-zurück-Dialog** (AP-16, AP-14)
- **AP-24 – Einstellungen** (AP-16)
  Sprache, Zahlenformat, Export/Import, Löschen.
- **AP-25 – Feinschliff Feedback** (AP-17 – AP-22)
  Badges, Meilenstein-Toasts, Zahlen-Animationen, Hinweistexte beim ersten Erscheinen eines Tabs.
  Umgesetzt (Details in 4.5, 5.2, 8.1, 8.2, 8.3): außerdem Aufräumen früherer Merkposten: `LabJob.duration`, alle Käufe der UI einheitlich über `GameViewModel.purchase` mit den Kauf-Funktionen der `GameEngine` (neu: `buyTeams`, `buyPreparators`, `unlockLayer`), Prozent-Anzeige für `add` auf Anteile (`StatId.isFraction`).

### Phase 3 – Inhalt und Balancing
- **AP-26 – Schichthintergründe und Icons** (AP-17)
- **AP-27a/b/c – Skelett-SVGs Quartär / Neogen / Kreide** (AP-21)
  Je 6 Arten nach Konvention 8.5, schematischer Stil.
- **AP-28 – Balancing-Werkzeug anbinden** (AP-15)
  `fossil_sim.py` liest die JSON-Dateien, Ausgabe als Tabelle bzw. CSV, kurze Anleitung in `tools/sim/README.md`.
- **AP-29 – Balancing-Durchgang und Playtest-Fixes** (alle)
  Pacing aus 5.1 prüfen, Werte in JSON anpassen, Dokument aktualisieren.

**Empfohlene Reihenfolge für den ersten spielbaren Stand:** AP-01 → 02 → 03 → 04 → 05 → 06 → 07 → 08 → 09 → 16 → 17 → 18. Danach ist der Kern-Loop (graben, präparieren, ausstellen, Geld) auf dem Handy spielbar.

---

## 15. Offene Punkte
- Endgültiger Name (Arbeitstitel „Fossil Collector“), Paketname z. B. `de.noelkoeppen.fossilcollector`.
- Genaue Tipp-Werte (`tapDigAmount`, `tapPrepAmount`) nach dem ersten Playtest.
- Ob ein kurzes Tutorial nötig ist oder Hinweistexte plus Tab-Freischaltung reichen.

# GAME_DESIGN.md – Fossil Collector (Arbeitstitel)

> Zentrales Design-Dokument. Grundlage für die Umsetzung durch Claude-Code-Agenten.
> Stand: 2026-11-05 · Version 1.1 (AP-02: Datenwerte festgelegt)
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
- `milestoneMult(n) = 2 ^ (Anzahl erreichter Schwellen aus balance.teamMilestones)`, Standard: [25, 50, 100, 200].
- **Aktive Phase:** Ein Tipp auf die aktuelle Schicht erzeugt `balance.tapDigAmount` Blöcke (Start 0.25).
- Blöcke liegen pro Schicht in einem Lager. Die Präparation verarbeitet immer zuerst die **tiefste** Schicht mit verfügbaren Blöcken.
- Je tiefer die Schicht: höherer `value`, höhere `fragility`, höheres `fameWeight`.

### 4.2 Präparation und Qualität [MVP]
- Präparatoren verarbeiten `prepRate` Blöcke/s, Tippen auf „Freilegen“ verarbeitet `balance.tapPrepAmount` (Start 0.25).
- Pro freigelegtem Block:
  1. Mit Wahrscheinlichkeit `findChance` (Start 0.08, Modifier `find_chance`) ist es ein **Fossil**, sonst **Gestein** und bringt `layer.value * balance.rockSellFactor` Geld (Start 0.5).
  2. **Art** wird per `species.rarityWeight` aus der Schicht gezogen.
  3. **Knochen** wird gleichverteilt gezogen. Pech-Schutz siehe 4.9.
  4. **Pro Zone** (4 Stück):
     ```
     base   = uniform(qualityBaseMin, qualityBaseMax)        // Start 0.30 .. 1.00, Modifier quality_base_min
     damage = max(0, layer.fragility - toolReduction) * uniform(0.5, 2.0)
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

### 4.6 Museum [MVP]
- Ausstellungsplätze = `halls * balance.slotsPerHall` (Start 1 Halle, 8 Plätze). Automatisch ausgestellt werden die wertvollsten Run-Funde.
- Wert pro Stück und Sekunde:
  `income(piece) = layer.value * (0.5 + q) * (skeletonComplete(species) ? skeletonMult : 1)`
  `skeletonMult` Start 2.0 (Modifier `skeleton_bonus`, additiv auf den Faktor).
- Gesamteinnahmen: `Σ income(piece) * mods(museum_income)`.
- Hallen werden mit Geld gekauft (Kapitel 6.3).

### 4.7 Sammelbuch und Completion [MVP: Stufe 1–2]
- Das Buch listet pro Schicht alle Arten und pro Art alle 8 Knochen mit ihrer besten Qualität (Farbe der Stufe) sowie dem Flag „rekonstruiert“.
- **Completion-Stufen pro Art:**

| Stufe | Bedingung | Status |
|---|---|---|
| 1 Erforscht | Mindestens ein Stück der Art im Labor abgeschlossen | [MVP] |
| 2 Skelett vollständig | Alle Knochen im Buch vorhanden (egal welches Alter/Geschlecht) | [MVP] |
| 3 Konsistentes Skelett | Alle Knochen von derselben Kombination aus Alter und Geschlecht | [Später] |
| 4 Vollständige Population | Jede Kombination aus Alter und Geschlecht als konsistentes Skelett | [Später] |

- **Buch-Boni:** Jede Art definiert in `species.json` einen Bonus (`bookBonus`: Stat und Basiswert). Er wirkt, sobald Stufe 2 erreicht ist, skaliert mit der **niedrigsten** Qualitätsstufe ihrer Knochen:
  `bookBonusValue = baseValue * balance.bookTierFactor[minTier]`, Standard-Faktoren [1, 2, 3, 5, 8] für Fragment … Museumsreif.
  Stufe 1 gibt pauschal `balance.researchedBonus` (+1 % `museum_income`).
- Buch-Boni haben die Modifier-Kategorie `book`, sind permanent und werden innerhalb der Kategorie **addiert**.

### 4.8 Alter und Geschlecht [Später, Datenmodell schon im MVP]
- Altersstufen (konfigurierbar, Start 3): Jungtier, Halbwüchsig, Ausgewachsen. Geschlecht: weiblich, männlich.
- Freischaltung über ein Labor-Forschungs-Upgrade „Bestimmung“. Davor sind alle Funde unbestimmt (`null`) und zählen nur für Stufe 2.
- Ersatzteile sind an Alter und Geschlecht gebunden.
- Spätere Upgrades „Grabungsfokus“ erhöhen gezielt die Chance auf fehlende Varianten.
- **MVP-Anforderung:** `Piece`, `pieceKey`, `spareZone`-Schlüssel und Save-Format enthalten `age`/`sex` als nullable.

### 4.9 Pech-Schutz [MVP]
- Bei der Knochenwahl innerhalb einer Art bekommen Knochen, die im **aktuellen Durchlauf** noch fehlen, das Gewicht `balance.missingBoneWeight` (Start 3), vorhandene das Gewicht 1.
- Dazu kommen Ersatzteile und Rekonstruktion als zweiter Weg zu fehlenden bzw. besseren Knochen.

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

Die Simulation (Kapitel 6.7) mit den Startwerten aus Kapitel 6.4 trifft dieses Pacing: Neogen nach ca. 28 min, nach 45 min 11–14 Ruhm.

### 5.2 Meilensteine [MVP]
Datengetriebene Meilensteine (`milestones.json`) mit kleiner Belohnung (Geld oder FP) und Toast-Meldung, z. B.: erster Fund, erstes Skelett, erste Museumsreif-Qualität, 10 Teams, neue Schicht, erste Rekonstruktion, erstes Prestige. Sie dienen vor allem als Orientierung („Was mache ich als Nächstes?“).

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

### 5.4 Skilltree [MVP: 4 Äste à 4 Knoten + Capstone]
- **Prinzip:** Alles ist kaufbar. Tiefere Knoten eines Astes sind stärker, Faustregel `Wirkung ≈ Basis * (1 + 0.5 * Tiefe)`. Jeder gekaufte Knoten verteuert alle weiteren:
  `nodeCost = node.baseCost * balance.skillCostGrowth ^ ownedNodeCount` (Start 1.25).
- Voraussetzung: der vorige Knoten im selben Ast. `baseCost` nach Tiefe: 1, 2, 3, 5, 8 Ruhm.
- Kategorie aller Skill-Modifier: `skill` (innerhalb addiert).

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
- Für Stats mit Untergrenze bzw. Obergrenze (z. B. `lab_speed`, Kosten-Rabatte) gelten Clamps aus `balance.statLimits` (z. B. Rabatt höchstens −90 %).
- **Semantik einzelner Stats (festgelegt in AP-02):**
  - `lab_speed` ist ein Tempo-Faktor (Basis 1). Laufzeit = `labDuration / lab_speed`. „Laborzeit −10 %“ wird deshalb als `lab_speed` +0.11 hinterlegt, „−25 %“ als +0.33.
  - `layer_unlock_cost` und `hall_cost` sind Kostenfaktoren mit Basis 1 (Rabatte als negatives `mult`), Untergrenze 0.1.
  - `special_exhibit_mult` hat Basis 1 (keine Wirkung). Der Capstone *Sonderausstellung* addiert 4, ergibt also ×5.
  - `reconstruction_missing_zones` hat Basis 0. Fehlende Zonen werden mit `balance.reconstructionFillQuality` (0.5) ergänzt.
- **Stat-IDs (MVP):** `dig_rate`, `prep_rate`, `find_chance`, `damage_reduction`, `quality_base_min`, `museum_income`, `skeleton_bonus`, `sell_price`, `lab_speed`, `research_points`, `fame_gain`, `layer_unlock_cost`, `hall_cost`, `start_teams`, `start_lab_slots`, `guaranteed_fossil_chance`, `no_damage_chance`, `reconstruction_missing_zones`, `special_exhibit_mult`, `offline_efficiency`, `offline_cap_hours`.
- `ModifierEngine` sammelt alle aktiven Modifier (Upgrades, Skills, Buch, Meilensteine) und cached das Ergebnis, bis sich die Quellen ändern.

### 6.3 Kostenkurven und Mehrfachkauf
- Preis des n-ten Kaufs: `price(n) = baseCost * growth ^ n`
- Kosten für k Stück ab Bestand n: `cost(n, k) = baseCost * growth^n * (growth^k - 1) / (growth - 1)`
- Maximal kaufbar mit Geld M: `k = floor( log( M * (growth - 1) / (baseCost * growth^n) + 1 ) / log(growth) )`
- Kaufmodi in der UI: ×1, ×10, Max [MVP].

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

**Globale Werte (`balance.json`)**: `findChance 0.08`, `qualityBaseMin 0.30`, `qualityBaseMax 1.00`, `rockSellFactor 0.5`, `duplicateSellFactor 20`, `labDuration 30`, `labQueuePerSlot 3`, `reconstructionCap 0.89`, `skeletonMult 2.0`, `missingBoneWeight 3`, `tapDigAmount 0.25`, `tapPrepAmount 0.25`, `skillCostGrowth 1.25`, `offlineEfficiency 0.5`, `offlineCapHours 4`, `maxDetailedOfflineFinds 2000`.

In AP-02 ergänzt, damit keine Formelkonstanten im Code stehen: `slotsPerHall 8` (4.6), `damageRollMin 0.5` / `damageRollMax 2.0` (Zufallsfaktor des Schadens, 4.2), `qualityValueOffset 0.5` (Summand in `0.5 + q` bei Einkommen, Verkauf und Ruhm), `teamMilestoneMultiplier 2` (4.1), `reconstructionFillQuality 0.5` (Capstone *Restaurator*), `researchedBonus` (+1 % `museum_income`, Kategorie `book`, 4.7).

**Arten (MVP, Seltenheitsgewicht in Klammern)**

| Quartär | Neogen | Kreide |
|---|---|---|
| Höhlenbär (40) | Hipparion (40) | Psittacosaurus (40) |
| Riesenhirsch (25) | Gomphotherium (25) | Parasaurolophus (25) |
| Wollnashorn (15) | Chalicotherium (15) | Triceratops (15) |
| Wollhaarmammut (10) | Deinotherium (10) | Velociraptor (10) |
| Säbelzahnkatze (Smilodon) (5) | Terrorvogel (Titanis) (5) | Ankylosaurus (5) |
| Riesenfaultier (Megatherium) (2) | Thylacosmilus (2) | Tyrannosaurus (2) |

**Buch-Boni pro Art** (alle `op: mult`, Kategorie `book`; Basiswert, skaliert mit `bookTierFactor`):

| Quartär | Neogen | Kreide |
|---|---|---|
| Höhlenbär: `museum_income` 1 % | Hipparion: `dig_rate` 1 % | Psittacosaurus: `research_points` 1 % |
| Riesenhirsch: `prep_rate` 1 % | Gomphotherium: `museum_income` 1,5 % | Parasaurolophus: `prep_rate` 2 % |
| Wollnashorn: `sell_price` 1,5 % | Chalicotherium: `research_points` 2 % | Triceratops: `dig_rate` 2 % |
| Wollhaarmammut: `dig_rate` 2 % | Deinotherium: `prep_rate` 2 % | Velociraptor: `find_chance` 2 % |
| Smilodon: `find_chance` 2 % | Titanis: `sell_price` 2,5 % | Ankylosaurus: `lab_speed` 2,5 % |
| Megatherium: `museum_income` 3 % | Thylacosmilus: `find_chance` 3 % | Tyrannosaurus: `fame_gain` 3 % |

Faustregel: seltenere Arten geben etwas mehr. Code-IDs: `cave_bear`, `giant_deer`, `woolly_rhino`, `woolly_mammoth`, `smilodon`, `megatherium`, `hipparion`, `gomphotherium`, `chalicotherium`, `deinotherium`, `titanis`, `thylacosmilus`, `psittacosaurus`, `parasaurolophus`, `triceratops`, `velociraptor`, `ankylosaurus`, `tyrannosaurus`.

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
- Werte nie als `Int`/`Long` speichern. `NaN` bzw. `Infinity` werden in `advance()` erkannt und als Fehler geloggt (Debug: Exception).

### 6.7 Balancing-Werkzeug [MVP]
- `tools/sim/fossil_sim.py` (Referenz liegt bei, Version aus der Design-Phase) simuliert einen Durchlauf mit einer gierigen Kauf-KI und gibt den Verlauf (Geld, Einkommen, Funde, Skelette, Ruhm) pro Zeitpunkt aus.
- AP-28 stellt es so um, dass es die JSON-Dateien aus `core/src/main/resources/data/` liest. Zusätzlich gibt es einen Kotlin-Test `PacingTest`, der einen 60-Minuten-Durchlauf mit festem Seed simuliert und prüft, dass Schicht 2 zwischen 15 und 40 Minuten erreicht wird.

---

## 7. Offline-Fortschritt und Speichern

### 7.1 Offline [MVP: einfach]
- Beim App-Start bzw. bei der Rückkehr: `elapsed = now - lastSavedAt`. Negative Werte → 0.
- `effective = min(elapsed, offlineCapHours) * offlineEfficiency` (Start 4 h, 50 %).
- Aufruf von `advance(state, effective)` in 1-Sekunden-Schritten. Nach `maxDetailedOfflineFinds` einzeln gewürfelten Fossilien werden weitere Funde über den Erwartungswert direkt in Verkaufserlös bzw. FP umgerechnet.
- **Willkommen-zurück-Dialog:** Abwesenheitszeit, Geld, Anzahl Funde und die besten 3 neuen Funde (mit Qualitätsfarbe).
- Upgrades für Offline-Dauer und -Effizienz: [Später] (Ast Expedition).
- Keine Absicherung gegen das Verstellen der Uhr über die Obergrenze hinaus (Einzelspieler, keine Ranglisten).

### 7.2 Speichern [MVP]
- Eine JSON-Datei im App-internen Speicher (`files/save.json`), serialisiert mit kotlinx.serialization.
- Feld `saveVersion` (Start 1) und `SaveMigrator` mit Migrationsschritten `v(n) -> v(n+1)`.
- Autosave alle 30 s sowie in `onStop` bzw. wenn die App in den Hintergrund geht. Zusätzlich direkt nach dem Prestige.
- **Atomares Schreiben:** in `save.json.tmp` schreiben, dann umbenennen. Rotation von 3 Backups (`save.bak1..3`). Ist die Hauptdatei beim Laden defekt, wird das neueste lesbare Backup genommen und der Spieler informiert.
- **Export/Import** in den Einstellungen: Spielstand als Base64-Text (Zwischenablage). Der Import validiert und migriert, vorher kommt eine Bestätigung.
- Cloud-Save: [Später/optional].

---

## 8. UI, Navigation, Grafik

### 8.1 Grundlayout [MVP]
- Hochformat, einhändig, Material 3, dunkles Thema als Standard (Museums- bzw. Gesteinsoptik).
- **Obere Leiste:** Geld (+ $/s), FP, Ruhm, Zahnrad (Einstellungen).
- **Untere Navigation:** Grabung · Labor · Museum · Sammelbuch · Ruhm.
- **Badges** an Tabs: Forschung fertig, neuer Bucheintrag, bezahlbares Upgrade, Prestige verfügbar.

### 8.2 Schrittweise Einführung der Tabs [MVP]

| Tab | Sichtbar ab |
|---|---|
| Grabung | Start |
| Museum | erstes Fossil |
| Sammelbuch | 5 verschiedene Funde |
| Labor | erstes Duplikat |
| Ruhm | Prestige-Vorschau ≥ 1 oder nach dem ersten Prestige |

Beim ersten Erscheinen eines Tabs gibt es einen kurzen Hinweistext (ein Satz).

### 8.3 Screens
- **Grabung:** Vertikaler Querschnitt mit Schichten als Farbbänder (oben jung, unten alt). Pro Schicht: Blöcke im Lager, Blöcke/s, Teams kaufen (×1/×10/Max). Gesperrte Schichten zeigen Kosten und Spar-Fortschritt. Darunter der Bereich Präparation: großer Freilege-Button (Tippen), Präparatoren kaufen, Verarbeitungsrate. **Fund-Popup:** Ein neuer oder besserer Fund erscheint kurz als Karte (Art, Knochen, Qualitätsstufe in Farbe). Bei vielen Funden pro Sekunde werden die Popups gebündelt (höchstens 1 pro Sekunde, Priorität: bessere Stufe).
- **Labor:** Forschungsplätze mit Fortschrittsbalken, Warteschlange, Laborplatz kaufen, Werkzeugstufen (FP), Liste rekonstruierbarer Knochen mit Button „Rekonstruieren“.
- **Museum:** Einnahmen/s, Hallen als Raster der ausgestellten Stücke, Halle kaufen, Hinweis auf Skelett-Boni. Capstone *Sonderausstellung*: Auswahl eines Skeletts.
- **Sammelbuch:** Reiter pro Schicht, Raster der Arten (Silhouette; nicht gefunden = dunkel mit „?“), Completion-Stufe als Symbol. **Art-Detail:** Skelett-Grafik mit Knochen eingefärbt nach Stufe (fehlend = grau gestrichelt), Liste der Knochen mit Qualität in % und Zonen-Balken, Buch-Bonus der Art.
- **Ruhm:** Ruhm verfügbar/gesamt, Skilltree als 4 vertikale Äste, Prestige-Button mit Vorschau, Dialog mit Reset-Übersicht, Respec-Option im Prestige-Dialog.
- **Einstellungen:** Sprache (System/DE/EN), Zahlenformat, Export/Import, Spielstand löschen (doppelte Bestätigung), Version.

### 8.4 Qualitätsfarben
Fragment Grau `#8A8A8A` · Beschädigt Braun `#A0703C` · Gut Grün `#5FA35F` · Exzellent Blau `#4A90D9` · Museumsreif Gold `#E2B93B`.

### 8.5 Grafikstil
- Flache, **schematische Vektorgrafik** im Stil von Museumstafeln: knochenfarbene Formen (`#E8DFC8`) auf dunklem Grund, begrenzte Palette. Bewusst vereinfacht, nicht anatomisch exakt.
- Quelle: selbst erzeugte SVGs in `art/skeletons/<speciesId>.svg`, keine fremden Vorlagen, keine Anlehnung an bekannte Film- oder Markengrafik.
- **Konvention:** Jeder Knochen ist eine Gruppe `id="<boneId>"`, jede Zone ein Pfad `id="<boneId>__<zoneId>"`. Ein Build-Skript (`tools/svg2json.py`) wandelt die SVGs in `skeleton_<speciesId>.json` (Pfaddaten plus IDs) um. Die App zeichnet sie mit Compose `PathParser` und färbt sie zur Laufzeit ein.
- Schichthintergründe: Farbbänder mit einfacher Textur, ebenfalls per SVG oder Compose-Zeichnung.
- **MVP:** Platzhalter (ein generisches Vierbeiner- bzw. Zweibeiner-Skelett für alle Arten) ist zulässig. Die echten 18 Skelette entstehen in AP-27.

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
  sim/        fossil_sim.py
  svg2json.py
  validate_data.py (optional, CI)
/art/skeletons/*.svg
```

### 10.3 Spielschleife
- `GameViewModel` startet eine Coroutine mit 10 Ticks/s: `engine.advance(state, dt = 0.1, rng)`. Danach wird ein unveränderlicher UI-State per `StateFlow` veröffentlicht.
- `advance()` ist der **einzige** Ort, an dem Zeit Spielzustand verändert. Spieleraktionen (kaufen, tippen, rekonstruieren, Prestige) sind eigene Engine-Funktionen, die den State zurückgeben.
- `advance()` gibt eine Liste von `GameEvent` zurück (Fund, Forschung fertig, Meilenstein …), die die UI für Popups und Badges nutzt.

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

**`milestones.json`**: `{ "id", "condition": { "type": "pieces_found", "value": 1 }, "reward": { "money": 50 } }`. Bedingungstypen (MVP): `pieces_found`, `skeletons_completed`, `pristine_found`, `teams_total`, `layers_unlocked`, `reconstructions`, `prestige_count`. Belohnung: `money` und/oder `researchPoints`.

**`balance.json`**: alle globalen Konstanten aus Kapitel 6.4 sowie `qualityTiers` (`[{ "id": "GOOD", "min": 0.5, "color": "#5FA35F" }, …]`, aufsteigend, Untergrenze inklusive), `bookTierFactor` (gleiche Reihenfolge wie `qualityTiers`), `teamMilestones`, `statLimits` (`{ "hall_cost": { "min": 0.1 } }`).

**`locale/de.json`, `locale/en.json`**: `{ "species.parasaurolophus.name": "Parasaurolophus", "bone.dorsal.name": "Rückenwirbel", "zone.dorsal.centrum": "Wirbelkörper" }`. Weitere Schlüssel: `layer.<id>.name`, `skill.<id>.name`/`.desc`, `skill_branch.<branch>.name`, `milestone.<id>.name`/`.desc`, `quality.<tier>.name` (Aufbau in `LocaleKeys`).

**Schemas:** `core/src/main/resources/schema/*.schema.json`. Die Validierung läuft über `DataValidationTest` (Referenzen, Wertebereiche, Übersetzungen) und `tools/validate_data.py` (Struktur gegen die Schemas, läuft in der CI).

**Spielstand (Auszug)**
```json
{
  "saveVersion": 1,
  "lastSavedAt": 1762351200000,
  "meta": {
    "fame": 4, "fameTotal": 19, "prestigeCount": 2,
    "skills": ["dig_1","dig_2"],
    "book": { "parasaurolophus:dorsal:_:_": { "quality": 0.83, "reconstructed": false } },
    "researchedSpecies": ["cave_bear"],
    "milestonesDone": ["first_find"],
    "stats": { "piecesFound": 1520 }
  },
  "run": {
    "money": 1234.5, "researchPoints": 12, "unlockedLayers": 2,
    "teams": { "quaternary": 41, "neogene": 5 }, "preparators": 26, "halls": 4, "labSlots": 2,
    "toolLevel": 3, "blocks": { "quaternary": 2.4, "neogene": 0.7 },
    "pieces": { "cave_bear:skull:_:_": { "zones": [0.9,0.7,0.8,0.6] } },
    "spareZones": { "cave_bear:skull:_:_:snout": 0.92 },
    "labQueue": [], "labJobs": [ { "piece": "...", "remaining": 12.0 } ],
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
- Sprache: Standard ist die Systemsprache, umstellbar in den Einstellungen.
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

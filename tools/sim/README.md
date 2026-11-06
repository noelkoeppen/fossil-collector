# Balancing-Simulation (`fossil_sim.py`)

Simuliert den ersten Durchlauf mit einer gierigen Kauf-KI und gibt den Verlauf (Geld, Einkommen,
Funde, Skelette, Ruhm-Vorschau, Bestände) aus. Grundlage für Balancing-Änderungen
(GAME_DESIGN.md Kapitel 5.1, 6.7). Nur Python-Standardbibliothek (ab Python 3.9).

- **Alle Spielwerte** kommen aus `core/src/main/resources/data/*.json` (`layers`, `species`,
  `bones`, `generators`, `upgrades`, `balance`, `milestones`, `skilltree`). Im Skript stehen nur
  Formeln und das Verhalten des simulierten Spielers (`BotConfig`).
- **Gleiche Logik wie die Kotlin-Engine:** Grabung mit Team-Meilensteinen, Präparation in ganzen
  Blöcken (tiefste Schicht zuerst), Pech-Schutz, Funde einordnen (Buch, Run, Duplikate in Labor
  oder Verkauf), Museum mit Plätzen und Skelett-Faktor, Labor (FIFO, Ersatzteile, FP),
  Rekonstruktion mit Cap, Werkzeugstufen, Buch-Boni, Meilensteine, Modifier-System mit Clamps.
- **Gleiche Kauf-KI** wie `GreedyBot` im Kotlin-`PacingTest` (Tippen in den ersten 10 min,
  billigste sinnvolle Option kaufen, sonst sparen; Werkzeuge mit allen FP; Rekonstruktion nach
  jedem Laborjob).
- **Gleicher Zufall:** `KotlinRandom` bildet `kotlin.random.Random(seed)` (XorWow) nach. Für
  denselben Seed ist der Verlauf identisch mit dem `PacingTest` (siehe Vergleich unten).

## Aufruf

```
python3 tools/sim/fossil_sim.py                 # Seed 1, 60 min, Tabelle alle 5 min
python3 tools/sim/fossil_sim.py 2               # Seed 2 (Kurzform)
python3 tools/sim/fossil_sim.py --seeds 1-5 -q  # nur Zusammenfassung für Seeds 1 bis 5
python3 tools/sim/fossil_sim.py --seeds 1,2,3 --minutes 90 --interval 10 --csv verlauf.csv
```

| Option | Bedeutung |
|---|---|
| `seed` / `--seeds 1,2,3` bzw. `1-5` | Seed(s), Standard 1 |
| `--minutes` | Dauer in Minuten (Standard 60) |
| `--interval` | Abstand der Tabellenzeilen in Minuten (Standard 5) |
| `--data PFAD` | anderer Datenordner (Standard `core/src/main/resources/data`) |
| `--csv DATEI` | Verlauf zusätzlich als CSV (`-` = Standardausgabe, Texte dann auf stderr) |
| `--set PFAD=WERT` | Datenwert nur für diesen Lauf ändern, mehrfach möglich (siehe unten) |
| `--no-book-bonus` | Buch-Boni und `researchedBonus` aus |
| `--no-milestones` | Meilensteine (Belohnungen) aus |
| `--no-bad-luck-protection` | Pech-Schutz aus (fehlende Knochen mit Gewicht 1) |
| `--no-reconstruction` | KI rekonstruiert nicht |
| `--skills dig_1,dig_2` | Skills gelten als gekauft (z. B. für spätere Durchläufe; Start-Skills wirken ab Beginn) |
| `--active-minutes` | so lange tippt der Spieler (Standard 10) |
| `--tick` | Schrittweite in Sekunden (Standard 1, wie der `PacingTest`) |
| `-v` / `-q` | Ereignisse ausgeben / nur Zusammenfassung |

**Werte ausprobieren, ohne die JSON-Dateien zu ändern:** `--set` liest den Wert als JSON. In
Listen-Dateien (`layers`, `species`, `generators`, …) wählt der zweite Teil das Objekt mit dieser
`id`.

```
python3 tools/sim/fossil_sim.py --seeds 1-3 -q --set layers.neogene.unlockCost=8000 --set layers.neogene.fameWeight=2
python3 tools/sim/fossil_sim.py --seeds 1-3 -q --set "balance.bookTierFactor=[1,1.5,2,3,4]"
python3 tools/sim/fossil_sim.py --seeds 1-3 -q --set generators.preparator.baseCost=40
```

Ausgabe: je Seed eine Tabelle (Minute, Geld, Museumseinkommen/s, Geldzufluss/s seit der letzten
Zeile, Funde im Run, Skelette, Ruhm-Vorschau, Teams je Schicht, Präparatoren, Hallen,
Laborplätze, Werkzeugstufe, Duplikate) und am Ende eine Zusammenfassung (Freischaltzeit je
Schicht, erstes Skelett, Ruhm am Ende). Die CSV-Spalten heißen `seed, minute, money,
museum_income, earned_per_second, pieces, skeletons, fame, teams, preparators, halls, lab_slots,
tool_level, research_points, duplicates, unlocked_layers` (`teams` als `57/9`).

Tests: `python3 tools/sim/test_fossil_sim.py` (läuft in der CI, zusammen mit einem Lauf für die
Seeds 1 bis 3).

## Vergleich mit dem Kotlin-`PacingTest` (Daten Stand AP-28)

Kotlin: `./gradlew :core:test --tests '*PacingTest*' -i`, Python:
`python3 tools/sim/fossil_sim.py --seeds 1-3`.

| Seed | Neogen Kotlin | Neogen Python | Ruhm 45 min Kotlin | Ruhm 45 min Python | Ruhm 60 min Kotlin | Ruhm 60 min Python | Abweichung |
|---|---|---|---|---|---|---|---|
| 1 | 21,8 min | 21,8 min | 22 | 22 | 31 | 31 | 0 % |
| 2 | 22,9 min | 22,9 min | 22 | 22 | 31 | 31 | 0 % |
| 3 | 22,8 min | 22,8 min | 25 | 25 | 31 | 31 | 0 % |

Alle Zeilen der Verlaufstabelle (Geld, Museum, Zufluss, Funde, Bestände, Duplikate) stimmen
ebenfalls überein, ebenso für die Seeds 4 und 5 (Neogen 22,5 bzw. 22,3 min, Ruhm 45 min 26,
60 min 31). Die Übereinstimmung ist exakt, weil Zufallsfolge, Würfelreihenfolge und
Rechenreihenfolge der Engine nachgebildet sind (z. B. Summen ohne Kompensation, weil Pythons
`sum` ab 3.12 kompensiert summiert).

Grenzen: Die Simulation bildet nur den ersten Durchlauf mit der KI nach (kein Prestige, keine
Sonderausstellung, kein Offline-Fortschritt). Ändert sich die Engine, muss `fossil_sim.py`
nachgezogen werden; der Vergleich oben zeigt dann, ob beide noch übereinstimmen.

**Ablation** (je Schalter, Seeds 1–5, Neogen in min / Ruhm nach 60 min):

| Lauf | Neogen | Ruhm 60 min |
|---|---|---|
| Standard | 21,8–22,9 | 31 |
| `--no-book-bonus` | 23,3–24,3 | 26–30 |
| `--no-milestones` | 23,1–24,0 | 27–31 |
| `--no-bad-luck-protection` | 22,6–24,4 | 27–31 |
| `--no-reconstruction` | 22,2–23,1 | 27–30 |
| alle vier Referenz-Annahmen der Design-Phase (`--no-book-bonus --no-milestones --no-bad-luck-protection --set balance.reconstructionCap=0.85`, Seeds 1–3) | 25,4–27,3 | Ruhm 45 min 13–15 |

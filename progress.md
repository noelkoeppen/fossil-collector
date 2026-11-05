# Fortschritt – Fossil Collector

> Aktueller Stand der Umsetzung. Wird am Ende jedes Arbeitspakets (AP) im selben PR aktualisiert.
> Spezifikation: [`docs/GAME_DESIGN.md`](docs/GAME_DESIGN.md) · Aufgabenliste: Kapitel 14
> Stand: 2026-11-05

## Kurzüberblick

| | |
|---|---|
| Erledigt | AP-01 (gemergt), AP-02 (PR #2 offen, CI grün) |
| Als Nächstes | AP-03 *GameNumber und NumberFormatter*, danach AP-04 *Modifier-System* |
| Erster spielbarer Stand | nach AP-01 → 09, 16, 17, 18 (Kapitel 14) |

## Status je Arbeitspaket

Legende: erledigt = gemergt · PR = PR offen · offen = noch nicht begonnen

### Phase 0 – Fundament
| AP | Titel | Status | PR | Notiz |
|---|---|---|---|---|
| AP-01 | Projektgerüst | erledigt | #1 | Module `core`/`app`, Version Catalog, JUnit 5, CI |
| AP-02 | Datenmodelle und JSON-Daten | PR | #2 | Alle MVP-Daten, Schemas, `DataValidationTest`, `tools/validate_data.py` |
| AP-03 | GameNumber und NumberFormatter | offen | | Abhängigkeit AP-01 erfüllt, kann sofort starten |
| AP-04 | Modifier-System | offen | | wartet auf Merge von AP-02; `StatId`/`ModifierOp`/`ModifierCategory` existieren schon |

### Phase 1 – Spiellogik (`core`)
| AP | Titel | Status |
|---|---|---|
| AP-05 | GameState und Spielstand | offen |
| AP-06 | Kaufen und Kostenkurven | offen |
| AP-07 | Grabung und advance()-Grundgerüst | offen |
| AP-08 | Präparation und Qualität | offen |
| AP-09 | Funde einordnen, Museum, Verkauf | offen |
| AP-10 | Labor und Rekonstruktion | offen |
| AP-11 | Sammelbuch, Completion, Buch-Boni, Meilensteine | offen |
| AP-12 | Prestige und Ruhm | offen |
| AP-13 | Skilltree | offen |
| AP-14 | Offline-Fortschritt | offen |
| AP-15 | PacingTest | offen |

### Phase 2 – App und UI
| AP | Titel | Status |
|---|---|---|
| AP-16 | App-Shell | offen |
| AP-17 | Screen Grabung | offen |
| AP-18 | Screen Museum | offen |
| AP-19 | Screen Labor | offen |
| AP-20 | Screen Sammelbuch | offen |
| AP-21 | Skelett-Renderer | offen |
| AP-22 | Screen Ruhm | offen |
| AP-23 | Willkommen-zurück-Dialog | offen |
| AP-24 | Einstellungen | offen |
| AP-25 | Feinschliff Feedback | offen |

### Phase 3 – Inhalt und Balancing
| AP | Titel | Status |
|---|---|---|
| AP-26 | Schichthintergründe und Icons | offen |
| AP-27a/b/c | Skelett-SVGs Quartär / Neogen / Kreide | offen |
| AP-28 | Balancing-Werkzeug anbinden | offen |
| AP-29 | Balancing-Durchgang und Playtest-Fixes | offen |

## Was im Code schon existiert

- `core/.../data/`: Definitions-Klassen, `GameData` (Lookups per ID), `DataRepository` (lädt `data/*.json`, strikt), `DataValidator`, `LocaleKeys` (Aufbau der Text-Schlüssel)
- `core/.../modifier/StatId.kt`: `StatId`, `ModifierOp`, `ModifierCategory` (Berechnung folgt in AP-04)
- `core/src/main/resources/data/`: `layers`, `species` (18), `bones` (8 × 4 Zonen), `generators`, `upgrades`, `skilltree` (20 Knoten), `milestones` (7), `balance`, `locale/de.json` + `en.json`
- `core/src/main/resources/schema/`: JSON-Schemas zu allen Datendateien
- `tools/validate_data.py`: Schema-Prüfung, läuft in der CI
- `app`: leerer Startscreen aus AP-01

## Getroffene Entscheidungen (Details in GAME_DESIGN.md)

- **AP-02:** Buch-Boni je Art festgelegt (6.4). Neue `balance`-Konstanten: `slotsPerHall`, `damageRollMin/Max`, `qualityValueOffset`, `teamMilestoneMultiplier`, `reconstructionFillQuality`, `researchedBonus`.
- **AP-02:** `lab_speed` ist ein Tempo-Faktor (Laufzeit = `labDuration / lab_speed`). Kostenstats haben Basis 1 und Untergrenze 0.1. `special_exhibit_mult` hat Basis 1, der Capstone addiert 4 (= ×5).
- **AP-02:** Definitionswerte sind `Double`/`Int` (statische Konfiguration). `GameNumber` gilt für den Spielzustand.

## Offene Punkte

- Endgültiger Name und Paketname (aktuell `de.noelkoeppen.fossilcollector`)
- Tipp-Werte (`tapDigAmount`, `tapPrepAmount`) nach dem ersten Playtest
- Tutorial nötig oder reichen Hinweistexte plus Tab-Freischaltung?
- `TextProvider` (Kapitel 12) noch nicht gebaut; kommt mit der ersten Stelle, die Inhaltstexte anzeigt
- `species.art` verweist auf `skeleton_<id>.json`, die erst in AP-21/27 entstehen

## Hinweise für Agenten-Sessions

- Maven Central antwortet in der Cloud-Umgebung zeitweise mit HTTP 429. Gradle einfach nach kurzer Pause erneut starten, bis alle Abhängigkeiten im Cache sind.
- Ohne Android SDK läuft lokal nur `./gradlew :core:test`. Den APK-Build prüft die CI.
- `gh pr create` scheitert dort (GraphQL gesperrt). PRs über die REST-API anlegen: `gh api repos/noelkoeppen/fossil-collector/pulls -f title=... -f head=... -f base=main -F body=@datei.md`.

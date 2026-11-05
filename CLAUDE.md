# CLAUDE.md – Fossil Collector

Android-Incremental-Spiel (Kotlin + Jetpack Compose). Die verbindliche Spezifikation ist
[`docs/GAME_DESIGN.md`](docs/GAME_DESIGN.md).

## Arbeitsablauf
1. Vor jeder Aufgabe `docs/GAME_DESIGN.md` lesen, mindestens Kapitel 0 (Regeln für Agenten)
   und das betroffene Arbeitspaket (AP) in Kapitel 14 samt den Kapiteln, auf die es verweist.
2. Genau **ein AP pro Session**. Branch `ap-XX-kurzname`, ein PR pro AP. Titel `AP-XX: <Titel>`.
3. Nur umsetzen, was im AP steht. Features mit `[Später]` nicht bauen, aber auch nicht verbauen.
4. Abhängigkeiten des AP müssen erledigt sein. Wenn nicht: abbrechen und das melden.
5. Vor dem PR: Build, alle Tests und die Datenvalidierung lokal ausführen, alles grün.
6. Weicht die Umsetzung vom Design ab, `docs/GAME_DESIGN.md` im selben PR anpassen und die
   Abweichung im PR begründen.
7. Im PR-Text: erledigte Akzeptanzkriterien als Checkliste und offene Punkte.
8. `progress.md` zu Beginn lesen und im selben PR aktualisieren (Status des AP, neue
   Entscheidungen, offene Punkte).

## Harte Regeln (Kurzfassung von Kapitel 0)
- Keine hartcodierten Spielwerte. Alle Balancing-Zahlen kommen aus `core/src/main/resources/data/*.json`.
- Modul `core` ist reines Kotlin/JVM ohne Android-Imports. Spiellogik gehört ausschließlich dorthin.
- Zeit verändert den Spielzustand nur über `advance(state, dt, rng)`. Offline nutzt dieselbe Funktion.
- Spielwerte als `GameNumber`, nie `Int`/`Long`.
- Zufall nur über das injizierte `GameRandom`. Tests mit festem Seed.
- Alle Boni über das Modifier-System, keine Sonderfälle im Code.
- `age`/`sex` an Funden bleiben als nullable-Felder erhalten.
- Keine Strings im Code: UI-Texte in `strings.xml` (de + en), Inhaltstexte in `data/locale/*.json`.
- Neue Logik in `core` braucht Unit-Tests.
- Keine Emojis im Code.

## Nützliches
- Balancing-Simulation: `python3 tools/sim/fossil_sim.py [seed]` (Referenzstand aus der
  Design-Phase, wird in AP-28 an die JSON-Daten angebunden).

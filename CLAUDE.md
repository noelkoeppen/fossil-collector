# CLAUDE.md – Fossil Collector

Aktives Grabungsspiel für Android (Kotlin + Jetpack Compose): Fossilien von Hand ausgraben, freilegen,
bergen und am 3D-Skelett zusammensetzen. Die verbindliche Spezifikation ist
[`docs/GAME_DESIGN.md`](docs/GAME_DESIGN.md) (Version 2.0).

Der frühere Incremental-Stand liegt auf dem Branch `archive/incremental-v1.1`. Die alten
`ap-XX`-Branches und Arbeitspakete gelten nicht mehr.

## Arbeitsablauf
1. Vor jeder Aufgabe `docs/GAME_DESIGN.md` lesen, mindestens Kapitel 0 (Regeln für Agenten)
   und das betroffene Arbeitspaket (AP) in Kapitel 12 samt den Kapiteln, auf die es verweist.
2. Genau **ein AP pro Session**. Branch `ap2-XX-kurzname`, ein PR pro AP. Titel `AP2-XX: <Titel>`.
3. Nur umsetzen, was im AP steht. Features mit `[Später]` nicht bauen, aber auch nicht verbauen.
4. Abhängigkeiten des AP müssen erledigt sein. Wenn nicht: abbrechen und das melden.
5. Vor dem PR: Build, alle Tests und die Datenvalidierung lokal ausführen, alles grün.
6. Weicht die Umsetzung vom Design ab, `docs/GAME_DESIGN.md` im selben PR anpassen und die
   Abweichung im PR begründen.
7. Im PR-Text: erledigte Akzeptanzkriterien als Checkliste und offene Punkte.

## Harte Regeln (Kurzfassung von Kapitel 0)
- Keine hartcodierten Spielwerte. Alle Balancing-Zahlen kommen aus `core/src/main/resources/data/*.json`.
- Modul `core` ist reines Kotlin/JVM ohne Android-Imports. Spiellogik gehört ausschließlich dorthin.
  Die UI liefert nur Eingaben und zeichnet den Zustand.
- Keine Simulation über Zeit: kein Tick, kein Offline-Fortschritt, kein `advance()`. Der Spielzustand
  ändert sich nur durch Spieleraktionen.
- Zufall nur über das injizierte `GameRandom`. Tests mit festem Seed.
- Alle Boni über das Modifier-System, keine Sonderfälle im Code.
- Eine neue Art darf keinen Code erfordern, nur Modellordner (`art/models3d/<speciesId>/`) und Daten.
- Keine Strings im Code: UI-Texte in `strings.xml` (de + en), Inhaltstexte in `data/locale/*.json`.
- Neue Logik in `core` braucht Unit-Tests.
- Keine Emojis im Code.

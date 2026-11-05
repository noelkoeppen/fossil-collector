package de.noelkoeppen.fossilcollector.core.model

import de.noelkoeppen.fossilcollector.core.number.GameNumber
import kotlinx.serialization.Serializable

/**
 * Vollstaendiger Spielstand (Kapitel 7.2 und 11, "Spielstand (Auszug)").
 *
 * Unveraenderlich: Engine-Funktionen geben einen neuen Zustand zurueck (Kapitel 10.3).
 *
 * @property saveVersion Version des Speicherformats, siehe [CURRENT_SAVE_VERSION]
 * @property lastSavedAt Zeitpunkt des letzten Speicherns in Millisekunden seit 1970 (UTC);
 *   Zeitstempel, kein Spielwert, deshalb `Long`
 */
@Serializable
data class GameState(
    val saveVersion: Int = CURRENT_SAVE_VERSION,
    val lastSavedAt: Long,
    val meta: MetaState,
    val run: RunState,
    val settings: Settings = Settings(),
) {
    companion object {
        /** Aktuelle Version des Speicherformats. Bei jeder Formataenderung erhoehen und Migration ergaenzen. */
        const val CURRENT_SAVE_VERSION: Int = 1

        /**
         * Neuer Spielstand (Startzustand). Legt nur die Struktur an; alle Startwerte, die das
         * Balancing betreffen, kommen ueber [start] aus den Daten (Kapitel 0, Regel 3).
         *
         * @param firstLayerId ID der ersten Schicht (laut Kapitel 4.1 von Beginn an offen)
         * @param start Startwerte des Durchlaufs
         * @param now aktueller Zeitpunkt in Millisekunden, wird als [lastSavedAt] gesetzt
         */
        fun newGame(firstLayerId: String, start: RunStartValues, now: Long): GameState =
            GameState(
                saveVersion = CURRENT_SAVE_VERSION,
                lastSavedAt = now,
                meta = MetaState(),
                run = RunState.newRun(firstLayerId, start),
                settings = Settings(),
            )
    }
}

/**
 * Permanenter Teil des Spielstands, bleibt beim Prestige erhalten (Kapitel 5.3).
 *
 * @property fame unverteilter Ruhm
 * @property fameTotal insgesamt verdienter Ruhm
 * @property prestigeCount Anzahl der Prestiges
 * @property skills IDs der gekauften Skills (`skilltree.json`)
 * @property book Sammelbuch: bester Fund je [PieceKey]
 * @property researchedSpecies Arten mit Completion-Stufe 1 (Kapitel 4.7)
 * @property milestonesDone IDs erreichter Meilensteine
 * @property stats Statistiken
 */
@Serializable
data class MetaState(
    val fame: GameNumber = GameNumber.ZERO,
    val fameTotal: GameNumber = GameNumber.ZERO,
    val prestigeCount: GameNumber = GameNumber.ZERO,
    val skills: Set<String> = emptySet(),
    val book: Map<PieceKey, BookEntry> = emptyMap(),
    val researchedSpecies: Set<String> = emptySet(),
    val milestonesDone: Set<String> = emptySet(),
    val stats: Stats = Stats(),
)

/** Statistiken (bleiben beim Prestige erhalten). Neue Felder immer mit Standardwert ergaenzen. */
@Serializable
data class Stats(
    val piecesFound: GameNumber = GameNumber.ZERO,
)

/**
 * Zustand des aktuellen Durchlaufs, wird beim Prestige zurueckgesetzt (Kapitel 5.3).
 *
 * Alle Mengen und Zaehler sind [GameNumber] (Kapitel 0, Regel 6).
 *
 * @property money Geld
 * @property researchPoints Forschungspunkte (FP)
 * @property unlockedLayers Anzahl freigeschalteter Schichten (in Reihenfolge `layers.json: order`)
 * @property teams Grabungsteams je Schicht-ID
 * @property preparators Anzahl Praeparatoren
 * @property halls Anzahl Museumshallen
 * @property labSlots Anzahl Laborplaetze
 * @property toolLevel Werkzeugstufe
 * @property blocks Rohbloecke im Lager je Schicht-ID
 * @property pieces bester Run-Fund je [PieceKey] (im Museum ausgestellt)
 * @property spareZones bestes Ersatzteil je [SpareZoneKey], Wert = Zonen-Qualitaet 0..1
 * @property labQueue Duplikate, die auf einen Laborplatz warten
 * @property labJobs laufende Forschungen
 * @property specialExhibit Art-ID fuer die Capstone-Sonderausstellung oder `null`
 * @property prepProgress angefangener Praeparationsfortschritt in Bloecken, immer in `[0, 1)`
 *   (Bruchteil-Verarbeitung, Kapitel 4.2; Standardwert 0, damit aeltere Spielstaende ohne
 *   Migration laden)
 */
@Serializable
data class RunState(
    val money: GameNumber,
    val researchPoints: GameNumber,
    val unlockedLayers: GameNumber,
    val teams: Map<String, GameNumber>,
    val preparators: GameNumber,
    val halls: GameNumber,
    val labSlots: GameNumber,
    val toolLevel: GameNumber,
    val blocks: Map<String, GameNumber>,
    val pieces: Map<PieceKey, Piece> = emptyMap(),
    val spareZones: Map<SpareZoneKey, Double> = emptyMap(),
    val labQueue: List<QueuedPiece> = emptyList(),
    val labJobs: List<LabJob> = emptyList(),
    val specialExhibit: String? = null,
    val prepProgress: GameNumber = GameNumber.ZERO,
) {
    companion object {
        /**
         * Neuer Durchlauf (Spielbeginn und spaeter nach dem Prestige). Legt nur die Struktur an:
         * die erste Schicht ist offen, Lager und Bestaende sind leer. Balancing-Startwerte
         * kommen ueber [start].
         */
        fun newRun(firstLayerId: String, start: RunStartValues): RunState =
            RunState(
                money = start.money,
                researchPoints = start.researchPoints,
                unlockedLayers = GameNumber.ONE,
                teams = mapOf(firstLayerId to start.teamsInFirstLayer),
                preparators = start.preparators,
                halls = start.halls,
                labSlots = start.labSlots,
                toolLevel = start.toolLevel,
                blocks = mapOf(firstLayerId to GameNumber.ZERO),
            )
    }
}

/**
 * Startwerte eines Durchlaufs. Sie betreffen das Balancing und werden deshalb vom Aufrufer
 * aus den JSON-Daten bzw. Modifiern (z. B. `start_teams`, `start_lab_slots`, Gratis-Hallen
 * aus `generators.json: free`) ermittelt. Bewusst ohne Standardwerte.
 */
data class RunStartValues(
    val money: GameNumber,
    val researchPoints: GameNumber,
    val teamsInFirstLayer: GameNumber,
    val preparators: GameNumber,
    val halls: GameNumber,
    val labSlots: GameNumber,
    val toolLevel: GameNumber,
)

package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.random.GameRandom

/**
 * Zentrale Simulation (GAME_DESIGN.md, Kapitel 0 Regel 5 und Kapitel 10.3).
 *
 * [advance] ist der **einzige** Ort, an dem Zeit den Spielzustand veraendert. Online-Tick
 * (`dt = 0.1`) und Offline-Fortschritt (AP-14) rufen dieselbe Funktion auf. Spieleraktionen
 * (z. B. [tapDig]) sind eigene Funktionen, die einen neuen Zustand zurueckgeben.
 *
 * Ablauf von [advance]:
 * 1. `dt` pruefen: nicht endlich -> [NumericErrorHandler]; `dt <= 0` -> keine Aenderung.
 * 2. Modifier-Quellen mit dem Eingabe-Zustand abgleichen ([ModifierSources.sync]).
 * 3. Alle [systems] nacheinander ausfuehren (Teilschritte, siehe [defaultSystems]).
 * 4. Ergebnis auf `NaN`/`Infinity` pruefen ([StateValidator]); bei Fund -> [NumericErrorHandler]
 *    und Tick verwerfen.
 *
 * **Erweiterungsstelle:** Neue Spielsysteme (Praeparation AP-08, Funde/Museum AP-09, Labor AP-10,
 * Meilensteine AP-11 ...) werden als [AdvanceSystem] implementiert und in [defaultSystems] an der
 * passenden Stelle eingefuegt. An [advance] selbst muss dafuer nichts geaendert werden.
 *
 * Grosse `dt` sind erlaubt und werden in einem Schritt gerechnet. Die Aufteilung der
 * Offline-Zeit in 1-Sekunden-Schritte (Kapitel 7.1) ist Sache des Aufrufers (AP-14).
 *
 * Die Engine ist wie die [ModifierEngine] nicht thread-safe (Nutzung nur aus der Spielschleife).
 *
 * @param data Spieldefinitionen
 * @param modifiers Modifier-Engine; wird in [advance] mit dem Zustand synchronisiert
 * @param systems Teilschritte von [advance] in Ausfuehrungsreihenfolge
 * @param errorHandler Reaktion auf nicht endliche Werte, Standard wirft
 * @param modifierSources Abgleich zwischen Spielstand und [modifiers]
 */
class GameEngine(
    private val data: GameData,
    private val modifiers: ModifierEngine,
    private val systems: List<AdvanceSystem> = defaultSystems(data, modifiers),
    private val errorHandler: NumericErrorHandler = NumericErrorHandler.THROW,
    private val modifierSources: ModifierSources = ModifierSources.DEFAULT,
) {
    /** Grabung fuer Abfragen der UI und Spieleraktionen (eigene Instanz, zustandslos). */
    val dig: DigSystem = DigSystem(data, modifiers)

    /**
     * Praeparation fuer Abfragen der UI und das Tippen. Enthaelt [systems] ein [PrepSystem], wird
     * genau diese Instanz genutzt (damit Tippen denselben [FindHandler] verwendet wie [advance]),
     * sonst eine eigene ohne Handler.
     */
    val prep: PrepSystem = systems.filterIsInstance<PrepSystem>().firstOrNull() ?: PrepSystem(data, modifiers)

    /**
     * Museum fuer Abfragen der UI (Einnahmen, ausgestellte Stuecke). Enthaelt [systems] ein
     * [MuseumSystem], wird genau diese Instanz genutzt (gemeinsamer Cache), sonst eine eigene.
     */
    val museum: MuseumSystem = systems.filterIsInstance<MuseumSystem>().firstOrNull() ?: MuseumSystem(data, modifiers)

    /**
     * Labor fuer Abfragen der UI (Dauer, Fortschritt, FP). Enthaelt [systems] ein [LabSystem], wird
     * genau diese Instanz genutzt, sonst eine eigene.
     */
    val lab: LabSystem = systems.filterIsInstance<LabSystem>().firstOrNull() ?: LabSystem(data, modifiers)

    /** Rekonstruktion aus Ersatzteilen (Spieleraktion und Abfragen, Kapitel 4.5). */
    val reconstruction: Reconstruction = Reconstruction(data, modifiers)

    /**
     * Schreitet [state] um [dt] Sekunden fort. Veraendert [state] nicht.
     *
     * @return neuer Zustand und Ereignisse; bei `dt <= 0` die Eingabe-Instanz ohne Ereignisse
     * @throws NonFiniteValueException mit dem Standard-Handler, wenn `dt` oder ein Wert im
     *   Ergebnis `NaN`/unendlich ist
     */
    fun advance(state: GameState, dt: Double, rng: GameRandom): AdvanceResult {
        if (!dt.isFinite()) return reject(state, NonFiniteValueException("dt", dt))
        if (dt <= 0.0) return AdvanceResult(state, emptyList())

        syncModifiers(state)
        val context = AdvanceContext(rng)
        var current = state
        for (system in systems) current = system.advance(current, dt, context)

        StateValidator.findNonFinite(current)?.let { return reject(state, it) }
        return AdvanceResult(current, context.events)
    }

    /** Gleicht die Modifier-Quellen mit [state] ab (z. B. nach dem Laden fuer UI-Abfragen). */
    fun syncModifiers(state: GameState) {
        modifierSources.sync(modifiers, state, data)
    }

    /** Spieleraktion: Tippen auf die Grabung in [layerId], siehe [DigSystem.tapDig]. */
    fun tapDig(state: GameState, layerId: String): GameState = dig.tapDig(state, layerId)

    /**
     * Spieleraktion: Tippen auf "Freilegen", siehe [PrepSystem.tapPrep]. Gleicht vorher die
     * Modifier-Quellen ab und prueft das Ergebnis wie [advance] auf `NaN`/`Infinity`.
     *
     * @return neuer Zustand und Ereignisse (Funde fuer das Fund-Popup)
     */
    fun tapPrep(state: GameState, rng: GameRandom): AdvanceResult {
        syncModifiers(state)
        val result = prep.tapPrep(state, rng)
        StateValidator.findNonFinite(result.state)?.let { return reject(state, it) }
        return result
    }

    /**
     * Spieleraktion: rekonstruiert den Knochen [pieceKey] aus Ersatzteilen, siehe
     * [Reconstruction.reconstruct]. Gleicht vorher die Modifier-Quellen ab (Capstone `lab_5`) und
     * prueft das Ergebnis wie [advance] auf `NaN`/`Infinity`.
     */
    fun reconstruct(state: GameState, pieceKey: PieceKey): ReconstructionResult {
        syncModifiers(state)
        val result = reconstruction.reconstruct(state, pieceKey)
        if (result is ReconstructionResult.Success) {
            StateValidator.findNonFinite(result.state)?.let {
                errorHandler.onNonFinite(it)
                return ReconstructionResult.Failure(ReconstructionFailure.NON_FINITE_RESULT)
            }
        }
        return result
    }

    /**
     * Rekonstruierbare Knochen mit erwarteter Qualitaet und Verbesserungs-Flags (fuer die UI), siehe
     * [Reconstruction.reconstructableBones]. Gleicht vorher die Modifier-Quellen ab.
     */
    fun reconstructableBones(state: GameState): List<ReconstructionPreview> {
        syncModifiers(state)
        return reconstruction.reconstructableBones(state)
    }

    /** Aktuelle Verarbeitungsrate der Praeparatoren in Bloecken pro Sekunde (fuer die UI). */
    fun prepRate(): Double = prep.prepRate()

    /**
     * Aktuelle Produktion der Schicht [layerId] in Bloecken pro Sekunde (fuer die UI). Nutzt den
     * Stand der Modifier-Quellen nach dem letzten [advance] bzw. [syncModifiers].
     */
    fun blocksPerSecond(state: GameState, layerId: String): GameNumber = dig.blocksPerSecond(state.run, layerId)

    /**
     * Museumseinnahmen pro Sekunde fuer [state] (fuer die UI, Kapitel 4.6). Nutzt den Stand der
     * Modifier-Quellen nach dem letzten [advance] bzw. [syncModifiers].
     */
    fun incomePerSecond(state: GameState): GameNumber = museum.incomePerSecond(state)

    /** Ausgestellte Stuecke, wertvollstes zuerst (fuer die UI), siehe [MuseumSystem.exhibitedPieces]. */
    fun exhibitedPieces(state: GameState): List<ExhibitedPiece> = museum.exhibitedPieces(state)

    private fun reject(state: GameState, error: NonFiniteValueException): AdvanceResult {
        errorHandler.onNonFinite(error)
        return AdvanceResult(state, emptyList())
    }

    companion object {
        /**
         * Standard-Reihenfolge der Teilschritte. Stand AP-10: Grabung, Praeparation mit Einordnen
         * der Funde ([FindClassifier]), Museum ([MuseumSystem]) und Labor ([LabSystem]).
         *
         * Geplante Reihenfolge (spaetere APs fuegen ihr System hier ein):
         * 1. Grabung ([DigSystem], AP-07)
         * 2. Praeparation ([PrepSystem], AP-08) verbraucht die Bloecke aus Schritt 1
         * 3. Funde einordnen (AP-09, als [FindHandler] der Praeparation) und Museumseinnahmen (AP-09)
         * 4. Labor (AP-10): nach der Praeparation, damit Duplikate dieses Ticks schon einen freien
         *    Platz belegen koennen; nach dem Museum, weil das Labor `run.pieces` nicht aendert und die
         *    Einnahmen davon unabhaengig sind.
         * 5. Meilensteine (AP-11)
         */
        fun defaultSystems(data: GameData, modifiers: ModifierEngine): List<AdvanceSystem> = listOf(
            DigSystem(data, modifiers),
            PrepSystem(data, modifiers, FindClassifier(data, modifiers)),
            MuseumSystem(data, modifiers),
            LabSystem(data, modifiers),
        )
    }
}

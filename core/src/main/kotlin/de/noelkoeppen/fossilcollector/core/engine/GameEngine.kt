package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseQuote
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseResult
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseTarget
import de.noelkoeppen.fossilcollector.core.engine.purchase.Purchases
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

    /** Kaufaktionen (zustandslos); Hallenkauf ueber [buyHalls] bzw. [hallQuote]. */
    private val purchases: Purchases = Purchases(data, modifiers)

    /**
     * Labor fuer Abfragen der UI (Dauer, Fortschritt, FP). Enthaelt [systems] ein [LabSystem], wird
     * genau diese Instanz genutzt, sonst eine eigene.
     */
    val lab: LabSystem = systems.filterIsInstance<LabSystem>().firstOrNull() ?: LabSystem(data, modifiers)

    /** Rekonstruktion aus Ersatzteilen (Spieleraktion und Abfragen, Kapitel 4.5). */
    val reconstruction: Reconstruction = Reconstruction(data, modifiers)

    /** Sammelbuch-Completion und Buch-Boni (Abfragen fuer die UI, Kapitel 4.7). */
    val completion: Completion = Completion(data)

    /**
     * Meilensteine (Kapitel 5.2). Enthaelt [systems] ein [MilestoneSystem], wird genau diese Instanz
     * genutzt, sonst eine eigene.
     */
    val milestones: MilestoneSystem =
        systems.filterIsInstance<MilestoneSystem>().firstOrNull() ?: MilestoneSystem(data)

    /** Prestige und Ruhm-Berechnung (Kapitel 5.3). */
    val prestigeSystem: Prestige = Prestige(data, modifiers)

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
     * Modifier-Quellen ab, prueft danach die Meilensteine (z. B. `first_find`, Ereignisse am Ende der
     * Liste) und das Ergebnis wie [advance] auf `NaN`/`Infinity`.
     *
     * @return neuer Zustand und Ereignisse (Funde fuer das Fund-Popup)
     */
    fun tapPrep(state: GameState, rng: GameRandom): AdvanceResult {
        syncModifiers(state)
        val prepared = prep.tapPrep(state, rng)
        val events = ArrayList(prepared.events)
        val result = AdvanceResult(milestones.check(prepared.state) { events += it }, events)
        StateValidator.findNonFinite(result.state)?.let { return reject(state, it) }
        return result
    }

    /**
     * Prueft sofort alle offenen Meilensteine (Kapitel 5.2), z. B. nach einem Kauf oder dem Prestige.
     * [advance] prueft ohnehin am Ende jedes Ticks; [tapPrep] und [reconstruct] pruefen selbst.
     *
     * @return neuer Zustand (bzw. die Eingabe-Instanz, wenn nichts erreicht wurde) und
     *   [GameEvent.MilestoneReached]-Ereignisse
     */
    fun checkMilestones(state: GameState): AdvanceResult {
        val events = ArrayList<GameEvent>()
        val result = milestones.check(state) { events += it }
        if (result === state) return AdvanceResult(state, emptyList())
        StateValidator.findNonFinite(result)?.let { return reject(state, it) }
        return AdvanceResult(result, events)
    }

    /** Stand aller Arten im Sammelbuch (Completion, Boni), siehe [Completion.overview]. */
    fun bookOverview(state: GameState): List<SpeciesCompletion> = completion.overview(state)

    /** Completion-Stufe einer Art, siehe [Completion.levelOf]. */
    fun completionLevel(state: GameState, speciesId: String): CompletionLevel = completion.levelOf(state, speciesId)

    /**
     * Spieleraktion: rekonstruiert den Knochen [pieceKey] aus Ersatzteilen, siehe
     * [Reconstruction.reconstruct]. Gleicht vorher die Modifier-Quellen ab (Capstone `lab_5`), prueft
     * danach die Meilensteine (Ereignisse am Ende der Liste) und das Ergebnis wie [advance] auf
     * `NaN`/`Infinity`.
     */
    fun reconstruct(state: GameState, pieceKey: PieceKey): ReconstructionResult {
        syncModifiers(state)
        var result = reconstruction.reconstruct(state, pieceKey)
        if (result is ReconstructionResult.Success) {
            val events = ArrayList(result.events)
            result = result.copy(state = milestones.check(result.state) { events += it }, events = events)
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

    /**
     * Spieleraktion: Sonderausstellung waehlen bzw. mit `null` aufheben, siehe
     * [MuseumSystem.setSpecialExhibit]. Gleicht vorher die Modifier-Quellen ab.
     */
    fun setSpecialExhibit(state: GameState, speciesId: String?): SpecialExhibitResult {
        syncModifiers(state)
        return museum.setSpecialExhibit(state, speciesId)
    }

    /**
     * Spieleraktion: Museumshallen kaufen (Kapitel 4.6, 6.3), siehe [Purchases.buyHalls]. Gleicht
     * vorher die Modifier-Quellen ab (Rabatt `hall_cost`).
     */
    fun buyHalls(state: GameState, mode: BuyMode): PurchaseResult {
        syncModifiers(state)
        return purchases.buyHalls(state, mode)
    }

    /**
     * Vorschau fuer den Hallenkauf im Modus [mode] (fuer die UI), siehe [Purchases.quote]. Nutzt den
     * Stand der Modifier-Quellen nach dem letzten [advance] bzw. [syncModifiers].
     */
    fun hallQuote(state: GameState, mode: BuyMode): PurchaseQuote? =
        purchases.quote(state, PurchaseTarget.Halls, mode)

    /**
     * Ruhm bei Neustart jetzt (Kapitel 5.3, "Ruhm bei Neustart: +7"), siehe [Prestige]. Gleicht
     * vorher die Modifier-Quellen ab (`fame_gain`).
     */
    fun fameGainPreview(state: GameState): GameNumber {
        syncModifiers(state)
        return prestigeSystem.fameGainPreview(state)
    }

    /** `true`, wenn die Prestige-Vorschau mindestens 1 Ruhm zeigt (Button aktiv, Ruhm-Tab sichtbar). */
    fun canPrestige(state: GameState): Boolean {
        syncModifiers(state)
        return prestigeSystem.canPrestige(state)
    }

    /** Daten fuer den Prestige-Bestaetigungsdialog, siehe [PrestigeSummary]. */
    fun prestigeSummary(state: GameState): PrestigeSummary {
        syncModifiers(state)
        return prestigeSystem.summary(state)
    }

    /**
     * Spieleraktion: Prestige (Kapitel 5.3). Ablauf:
     * 1. Modifier-Quellen mit [state] abgleichen, Ruhm-Vorschau berechnen; unter 1 -> abgelehnt.
     * 2. Metazustand: `fame` und `fameTotal` + Gewinn, `prestigeCount + 1` (Skills, Buch, erforschte
     *    Arten, Statistiken, Meilensteine und Einstellungen bleiben).
     * 3. [metaStep] anwenden (Erweiterungsstelle fuer den Respec aus AP-13, Standard ohne Wirkung).
     * 4. Modifier-Quellen mit dem neuen Metazustand und einem leeren Durchlauf abgleichen, damit
     *    `start_teams`/`start_lab_slots` der Skills wirken, dann [RunStart.values] und neuer Durchlauf
     *    ueber [RunState.newRun] (alles aus dem Run-Zustand wird zurueckgesetzt, inkl.
     *    Sonderausstellung).
     * 5. Quellen mit dem Ergebnis abgleichen, Meilensteine pruefen (`first_prestige`, Belohnung landet
     *    im neuen Durchlauf), Ergebnis auf `NaN`/`Infinity` pruefen.
     *
     * @return Erfolg mit neuem Zustand und Ereignissen ([GameEvent.Prestiged] zuerst) oder Fehlgrund
     */
    fun prestige(state: GameState, metaStep: PrestigeMetaStep = PrestigeMetaStep.NONE): PrestigeResult {
        syncModifiers(state)
        val gain = prestigeSystem.fameGainPreview(state)
        if (!(gain >= GameNumber.ONE)) return PrestigeResult.Failure(PrestigeFailure.NOT_ENOUGH_FAME)

        val meta = metaStep.apply(prestigeSystem.metaAfterPrestige(state.meta, gain))
        syncModifiers(state.copy(meta = meta, run = prestigeSystem.emptyRun()))
        val run = prestigeSystem.newRun(RunStart.values(data, modifiers))
        val reset = state.copy(meta = meta, run = run)
        syncModifiers(reset)

        val events = ArrayList<GameEvent>()
        events += GameEvent.Prestiged(gain)
        val result = milestones.check(reset) { events += it }
        StateValidator.findNonFinite(result)?.let {
            errorHandler.onNonFinite(it)
            syncModifiers(state)
            return PrestigeResult.Failure(PrestigeFailure.NON_FINITE_RESULT)
        }
        return PrestigeResult.Success(result, events, gain)
    }

    private fun reject(state: GameState, error: NonFiniteValueException): AdvanceResult {
        errorHandler.onNonFinite(error)
        return AdvanceResult(state, emptyList())
    }

    companion object {
        /**
         * Standard-Reihenfolge der Teilschritte. Stand AP-11: Grabung, Praeparation mit Einordnen
         * der Funde ([FindClassifier]), Museum ([MuseumSystem]), Labor ([LabSystem]) und
         * Meilensteine ([MilestoneSystem]).
         *
         * Geplante Reihenfolge (spaetere APs fuegen ihr System hier ein):
         * 1. Grabung ([DigSystem], AP-07)
         * 2. Praeparation ([PrepSystem], AP-08) verbraucht die Bloecke aus Schritt 1
         * 3. Funde einordnen (AP-09, als [FindHandler] der Praeparation) und Museumseinnahmen (AP-09)
         * 4. Labor (AP-10): nach der Praeparation, damit Duplikate dieses Ticks schon einen freien
         *    Platz belegen koennen; nach dem Museum, weil das Labor `run.pieces` nicht aendert und die
         *    Einnahmen davon unabhaengig sind.
         * 5. Meilensteine (AP-11): zuletzt, damit alle Zaehler dieses Ticks beruecksichtigt sind.
         */
        fun defaultSystems(data: GameData, modifiers: ModifierEngine): List<AdvanceSystem> = listOf(
            DigSystem(data, modifiers),
            PrepSystem(data, modifiers, FindClassifier(data, modifiers)),
            MuseumSystem(data, modifiers),
            LabSystem(data, modifiers),
            MilestoneSystem(data),
        )
    }
}

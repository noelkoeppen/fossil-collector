package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.MilestoneConditionType
import de.noelkoeppen.fossilcollector.core.data.MilestoneDefinition
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Fortschritt eines Meilensteins (Abfrage fuer die UI).
 *
 * @property milestone Definition
 * @property current aktueller Zaehlerstand der Bedingung
 * @property target Zielwert `condition.value`
 * @property done bereits erreicht (in `meta.milestonesDone`)
 */
data class MilestoneProgress(
    val milestone: MilestoneDefinition,
    val current: Double,
    val target: Double,
    val done: Boolean,
)

/**
 * Meilensteine (GAME_DESIGN.md, Kapitel 5.2, Daten aus `milestones.json`). Laeuft als letztes
 * System in [GameEngine.defaultSystems]; Spieleraktionen koennen die Pruefung ueber
 * [GameEngine.checkMilestones] sofort ausloesen.
 *
 * Ein Meilenstein ist erreicht, wenn der Zaehler seiner Bedingung mindestens `condition.value`
 * betraegt ([counter]):
 * - `pieces_found`: `meta.stats.piecesFound`
 * - `skeletons_completed`: Arten mit vollstaendigem Skelett im Sammelbuch (Completion-Stufe 2)
 * - `pristine_found`: `meta.stats.pristineFound` (Funde der hoechsten Qualitaetsstufe)
 * - `teams_total`: Summe aller Grabungsteams im Durchlauf
 * - `layers_unlocked`: `run.unlockedLayers`
 * - `research_completed`: `meta.stats.researchCompleted`
 * - `reconstructions`: `meta.stats.reconstructions`
 * - `prestiges`: `meta.prestigeCount` (tritt erst mit AP-12 ein)
 *
 * Beim Erreichen (genau einmal je ID): ID in `meta.milestonesDone`, `reward.money` und
 * `reward.researchPoints` werden gutgeschrieben, Ereignis [GameEvent.MilestoneReached]. Die
 * `reward.effects` wirken dauerhaft ueber [MilestoneSources] (`milestone:<id>`) ab dem naechsten
 * Abgleich der Modifier-Quellen.
 *
 * Ausgewertet wird in Datenreihenfolge in einem Durchgang. Belohnungen veraendern keinen der
 * Zaehler, deshalb kann ein Meilenstein keinen weiteren im selben Durchgang ausloesen.
 */
class MilestoneSystem(private val data: GameData) : AdvanceSystem {

    override fun advance(state: GameState, dt: Double, context: AdvanceContext): GameState =
        check(state, context::emit)

    /**
     * Prueft alle offenen Meilensteine und vergibt die Belohnungen. Gibt [state] unveraendert
     * (dieselbe Instanz) zurueck, wenn keiner neu erreicht ist.
     */
    fun check(state: GameState, emit: (GameEvent) -> Unit): GameState {
        val done = state.meta.milestonesDone
        val open = data.milestones.filter { it.id !in done }
        if (open.isEmpty()) return state

        val counters = HashMap<MilestoneConditionType, Double>()
        var current = state
        for (milestone in open) {
            val condition = milestone.condition
            val value = counters.getOrPut(condition.type) { counter(state, condition.type) }
            if (value < condition.value) continue

            val reward = milestone.reward
            current = current.copy(
                meta = current.meta.copy(milestonesDone = current.meta.milestonesDone + milestone.id),
                run = current.run.copy(
                    money = current.run.money + reward.money,
                    researchPoints = current.run.researchPoints + reward.researchPoints,
                ),
            )
            emit(GameEvent.MilestoneReached(milestone.id, GameNumber(reward.money), GameNumber(reward.researchPoints)))
        }
        return current
    }

    /** Aktueller Zaehlerstand fuer die Bedingung [type]. */
    fun counter(state: GameState, type: MilestoneConditionType): Double = when (type) {
        MilestoneConditionType.PIECES_FOUND -> state.meta.stats.piecesFound.toDouble()
        MilestoneConditionType.SKELETONS_COMPLETED ->
            Skeletons.completeSpeciesInBook(state.meta.book, data).size.toDouble()
        MilestoneConditionType.PRISTINE_FOUND -> state.meta.stats.pristineFound.toDouble()
        MilestoneConditionType.TEAMS_TOTAL -> state.run.teams.values.sumOf { it.toDouble() }
        MilestoneConditionType.LAYERS_UNLOCKED -> state.run.unlockedLayers.toDouble()
        MilestoneConditionType.RESEARCH_COMPLETED -> state.meta.stats.researchCompleted.toDouble()
        MilestoneConditionType.RECONSTRUCTIONS -> state.meta.stats.reconstructions.toDouble()
        MilestoneConditionType.PRESTIGES -> state.meta.prestigeCount.toDouble()
    }

    /** Fortschritt aller Meilensteine in Datenreihenfolge (fuer die UI). */
    fun progress(state: GameState): List<MilestoneProgress> = data.milestones.map { milestone ->
        MilestoneProgress(
            milestone = milestone,
            current = counter(state, milestone.condition.type),
            target = milestone.condition.value,
            done = milestone.id in state.meta.milestonesDone,
        )
    }
}

/**
 * Dauerhafte Effekte erreichter Meilensteine, Quelle `milestone:<id>` mit `reward.effects`
 * (Kategorie `milestone`, Kapitel 6.2). Meilensteine ohne Effekte und unbekannte IDs im Spielstand
 * erzeugen keine Quelle.
 */
object MilestoneSources : ModifierSourceProvider {
    override val prefix: String = "milestone:"

    override fun sources(state: GameState, data: GameData): Map<String, List<Modifier>> =
        state.meta.milestonesDone
            .mapNotNull { id ->
                val effects = data.milestonesById[id]?.reward?.effects
                if (effects.isNullOrEmpty()) null else prefix + id to effects
            }
            .toMap()
}

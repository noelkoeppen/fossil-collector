package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.LayerDefinition
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.RunState
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import kotlin.math.pow

/**
 * Grabung (GAME_DESIGN.md, Kapitel 4.1): Grabungsteams erzeugen Rohbloecke je Schicht, Tippen
 * erzeugt zusaetzliche Bloecke.
 *
 * Produktion je freigeschalteter Schicht `L`:
 * ```
 * blocksPerSec(L) = teams(L) * mods(dig_rate, Basis layer.teamRate) * milestoneMult(teams(L))
 * milestoneMult(n) = balance.teamMilestoneFactor ^ (Anzahl Schwellen t aus balance.teamMilestones mit n >= t)
 * ```
 * `dig_rate` ist die Rate **je Team** mit dem Basiswert `layer.teamRate`. `mult`-Modifier (Skill
 * `dig_2`, Buch-Boni) wirken damit genau wie `teams * teamRate * milestoneMult * mods(dig_rate)`;
 * eventuelle `add`-Modifier wuerden die Rate je Team erhoehen.
 *
 * Gesperrte Schichten (Index >= `run.unlockedLayers`) produzieren nichts, auch wenn im Spielstand
 * Teams fuer sie stehen. Fehlt der Lagereintrag einer offenen Schicht, gilt er als 0.
 *
 * Die Modifier-Quellen muessen aktuell sein; [GameEngine.advance] gleicht sie vor den Systemen ab.
 */
class DigSystem(
    private val data: GameData,
    private val modifiers: ModifierEngine,
) : AdvanceSystem {

    /** Freigeschaltete Schichten in Reihenfolge. */
    fun unlockedLayers(run: RunState): List<LayerDefinition> =
        data.layers.take(run.unlockedLayers.toDouble().toInt().coerceIn(0, data.layers.size))

    /** `true`, wenn [layerId] eine freigeschaltete Schicht ist. */
    fun isUnlocked(run: RunState, layerId: String): Boolean = unlockedLayers(run).any { it.id == layerId }

    /** Anzahl der erreichten Team-Schwellen aus `balance.teamMilestones` (Zaehler, kein Spielwert). */
    fun reachedMilestones(teams: GameNumber): Int = data.balance.teamMilestones.count { teams >= it }

    /** `teamMilestoneFactor ^ reachedMilestones(teams)`. */
    fun milestoneMultiplier(teams: GameNumber): Double =
        data.balance.teamMilestoneFactor.pow(reachedMilestones(teams))

    /** Rate je Team in [layer] inkl. `dig_rate`-Modifiern (ohne Meilensteine). */
    fun ratePerTeam(layer: LayerDefinition): Double = modifiers.value(StatId.DIG_RATE, layer.teamRate)

    /** Bloecke pro Sekunde in [layerId]; 0 fuer gesperrte oder unbekannte Schichten. */
    fun blocksPerSecond(run: RunState, layerId: String): GameNumber {
        val layer = unlockedLayers(run).firstOrNull { it.id == layerId } ?: return GameNumber.ZERO
        return blocksPerSecond(run, layer)
    }

    private fun blocksPerSecond(run: RunState, layer: LayerDefinition): GameNumber {
        val teams = run.teams[layer.id] ?: GameNumber.ZERO
        if (teams <= GameNumber.ZERO) return GameNumber.ZERO
        return teams * (ratePerTeam(layer) * milestoneMultiplier(teams))
    }

    override fun advance(state: GameState, dt: Double, context: AdvanceContext): GameState {
        val run = state.run
        var blocks: MutableMap<String, GameNumber>? = null
        for (layer in unlockedLayers(run)) {
            val produced = blocksPerSecond(run, layer) * dt
            // Nicht endliche Werte bewusst durchreichen, damit GameEngine sie erkennt (Kapitel 6.6).
            if (produced.isFinite && produced <= GameNumber.ZERO) continue
            val target = blocks ?: LinkedHashMap(run.blocks).also { blocks = it }
            target[layer.id] = (target[layer.id] ?: GameNumber.ZERO) + produced
            context.emit(GameEvent.BlocksDug(layer.id, produced))
        }
        val newBlocks = blocks ?: return state
        return state.copy(run = run.copy(blocks = newBlocks))
    }

    /**
     * Spieleraktion "Tippen auf die Grabung" (Kapitel 4.1): erzeugt `balance.tapDigAmount` Bloecke
     * in der freigeschalteten Schicht [layerId]. `dig_rate` wirkt **nicht** auf das Tippen
     * (Entscheidung AP-07, siehe Kapitel 4.1). Fuer gesperrte oder unbekannte Schichten wird die
     * unveraenderte Eingabe-Instanz zurueckgegeben.
     */
    fun tapDig(state: GameState, layerId: String): GameState {
        if (!isUnlocked(state.run, layerId)) return state
        val run = state.run
        val current = run.blocks[layerId] ?: GameNumber.ZERO
        val newRun = run.copy(blocks = run.blocks + (layerId to current + data.balance.tapDigAmount))
        return state.copy(run = newRun)
    }
}

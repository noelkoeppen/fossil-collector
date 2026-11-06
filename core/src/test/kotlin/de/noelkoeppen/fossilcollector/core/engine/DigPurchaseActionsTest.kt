package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseFailure
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseResult
import de.noelkoeppen.fossilcollector.core.engine.purchase.Purchases
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests fuer die Kaufaktionen der Grabung ueber die [GameEngine] (AP-25: alle Kaeufe der UI laufen
 * ueber die Engine): [GameEngine.buyTeams], [GameEngine.buyPreparators], [GameEngine.unlockLayer].
 * Erwartung: dasselbe Ergebnis wie [Purchases] mit abgeglichenen Modifier-Quellen.
 */
class DigPurchaseActionsTest {

    private val data: GameData = DataRepository().load()

    private fun state(money: Double, skills: Set<String> = emptySet()): GameState {
        val base = GameState.newGame(data.layers.first().id, RunStart.values(data, ModifierEngine.fromGameData(data)), 0L)
        return base.copy(meta = base.meta.copy(skills = skills), run = base.run.copy(money = GameNumber(money)))
    }

    /** Referenz: [Purchases] mit Modifier-Engine, deren Quellen zu [state] passen. */
    private fun reference(state: GameState): Purchases {
        val modifiers = ModifierEngine.fromGameData(data)
        ModifierSources.sync(modifiers, state, data)
        return Purchases(data, modifiers)
    }

    @Test
    fun `Teams kaufen entspricht Purchases`() {
        val s = state(money = 1e6)
        val layer = data.layers.first().id
        val expected = reference(s).buyTeams(s, layer, BuyMode.X10)
        assertEquals(expected, GameEngine(data, ModifierEngine.fromGameData(data)).buyTeams(s, layer, BuyMode.X10))
        assertTrue(expected is PurchaseResult.Success)
    }

    @Test
    fun `Praeparatoren kaufen entspricht Purchases`() {
        val s = state(money = 1e6)
        val expected = reference(s).buyPreparators(s, BuyMode.MAX)
        assertEquals(expected, GameEngine(data, ModifierEngine.fromGameData(data)).buyPreparators(s, BuyMode.MAX))
        assertTrue(expected is PurchaseResult.Success)
    }

    @Test
    fun `Schicht freischalten gleicht vorher den Rabatt ab`() {
        // Skill-Rabatt auf layer_unlock_cost wirkt, obwohl die Engine-Modifier noch leer sind.
        val skill = data.skills.first { s -> s.effects.any { it.stat == StatId.LAYER_UNLOCK_COST } }
        val s = state(money = 1e12, skills = setOf(skill.id))
        val next = data.layers[1].id
        val expected = reference(s).unlockLayer(s, next)
        val actual = GameEngine(data, ModifierEngine.fromGameData(data)).unlockLayer(s, next)
        assertEquals(expected, actual)
        val full = Purchases(data, ModifierEngine.fromGameData(data)).unlockLayer(s, next)
        assertTrue((actual as PurchaseResult.Success).cost < (full as PurchaseResult.Success).cost)
    }

    @Test
    fun `Schicht freischalten ohne Geld schlaegt fehl und laesst den Zustand unveraendert`() {
        val s = state(money = 0.0)
        val result = GameEngine(data, ModifierEngine.fromGameData(data)).unlockLayer(s, data.layers[1].id)
        assertEquals(PurchaseResult.Failure(PurchaseFailure.INSUFFICIENT_FUNDS), result)
    }
}

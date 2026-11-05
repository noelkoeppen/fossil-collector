package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.RunStart
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseResult
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseTarget
import de.noelkoeppen.fossilcollector.core.engine.purchase.Purchases
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.RunState
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DigStateMapperTest {

    private val data = DataRepository().load()
    private val modifiers = ModifierEngine.fromGameData(data)
    private val engine = GameEngine(data, modifiers)
    private val purchases = Purchases(data, modifiers)

    private fun newGame(): GameState =
        GameState.newGame(data.layers.first().id, RunStart.values(data, modifiers), now = 0L)

    private fun map(state: GameState): DigUiState {
        engine.syncModifiers(state)
        return DigStateMapper.map(data, engine, purchases, state)
    }

    private fun GameState.withRun(block: (RunState) -> RunState) =
        copy(run = block(run))

    @Test
    fun newGameHasFirstLayerOpenAndOthersLockedInDataOrder() {
        val dig = map(newGame())
        assertEquals(data.layers.map { it.id }, dig.layers.map { it.layerId })
        assertEquals(data.layers.map { it.color }, dig.layers.map { it.color })

        val first = dig.layers[0] as DigLayerUi.Unlocked
        assertEquals(GameNumber.ZERO, first.blocks)
        assertEquals(GameNumber.ZERO, first.blocksPerSecond)

        val next = dig.layers[1] as DigLayerUi.Locked
        assertTrue(next.isNext)
        assertFalse(next.canUnlock)
        assertEquals(GameNumber(data.layers[1].unlockCost), next.unlockCost)
        assertEquals(0.0, next.savingsProgress)

        val later = dig.layers[2] as DigLayerUi.Locked
        assertFalse(later.isNext)
        assertFalse(later.canUnlock)
    }

    @Test
    fun teamQuotesMatchPurchasesForEveryMode() {
        val state = newGame().withRun { it.copy(money = GameNumber(500.0)) }
        val layer = map(state).layers[0] as DigLayerUi.Unlocked
        for (mode in BuyMode.entries) {
            assertEquals(purchases.quote(state, PurchaseTarget.Teams(layer.layerId), mode), layer.teamQuotes[mode])
        }
        assertTrue(layer.teamQuotes.getValue(BuyMode.X1)!!.affordable)
    }

    @Test
    fun ratesComeFromEngine() {
        val firstId = data.layers.first().id
        val state = newGame().withRun { it.copy(teams = it.teams + (firstId to GameNumber(30.0)), preparators = GameNumber(5.0)) }
        val dig = map(state)
        val layer = dig.layers[0] as DigLayerUi.Unlocked
        assertEquals(engine.blocksPerSecond(state, firstId), layer.blocksPerSecond)
        assertEquals(GameNumber(30.0), layer.teams)
        assertEquals(GameNumber(engine.prepRate()), dig.prep.ratePerSecond)
        assertTrue(dig.prep.ratePerSecond > GameNumber.ZERO)
        for (mode in BuyMode.entries) {
            assertEquals(purchases.quote(state, PurchaseTarget.Preparators, mode), dig.prep.preparatorQuotes[mode])
        }
    }

    @Test
    fun savingsProgressAndUnlockFollowMoney() {
        val cost = data.layers[1].unlockCost
        val half = map(newGame().withRun { it.copy(money = GameNumber(cost / 2)) }).layers[1] as DigLayerUi.Locked
        assertEquals(0.5, half.savingsProgress, 1e-9)
        assertFalse(half.canUnlock)

        val enough = map(newGame().withRun { it.copy(money = GameNumber(cost * 3)) }).layers[1] as DigLayerUi.Locked
        assertEquals(1.0, enough.savingsProgress)
        assertTrue(enough.canUnlock)
        // Nur die naechste Schicht ist freischaltbar, auch wenn das Geld fuer eine tiefere reicht.
        val deeper = map(newGame().withRun { it.copy(money = GameNumber(data.layers[2].unlockCost * 2)) }).layers[2] as DigLayerUi.Locked
        assertFalse(deeper.canUnlock)
    }

    @Test
    fun savingsProgressEdgeCases() {
        assertEquals(1.0, DigStateMapper.savingsProgress(GameNumber.ZERO, GameNumber.ZERO))
        assertEquals(0.0, DigStateMapper.savingsProgress(GameNumber(-5.0), GameNumber(10.0)))
        assertEquals(0.25, DigStateMapper.savingsProgress(GameNumber(2.5), GameNumber(10.0)))
    }

    @Test
    fun prepTapNeedsAWholeBlockAcrossUnlockedLayers() {
        val firstId = data.layers.first().id
        val none = map(newGame().withRun { it.copy(blocks = mapOf(firstId to GameNumber(0.75))) })
        assertFalse(none.prep.canTap)
        assertEquals(GameNumber.ZERO, none.prep.availableBlocks)

        val some = map(newGame().withRun { it.copy(blocks = mapOf(firstId to GameNumber(2.5)), prepProgress = GameNumber(0.5)) })
        assertTrue(some.prep.canTap)
        assertEquals(GameNumber(2.0), some.prep.availableBlocks)
        assertEquals(0.5, some.prep.progress)

        // Bloecke einer gesperrten Schicht zaehlen nicht.
        val lockedId = data.layers[1].id
        val locked = map(newGame().withRun { it.copy(blocks = mapOf(lockedId to GameNumber(5.0))) })
        assertFalse(locked.prep.canTap)
    }

    @Test
    fun unlockedLayerAfterPurchaseIsShownOpen() {
        val state = newGame().withRun { it.copy(money = GameNumber(data.layers[1].unlockCost)) }
        val unlocked = (purchases.unlockNextLayer(state) as PurchaseResult.Success).state
        val dig = map(unlocked)
        assertTrue(dig.layers[1] is DigLayerUi.Unlocked)
        assertTrue((dig.layers[2] as DigLayerUi.Locked).isNext)
    }
}

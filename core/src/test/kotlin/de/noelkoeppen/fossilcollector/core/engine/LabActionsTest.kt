package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.Currency
import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.DefinitionIds
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseFailure
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseResult
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.QueuedPiece
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ceil

/**
 * Tests fuer die Laborabfragen und -aktionen der [GameEngine] (AP-19): Laborplaetze und
 * Werkzeugstufen kaufen ([GameEngine.buyLabSlots], [GameEngine.labSlotQuote],
 * [GameEngine.buyToolLevel], [GameEngine.toolLevelQuote]), [GameEngine.damageReduction] und
 * [GameEngine.labQueueCapacity]. Erwartungswerte werden aus den echten Daten abgeleitet.
 */
class LabActionsTest {

    private val data: GameData = DataRepository().load()

    private fun engine() = GameEngine(data, ModifierEngine.fromGameData(data))

    private fun state(money: Double = 0.0, researchPoints: Double = 0.0, toolLevel: Double = 0.0): GameState {
        val base = GameState.newGame(
            data.layers.first().id,
            RunStart.values(data, ModifierEngine.fromGameData(data)),
            now = 0L,
        )
        return base.copy(
            run = base.run.copy(
                money = GameNumber(money),
                researchPoints = GameNumber(researchPoints),
                toolLevel = GameNumber(toolLevel),
            ),
        )
    }

    private val labSlot get() = data.generator(DefinitionIds.LAB_SLOT)
    private val tools get() = data.upgrade(DefinitionIds.TOOLS)
    private val damagePerLevel get() = tools.effectsPerLevel.single().value

    // --- Laborplaetze ---

    @Test
    fun `erster Laborplatz kostet baseCost in Geld`() {
        val engine = engine()
        val s = state(money = labSlot.baseCost)
        val quote = engine.labSlotQuote(s, BuyMode.X1)
        assertNotNull(quote)
        assertEquals(labSlot.baseCost, quote!!.cost.toDouble(), EPS)
        assertEquals(Currency.MONEY, quote.currency)
        assertTrue(quote.affordable)
    }

    @Test
    fun `Laborplatz kaufen erhoeht Plaetze und Warteschlange`() {
        val engine = engine()
        val s = state(money = labSlot.baseCost + 1.0)
        val result = engine.buyLabSlots(s, BuyMode.X1) as PurchaseResult.Success
        assertEquals(s.run.labSlots.toDouble() + 1.0, result.state.run.labSlots.toDouble(), EPS)
        assertEquals(1.0, result.state.run.money.toDouble(), EPS)
        assertEquals(engine.lab.slots(s) + 1, engine.lab.slots(result.state))
        assertEquals(
            engine.labQueueCapacity(s) + data.balance.labQueuePerSlot.toInt(),
            engine.labQueueCapacity(result.state),
        )
    }

    @Test
    fun `Laborplatz ohne Geld schlaegt fehl`() {
        val engine = engine()
        val s = state(money = labSlot.baseCost - 1.0)
        assertFalse(engine.labSlotQuote(s, BuyMode.X1)!!.affordable)
        val result = engine.buyLabSlots(s, BuyMode.MAX) as PurchaseResult.Failure
        assertEquals(PurchaseFailure.INSUFFICIENT_FUNDS, result.reason)
    }

    // --- Werkzeugstufen ---

    @Test
    fun `Werkzeugstufe wird mit FP bezahlt`() {
        val engine = engine()
        val s = state(money = 1_000_000.0, researchPoints = tools.baseCost)
        val quote = engine.toolLevelQuote(s, BuyMode.X1)!!
        assertEquals(Currency.RESEARCH_POINTS, quote.currency)
        assertEquals(tools.baseCost, quote.cost.toDouble(), EPS)
        val result = engine.buyToolLevel(s, BuyMode.X1) as PurchaseResult.Success
        assertEquals(1.0, result.state.run.toolLevel.toDouble(), EPS)
        assertEquals(0.0, result.state.run.researchPoints.toDouble(), EPS)
        assertEquals(1_000_000.0, result.state.run.money.toDouble(), EPS, "Geld bleibt unberuehrt")
    }

    @Test
    fun `Werkzeugstufe ohne FP schlaegt fehl`() {
        val engine = engine()
        val s = state(money = 1_000_000.0)
        assertFalse(engine.toolLevelQuote(s, BuyMode.X1)!!.affordable)
        val result = engine.buyToolLevel(s, BuyMode.X1) as PurchaseResult.Failure
        assertEquals(PurchaseFailure.INSUFFICIENT_FUNDS, result.reason)
    }

    @Test
    fun `Max kauft so viele Werkzeugstufen wie bezahlbar`() {
        val engine = engine()
        // Kosten fuer 2 Stufen: baseCost * (1 + growth)
        val twoLevels = tools.baseCost * (1.0 + tools.growth)
        val s = state(researchPoints = twoLevels + 1e-6)
        assertEquals(2.0, engine.toolLevelQuote(s, BuyMode.MAX)!!.quantity.toDouble(), EPS)
        val result = engine.buyToolLevel(s, BuyMode.MAX) as PurchaseResult.Success
        assertEquals(2.0, result.state.run.toolLevel.toDouble(), EPS)
    }

    // --- Abfragen ---

    @Test
    fun `damageReduction folgt der Werkzeugstufe nach dem Abgleich`() {
        val engine = engine()
        val s0 = state()
        engine.syncModifiers(s0)
        assertEquals(0.0, engine.damageReduction(), EPS)

        val s3 = state(toolLevel = 3.0)
        engine.syncModifiers(s3)
        assertEquals(3 * damagePerLevel, engine.damageReduction(), EPS)
    }

    @Test
    fun `Werkzeugkauf wirkt nach dem Abgleich auf damageReduction`() {
        val engine = engine()
        val s = state(researchPoints = tools.baseCost)
        val bought = (engine.buyToolLevel(s, BuyMode.X1) as PurchaseResult.Success).state
        engine.syncModifiers(bought)
        assertEquals(damagePerLevel, engine.damageReduction(), EPS)
    }

    @Test
    fun `Warteschlangen-Kapazitaet ist labSlots mal labQueuePerSlot`() {
        val engine = engine()
        val s = state()
        val expected = ceil(s.run.labSlots.toDouble() * data.balance.labQueuePerSlot).toInt()
        assertEquals(expected, engine.labQueueCapacity(s))
        val none = s.copy(run = s.run.copy(labSlots = GameNumber.ZERO))
        assertEquals(0, engine.labQueueCapacity(none))
    }

    @Test
    fun `Kapazitaet passt zur Regel des FindClassifier`() {
        // Ein Stueck weniger als die Kapazitaet: noch Platz; genau die Kapazitaet: voll.
        val engine = engine()
        val classifier = FindClassifier(data, ModifierEngine.fromGameData(data))
        val s = state().let { it.copy(run = it.run.copy(labSlots = GameNumber(2.5))) }
        val capacity = engine.labQueueCapacity(s)
        val queued = QueuedPiece(
            PieceKey("cave_bear", data.species("cave_bear").bones.first()),
            listOf(0.5),
        )
        val almost = s.copy(run = s.run.copy(labQueue = List(capacity - 1) { queued }))
        val full = s.copy(run = s.run.copy(labQueue = List(capacity) { queued }))
        assertTrue(classifier.hasQueueSpace(almost.run))
        assertFalse(classifier.hasQueueSpace(full.run))
    }

    private companion object {
        const val EPS = 1e-9
    }
}

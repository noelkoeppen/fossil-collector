package de.noelkoeppen.fossilcollector.core.engine.purchase

import de.noelkoeppen.fossilcollector.core.data.Currency
import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.DefinitionIds
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.ModifierSources
import de.noelkoeppen.fossilcollector.core.engine.RunStart
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests fuer [Purchases] mit den echten Daten aus `core/src/main/resources/data/`
 * (Werte laut Kapitel 6.4, von Hand nachgerechnet).
 */
class PurchasesTest {

    private val data: GameData = DataRepository().load()

    private fun n(v: Double) = GameNumber(v)

    /** Neuer Durchlauf mit [money] Geld, [rp] FP und den Skills [skills]; Engine passend synchronisiert. */
    private fun setup(money: Double = 0.0, rp: Double = 0.0, skills: Set<String> = emptySet(), gameData: GameData = data): Pair<GameState, Purchases> {
        val engine = ModifierEngine.fromGameData(gameData)
        val base = GameState.newGame(gameData.layers.first().id, RunStart.values(gameData, engine), now = 0L)
        val withSkills = base.copy(meta = base.meta.copy(skills = skills))
        ModifierSources.sync(engine, withSkills, gameData)
        val state = withSkills.copy(run = withSkills.run.copy(money = n(money), researchPoints = n(rp)))
        return state to Purchases(gameData, engine)
    }

    private fun success(result: PurchaseResult): PurchaseResult.Success {
        assertTrue(result is PurchaseResult.Success, "Erwartet Erfolg, war $result")
        return result as PurchaseResult.Success
    }

    private fun failure(result: PurchaseResult): PurchaseFailure {
        assertTrue(result is PurchaseResult.Failure, "Erwartet Fehlschlag, war $result")
        return (result as PurchaseResult.Failure).reason
    }

    // --- Grabungsteams ---

    @Test
    fun `erstes Team in Quartaer kostet 10`() {
        val (state, p) = setup(money = 10.0)
        val r = success(p.buyTeams(state, "quaternary", BuyMode.X1))
        assertEquals(n(1.0), r.state.run.teams["quaternary"])
        assertEquals(n(10.0), r.cost)
        assertEquals(n(0.0), r.state.run.money)
        assertEquals(Currency.MONEY, r.currency)
    }

    @Test
    fun `zweites Team kostet 11_2`() {
        val (state, p) = setup(money = 100.0)
        val s1 = success(p.buyTeams(state, "quaternary", BuyMode.X1)).state
        val r = success(p.buyTeams(s1, "quaternary", BuyMode.X1))
        assertEquals(11.2, r.cost.toDouble(), EPS)
        assertEquals(100.0 - 21.2, r.state.run.money.toDouble(), EPS)
    }

    @Test
    fun `zu wenig Geld fuer ein Team`() {
        val (state, p) = setup(money = 9.99)
        assertEquals(PurchaseFailure.INSUFFICIENT_FUNDS, failure(p.buyTeams(state, "quaternary", BuyMode.X1)))
        assertEquals(PurchaseFailure.INSUFFICIENT_FUNDS, failure(p.buyTeams(state, "quaternary", BuyMode.MAX)))
    }

    @Test
    fun `x10 kauft genau 10 oder nichts`() {
        // cost(0, 10) = 10 * (1.12^10 - 1) / 0.12 = 175.4873...
        val (enough, p) = setup(money = 175.49)
        val r = success(p.buyTeams(enough, "quaternary", BuyMode.X10))
        assertEquals(n(10.0), r.quantity)
        assertEquals(175.4873507, r.cost.toDouble(), 1e-6)
        assertEquals(n(10.0), r.state.run.teams["quaternary"])

        val (tooLittle, p2) = setup(money = 175.48)
        assertEquals(PurchaseFailure.INSUFFICIENT_FUNDS, failure(p2.buyTeams(tooLittle, "quaternary", BuyMode.X10)))
    }

    @Test
    fun `Max kauft so viele wie bezahlbar`() {
        val (state, p) = setup(money = 175.48)
        val r = success(p.buyTeams(state, "quaternary", BuyMode.MAX))
        assertEquals(n(9.0), r.quantity)
        assertTrue(r.state.run.money >= GameNumber.ZERO)
        // Rest reicht nicht fuer das 10. Team (Preis 10 * 1.12^9 = 27.73)
        assertEquals(PurchaseFailure.INSUFFICIENT_FUNDS, failure(p.buyTeams(r.state, "quaternary", BuyMode.X1)))
    }

    @Test
    fun `Max mit Geld 0 schlaegt fehl`() {
        val (state, p) = setup(money = 0.0)
        assertEquals(PurchaseFailure.INSUFFICIENT_FUNDS, failure(p.buyTeams(state, "quaternary", BuyMode.MAX)))
    }

    @Test
    fun `Teams nur in freigeschalteten Schichten`() {
        val (state, p) = setup(money = 1e9)
        assertEquals(PurchaseFailure.LAYER_LOCKED, failure(p.buyTeams(state, "neogene", BuyMode.X1)))
        assertEquals(PurchaseFailure.UNKNOWN_LAYER, failure(p.buyTeams(state, "jurassic", BuyMode.X1)))
    }

    // --- Praeparatoren, Hallen, Laborplaetze ---

    @Test
    fun `Praeparatoren 25 dann 29_25`() {
        val (state, p) = setup(money = 1000.0)
        val r1 = success(p.buyPreparators(state, BuyMode.X1))
        assertEquals(n(25.0), r1.cost)
        val r2 = success(p.buyPreparators(r1.state, BuyMode.X1))
        assertEquals(29.25, r2.cost.toDouble(), EPS)
        assertEquals(n(2.0), r2.state.run.preparators)
    }

    @Test
    fun `erste kaufbare Halle kostet 50 trotz Gratis-Halle`() {
        val (state, p) = setup(money = 200.0)
        assertEquals(n(1.0), state.run.halls)
        val r1 = success(p.buyHalls(state, BuyMode.X1))
        assertEquals(n(50.0), r1.cost)
        assertEquals(n(2.0), r1.state.run.halls)
        val r2 = success(p.buyHalls(r1.state, BuyMode.X1))
        assertEquals(n(150.0), r2.cost)
        assertEquals(n(0.0), r2.state.run.money)
    }

    @Test
    fun `Max bei Hallen mit genau passendem Geld`() {
        // 50 + 150 = 200
        val (state, p) = setup(money = 200.0)
        val r = success(p.buyHalls(state, BuyMode.MAX))
        assertEquals(n(2.0), r.quantity)
        assertEquals(n(3.0), r.state.run.halls)
    }

    @Test
    fun `Hallenrabatt wirkt auf den Preis`() {
        // curator_2: hall_cost -20 % -> 50 * 0.8 = 40, danach 120
        val (state, p) = setup(money = 160.0, skills = setOf("curator_1", "curator_2"))
        val r1 = success(p.buyHalls(state, BuyMode.X1))
        assertEquals(40.0, r1.cost.toDouble(), EPS)
        val r2 = success(p.buyHalls(r1.state, BuyMode.X1))
        assertEquals(120.0, r2.cost.toDouble(), EPS)
    }

    @Test
    fun `Laborplatz 300 dann 1200`() {
        val (state, p) = setup(money = 1500.0)
        assertEquals(n(1.0), state.run.labSlots)
        val r1 = success(p.buyLabSlots(state, BuyMode.X1))
        assertEquals(n(300.0), r1.cost)
        val r2 = success(p.buyLabSlots(r1.state, BuyMode.X1))
        assertEquals(n(1200.0), r2.cost)
        assertEquals(n(3.0), r2.state.run.labSlots)
        assertEquals(PurchaseFailure.INSUFFICIENT_FUNDS, failure(p.buyLabSlots(r2.state, BuyMode.X1)))
    }

    // --- Werkzeugstufen ---

    @Test
    fun `Werkzeug kostet FP und kein Geld`() {
        val (state, p) = setup(money = 1e9, rp = 3.0)
        val r = success(p.buyToolLevel(state, BuyMode.X1))
        assertEquals(n(3.0), r.cost)
        assertEquals(Currency.RESEARCH_POINTS, r.currency)
        assertEquals(n(1.0), r.state.run.toolLevel)
        assertEquals(n(0.0), r.state.run.researchPoints)
        assertEquals(n(1e9), r.state.run.money)
    }

    @Test
    fun `zu wenig FP trotz viel Geld`() {
        val (state, p) = setup(money = 1e9, rp = 2.99)
        assertEquals(PurchaseFailure.INSUFFICIENT_FUNDS, failure(p.buyToolLevel(state, BuyMode.X1)))
    }

    @Test
    fun `Werkzeug Max mit FP`() {
        // 3 + 4.8 + 7.68 = 15.48, naechste Stufe 12.288
        val (state, p) = setup(rp = 20.0)
        val r = success(p.buyToolLevel(state, BuyMode.MAX))
        assertEquals(n(3.0), r.quantity)
        assertEquals(15.48, r.cost.toDouble(), EPS)
        assertEquals(4.52, r.state.run.researchPoints.toDouble(), EPS)
    }

    @Test
    fun `Maximalstufe begrenzt Werkzeugkaeufe`() {
        val tools = data.upgrade(DefinitionIds.TOOLS)
        val capped = data.copy(upgrades = data.upgrades.map { if (it.id == tools.id) it.copy(maxLevel = 2) else it })
        val (state, p) = setup(rp = 1e6, gameData = capped)
        assertEquals(PurchaseFailure.MAX_LEVEL_REACHED, failure(p.buyToolLevel(state, BuyMode.X10)))
        val r = success(p.buyToolLevel(state, BuyMode.MAX))
        assertEquals(n(2.0), r.quantity)
        assertEquals(PurchaseFailure.MAX_LEVEL_REACHED, failure(p.buyToolLevel(r.state, BuyMode.X1)))
        assertNull(p.quote(r.state, PurchaseTarget.ToolLevel, BuyMode.X1))
    }

    // --- Schicht-Freischaltung ---

    @Test
    fun `naechste Schicht freischalten`() {
        val (state, p) = setup(money = 6000.0)
        val r = success(p.unlockNextLayer(state))
        assertEquals(n(6000.0), r.cost)
        assertEquals(n(2.0), r.state.run.unlockedLayers)
        assertEquals(n(0.0), r.state.run.money)
        assertEquals(n(0.0), r.state.run.teams["neogene"])
        assertEquals(n(0.0), r.state.run.blocks["neogene"])
        assertTrue(p.isLayerUnlocked(r.state, "neogene"))
        assertFalse(p.isLayerUnlocked(r.state, "cretaceous"))
    }

    @Test
    fun `zu wenig Geld fuer die Schicht`() {
        val (state, p) = setup(money = 5999.99)
        assertEquals(PurchaseFailure.INSUFFICIENT_FUNDS, failure(p.unlockNextLayer(state)))
    }

    @Test
    fun `Schichten nur in Reihenfolge`() {
        val (state, p) = setup(money = 1e9)
        assertEquals(PurchaseFailure.LAYER_NOT_NEXT, failure(p.unlockLayer(state, "cretaceous")))
        assertEquals(PurchaseFailure.LAYER_ALREADY_UNLOCKED, failure(p.unlockLayer(state, "quaternary")))
        assertEquals(PurchaseFailure.UNKNOWN_LAYER, failure(p.unlockLayer(state, "jurassic")))

        val s2 = success(p.unlockLayer(state, "neogene")).state
        val r3 = success(p.unlockLayer(s2, "cretaceous"))
        assertEquals(n(250_000.0), r3.cost)
        assertEquals(n(1e9 - 6000.0 - 250_000.0), r3.state.run.money)
        assertNull(p.nextLockedLayer(r3.state))
        assertEquals(PurchaseFailure.ALL_LAYERS_UNLOCKED, failure(p.unlockNextLayer(r3.state)))
    }

    @Test
    fun `Teams in neuer Schicht kosten deren Grundpreis`() {
        val (state, p) = setup(money = 8000.0)
        val s2 = success(p.unlockNextLayer(state)).state
        val r = success(p.buyTeams(s2, "neogene", BuyMode.X1))
        assertEquals(n(2000.0), r.cost)
        assertEquals(n(1.0), r.state.run.teams["neogene"])
    }

    @Test
    fun `Freischaltrabatt wirkt auf den Preis`() {
        // dig_4: layer_unlock_cost -25 % -> 6000 * 0.75 = 4500
        val (state, p) = setup(money = 4500.0, skills = setOf("dig_1", "dig_2", "dig_3", "dig_4"))
        assertEquals(4500.0, p.unlockCost(data.layer("neogene")).toDouble(), EPS)
        val r = success(p.unlockNextLayer(state))
        assertEquals(4500.0, r.cost.toDouble(), EPS)
        assertEquals(0.0, r.state.run.money.toDouble(), EPS)
    }

    // --- Allgemein ---

    @Test
    fun `Fehlschlag und Erfolg veraendern den Ausgangszustand nicht`() {
        val (state, p) = setup(money = 100.0)
        val before = state.copy()
        success(p.buyTeams(state, "quaternary", BuyMode.MAX))
        success(p.buyPreparators(state, BuyMode.X1))
        failure(p.buyLabSlots(state, BuyMode.X1))
        assertEquals(before, state)
    }

    @Test
    fun `Vorschau fuer die UI`() {
        val (state, p) = setup(money = 30.0)
        val x1 = p.quote(state, PurchaseTarget.Teams("quaternary"), BuyMode.X1)!!
        assertEquals(n(1.0), x1.quantity)
        assertEquals(n(10.0), x1.cost)
        assertTrue(x1.affordable)

        val x10 = p.quote(state, PurchaseTarget.Teams("quaternary"), BuyMode.X10)!!
        assertEquals(n(10.0), x10.quantity)
        assertEquals(175.4873507, x10.cost.toDouble(), 1e-6)
        assertFalse(x10.affordable)

        // 10 + 11.2 = 21.2 <= 30 < 33.744
        val max = p.quote(state, PurchaseTarget.Teams("quaternary"), BuyMode.MAX)!!
        assertEquals(n(2.0), max.quantity)
        assertTrue(max.affordable)

        val (broke, p2) = setup(money = 0.0)
        val none = p2.quote(broke, PurchaseTarget.Halls, BuyMode.MAX)!!
        assertEquals(n(0.0), none.quantity)
        assertEquals(n(50.0), none.cost)
        assertFalse(none.affordable)

        assertNull(p.quote(state, PurchaseTarget.Teams("neogene"), BuyMode.X1))
    }

    @Test
    fun `Kostenkurve mit Rabatt fuer die UI`() {
        val (state, p) = setup(skills = setOf("curator_1", "curator_2"))
        val curve = p.costCurve(state, PurchaseTarget.Halls)!!
        assertEquals(40.0, curve.baseCost, EPS)
        assertSame(null, p.costCurve(state, PurchaseTarget.Teams("cretaceous")))
    }

    private companion object {
        const val EPS = 1e-9
    }
}

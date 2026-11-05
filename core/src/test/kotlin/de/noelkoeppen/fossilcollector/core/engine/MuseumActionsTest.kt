package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseFailure
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseResult
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests fuer die Museumsaktionen der [GameEngine] (AP-18): Sonderausstellung waehlen
 * ([GameEngine.setSpecialExhibit]) und Hallen kaufen ([GameEngine.buyHalls], [GameEngine.hallQuote]).
 * Echte Daten: Capstone `curator_5` gibt `special_exhibit_mult` 5, Hallen `baseCost` 50, 1 Gratis-Halle.
 */
class MuseumActionsTest {

    private val data: GameData = DataRepository().load()

    private val bear get() = data.species("cave_bear")
    private val deer get() = data.species("giant_deer")

    private val curatorBranch = setOf("curator_1", "curator_2", "curator_3", "curator_4", "curator_5")

    private fun engine() = GameEngine(data, ModifierEngine.fromGameData(data))

    private fun state(
        pieces: Map<PieceKey, Piece> = emptyMap(),
        skills: Set<String> = emptySet(),
        specialExhibit: String? = null,
        money: Double = 0.0,
    ): GameState {
        val base = GameState.newGame(
            data.layers.first().id,
            RunStart.values(data, ModifierEngine.fromGameData(data)),
            now = 0L,
        )
        return base.copy(
            meta = base.meta.copy(skills = skills),
            run = base.run.copy(pieces = pieces, specialExhibit = specialExhibit, money = GameNumber(money)),
        )
    }

    private fun skeleton(speciesId: String): Map<PieceKey, Piece> =
        data.species(speciesId).bones.associate { PieceKey(speciesId, it) to Piece(listOf(0.5, 0.5, 0.5, 0.5)) }

    private fun success(result: SpecialExhibitResult): GameState =
        (result as? SpecialExhibitResult.Success)?.state ?: error("Erwartet Success, war $result")

    private fun failure(result: SpecialExhibitResult): SpecialExhibitFailure =
        (result as? SpecialExhibitResult.Failure)?.reason ?: error("Erwartet Failure, war $result")

    // --- Sonderausstellung ---

    @Test
    fun `vollstaendiges Skelett mit Capstone wird gewaehlt und wirkt`() {
        val engine = engine()
        val s = state(pieces = skeleton(bear.id), skills = curatorBranch)
        val chosen = success(engine.setSpecialExhibit(s, bear.id))
        assertEquals(bear.id, chosen.run.specialExhibit)
        assertNull(s.run.specialExhibit, "Eingabe bleibt unveraendert")
        assertTrue(engine.museum.isSpecialExhibitActive(chosen))
        assertTrue(engine.exhibitedPieces(chosen).all { it.specialExhibit })
    }

    @Test
    fun `ohne Capstone wird die Auswahl abgelehnt`() {
        val s = state(pieces = skeleton(bear.id), skills = setOf("curator_1", "curator_2", "curator_3", "curator_4"))
        assertEquals(SpecialExhibitFailure.NOT_UNLOCKED, failure(engine().setSpecialExhibit(s, bear.id)))
    }

    @Test
    fun `unvollstaendiges Skelett wird abgelehnt`() {
        val incomplete = skeleton(bear.id).entries.drop(1).associate { it.key to it.value }
        val s = state(pieces = incomplete, skills = curatorBranch)
        assertEquals(SpecialExhibitFailure.SKELETON_INCOMPLETE, failure(engine().setSpecialExhibit(s, bear.id)))
    }

    @Test
    fun `unbekannte Art wird abgelehnt`() {
        val s = state(pieces = skeleton(bear.id), skills = curatorBranch)
        assertEquals(SpecialExhibitFailure.UNKNOWN_SPECIES, failure(engine().setSpecialExhibit(s, "unicorn")))
    }

    @Test
    fun `null hebt die Auswahl auf, auch ohne Capstone`() {
        val s = state(pieces = skeleton(bear.id), specialExhibit = bear.id)
        assertNull(success(engine().setSpecialExhibit(s, null)).run.specialExhibit)
    }

    @Test
    fun `Wechsel auf eine andere vollstaendige Art`() {
        val engine = engine()
        val s = state(pieces = skeleton(bear.id) + skeleton(deer.id), skills = curatorBranch, specialExhibit = bear.id)
        val switched = success(engine.setSpecialExhibit(s, deer.id))
        assertEquals(deer.id, switched.run.specialExhibit)
    }

    @Test
    fun `gleiche Auswahl gibt dieselbe Instanz zurueck`() {
        val s = state(pieces = skeleton(bear.id), skills = curatorBranch, specialExhibit = bear.id)
        assertSame(s, success(engine().setSpecialExhibit(s, bear.id)))
        val none = state()
        assertSame(none, success(engine().setSpecialExhibit(none, null)))
    }

    // --- Hallen ---

    @Test
    fun `Halle kaufen kostet baseCost und erhoeht die Plaetze`() {
        val engine = engine()
        val s = state(money = 60.0)
        val quote = engine.hallQuote(s, BuyMode.X1)
        assertNotNull(quote)
        assertEquals(50.0, quote!!.cost.toDouble(), EPS)
        assertTrue(quote.affordable)
        val result = engine.buyHalls(s, BuyMode.X1) as PurchaseResult.Success
        assertEquals(s.run.halls.toDouble() + 1.0, result.state.run.halls.toDouble(), EPS)
        assertEquals(10.0, result.state.run.money.toDouble(), EPS)
        assertEquals(engine.museum.slots(s.run) + data.balance.slotsPerHall, engine.museum.slots(result.state.run), EPS)
    }

    @Test
    fun `Halle kaufen ohne Geld schlaegt fehl`() {
        val engine = engine()
        val s = state(money = 10.0)
        assertFalse(engine.hallQuote(s, BuyMode.X10)!!.affordable)
        val result = engine.buyHalls(s, BuyMode.MAX) as PurchaseResult.Failure
        assertEquals(PurchaseFailure.INSUFFICIENT_FUNDS, result.reason)
    }

    @Test
    fun `Hallenkauf beruecksichtigt den Rabatt hall_cost ohne vorheriges advance`() {
        val engine = engine()
        val s = state(skills = setOf("curator_1", "curator_2"), money = 50.0)
        val result = engine.buyHalls(s, BuyMode.X1) as PurchaseResult.Success
        assertTrue(result.cost.toDouble() < 50.0)
    }

    private companion object {
        const val EPS = 1e-9
    }
}

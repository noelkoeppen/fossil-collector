package de.noelkoeppen.fossilcollector.ui.museum

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.QualityTiers
import de.noelkoeppen.fossilcollector.core.engine.RunStart
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.number.NumberLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests fuer [MuseumUiMapper], [MuseumUiState.hallSlots] und [formatFactor]. */
class MuseumUiMapperTest {

    private val data: GameData = DataRepository().load()
    private val bear get() = data.species("cave_bear")
    private val deer get() = data.species("giant_deer")

    private val curatorBranch = setOf("curator_1", "curator_2", "curator_3", "curator_4", "curator_5")

    private fun state(
        pieces: Map<PieceKey, Piece> = emptyMap(),
        skills: Set<String> = emptySet(),
        specialExhibit: String? = null,
        money: Double = 0.0,
    ): GameState {
        val base = GameState.newGame(data.layers.first().id, RunStart.values(data, ModifierEngine.fromGameData(data)), 0L)
        return base.copy(
            meta = base.meta.copy(skills = skills),
            run = base.run.copy(pieces = pieces, specialExhibit = specialExhibit, money = GameNumber(money)),
        )
    }

    private fun map(state: GameState): MuseumUiState {
        val engine = GameEngine(data, ModifierEngine.fromGameData(data))
        engine.syncModifiers(state)
        return MuseumUiMapper.map(engine, state, data)
    }

    private fun piece(q: Double) = Piece(listOf(q, q, q, q))

    private fun skeleton(speciesId: String, q: Double = 0.5): Map<PieceKey, Piece> =
        data.species(speciesId).bones.associate { PieceKey(speciesId, it) to piece(q) }

    @Test
    fun `leeres Museum hat Plaetze der Starthalle und kein Einkommen`() {
        val ui = map(state())
        assertEquals(0.0, ui.incomePerSecond.toDouble())
        assertTrue(ui.exhibits.isEmpty())
        assertEquals(data.balance.slotsPerHall.toInt(), ui.slotsPerHall)
        assertEquals(ui.slotsPerHall, ui.slotCount)
        assertEquals(1, ui.hallCount)
        assertEquals(ui.slotCount, ui.emptySlots)
        assertTrue(ui.hallSlots(0).all { it == null })
        assertTrue(ui.completeSkeletons.isEmpty())
        assertNull(ui.specialExhibit, "ohne Capstone ausgeblendet")
        assertEquals(data.balance.skeletonMult, ui.skeletonFactor)
    }

    @Test
    fun `Stuecke werden mit IDs, Stufe und Einkommen abgebildet`() {
        val key = PieceKey(deer.id, deer.bones.first())
        val s = state(pieces = mapOf(key to piece(0.95)))
        val ui = map(s)
        val exhibit = ui.exhibits.single()
        val tier = QualityTiers.fromGameData(data).tierOf(0.95)
        assertEquals(key.toString(), exhibit.key)
        assertEquals(deer.id, exhibit.speciesId)
        assertEquals(key.boneId, exhibit.boneId)
        assertEquals(tier.id, exhibit.tierId)
        assertEquals(0.95, exhibit.quality, 1e-12)
        assertEquals(ui.incomePerSecond.toDouble(), exhibit.incomePerSecond.toDouble(), 1e-12)
        assertFalse(exhibit.skeletonComplete)
        assertEquals(ui.slotCount - 1, ui.emptySlots)
    }

    @Test
    fun `vollstaendige Skelette werden gelistet und markiert`() {
        val ui = map(state(pieces = skeleton(bear.id)))
        assertEquals(listOf(bear.id), ui.completeSkeletons)
        assertTrue(ui.exhibits.all { it.skeletonComplete })
    }

    @Test
    fun `Sonderausstellung erscheint mit Capstone`() {
        val ui = map(state(pieces = skeleton(bear.id) + skeleton(deer.id), skills = curatorBranch, specialExhibit = bear.id))
        val special = assertNotNullSpecial(ui)
        assertTrue(special.factor > 0.0)
        assertEquals(bear.id, special.selectedSpeciesId)
        assertTrue(special.active)
        assertEquals(setOf(bear.id, deer.id), special.options.toSet())
        assertTrue(ui.exhibits.filter { it.speciesId == bear.id }.all { it.specialExhibit })
    }

    @Test
    fun `Sonderausstellung mit unvollstaendiger Art ist inaktiv`() {
        val ui = map(state(pieces = skeleton(deer.id), skills = curatorBranch, specialExhibit = bear.id))
        assertFalse(assertNotNullSpecial(ui).active)
    }

    @Test
    fun `Hallen-Kaufvorschau fuer alle Modi`() {
        val ui = map(state(money = 60.0))
        assertEquals(BuyMode.entries.toSet(), ui.hallQuotes.keys)
        assertTrue(ui.hallQuotes.getValue(BuyMode.X1)!!.affordable)
        assertFalse(ui.hallQuotes.getValue(BuyMode.X10)!!.affordable)
        assertEquals(1.0, ui.hallQuotes.getValue(BuyMode.MAX)!!.quantity.toDouble())
    }

    @Test
    fun `hallSlots verteilt die Stuecke auf Hallen und fuellt mit leeren Plaetzen`() {
        val exhibits = (0 until 10).map {
            ExhibitUi("k$it", "s", "b", "good", 0.5, GameNumber(1.0), false, false)
        }
        val ui = MuseumUiState.EMPTY.copy(slotsPerHall = 8, slotCount = 16, exhibits = exhibits)
        assertEquals(2, ui.hallCount)
        assertEquals(exhibits.take(8), ui.hallSlots(0))
        val second = ui.hallSlots(1)
        assertEquals(8, second.size)
        assertEquals(exhibits.drop(8), second.take(2))
        assertTrue(second.drop(2).all { it == null })
        assertEquals(6, ui.emptySlots)
        assertTrue(ui.hallSlots(2).isEmpty())
    }

    @Test
    fun `Faktoren werden mit Nachkommastellen formatiert`() {
        assertEquals("2", formatFactor(2.0, NumberLanguage.DE))
        assertEquals("2,5", formatFactor(2.5, NumberLanguage.DE))
        assertEquals("2.5", formatFactor(2.5, NumberLanguage.EN))
        assertEquals("1,33", formatFactor(4.0 / 3.0, NumberLanguage.DE))
        assertEquals(93, qualityPercent(0.925))
        assertEquals(100, qualityPercent(1.2))
    }

    private fun assertNotNullSpecial(ui: MuseumUiState): SpecialExhibitUi {
        assertNotNull(ui.specialExhibit)
        return ui.specialExhibit!!
    }
}

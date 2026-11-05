package de.noelkoeppen.fossilcollector.ui.book

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.CompletionLevel
import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.QualityTiers
import de.noelkoeppen.fossilcollector.core.engine.RunStart
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.number.NumberLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests fuer [BookUiMapper] und [formatBonusNumber]. */
class BookUiMapperTest {

    private val data: GameData = DataRepository().load()
    private val modifiers = ModifierEngine.fromGameData(data)
    private val engine = GameEngine(data, modifiers)
    private val tiers = QualityTiers.fromGameData(data)
    private val bear get() = data.species("cave_bear")

    private fun state(
        book: Map<PieceKey, BookEntry> = emptyMap(),
        researched: Set<String> = emptySet(),
        unlockedLayers: Double = 1.0,
    ): GameState {
        val base = GameState.newGame(data.layers.first().id, RunStart.values(data, modifiers), 0L)
        return base.copy(
            meta = base.meta.copy(book = book, researchedSpecies = researched),
            run = base.run.copy(unlockedLayers = GameNumber(unlockedLayers)),
        )
    }

    private fun map(state: GameState, mapper: BookUiMapper = BookUiMapper(data)): BookUiState = mapper.map(engine, state)

    private fun fullSkeleton(speciesId: String, q: Double): Map<PieceKey, BookEntry> =
        data.species(speciesId).bones.associate { PieceKey(speciesId, it) to BookEntry(q, zones = listOf(q, q, q, q)) }

    @Test
    fun `alle Schichten und Arten in Datenreihenfolge, gesperrte Schichten markiert`() {
        val ui = map(state())
        assertEquals(data.layers.map { it.id }, ui.layers.map { it.layerId })
        assertTrue(ui.layers.first().unlocked)
        assertTrue(ui.layers.drop(1).none { it.unlocked }, "nur die erste Schicht ist offen")
        for (layer in ui.layers) {
            assertEquals(data.speciesByLayer.getValue(layer.layerId).map { it.id }, layer.species.map { it.speciesId })
            assertTrue(layer.species.none { it.discovered }, "leeres Buch: alle Arten unbekannt")
            assertEquals(0, layer.discoveredCount)
        }
    }

    @Test
    fun `freigeschaltete Schichten folgen run unlockedLayers`() {
        val ui = map(state(unlockedLayers = 2.0))
        assertEquals(listOf(true, true, false), ui.layers.map { it.unlocked })
    }

    @Test
    fun `teilweise gefundene Art ist entdeckt, fehlende Knochen ohne Stufe`() {
        val boneId = bear.bones.first()
        val entry = BookEntry(0.6, reconstructed = true, zones = listOf(0.5, 0.6, 0.7, 0.6))
        val ui = map(state(book = mapOf(PieceKey(bear.id, boneId) to entry)))
        val species = ui.species(bear.id)!!
        assertTrue(species.discovered)
        assertEquals(CompletionLevel.NONE, species.level)
        assertEquals(1, species.bonesInBook)
        assertEquals(bear.bones.size, species.bonesTotal)
        assertNull(species.minTierIndex)
        assertEquals(bear.bones, species.bones.map { it.boneId })

        val bone = species.bones.first()
        assertTrue(bone.inBook)
        assertTrue(bone.reconstructed)
        assertEquals(0.6, bone.quality)
        assertEquals(tiers.tierOf(0.6).id, bone.tierId)
        assertEquals(tiers.indexOf(0.6), bone.tierIndex)
        assertEquals(data.bone(boneId).zones, bone.zones.map { it.zoneId })
        assertEquals(listOf(0.5, 0.6, 0.7, 0.6), bone.zones.map { it.quality })

        val missing = species.bones[1]
        assertFalse(missing.inBook)
        assertNull(missing.tierIndex)
        assertTrue(missing.zones.all { it.quality == null })

        assertEquals(SkeletonBone(tiers.indexOf(0.6), listOf(0.5, 0.6, 0.7, 0.6)), species.skeleton[boneId])
        assertEquals(SkeletonBone(null, null), species.skeleton[missing.boneId])
        assertEquals(bear.bones, species.skeleton.keys.toList())
        assertEquals(1, ui.layers.first().discoveredCount)
    }

    @Test
    fun `Eintrag ohne Zonen zeigt unbekannte Zonen`() {
        val boneId = bear.bones.first()
        val ui = map(state(book = mapOf(PieceKey(bear.id, boneId) to BookEntry(0.4))))
        val species = ui.species(bear.id)!!
        assertTrue(species.bones.first().zones.all { it.quality == null })
        assertEquals(SkeletonBone(tiers.indexOf(0.4), null), species.skeleton[boneId])
    }

    @Test
    fun `bester Eintrag ueber Alter und Geschlecht zaehlt`() {
        val boneId = bear.bones.first()
        val book = mapOf(
            PieceKey(bear.id, boneId) to BookEntry(0.3),
            PieceKey(bear.id, boneId, age = "adult", sex = "m") to BookEntry(0.8, reconstructed = true),
        )
        val bone = map(state(book = book)).species(bear.id)!!.bones.first()
        assertEquals(0.8, bone.quality)
        assertTrue(bone.reconstructed)
    }

    @Test
    fun `vollstaendiges Skelett hat Stufe 2 und aktiven Buch-Bonus mit Stufenfaktor`() {
        val q = 0.6
        val ui = map(state(book = fullSkeleton(bear.id, q), researched = setOf(bear.id)))
        val species = ui.species(bear.id)!!
        val minIndex = tiers.indexOf(q)
        assertEquals(CompletionLevel.SKELETON_COMPLETE, species.level)
        assertEquals(minIndex, species.minTierIndex)
        assertEquals(tiers.tierOf(q).id, species.minTierId)

        val bonus = species.bookBonus
        val factor = data.balance.bookTierFactor[minIndex]
        assertTrue(bonus.active)
        assertEquals(bear.bookBonus.stat, bonus.stat)
        assertEquals(bear.bookBonus.op, bonus.op)
        assertEquals(factor, bonus.factor)
        assertEquals(bear.bookBonus.value * factor, bonus.value!!, 1e-12)
        assertEquals(bear.bookBonus.value * data.balance.bookTierFactor.first(), bonus.minValue, 1e-12)
        assertEquals(bear.bookBonus.value * data.balance.bookTierFactor.last(), bonus.maxValue, 1e-12)

        assertTrue(species.researchedBonus.active)
        assertEquals(data.balance.researchedBonus.value, species.researchedBonus.value)
        assertEquals(1, ui.layers.first().completeCount)
    }

    @Test
    fun `ohne Stufe 2 ist der Buch-Bonus eine Vorschau`() {
        val ui = map(state(researched = setOf(bear.id)))
        val species = ui.species(bear.id)!!
        assertEquals(CompletionLevel.RESEARCHED, species.level)
        assertTrue(species.discovered, "erforscht gilt als entdeckt")
        assertFalse(species.bookBonus.active)
        assertNull(species.bookBonus.value)
        assertNull(species.bookBonus.factor)
        assertTrue(species.researchedBonus.active)
        val other = ui.species("giant_deer")!!
        assertFalse(other.researchedBonus.active)
        assertEquals(data.balance.researchedBonus.value, other.researchedBonus.value, "Vorschau zeigt den Pauschalwert")
    }

    @Test
    fun `Cache liefert dieselbe Instanz, solange Buch, Forschung und Schichten gleich sind`() {
        val mapper = BookUiMapper(data)
        val s = state(book = fullSkeleton(bear.id, 0.5))
        val first = map(s, mapper)
        // Andere Run-Werte (z. B. Geld) aendern das Buch nicht.
        assertSame(first, map(s.copy(run = s.run.copy(money = GameNumber(123.0))), mapper))

        val newBook = s.meta.book + (PieceKey("giant_deer", "skull") to BookEntry(0.9))
        val second = map(s.copy(meta = s.meta.copy(book = newBook)), mapper)
        assertNotSame(first, second)
        assertTrue(second.species("giant_deer")!!.discovered)

        val withLayer = s.copy(meta = s.meta.copy(book = newBook), run = s.run.copy(unlockedLayers = GameNumber(2.0)))
        val third = map(withLayer, mapper)
        assertNotSame(second, third)
        assertTrue(third.layers[1].unlocked)

        val researched = withLayer.copy(meta = withLayer.meta.copy(researchedSpecies = setOf(bear.id)))
        assertNotSame(third, map(researched, mapper))
    }

    @Test
    fun `Bonuszahlen mit Vorzeichen, Prozent bei mult`() {
        assertEquals("+1", formatBonusNumber(0.01, ModifierOp.MULT, NumberLanguage.DE))
        assertEquals("+2,5", formatBonusNumber(0.025, ModifierOp.MULT, NumberLanguage.DE))
        assertEquals("+2.5", formatBonusNumber(0.025, ModifierOp.MULT, NumberLanguage.EN))
        assertEquals("+7", formatBonusNumber(0.07, ModifierOp.MULT, NumberLanguage.EN))
        assertEquals("+0,02", formatBonusNumber(0.02, ModifierOp.ADD, NumberLanguage.DE))
        assertEquals("-10", formatBonusNumber(-0.1, ModifierOp.MULT, NumberLanguage.EN))
    }
}

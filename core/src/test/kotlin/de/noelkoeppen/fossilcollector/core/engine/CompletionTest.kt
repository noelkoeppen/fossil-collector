package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import de.noelkoeppen.fossilcollector.core.modifier.ModifierCategory
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Tests fuer [Completion] und [BookSources] (Kapitel 4.7) mit den echten Daten:
 * `bookTierFactor` [1, 2, 3, 5, 8], Stufen-Grenzen 0 / 0.25 / 0.5 / 0.7 / 0.9,
 * `researchedBonus` museum_income mult 0.01, Hoehlenbaer museum_income mult 0.01,
 * Wollhaarmammut dig_rate mult 0.02, Triceratops damage_reduction add 0.01.
 */
class CompletionTest {

    private val data: GameData = DataRepository().load()
    private val completion = Completion(data)

    private fun n(v: Double) = GameNumber(v)

    private fun newState(): GameState =
        GameState.newGame(data.layers.first().id, RunStart.values(data, ModifierEngine.fromGameData(data)), 0L)

    /** Buch mit allen Knochen von [speciesId] in Qualitaet [quality]; [overrides] je Knochen. */
    private fun fullBook(speciesId: String, quality: Double, overrides: Map<String, Double> = emptyMap()) =
        data.species(speciesId).bones.associate { bone ->
            PieceKey(speciesId, bone) to BookEntry(overrides[bone] ?: quality)
        }

    private fun GameState.withBook(book: Map<PieceKey, BookEntry>) = copy(meta = meta.copy(book = meta.book + book))

    private fun GameState.withResearched(vararg ids: String) =
        copy(meta = meta.copy(researchedSpecies = meta.researchedSpecies + ids))

    private fun synced(state: GameState): ModifierEngine {
        val engine = ModifierEngine.fromGameData(data)
        ModifierSources.sync(engine, state, data)
        return engine
    }

    // --- Completion-Stufen ---

    @Test
    fun `ohne Forschung und Buch Stufe 0`() {
        assertEquals(CompletionLevel.NONE, completion.levelOf(newState(), "cave_bear"))
    }

    @Test
    fun `erforschte Art hat Stufe 1`() {
        val s = newState().withResearched("cave_bear")
        assertEquals(CompletionLevel.RESEARCHED, completion.levelOf(s, "cave_bear"))
        assertEquals(CompletionLevel.NONE, completion.levelOf(s, "giant_deer"))
    }

    @Test
    fun `unvollstaendiges Buch bleibt bei Stufe 1`() {
        val book = fullBook("cave_bear", 0.5) - PieceKey("cave_bear", "skull")
        val s = newState().withResearched("cave_bear").withBook(book)
        assertEquals(CompletionLevel.RESEARCHED, completion.levelOf(s, "cave_bear"))
        val overview = completion.completionOf(s, data.species("cave_bear"))
        assertEquals(7, overview.bonesInBook)
        assertEquals(8, overview.bonesTotal)
        assertNull(overview.minTierIndex)
        assertNull(overview.bookBonus)
    }

    @Test
    fun `vollstaendiges Buch ergibt Stufe 2, auch ohne Forschung`() {
        val s = newState().withBook(fullBook("cave_bear", 0.5))
        assertEquals(CompletionLevel.SKELETON_COMPLETE, completion.levelOf(s, "cave_bear"))
        assertEquals(CompletionLevel.SKELETON_COMPLETE, completion.levelOf(s.withResearched("cave_bear"), "cave_bear"))
        assertTrue(CompletionLevel.SKELETON_COMPLETE.level > CompletionLevel.RESEARCHED.level)
    }

    @Test
    fun `Alter und Geschlecht spielen fuer Stufe 2 keine Rolle`() {
        val book = data.species("cave_bear").bones.associate { PieceKey("cave_bear", it, "adult", "male") to BookEntry(0.6) }
        assertEquals(CompletionLevel.SKELETON_COMPLETE, completion.levelOf(newState().withBook(book), "cave_bear"))
    }

    @Test
    fun `unbekannte Art hat Stufe 0`() {
        assertEquals(CompletionLevel.NONE, completion.levelOf(newState().withResearched("dodo"), "dodo"))
    }

    @Test
    fun `Uebersicht listet alle Arten in Datenreihenfolge`() {
        val s = newState().withResearched("giant_deer").withBook(fullBook("cave_bear", 0.95))
        val overview = completion.overview(s)
        assertEquals(data.species.map { it.id }, overview.map { it.speciesId })
        val bear = overview.first { it.speciesId == "cave_bear" }
        assertEquals(CompletionLevel.SKELETON_COMPLETE, bear.level)
        assertTrue(bear.skeletonComplete)
        assertFalse(bear.researched)
        assertEquals(4, bear.minTierIndex)
        assertEquals("quaternary", bear.layerId)
        assertNull(bear.researchedBonus)
        val deer = overview.first { it.speciesId == "giant_deer" }
        assertEquals(CompletionLevel.RESEARCHED, deer.level)
        assertEquals(data.balance.researchedBonus, deer.researchedBonus)
    }

    // --- minTier und Buch-Bonus ---

    @ParameterizedTest(name = "minTier bei Qualitaet {0} -> Index {1}, Faktor {2}")
    @CsvSource(
        "0.10, 0, 1.0",
        "0.25, 1, 2.0",
        "0.60, 2, 3.0",
        "0.70, 3, 5.0",
        "0.95, 4, 8.0",
    )
    fun `Buch-Bonus skaliert mit der niedrigsten Stufe`(lowest: Double, index: Int, factor: Double) {
        // Alle anderen Knochen Museumsreif, nur der Schaedel bestimmt die niedrigste Stufe.
        val book = fullBook("cave_bear", 0.99, mapOf("skull" to lowest))
        val species = data.species("cave_bear")
        assertEquals(index, completion.minTierIndex(book, species))
        assertEquals(factor, completion.tierFactor(index))
        val bonus = completion.bookBonus(book, species)
        assertEquals(Modifier(StatId.MUSEUM_INCOME, ModifierOp.MULT, 0.01 * factor, ModifierCategory.BOOK), bonus)

        val engine = synced(newState().withBook(book))
        assertEquals(1.0 + 0.01 * factor, engine.factor(StatId.MUSEUM_INCOME), EPS)
    }

    @Test
    fun `je Knochen zaehlt der beste Eintrag ueber Alter und Geschlecht`() {
        val book = fullBook("cave_bear", 0.95, mapOf("skull" to 0.1)) +
            (PieceKey("cave_bear", "skull", "adult", null) to BookEntry(0.8))
        assertEquals(3, completion.minTierIndex(book, data.species("cave_bear")))
    }

    @Test
    fun `Faktor-Index wird auf den gueltigen Bereich begrenzt`() {
        assertEquals(1.0, completion.tierFactor(-1))
        assertEquals(8.0, completion.tierFactor(99))
    }

    // --- Quellen in der ModifierEngine ---

    @Test
    fun `erforschte Art gibt pauschal researchedBonus`() {
        val engine = synced(newState().withResearched("cave_bear", "giant_deer"))
        assertEquals(setOf("book:researched:cave_bear", "book:researched:giant_deer"), engine.sourceIds)
        assertEquals(listOf(data.balance.researchedBonus), engine.source("book:researched:cave_bear"))
        // zwei Arten in Kategorie book addiert: 1 + 0.01 + 0.01
        assertEquals(1.02, engine.factor(StatId.MUSEUM_INCOME), EPS)
    }

    @Test
    fun `Stufe 2 behaelt den researchedBonus zusaetzlich zum Buch-Bonus`() {
        val s = newState().withResearched("cave_bear").withBook(fullBook("cave_bear", 0.6))
        val engine = synced(s)
        assertEquals(setOf("book:cave_bear", "book:researched:cave_bear"), engine.sourceIds)
        // Kategorie book addiert: 0.01 * 3 (Gut) + 0.01 (erforscht)
        assertEquals(1.04, engine.factor(StatId.MUSEUM_INCOME), EPS)
    }

    @Test
    fun `Buch-Bonus wirkt multiplikativ zu anderen Kategorien`() {
        val s = newState().withBook(fullBook("cave_bear", 0.6)).let { it.copy(meta = it.meta.copy(skills = setOf("curator_1"))) }
        // (1 + 0.1 skill) * (1 + 0.03 book)
        assertEquals(1.1 * 1.03, synced(s).factor(StatId.MUSEUM_INCOME), EPS)
    }

    @Test
    fun `add-Buch-Bonus wirkt absolut`() {
        val engine = synced(newState().withBook(fullBook("triceratops", 0.75)))
        assertEquals(0.05, engine.additive(StatId.DAMAGE_REDUCTION), EPS)
    }

    @Test
    fun `Buch-Bonus fuer dig_rate erhoeht die Blockproduktion ueber advance`() {
        val base = newState()
        val s = base.copy(run = base.run.copy(teams = mapOf("quaternary" to n(10.0))))
        val modifiers = ModifierEngine.fromGameData(data)
        val engine = GameEngine(data, modifiers, listOf(DigSystem(data, modifiers)))
        val without = engine.advance(s, 1.0, SeededRandom(1L))
        val with = engine.advance(s.withBook(fullBook("woolly_mammoth", 0.95)), 1.0, SeededRandom(1L))
        // 10 Teams * 0.1 Bloecke/s; Wollhaarmammut Museumsreif: dig_rate +0.02 * 8 = +16 %
        assertEquals(1.0, without.state.run.blocks.getValue("quaternary").toDouble(), EPS)
        assertEquals(1.16, with.state.run.blocks.getValue("quaternary").toDouble(), EPS)
    }

    @Test
    fun `Buch-Bonus geht mit Verbesserung des schwaechsten Knochens hoch`() {
        val book = fullBook("cave_bear", 0.95, mapOf("jaw" to 0.3))
        val modifiers = ModifierEngine.fromGameData(data)
        ModifierSources.sync(modifiers, newState().withBook(book), data)
        assertEquals(1.02, modifiers.factor(StatId.MUSEUM_INCOME), EPS)
        ModifierSources.sync(modifiers, newState().withBook(book + (PieceKey("cave_bear", "jaw") to BookEntry(0.92))), data)
        assertEquals(1.08, modifiers.factor(StatId.MUSEUM_INCOME), EPS)
    }

    @Test
    fun `unbekannte Arten im Spielstand erzeugen keine Quellen`() {
        val s = newState().withResearched("dodo").withBook(mapOf(PieceKey("dodo", "skull") to BookEntry(0.9)))
        assertTrue(synced(s).sourceIds.isEmpty())
    }

    @Test
    fun `GameEngine liefert Uebersicht und Stufe`() {
        val engine = GameEngine(data, ModifierEngine.fromGameData(data))
        val s = newState().withResearched("cave_bear")
        assertEquals(CompletionLevel.RESEARCHED, engine.completionLevel(s, "cave_bear"))
        assertEquals(data.species.size, engine.bookOverview(s).size)
    }

    private companion object {
        const val EPS = 1e-9
    }
}

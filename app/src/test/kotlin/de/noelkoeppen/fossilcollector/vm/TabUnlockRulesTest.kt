package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.RunStart
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.RunStartValues
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TabUnlockRulesTest {

    private val start = RunStartValues(
        money = GameNumber.ZERO,
        researchPoints = GameNumber.ZERO,
        teamsInFirstLayer = GameNumber.ONE,
        preparators = GameNumber.ZERO,
        halls = GameNumber.ONE,
        labSlots = GameNumber.ONE,
        toolLevel = GameNumber.ZERO,
    )
    private val fresh = GameState.newGame("layer_a", start, now = 0L)

    private fun withBook(count: Int): GameState {
        val book = (1..count).associate { PieceKey("species_$it", "bone") to bookEntry() }
        return fresh.copy(meta = fresh.meta.copy(book = book))
    }

    private fun bookEntry(): BookEntry = BookEntry(quality = 0.5)

    /** Prestige-Vorschau unter 1 (Standardfall der Tests ohne Engine). */
    private val noPrestige: (GameState) -> Boolean = { false }

    @Test
    fun newGameShowsOnlyDig() {
        assertEquals(setOf(GameTab.DIG), TabUnlockRules.visibleTabs(fresh, noPrestige))
    }

    @Test
    fun museumAfterFirstFossil() {
        val found = fresh.copy(meta = fresh.meta.copy(stats = fresh.meta.stats.copy(piecesFound = GameNumber.ONE)))
        assertTrue(TabUnlockRules.isUnlocked(GameTab.MUSEUM, found, noPrestige))
        assertTrue(TabUnlockRules.isUnlocked(GameTab.MUSEUM, withBook(1), noPrestige))
        assertFalse(TabUnlockRules.isUnlocked(GameTab.BOOK, withBook(1), noPrestige))
    }

    @Test
    fun bookAfterFiveDistinctFinds() {
        assertFalse(TabUnlockRules.isUnlocked(GameTab.BOOK, withBook(TabUnlockRules.BOOK_TAB_DISTINCT_FINDS - 1), noPrestige))
        assertTrue(TabUnlockRules.isUnlocked(GameTab.BOOK, withBook(TabUnlockRules.BOOK_TAB_DISTINCT_FINDS), noPrestige))
    }

    @Test
    fun labAfterFirstDuplicatePlaceholder() {
        assertFalse(TabUnlockRules.hasFirstDuplicate(fresh))
        val researched = fresh.copy(meta = fresh.meta.copy(researchedSpecies = setOf("species_1")))
        assertTrue(TabUnlockRules.hasFirstDuplicate(researched))
    }

    @Test
    fun fameAfterFirstPrestige() {
        assertFalse(TabUnlockRules.isUnlocked(GameTab.FAME, fresh, noPrestige))
        val prestiged = fresh.copy(meta = fresh.meta.copy(prestigeCount = GameNumber.ONE))
        assertTrue(TabUnlockRules.isUnlocked(GameTab.FAME, prestiged, noPrestige))
    }

    @Test
    fun fameWhenPrestigePreviewAtLeastOne() {
        assertTrue(TabUnlockRules.isUnlocked(GameTab.FAME, fresh) { true })
        assertTrue(TabUnlockRules.hasFameAccess(fresh) { true })
    }

    @Test
    fun fameTabUsesEnginePrestigePreview() {
        val data = DataRepository().load()
        val engine = GameEngine(data, ModifierEngine.fromGameData(data))
        val start = GameState.newGame(data.layers.first().id, RunStart.values(data, ModifierEngine.fromGameData(data)), 0L)
        assertFalse(TabUnlockRules.isUnlocked(GameTab.FAME, start, engine::canPrestige))
        // Ein vollstaendiges Quartaer-Skelett mit q = 0.6 -> floor(1 * 1.1) = 1 Ruhm
        val pieces = data.species("cave_bear").bones.associate { PieceKey("cave_bear", it) to Piece(listOf(0.6)) }
        val withSkeleton = start.copy(run = start.run.copy(pieces = pieces))
        assertTrue(TabUnlockRules.isUnlocked(GameTab.FAME, withSkeleton, engine::canPrestige))
    }

    @Test
    fun visibleTabsKeepNavigationOrder() {
        val state = withBook(5).copy(meta = withBook(5).meta.copy(prestigeCount = GameNumber.ONE))
        assertEquals(listOf(GameTab.DIG, GameTab.MUSEUM, GameTab.BOOK, GameTab.FAME), TabUnlockRules.visibleTabs(state, noPrestige).toList())
    }

    @Test
    fun revealedTabsNeverDisappear() {
        val revealed = TabUnlockRules.revealTabs(emptySet(), withBook(5), noPrestige)
        val afterReset = TabUnlockRules.revealTabs(revealed, fresh, noPrestige)
        assertSame(revealed, afterReset)
        assertEquals(listOf(GameTab.DIG, GameTab.MUSEUM, GameTab.BOOK), afterReset.toList())
    }
}

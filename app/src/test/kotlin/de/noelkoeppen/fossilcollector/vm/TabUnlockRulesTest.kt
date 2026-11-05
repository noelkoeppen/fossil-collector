package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.RunStartValues
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

    @Test
    fun newGameShowsOnlyDig() {
        assertEquals(setOf(GameTab.DIG), TabUnlockRules.visibleTabs(fresh))
    }

    @Test
    fun museumAfterFirstFossil() {
        val found = fresh.copy(meta = fresh.meta.copy(stats = fresh.meta.stats.copy(piecesFound = GameNumber.ONE)))
        assertTrue(TabUnlockRules.isUnlocked(GameTab.MUSEUM, found))
        assertTrue(TabUnlockRules.isUnlocked(GameTab.MUSEUM, withBook(1)))
        assertFalse(TabUnlockRules.isUnlocked(GameTab.BOOK, withBook(1)))
    }

    @Test
    fun bookAfterFiveDistinctFinds() {
        assertFalse(TabUnlockRules.isUnlocked(GameTab.BOOK, withBook(TabUnlockRules.BOOK_TAB_DISTINCT_FINDS - 1)))
        assertTrue(TabUnlockRules.isUnlocked(GameTab.BOOK, withBook(TabUnlockRules.BOOK_TAB_DISTINCT_FINDS)))
    }

    @Test
    fun labAfterFirstDuplicatePlaceholder() {
        assertFalse(TabUnlockRules.hasFirstDuplicate(fresh))
        val researched = fresh.copy(meta = fresh.meta.copy(researchedSpecies = setOf("species_1")))
        assertTrue(TabUnlockRules.hasFirstDuplicate(researched))
    }

    @Test
    fun fameAfterFirstPrestige() {
        assertFalse(TabUnlockRules.isUnlocked(GameTab.FAME, fresh))
        val prestiged = fresh.copy(meta = fresh.meta.copy(prestigeCount = GameNumber.ONE))
        assertTrue(TabUnlockRules.isUnlocked(GameTab.FAME, prestiged))
    }

    @Test
    fun visibleTabsKeepNavigationOrder() {
        val state = withBook(5).copy(meta = withBook(5).meta.copy(prestigeCount = GameNumber.ONE))
        assertEquals(listOf(GameTab.DIG, GameTab.MUSEUM, GameTab.BOOK, GameTab.FAME), TabUnlockRules.visibleTabs(state).toList())
    }

    @Test
    fun revealedTabsNeverDisappear() {
        val revealed = TabUnlockRules.revealTabs(emptySet(), withBook(5))
        val afterReset = TabUnlockRules.revealTabs(revealed, fresh)
        assertSame(revealed, afterReset)
        assertEquals(listOf(GameTab.DIG, GameTab.MUSEUM, GameTab.BOOK), afterReset.toList())
    }
}

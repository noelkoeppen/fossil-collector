package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.data.Currency
import de.noelkoeppen.fossilcollector.core.engine.GameEvent
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseQuote
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.ui.lab.LabUiState
import de.noelkoeppen.fossilcollector.ui.museum.MuseumUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests fuer [TabBadges] und [UnseenUpdates] (AP-25, Kapitel 8.1). */
class TabBadgesTest {

    private val key = PieceKey("cave_bear", "skull")
    private val labDone = GameEvent.LabJobCompleted(key, GameNumber.ONE)
    private val bookUpdated = GameEvent.BookUpdated(key, 0.5, null)
    private val allTabs = GameTab.entries.toList()

    private fun quote(affordable: Boolean, currency: Currency = Currency.MONEY) =
        PurchaseQuote(GameNumber.ONE, GameNumber(10.0), currency, affordable)

    /** Kaufvorschauen: x1 wie angegeben, x10 und Max immer bezahlbar (duerfen nicht zaehlen). */
    private fun quotes(x1: Boolean): Map<BuyMode, PurchaseQuote?> =
        mapOf(BuyMode.X1 to quote(x1), BuyMode.X10 to quote(true), BuyMode.MAX to quote(true))

    private fun dig(teams: Boolean = false, preparators: Boolean = false, canUnlock: Boolean = false) = DigUiState(
        layers = listOf(
            DigLayerUi.Unlocked("a", "#000000", GameNumber.ZERO, GameNumber.ZERO, GameNumber.ONE, quotes(teams)),
            DigLayerUi.Locked("b", "#000000", GameNumber(100.0), 0.5, isNext = true, canUnlock = canUnlock),
        ),
        prep = PrepUi.EMPTY.copy(preparatorQuotes = quotes(preparators)),
    )

    private val museumNone = MuseumUiState.EMPTY.copy(hallQuotes = quotes(false))
    private val labNone = LabUiState.EMPTY.copy(labSlotQuotes = quotes(false), toolQuotes = mapOf(BuyMode.X1 to null))

    // --- bezahlbar ---

    @Test
    fun `nichts bezahlbar ergibt keine Tabs, x10 und Max zaehlen nicht`() {
        assertEquals(emptySet<GameTab>(), TabBadges.affordableTabs(dig(), museumNone, labNone))
    }

    @Test
    fun `Grabung bei Teams, Praeparatoren oder naechster Schicht`() {
        assertEquals(setOf(GameTab.DIG), TabBadges.affordableTabs(dig(teams = true), museumNone, labNone))
        assertEquals(setOf(GameTab.DIG), TabBadges.affordableTabs(dig(preparators = true), museumNone, labNone))
        assertEquals(setOf(GameTab.DIG), TabBadges.affordableTabs(dig(canUnlock = true), museumNone, labNone))
    }

    @Test
    fun `Museum bei Halle, Labor bei Laborplatz oder Werkzeug`() {
        val museum = museumNone.copy(hallQuotes = quotes(true))
        assertEquals(setOf(GameTab.MUSEUM), TabBadges.affordableTabs(dig(), museum, labNone))
        val slots = labNone.copy(labSlotQuotes = quotes(true))
        assertEquals(setOf(GameTab.LAB), TabBadges.affordableTabs(dig(), museumNone, slots))
        val tools = labNone.copy(toolQuotes = mapOf(BuyMode.X1 to quote(true, Currency.RESEARCH_POINTS)))
        assertEquals(setOf(GameTab.LAB), TabBadges.affordableTabs(dig(), museumNone, tools))
    }

    @Test
    fun `leere Zustaende sind nicht bezahlbar`() {
        assertTrue(TabBadges.affordableTabs(DigUiState.EMPTY, MuseumUiState.EMPTY, LabUiState.EMPTY).isEmpty())
    }

    // --- Badges ---

    @Test
    fun `jeder Grund an seinem Tab`() {
        val badges = TabBadges.badges(
            visibleTabs = allTabs,
            activeTab = null,
            unseen = UnseenUpdates(labCompleted = true, bookUpdated = true),
            affordable = setOf(GameTab.DIG, GameTab.MUSEUM, GameTab.LAB),
            canPrestige = true,
        )
        assertEquals(
            mapOf(
                GameTab.DIG to TabBadge.AFFORDABLE,
                GameTab.LAB to TabBadge.RESEARCH_DONE,
                GameTab.MUSEUM to TabBadge.AFFORDABLE,
                GameTab.BOOK to TabBadge.NEW_BOOK_ENTRY,
                GameTab.FAME to TabBadge.PRESTIGE,
            ),
            badges,
        )
    }

    @Test
    fun `Labor ohne fertige Forschung zeigt bezahlbar`() {
        val badges = TabBadges.badges(allTabs, null, UnseenUpdates.NONE, setOf(GameTab.LAB), canPrestige = false)
        assertEquals(mapOf(GameTab.LAB to TabBadge.AFFORDABLE), badges)
    }

    @Test
    fun `aktiver Tab und unsichtbare Tabs haben kein Badge`() {
        val badges = TabBadges.badges(
            visibleTabs = listOf(GameTab.DIG, GameTab.MUSEUM),
            activeTab = GameTab.DIG,
            unseen = UnseenUpdates(labCompleted = true, bookUpdated = true),
            affordable = setOf(GameTab.DIG, GameTab.MUSEUM, GameTab.LAB),
            canPrestige = true,
        )
        assertEquals(mapOf(GameTab.MUSEUM to TabBadge.AFFORDABLE), badges)
    }

    @Test
    fun `ohne Anlass keine Badges`() {
        assertTrue(TabBadges.badges(allTabs, null, UnseenUpdates.NONE, emptySet(), canPrestige = false).isEmpty())
    }

    // --- seit letztem Besuch ---

    @Test
    fun `Ereignisse markieren Labor und Buch, wenn sie nicht offen sind`() {
        val unseen = UnseenUpdates.NONE.afterEvents(listOf(labDone, bookUpdated), activeTab = GameTab.DIG)
        assertEquals(UnseenUpdates(labCompleted = true, bookUpdated = true), unseen)
    }

    @Test
    fun `Ereignisse des offenen Tabs gelten als gesehen`() {
        assertFalse(UnseenUpdates.NONE.afterEvents(listOf(labDone), GameTab.LAB).labCompleted)
        assertFalse(UnseenUpdates.NONE.afterEvents(listOf(bookUpdated), GameTab.BOOK).bookUpdated)
        // Einstellungen offen (kein Tab): alles ungesehen.
        assertTrue(UnseenUpdates.NONE.afterEvents(listOf(labDone), null).labCompleted)
    }

    @Test
    fun `Besuch loescht nur die eigene Markierung`() {
        val both = UnseenUpdates(labCompleted = true, bookUpdated = true)
        assertEquals(UnseenUpdates(labCompleted = false, bookUpdated = true), both.visited(GameTab.LAB))
        assertEquals(UnseenUpdates(labCompleted = true, bookUpdated = false), both.visited(GameTab.BOOK))
        assertSame(both, both.visited(GameTab.DIG))
        assertSame(both, both.visited(null))
    }

    @Test
    fun `andere Ereignisse aendern nichts`() {
        val none = UnseenUpdates.NONE
        assertSame(none, none.afterEvents(listOf(GameEvent.SpeciesResearched("cave_bear")), GameTab.DIG))
        assertSame(none, none.afterEvents(emptyList(), GameTab.DIG))
    }

    @Test
    fun `Markierung bleibt bis zum Besuch`() {
        val marked = UnseenUpdates.NONE.afterEvents(listOf(labDone), GameTab.DIG)
        // Spaeter das Labor offen und weitere Ereignisse: Markierung bleibt bis visited.
        assertTrue(marked.afterEvents(listOf(labDone), GameTab.LAB).labCompleted)
        assertFalse(marked.visited(GameTab.LAB).labCompleted)
    }
}

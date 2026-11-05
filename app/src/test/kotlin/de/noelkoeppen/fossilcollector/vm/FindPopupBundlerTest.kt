package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.engine.Find
import de.noelkoeppen.fossilcollector.core.engine.GameEvent
import de.noelkoeppen.fossilcollector.core.engine.QualityTiers
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FindPopupBundlerTest {

    private val tiers = QualityTiers.fromGameData(DataRepository().load())
    private val key = PieceKey("cave_bear", "skull")
    private val second = 1_000_000_000L

    private fun popup(quality: Double, kind: FindPopupKind = FindPopupKind.NEW, pieceKey: PieceKey = key) =
        checkNotNull(
            FindPopup.fromEvent(
                when (kind) {
                    FindPopupKind.NEW -> GameEvent.NewPiece(pieceKey, quality)
                    FindPopupKind.IMPROVED -> GameEvent.PieceImproved(pieceKey, quality, 0.0)
                },
                tiers,
            ),
        )

    // --- Ereignis -> Popup ---

    @Test
    fun newPieceAndImprovedBecomePopupsWithTier() {
        val new = popup(0.95)
        assertEquals("pristine", new.tierId)
        assertEquals(tiers.tiers.lastIndex, new.tierIndex)
        assertEquals(FindPopupKind.NEW, new.kind)

        val improved = popup(0.55, FindPopupKind.IMPROVED)
        assertEquals("good", improved.tierId)
        assertEquals(FindPopupKind.IMPROVED, improved.kind)
        assertEquals(key, improved.pieceKey)
    }

    @Test
    fun otherEventsGiveNoPopup() {
        val events = listOf(
            GameEvent.FossilFound(Find(key, "quaternary", listOf(0.5, 0.5, 0.5, 0.5)), "good"),
            GameEvent.DuplicateSold(key, 0.5, GameNumber(10.0)),
            GameEvent.DuplicateQueued(key, 0.5),
            GameEvent.BookUpdated(key, 0.5, null),
            GameEvent.BlocksDug("quaternary", GameNumber.ONE),
            GameEvent.SkeletonCompleted("cave_bear"),
        )
        events.forEach { assertNull(FindPopup.fromEvent(it, tiers), it.toString()) }
    }

    // --- Buendelung ---

    @Test
    fun firstPopupIsShownImmediately() {
        val bundler = FindPopupBundler()
        val p = popup(0.3)
        bundler.offer(p)
        assertEquals(p, bundler.poll(0L))
        assertNull(bundler.poll(0L))
    }

    @Test
    fun atMostOnePopupPerSecond() {
        val bundler = FindPopupBundler()
        bundler.offer(popup(0.3))
        assertTrue(bundler.poll(10L) != null)
        bundler.offer(popup(0.4))
        assertNull(bundler.poll(10L + second - 1))
        assertEquals(0.4, bundler.poll(10L + second)?.quality)
    }

    @Test
    fun bundledCandidatesPreferBetterTier() {
        val bundler = FindPopupBundler()
        bundler.offer(popup(0.3))
        bundler.poll(0L)
        // Waehrend der Sperrzeit: Gut, Exzellent, Fragment -> Exzellent gewinnt, Rest verfaellt.
        bundler.offer(popup(0.6))
        bundler.offer(popup(0.75, FindPopupKind.IMPROVED))
        bundler.offer(popup(0.1))
        assertNull(bundler.poll(second / 2))
        val shown = bundler.poll(second)
        assertEquals("excellent", shown?.tierId)
        assertEquals(FindPopupKind.IMPROVED, shown?.kind)
        assertNull(bundler.poll(3 * second))
    }

    @Test
    fun sameTierPrefersHigherQualityThenEarlier() {
        val a = popup(0.55, pieceKey = PieceKey("cave_bear", "jaw"))
        val b = popup(0.65, pieceKey = PieceKey("cave_bear", "ribs"))
        val c = popup(0.65, pieceKey = PieceKey("cave_bear", "pelvis"))
        assertTrue(FindPopupBundler.isBetter(b, a))
        assertFalse(FindPopupBundler.isBetter(a, b))
        assertFalse(FindPopupBundler.isBetter(c, b))

        val bundler = FindPopupBundler()
        bundler.offer(a)
        bundler.offer(b)
        bundler.offer(c)
        assertEquals(b, bundler.poll(0L))
    }

    @Test
    fun emptyBundlerKeepsNoCooldownBacklog() {
        val bundler = FindPopupBundler()
        assertNull(bundler.poll(0L))
        // Ohne gezeigtes Popup gibt es keine Sperrzeit.
        bundler.offer(popup(0.2))
        assertTrue(bundler.poll(1L) != null)
    }

    @Test
    fun resetClearsPendingAndCooldown() {
        val bundler = FindPopupBundler()
        bundler.offer(popup(0.2))
        bundler.poll(0L)
        bundler.offer(popup(0.9))
        bundler.reset()
        assertNull(bundler.poll(1L))
        bundler.offer(popup(0.5))
        assertTrue(bundler.poll(2L) != null)
    }
}

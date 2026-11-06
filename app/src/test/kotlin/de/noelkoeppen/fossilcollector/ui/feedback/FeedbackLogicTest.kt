package de.noelkoeppen.fossilcollector.ui.feedback

import de.noelkoeppen.fossilcollector.core.number.GameNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests fuer die reinen Funktionen aus [NumberTween] und [FeedbackTexts] (AP-25). */
class FeedbackLogicTest {

    @Test
    fun `maessiger Anstieg wird animiert`() {
        assertFalse(NumberTween.shouldSnap(100.0, 101.0))
        assertFalse(NumberTween.shouldSnap(100.0, 200.0))
    }

    @Test
    fun `Kauf, grosser Sprung und Startwert 0 springen sofort`() {
        assertTrue(NumberTween.shouldSnap(100.0, 99.0))
        assertTrue(NumberTween.shouldSnap(100.0, 200.1))
        assertTrue(NumberTween.shouldSnap(0.0, 1.0))
        assertTrue(NumberTween.shouldSnap(Double.NaN, 1.0))
        assertTrue(NumberTween.shouldSnap(1.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `Interpolation ist begrenzt und endet genau auf dem Ziel`() {
        assertEquals(100.0, NumberTween.interpolate(100.0, 200.0, 0f))
        assertEquals(150.0, NumberTween.interpolate(100.0, 200.0, 0.5f), 1e-9)
        assertEquals(200.0, NumberTween.interpolate(100.0, 200.0, 1f))
        assertEquals(200.0, NumberTween.interpolate(100.0, 200.0, 2f))
        assertEquals(100.0, NumberTween.interpolate(100.0, 200.0, -1f))
    }

    @Test
    fun `Nachfuehren vom angezeigten Wert laeuft bei steigendem Wert nur vorwaerts`() {
        // Simuliert 10 Ticks/s mit einer Animation von 250 ms: die Anzeige sinkt nie.
        var from = 100.0
        var to = 100.0
        var t = 1f
        var last = 100.0
        var target = 100.0
        repeat(50) {
            target += 1.0
            val shown = NumberTween.interpolate(from, to, t)
            assertFalse(NumberTween.shouldSnap(shown, target))
            from = shown
            to = target
            // Neue Animation ab dem angezeigten Wert, davon vergehen 100 ms bis zum naechsten Tick.
            t = 100f / NumberTween.DURATION_MILLIS
            val now = NumberTween.interpolate(from, to, t)
            assertTrue(now >= last)
            last = now
        }
    }

    @Test
    fun `Belohnung eines Meilensteins`() {
        val zero = GameNumber.ZERO
        val some = GameNumber(5.0)
        assertEquals(MilestoneRewardKind.NONE, FeedbackTexts.rewardKind(zero, zero))
        assertEquals(MilestoneRewardKind.MONEY, FeedbackTexts.rewardKind(some, zero))
        assertEquals(MilestoneRewardKind.RESEARCH, FeedbackTexts.rewardKind(zero, some))
        assertEquals(MilestoneRewardKind.BOTH, FeedbackTexts.rewardKind(some, some))
    }
}

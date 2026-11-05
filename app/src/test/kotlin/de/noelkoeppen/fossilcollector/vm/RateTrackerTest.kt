package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.number.GameNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RateTrackerTest {

    private fun GameNumber.d() = toDouble()

    @Test
    fun emptyTrackerIsZero() {
        assertEquals(0.0, RateTracker().perSecond().d())
    }

    @Test
    fun constantIncomeGivesRate() {
        val tracker = RateTracker(windowSeconds = 5.0)
        repeat(100) { tracker.record(GameNumber(0.5), 0.1) }
        assertEquals(5.0, tracker.perSecond().d(), 1e-9)
    }

    @Test
    fun oldSamplesLeaveTheWindow() {
        val tracker = RateTracker(windowSeconds = 1.0)
        repeat(10) { tracker.record(GameNumber(10.0), 0.1) }
        repeat(10) { tracker.record(GameNumber(1.0), 0.1) }
        assertEquals(10.0, tracker.perSecond().d(), 1e-9)
    }

    @Test
    fun ignoresInvalidSamples() {
        val tracker = RateTracker()
        tracker.record(GameNumber(1.0), 0.0)
        tracker.record(GameNumber(Double.NaN), 0.1)
        tracker.record(GameNumber(1.0), Double.POSITIVE_INFINITY)
        assertEquals(0.0, tracker.perSecond().d())
    }

    @Test
    fun resetClears() {
        val tracker = RateTracker()
        tracker.record(GameNumber(3.0), 0.1)
        tracker.reset()
        assertEquals(0.0, tracker.perSecond().d())
    }
}

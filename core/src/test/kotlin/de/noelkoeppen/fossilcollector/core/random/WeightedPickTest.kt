package de.noelkoeppen.fossilcollector.core.random

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WeightedPickTest {

    /** Liefert feste Werte fuer [nextDouble]. */
    private class FixedRandom(private val value: Double) : GameRandom {
        override fun nextDouble(): Double = value
        override fun nextDouble(from: Double, until: Double): Double = from + value * (until - from)
        override fun nextInt(bound: Int): Int = (value * bound).toInt()
    }

    @Test
    fun picksByCumulativeWeight() {
        val items = listOf("a" to 1.0, "b" to 3.0)
        assertEquals("a", FixedRandom(0.0).pickWeighted(items) { it.second }.first)
        assertEquals("a", FixedRandom(0.249).pickWeighted(items) { it.second }.first)
        assertEquals("b", FixedRandom(0.25).pickWeighted(items) { it.second }.first)
        assertEquals("b", FixedRandom(0.9999999).pickWeighted(items) { it.second }.first)
    }

    @Test
    fun distributionFollowsWeights() {
        val random = SeededRandom(1)
        val picks = List(10_000) { random.pickWeighted(listOf(1, 2, 7)) { it.toDouble() } }
        val counts = picks.groupingBy { it }.eachCount()
        assertEquals(0.1, counts.getValue(1) / 10_000.0, 0.02)
        assertEquals(0.2, counts.getValue(2) / 10_000.0, 0.02)
        assertEquals(0.7, counts.getValue(7) / 10_000.0, 0.02)
    }

    @Test
    fun rejectsEmptyListAndInvalidWeights() {
        val random = SeededRandom(1)
        assertThrows<IllegalArgumentException> { random.pickWeighted(emptyList<Int>()) { 1.0 } }
        assertThrows<IllegalArgumentException> { random.pickWeighted(listOf(1, 2)) { if (it == 1) 0.0 else 1.0 } }
        assertThrows<IllegalArgumentException> { random.pickWeighted(listOf(1)) { -1.0 } }
        assertThrows<IllegalArgumentException> { random.pickWeighted(listOf(1)) { Double.NaN } }
        assertThrows<IllegalArgumentException> { random.pickWeighted(listOf(1)) { Double.POSITIVE_INFINITY } }
    }
}

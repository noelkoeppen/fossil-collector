package de.noelkoeppen.fossilcollector.core.random

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SeededRandomTest {

    private fun sequence(rng: GameRandom): List<Double> =
        List(20) { i -> if (i % 2 == 0) rng.nextDouble() else rng.nextDouble(0.5, 2.0) }

    @Test
    fun `gleicher Seed ergibt dieselbe Folge`() {
        assertEquals(sequence(SeededRandom(42)), sequence(SeededRandom(42)))
        val a = SeededRandom(7)
        val b = SeededRandom(7)
        assertEquals(List(50) { a.nextInt(6) }, List(50) { b.nextInt(6) })
    }

    @Test
    fun `verschiedene Seeds ergeben verschiedene Folgen`() {
        assertNotEquals(sequence(SeededRandom(1)), sequence(SeededRandom(2)))
    }

    @Test
    fun `Werte liegen im Bereich`() {
        val rng = SeededRandom(123)
        repeat(10_000) {
            val d = rng.nextDouble()
            assertTrue(d >= 0.0 && d < 1.0)
            val r = rng.nextDouble(0.3, 1.0)
            assertTrue(r >= 0.3 && r < 1.0)
            val i = rng.nextInt(8)
            assertTrue(i in 0 until 8)
        }
    }

    @Test
    fun `nextInt trifft alle Werte`() {
        val rng = SeededRandom(99)
        val seen = (1..1000).map { rng.nextInt(4) }.toSet()
        assertEquals(setOf(0, 1, 2, 3), seen)
    }

    @Test
    fun `leerer Bereich gibt Untergrenze zurueck`() {
        assertEquals(1.0, SeededRandom(5).nextDouble(1.0, 1.0))
    }

    @Test
    fun `ungueltige Argumente werden abgelehnt`() {
        val rng = SeededRandom(5)
        assertThrows<IllegalArgumentException> { rng.nextDouble(2.0, 1.0) }
        assertThrows<IllegalArgumentException> { rng.nextDouble(0.0, Double.POSITIVE_INFINITY) }
        assertThrows<IllegalArgumentException> { rng.nextDouble(Double.NaN, 1.0) }
        assertThrows<IllegalArgumentException> { rng.nextInt(0) }
    }
}

package de.noelkoeppen.fossilcollector.core.engine.purchase

import de.noelkoeppen.fossilcollector.core.number.GameNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.pow

/** Tests fuer [CostCurve] gegen von Hand berechnete Werte (Kapitel 6.3). */
class CostCurveTest {

    private fun n(v: Double) = GameNumber(v)

    // Grabungsteam Quartaer: baseCost 10, growth 1.12 (Kapitel 6.4)
    private val team = CostCurve(10.0, 1.12)

    @Test
    fun `price des n-ten Kaufs`() {
        assertEquals(10.0, team.price(n(0.0)).toDouble(), EPS)
        assertEquals(11.2, team.price(n(1.0)).toDouble(), EPS)
        assertEquals(12.544, team.price(n(2.0)).toDouble(), EPS)
        // 10 * 1.12^10 = 31.0584820...
        assertEquals(31.05848208, team.price(n(10.0)).toDouble(), 1e-6)
    }

    @Test
    fun `cost fuer k Stueck ab Bestand n`() {
        // 10 * (1.12^10 - 1) / 0.12 = 175.4873507...
        assertEquals(175.4873507, team.cost(n(0.0), n(10.0)).toDouble(), 1e-6)
        // 10 + 11.2 + 12.544
        assertEquals(33.744, team.cost(n(0.0), n(3.0)).toDouble(), EPS)
        // ab Bestand 2: 12.544 + 14.04928
        assertEquals(26.59328, team.cost(n(2.0), n(2.0)).toDouble(), EPS)
        assertEquals(0.0, team.cost(n(5.0), n(0.0)).toDouble())
    }

    @Test
    fun `cost fuer ein Stueck ist exakt der Preis`() {
        for (i in 0 until 50) {
            val start = n(i.toDouble())
            assertEquals(team.price(start), team.cost(start, n(1.0)))
        }
        assertEquals(10.0, team.cost(n(0.0), n(1.0)).toDouble())
    }

    @Test
    fun `cost entspricht der Summe der Einzelpreise`() {
        val curve = CostCurve(25.0, 1.17)
        var sum = 0.0
        for (i in 3 until 3 + 40) sum += 25.0 * 1.17.pow(i)
        assertEquals(sum, curve.cost(n(3.0), n(40.0)).toDouble(), sum * 1e-12)
    }

    @Test
    fun `maxAffordable exakt an der Schwelle`() {
        val threshold = team.cost(n(0.0), n(10.0))
        assertEquals(n(10.0), team.maxAffordable(n(0.0), threshold))
        assertEquals(n(9.0), team.maxAffordable(n(0.0), n(Math.nextDown(threshold.toDouble()))))
        assertEquals(n(10.0), team.maxAffordable(n(0.0), n(Math.nextUp(threshold.toDouble()))))
        assertEquals(n(9.0), team.maxAffordable(n(0.0), n(175.48)))
        assertEquals(n(10.0), team.maxAffordable(n(0.0), n(175.49)))
    }

    @Test
    fun `maxAffordable mit wenig oder keinem Geld`() {
        assertEquals(n(0.0), team.maxAffordable(n(0.0), n(0.0)))
        assertEquals(n(0.0), team.maxAffordable(n(0.0), n(-5.0)))
        assertEquals(n(0.0), team.maxAffordable(n(0.0), n(9.99)))
        assertEquals(n(1.0), team.maxAffordable(n(0.0), n(10.0)))
        assertEquals(n(1.0), team.maxAffordable(n(0.0), n(21.19)))
        assertEquals(n(2.0), team.maxAffordable(n(0.0), n(21.21)))
    }

    @Test
    fun `maxAffordable ab hohem Bestand`() {
        // Bestand 5: Preis 10 * 1.12^5 = 17.6234...
        val p5 = team.price(n(5.0))
        assertEquals(n(0.0), team.maxAffordable(n(5.0), n(Math.nextDown(p5.toDouble()))))
        assertEquals(n(1.0), team.maxAffordable(n(5.0), p5))
    }

    @Test
    fun `maxAffordable mit Deckel`() {
        assertEquals(n(3.0), team.maxAffordable(n(0.0), n(1_000_000.0), cap = n(3.0)))
        assertEquals(n(0.0), team.maxAffordable(n(0.0), n(1_000_000.0), cap = n(0.0)))
        assertEquals(n(2.0), team.maxAffordable(n(0.0), n(21.21), cap = n(5.0)))
    }

    @Test
    fun `maxAffordable haelt die Invariante fuer viele Betraege`() {
        val curves = listOf(team, CostCurve(25.0, 1.17), CostCurve(50.0, 3.0), CostCurve(300.0, 4.0), CostCurve(3.0, 1.6))
        val amounts = listOf(1.0, 7.3, 49.99, 50.0, 123.456, 1e3, 98_765.4, 1e6, 3.3e9, 1e12, 7.77e14)
        for (curve in curves) {
            for (start in listOf(0.0, 1.0, 7.0, 33.0)) {
                for (m in amounts) {
                    val k = curve.maxAffordable(n(start), n(m))
                    assertTrue(curve.cost(n(start), k) <= n(m), "cost(n,k) <= M fuer $curve, $start, $m")
                    assertTrue(curve.cost(n(start), k + 1.0) > n(m), "cost(n,k+1) > M fuer $curve, $start, $m")
                }
            }
        }
    }

    @Test
    fun `ungueltige Eingaben werden abgelehnt`() {
        assertThrows<IllegalArgumentException> { CostCurve(10.0, 1.0) }
        assertThrows<IllegalArgumentException> { CostCurve(0.0, 1.12) }
        assertThrows<IllegalArgumentException> { team.maxAffordable(n(0.0), n(Double.POSITIVE_INFINITY)) }
        assertThrows<IllegalArgumentException> { team.maxAffordable(n(0.0), n(Double.NaN)) }
    }

    private companion object {
        const val EPS = 1e-9
    }
}

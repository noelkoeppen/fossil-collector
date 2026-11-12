package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.QualityTierDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Stufen-Grenzen aus `balance.qualityTiers`: 0.25 / 0.50 / 0.70 / 0.90. */
class QualityTiersTest {

    private val tiers = QualityTiers.fromGameData(DataRepository().load())

    private fun tier(q: Double) = tiers.tierOf(q).id

    @Test
    fun `Grenzen gehoeren zur hoeheren Stufe`() {
        assertEquals("fragment", tier(0.0))
        assertEquals("fragment", tier(Math.nextDown(0.25)))
        assertEquals("damaged", tier(0.25))
        assertEquals("damaged", tier(Math.nextDown(0.5)))
        assertEquals("good", tier(0.5))
        assertEquals("good", tier(Math.nextDown(0.7)))
        assertEquals("excellent", tier(0.7))
        assertEquals("excellent", tier(Math.nextDown(0.9)))
        assertEquals("pristine", tier(0.9))
        assertEquals("pristine", tier(1.0))
    }

    @Test
    fun `Werte ausserhalb von 0 bis 1 landen in der Rand-Stufe`() {
        assertEquals("fragment", tier(-0.5))
        assertEquals("fragment", tier(Double.NaN))
        assertEquals("pristine", tier(1.5))
    }

    @Test
    fun `Index entspricht der Reihenfolge der Stufen`() {
        assertEquals(0, tiers.indexOf(0.1))
        assertEquals(1, tiers.indexOf(0.3))
        assertEquals(2, tiers.indexOf(0.6))
        assertEquals(3, tiers.indexOf(0.8))
        assertEquals(4, tiers.indexOf(0.95))
    }

    @Test
    fun `unsortierte Stufen werden sortiert, leere Liste ist ungueltig`() {
        val custom = QualityTiers(
            listOf(
                QualityTierDefinition("high", 0.5, "#FFFFFF"),
                QualityTierDefinition("low", 0.0, "#000000"),
            ),
        )
        assertEquals("low", custom.tierOf(0.49).id)
        assertEquals("high", custom.tierOf(0.5).id)
        assertThrows<IllegalArgumentException> { QualityTiers(emptyList()) }
    }
}

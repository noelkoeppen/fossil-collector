package de.noelkoeppen.fossilcollector.core.modifier

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests fuer [StatId.isFraction] (Anzeige additiver Boni auf Anteile als Prozent). */
class StatIdTest {

    @Test
    fun `Qualitaet und Bruchschutz sind Anteile`() {
        val fractions = StatId.entries.filter { it.isFraction }.toSet()
        assertEquals(setOf(StatId.QUALITY_BASE_MIN, StatId.DAMAGE_REDUCTION), fractions)
    }

    @Test
    fun `jeder Stat mit Grenzen 0 bis 1 in den Daten ist ein Anteil`() {
        val limits = DataRepository().load().balance.statLimits
        for ((stat, limit) in limits) {
            if (limit.min == 0.0 && limit.max == 1.0) assertTrue(stat.isFraction, "$stat sollte isFraction sein")
        }
    }
}

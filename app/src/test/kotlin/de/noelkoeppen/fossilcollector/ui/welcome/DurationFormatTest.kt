package de.noelkoeppen.fossilcollector.ui.welcome

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Tests fuer [formatDuration] (AP-23). */
class DurationFormatTest {

    private val de = DurationUnits(days = "%1\$d T", hours = "%1\$d h", minutes = "%1\$d min", seconds = "%1\$d s")
    private val en = de.copy(days = "%1\$d d")

    @Test
    fun `zwei groesste Einheiten`() {
        assertEquals("2 h 15 min", formatDuration(2 * 3600.0 + 15 * 60 + 40, de))
        assertEquals("5 min 30 s", formatDuration(330.0, de))
        assertEquals("1 T 3 h", formatDuration(86_400.0 + 3 * 3600 + 59 * 60, de))
        assertEquals("1 d 3 h", formatDuration(86_400.0 + 3 * 3600, en))
    }

    @Test
    fun `zweite Einheit entfaellt bei 0`() {
        assertEquals("4 h", formatDuration(4 * 3600.0 + 30, de))
        assertEquals("1 min", formatDuration(60.0, de))
        assertEquals("2 T", formatDuration(2 * 86_400.0 + 59 * 60, de))
    }

    @Test
    fun `Sekunden, abgerundet und Randfaelle`() {
        assertEquals("45 s", formatDuration(45.9, de))
        assertEquals("0 s", formatDuration(0.4, de))
        assertEquals("0 s", formatDuration(-5.0, de))
        assertEquals("0 s", formatDuration(Double.NaN, de))
        assertEquals("0 s", formatDuration(Double.POSITIVE_INFINITY, de))
    }

    @Test
    fun `eigenes Trennzeichen`() {
        assertEquals("2 h, 15 min", formatDuration(2 * 3600.0 + 15 * 60, de.copy(separator = ", ")))
    }
}

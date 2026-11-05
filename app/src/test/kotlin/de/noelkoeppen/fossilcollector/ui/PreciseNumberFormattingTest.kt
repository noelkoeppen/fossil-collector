package de.noelkoeppen.fossilcollector.ui

import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.number.NumberLanguage
import de.noelkoeppen.fossilcollector.core.number.NumberNotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PreciseNumberFormattingTest {

    private val de = GameNumberFormat(NumberLanguage.DE, NumberNotation.SHORT)
    private val en = GameNumberFormat(NumberLanguage.EN, NumberNotation.SHORT)

    private fun GameNumberFormat.p(value: Double) = formatPrecise(GameNumber(value))

    @Test
    fun smallValuesKeepUpToTwoDecimals() {
        assertEquals("0,25", de.p(0.25))
        assertEquals("0.25", en.p(0.25))
        assertEquals("0,1", de.p(0.1))
        assertEquals("1,23", de.p(1.234))
        assertEquals("1,24", de.p(1.235))
        assertEquals("12", de.p(12.0))
        assertEquals("999,99", de.p(999.99))
    }

    @Test
    fun zeroAndNegative() {
        assertEquals("0", de.p(0.0))
        assertEquals("0", de.p(-0.001))
        assertEquals("-0,5", de.p(-0.5))
    }

    @Test
    fun fromThousandUsesNumberFormatter() {
        assertEquals("1 K", de.p(999.996))
        assertEquals("1,5 K", de.p(1_500.0))
        assertEquals("2.3M", en.p(2_300_000.0))
        assertEquals("∞", de.p(Double.POSITIVE_INFINITY))
    }

    @Test
    fun contentLanguageFollowsNumberLanguage() {
        assertEquals("de", resolveContentLanguage(LanguageSetting.SYSTEM, "de"))
        assertEquals("en", resolveContentLanguage(LanguageSetting.SYSTEM, "fr"))
        assertEquals("en", resolveContentLanguage(LanguageSetting.EN, "de"))
        assertEquals("de", resolveContentLanguage(LanguageSetting.DE, "en"))
    }
}

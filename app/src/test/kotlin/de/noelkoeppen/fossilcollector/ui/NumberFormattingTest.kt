package de.noelkoeppen.fossilcollector.ui

import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.number.NumberLanguage
import de.noelkoeppen.fossilcollector.core.number.NumberNotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NumberFormattingTest {

    @Test
    fun systemSettingFollowsConfiguration() {
        assertEquals(NumberLanguage.DE, resolveNumberLanguage(LanguageSetting.SYSTEM, "de"))
        assertEquals(NumberLanguage.EN, resolveNumberLanguage(LanguageSetting.SYSTEM, "en"))
        assertEquals(NumberLanguage.EN, resolveNumberLanguage(LanguageSetting.SYSTEM, "fr"))
        assertEquals(NumberLanguage.EN, resolveNumberLanguage(LanguageSetting.SYSTEM, ""))
    }

    @Test
    fun explicitSettingWins() {
        assertEquals(NumberLanguage.DE, resolveNumberLanguage(LanguageSetting.DE, "en"))
        assertEquals(NumberLanguage.EN, resolveNumberLanguage(LanguageSetting.EN, "de"))
    }

    @Test
    fun formatUsesCoreFormatter() {
        assertEquals("1,5 K", GameNumberFormat(NumberLanguage.DE, NumberNotation.SHORT).format(GameNumber(1500.0)))
        assertEquals("1.5K", GameNumberFormat(NumberLanguage.EN, NumberNotation.SHORT).format(GameNumber(1500.0)))
    }
}

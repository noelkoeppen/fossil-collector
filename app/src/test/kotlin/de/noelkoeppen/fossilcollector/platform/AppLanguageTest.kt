package de.noelkoeppen.fossilcollector.platform

import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.ui.resolveContentLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests fuer die Sprachaufloesung der UI (AP-24). */
class AppLanguageTest {

    private val systems = listOf("de", "en", "fr", "DE", "")

    @Test
    fun `feste Sprache gilt unabhaengig vom System`() {
        for (system in systems) {
            assertEquals("de", AppLocale.uiLanguageTag(LanguageSetting.DE, system))
            assertEquals("en", AppLocale.uiLanguageTag(LanguageSetting.EN, system))
        }
    }

    @Test
    fun `System waehlt Deutsch nur fuer deutsches System, sonst Englisch`() {
        assertEquals("de", AppLocale.uiLanguageTag(LanguageSetting.SYSTEM, "de"))
        assertEquals("de", AppLocale.uiLanguageTag(LanguageSetting.SYSTEM, "DE"))
        assertEquals("en", AppLocale.uiLanguageTag(LanguageSetting.SYSTEM, "en"))
        assertEquals("en", AppLocale.uiLanguageTag(LanguageSetting.SYSTEM, "fr"))
        assertEquals("en", AppLocale.uiLanguageTag(LanguageSetting.SYSTEM, ""))
    }

    @Test
    fun `UI-Texte und Inhaltstexte haben immer dieselbe Sprache`() {
        for (setting in LanguageSetting.entries) {
            for (system in systems) {
                val ui = AppLocale.uiLanguageTag(setting, system)
                assertEquals(resolveContentLanguage(setting, system), ui)
                // Innerhalb der App ist die Konfiguration auf `ui` gesetzt; auch dann bleibt alles gleich.
                assertEquals(ui, resolveContentLanguage(setting, ui))
            }
        }
    }

    @Test
    fun `Neuaufbau nur bei geaenderter UI-Sprache`() {
        assertTrue(AppLocale.needsRecreate(LanguageSetting.SYSTEM, LanguageSetting.EN, "de"))
        assertTrue(AppLocale.needsRecreate(LanguageSetting.DE, LanguageSetting.EN, "de"))
        assertTrue(AppLocale.needsRecreate(LanguageSetting.EN, LanguageSetting.SYSTEM, "de"))
        assertFalse(AppLocale.needsRecreate(LanguageSetting.SYSTEM, LanguageSetting.DE, "de"))
        assertFalse(AppLocale.needsRecreate(LanguageSetting.SYSTEM, LanguageSetting.EN, "fr"))
        assertFalse(AppLocale.needsRecreate(LanguageSetting.EN, LanguageSetting.EN, "de"))
    }

    @Test
    fun `gespiegelte Einstellung wird tolerant gelesen`() {
        assertEquals(LanguageSetting.SYSTEM, AppLanguageStore.parse(null))
        assertEquals(LanguageSetting.SYSTEM, AppLanguageStore.parse("kaputt"))
        for (setting in LanguageSetting.entries) assertEquals(setting, AppLanguageStore.parse(setting.name))
    }
}

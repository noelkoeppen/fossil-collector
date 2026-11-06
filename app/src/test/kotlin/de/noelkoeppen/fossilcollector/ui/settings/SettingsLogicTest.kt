package de.noelkoeppen.fossilcollector.ui.settings

import de.noelkoeppen.fossilcollector.vm.ImportError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests fuer [SettingsLogic] (AP-24). */
class SettingsLogicTest {

    @Test
    fun `Bestaetigungswort ohne Ruecksicht auf Gross-Kleinschreibung und Rand`() {
        assertTrue(SettingsLogic.deleteWordMatches("LÖSCHEN", "LÖSCHEN"))
        assertTrue(SettingsLogic.deleteWordMatches("  löschen ", "LÖSCHEN"))
        assertTrue(SettingsLogic.deleteWordMatches("delete", "DELETE"))
    }

    @Test
    fun `falsches oder leeres Wort bestaetigt nicht`() {
        assertFalse(SettingsLogic.deleteWordMatches("", "LÖSCHEN"))
        assertFalse(SettingsLogic.deleteWordMatches("LOESCHEN", "LÖSCHEN"))
        assertFalse(SettingsLogic.deleteWordMatches("LÖSCH", "LÖSCHEN"))
        assertFalse(SettingsLogic.deleteWordMatches("", ""))
    }

    @Test
    fun `jeder Importfehler hat einen eigenen Text`() {
        val texts = ImportError.entries.map(SettingsLogic::importErrorText)
        assertEquals(ImportError.entries.size, texts.toSet().size)
    }
}

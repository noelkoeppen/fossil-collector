package de.noelkoeppen.fossilcollector.core.save

import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.model.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Speichern -> laden -> identisch; veraltete Spielstaende werden verworfen. */
class SaveRoundTripTest {

    private val codec = SaveCodec.DEFAULT

    @Test
    fun richStateRoundTrip() {
        val state = SaveFixtures.rich
        val text = codec.encode(state)
        val loaded = codec.decode(text)
        assertEquals(state, loaded)
        // Erneutes Speichern ergibt denselben Text (stabile Ausgabe).
        assertEquals(text, codec.encode(loaded))
    }

    @Test
    fun newGameRoundTrip() {
        val state = GameState.newGame(now = 42L)
        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun encodedStateContainsAllFields() {
        val tree = Json.parseToJsonElement(codec.encode(SaveFixtures.fullV3)).jsonObject
        assertEquals(setOf("saveVersion", "lastSavedAt", "settings", "inventory"), tree.keys)
        assertEquals(JsonPrimitive(3), tree.getValue("saveVersion"))
        // encodeDefaults: auch Standardwerte werden geschrieben, age/sex als null.
        val piece = tree.getValue("inventory").jsonArray.first().jsonObject
        assertEquals(setOf("speciesId", "boneId", "quality", "age", "sex"), piece.keys)
        assertEquals(JsonNull, piece.getValue("age"))
        assertEquals(JsonNull, piece.getValue("sex"))
        val defaults = Json.parseToJsonElement(codec.encode(GameState.newGame(now = 0L))).jsonObject
        assertEquals(JsonPrimitive("system"), defaults.getValue("settings").jsonObject.getValue("language"))
        assertTrue(defaults.getValue("inventory").jsonArray.isEmpty())
    }

    @Test
    fun fullV3FixtureLoadsAsExpected() {
        val loaded = codec.decode(SaveFixtures.read("v3_full.json"))
        assertEquals(SaveFixtures.fullV3, loaded)
        assertEquals(loaded, codec.decode(codec.encode(loaded)))
    }

    @Test
    fun minimalV3FixtureUsesDefaults() {
        val loaded = codec.decode(SaveFixtures.read("v3_minimal.json"))
        assertEquals(Settings(), loaded.settings)
        assertEquals(0L, loaded.lastSavedAt)
        assertTrue(loaded.inventory.isEmpty())
    }

    @Test
    fun legacyIdleSaveIsDiscardedButKeepsLanguage() {
        // Incremental-Spielstand (Version 1): wird nicht geladen; die lesbaren Einstellungen werden
        // mitgeliefert, das fruehere Feld numberFormat wird ignoriert.
        val error = assertThrows<SaveException.ObsoleteVersion> { codec.decode(SaveFixtures.read("legacy/v1_idle.json")) }
        assertEquals(1, error.foundVersion)
        assertEquals(GameState.OLDEST_SUPPORTED_SAVE_VERSION, error.oldestSupportedVersion)
        assertEquals(Settings(LanguageSetting.SYSTEM), error.legacySettings)
    }

    @Test
    fun legacySaveAfterAp202IsDiscardedButKeepsLanguage() {
        val error = assertThrows<SaveException.ObsoleteVersion> { codec.decode(SaveFixtures.read("legacy/v1_settings_en.json")) }
        assertEquals(Settings(LanguageSetting.EN), error.legacySettings)
    }

    @Test
    fun legacySaveWithUnreadableSettingsIsDiscardedWithoutSettings() {
        val broken = assertThrows<SaveException.ObsoleteVersion> { codec.decode(SaveFixtures.read("legacy/v1_broken_settings.json")) }
        assertNull(broken.legacySettings)
        val missing = assertThrows<SaveException.ObsoleteVersion> { codec.decode("""{ "saveVersion": 2, "settings": 5 }""") }
        assertEquals(2, missing.foundVersion)
        assertNull(missing.legacySettings)
    }

    @Test
    fun legacySaveViaImportIsObsolete() {
        val base64 = java.util.Base64.getEncoder().encodeToString(SaveFixtures.read("legacy/v1_idle.json").toByteArray())
        assertThrows<SaveException.ObsoleteVersion> { codec.importBase64(base64) }
    }

    @Test
    fun unknownKeysAreIgnored() {
        val loaded = codec.decode(SaveFixtures.read("v3_unknown_keys.json"))
        assertEquals(5L, loaded.lastSavedAt)
        assertEquals(LanguageSetting.EN, loaded.settings.language)
        val saved = codec.encode(loaded)
        assertFalse(saved.contains("futureTopLevel"))
        assertFalse(saved.contains("futureSetting"))
    }

    @Test
    fun base64RoundTrip() {
        val state = SaveFixtures.rich
        val exported = codec.exportBase64(state)
        assertTrue(exported.matches(Regex("[A-Za-z0-9+/=]+")))
        assertEquals(state, codec.importBase64(exported))
    }

    @Test
    fun base64ImportToleratesWhitespace() {
        val exported = codec.exportBase64(SaveFixtures.fullV3)
        val wrapped = "  " + exported.chunked(20).joinToString("\r\n") + "\n\t"
        assertEquals(SaveFixtures.fullV3, codec.importBase64(wrapped))
    }
}

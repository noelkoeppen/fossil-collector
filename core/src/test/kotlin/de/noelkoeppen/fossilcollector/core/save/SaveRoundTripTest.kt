package de.noelkoeppen.fossilcollector.core.save

import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.model.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        assertEquals(setOf("saveVersion", "lastSavedAt", "settings", "inventory", "excavation"), tree.keys)
        assertEquals(JsonPrimitive(GameState.CURRENT_SAVE_VERSION), tree.getValue("saveVersion"))
        assertEquals(JsonNull, tree.getValue("excavation"))
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

    // --- Laufende Ausgrabung (AP2-05) ---

    @Test
    fun runningExcavationRoundTrip() {
        val state = SaveFixtures.withExcavation()
        val site = checkNotNull(state.excavation)
        assertTrue(site.finds.isNotEmpty())
        val text = codec.encode(state)
        val loaded = codec.decode(text)
        assertEquals(state, loaded)
        assertEquals(text, codec.encode(loaded))
        assertEquals(state, codec.importBase64(codec.exportBase64(state)))
        // Gegrabene Tiefen und Schaden bleiben erhalten.
        val loadedSite = checkNotNull(loaded.excavation)
        assertTrue(site.depthsCopy().contentEquals(loadedSite.depthsCopy()))
        assertEquals(site.finds.map { it.find }, loadedSite.finds.map { it.find })
    }

    @Test
    fun runningExcavationIsStoredCompactly() {
        val text = codec.encode(SaveFixtures.withExcavation())
        val site = Json.parseToJsonElement(text).jsonObject.getValue("excavation").jsonObject
        assertEquals(setOf("layerId", "width", "height", "depths", "finds"), site.keys)
        assertTrue(site.getValue("depths") is JsonPrimitive, "Tiefen als kompakter Text")
        // 32x48 Zellen: 4 KB Base64 fuer die Tiefen plus Funde; deutlich unter einer JSON-Zahlenliste.
        assertTrue(text.length < 8_000, "Spielstand mit Ausgrabung ist ${text.length} Zeichen lang")
    }

    @Test
    fun invalidExcavationIsRejected() {
        val base = codec.encode(SaveFixtures.withExcavation())
        val site = checkNotNull(SaveFixtures.withExcavation().excavation)
        val depths = Json.parseToJsonElement(base).jsonObject.getValue("excavation").jsonObject.getValue("depths").jsonPrimitive.content
        assertThrows<SaveException.InvalidContent> { codec.decode(base.replace(depths, "###")) }
        assertThrows<SaveException.InvalidContent> { codec.decode(base.replace(depths, depths.drop(4))) }
        assertThrows<SaveException.InvalidContent> { codec.decode(base.replace("\"width\":${site.width}", "\"width\":${site.width + 1}")) }
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

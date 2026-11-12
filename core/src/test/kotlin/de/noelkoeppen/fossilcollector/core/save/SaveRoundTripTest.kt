package de.noelkoeppen.fossilcollector.core.save

import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.model.Settings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Speichern -> laden -> identisch. */
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
        val tree = Json.parseToJsonElement(codec.encode(SaveFixtures.fullV1)).jsonObject
        assertEquals(setOf("saveVersion", "lastSavedAt", "settings"), tree.keys)
        assertEquals(JsonPrimitive(1), tree.getValue("saveVersion"))
        // encodeDefaults: auch Standardwerte werden geschrieben.
        val defaults = Json.parseToJsonElement(codec.encode(GameState.newGame(now = 0L))).jsonObject
        assertEquals(JsonPrimitive("system"), defaults.getValue("settings").jsonObject.getValue("language"))
    }

    @Test
    fun fullV1FixtureLoadsAsExpected() {
        val loaded = codec.decode(SaveFixtures.read("v1_full.json"))
        assertEquals(SaveFixtures.fullV1, loaded)
        assertEquals(loaded, codec.decode(codec.encode(loaded)))
    }

    @Test
    fun minimalV1FixtureUsesDefaults() {
        val loaded = codec.decode(SaveFixtures.read("v1_minimal.json"))
        assertEquals(Settings(), loaded.settings)
        assertEquals(0L, loaded.lastSavedAt)
    }

    @Test
    fun legacyIdleSaveKeepsSettingsAndDropsRemovedSystems() {
        // Spielstaende vor AP2-02 enthalten noch `meta` und `run` der entfernten Systeme. Sie laden
        // weiter (gleiche saveVersion); uebernommen werden nur Zeitstempel und Einstellungen.
        val text = SaveFixtures.read("legacy/v1_idle.json")
        val loaded = codec.decode(text)
        assertEquals(1762351200000L, loaded.lastSavedAt)
        assertEquals(Settings(LanguageSetting.SYSTEM), loaded.settings)
        val saved = Json.parseToJsonElement(codec.encode(loaded)).jsonObject
        assertEquals(setOf("saveVersion", "lastSavedAt", "settings"), saved.keys)
        assertEquals(setOf("language"), saved.getValue("settings").jsonObject.keys)
    }

    @Test
    fun unknownKeysAreIgnored() {
        val loaded = codec.decode(SaveFixtures.read("v1_unknown_keys.json"))
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
        val exported = codec.exportBase64(SaveFixtures.fullV1)
        val wrapped = "  " + exported.chunked(20).joinToString("\r\n") + "\n\t"
        assertEquals(SaveFixtures.fullV1, codec.importBase64(wrapped))
    }
}

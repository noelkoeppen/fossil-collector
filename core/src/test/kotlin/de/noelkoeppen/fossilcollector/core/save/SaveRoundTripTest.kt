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
        assertEquals(setOf("layerId", "width", "height", "depths", "finds", "preparations", "recoveries"), site.keys)
        assertTrue(site.getValue("depths") is JsonPrimitive, "Tiefen als kompakter Text")
        // 32x48 Zellen: 4 KB Base64 fuer die Tiefen plus Funde; deutlich unter einer JSON-Zahlenliste.
        assertTrue(text.length < 8_000, "Spielstand mit Ausgrabung ist ${text.length} Zeichen lang")
    }

    // --- Feines Freilegen (AP2-08) ---

    @Test
    fun runningPreparationRoundTrip() {
        val state = SaveFixtures.withPreparation()
        val prep = checkNotNull(state.excavation).preparations.single()
        assertTrue(prep.progress > 0.0 && prep.progress < 1.0, "teilweise freigelegt: ${prep.progress}")
        assertTrue((0 until prep.cellCount).any { prep.isHard(it) }, "harte Stellen vorhanden")
        val text = codec.encode(state)
        val loaded = codec.decode(text)
        assertEquals(state, loaded)
        assertEquals(text, codec.encode(loaded))
        val loadedPrep = checkNotNull(loaded.excavation).preparations.single()
        assertTrue(prep.depthsCopy().contentEquals(loadedPrep.depthsCopy()))
        assertTrue(prep.hardCopy().contentEquals(loadedPrep.hardCopy()))
        assertTrue(prep.boneCopy().contentEquals(loadedPrep.boneCopy()))
        assertEquals(prep.progress, loadedPrep.progress)
        // Qualitaet nach dem Kratzer-Treffer bleibt im Fund.
        assertEquals(checkNotNull(state.excavation).piece(prep.ref).quality, checkNotNull(loaded.excavation).piece(prep.ref).quality)
    }

    @Test
    fun runningPreparationIsStoredCompactly() {
        val state = SaveFixtures.withPreparation()
        val prep = checkNotNull(state.excavation).preparations.single()
        val text = codec.encode(state)
        val stored = Json.parseToJsonElement(text).jsonObject.getValue("excavation").jsonObject
            .getValue("preparations").jsonArray.single().jsonObject
        assertEquals(
            setOf("findIndex", "boneId", "originX", "originY", "subdivision", "width", "height", "residue", "bone", "finished"),
            stored.keys,
        )
        // Ein Byte je feiner Zelle (Base64: 4 Zeichen je 3 Bytes), ein Bit je Zelle fuer den Knochen.
        val residue = stored.getValue("residue").jsonPrimitive.content
        assertTrue(residue.length <= (prep.cellCount + 2) / 3 * 4, "Restschicht ${residue.length} Zeichen")
        val bone = stored.getValue("bone").jsonPrimitive.content
        assertTrue(bone.length <= ((prep.cellCount + 7) / 8 + 2) / 3 * 4, "Knochenzellen ${bone.length} Zeichen")
    }

    @Test
    fun invalidPreparationIsRejected() {
        val state = SaveFixtures.withPreparation()
        val base = codec.encode(state)
        val stored = Json.parseToJsonElement(base).jsonObject.getValue("excavation").jsonObject
            .getValue("preparations").jsonArray.single().jsonObject
        val residue = stored.getValue("residue").jsonPrimitive.content
        val bone = stored.getValue("bone").jsonPrimitive.content
        assertThrows<SaveException.InvalidContent> { codec.decode(base.replace(residue, "###")) }
        assertThrows<SaveException.InvalidContent> { codec.decode(base.replace(residue, residue.drop(4))) }
        assertThrows<SaveException.InvalidContent> { codec.decode(base.replace("\"bone\":\"$bone\"", "\"bone\":\"\"")) }
        val boneId = stored.getValue("boneId").jsonPrimitive.content
        assertThrows<SaveException.InvalidContent> { codec.decode(base.replace("\"boneId\":\"$boneId\"", "\"boneId\":\"unknown\"")) }
    }

    // --- Bergen (AP2-09) ---

    @Test
    fun runningRecoveryRoundTrip() {
        val state = SaveFixtures.withRecovery()
        val site = checkNotNull(state.excavation)
        assertEquals(2, site.recoveries.size)
        assertTrue(site.recoveries[0].recovered, "erster Knochen geborgen")
        assertTrue(site.recoveries[1].securedCount == 1 && !site.recoveries[1].recovered, "zweiter Knochen teilweise gesichert")
        assertEquals(SaveFixtures.rich.inventory.size + 1, state.inventory.size, "geborgener Knochen im Inventar")
        val text = codec.encode(state)
        val loaded = codec.decode(text)
        assertEquals(state, loaded)
        assertEquals(text, codec.encode(loaded))
        assertEquals(state, codec.importBase64(codec.exportBase64(state)))
        val stored = Json.parseToJsonElement(text).jsonObject.getValue("excavation").jsonObject
            .getValue("recoveries").jsonArray.first().jsonObject
        assertEquals(setOf("findIndex", "boneId", "spots", "secured", "recovered"), stored.keys)
    }

    @Test
    fun invalidRecoveryIsRejected() {
        val state = SaveFixtures.withRecovery()
        val base = codec.encode(state)
        val rec = checkNotNull(state.excavation).recoveries[1]
        val spots = rec.spots.joinToString(",")
        val secured = rec.secured.joinToString(",")
        // Stelle ausserhalb des Knochens, Laengen passen nicht zusammen.
        assertThrows<SaveException.InvalidContent> { codec.decode(base.replace("\"spots\":[$spots]", "\"spots\":[-1${",0".repeat(rec.spots.size - 1)}]")) }
        assertThrows<SaveException.InvalidContent> { codec.decode(base.replace("\"secured\":[$secured]", "\"secured\":[]")) }
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

package de.noelkoeppen.fossilcollector.core.save

import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SaveMigratorTest {

    /** Testschritt: haengt die eigene Version an das Feld `trace` an. */
    private class TraceStep(override val fromVersion: Int) : SaveMigration {
        override fun migrate(save: JsonObject): JsonObject {
            val trace = save["trace"]?.jsonArray ?: JsonArray(emptyList())
            return JsonObject(save + ("trace" to JsonArray(trace + JsonPrimitive(fromVersion))))
        }
    }

    /**
     * Test-Migration v1 -> v2 fuer `migration/renamed_field_v1.json`: In der gedachten
     * Version 1 hiess `lastSavedAt` noch `savedAt`.
     */
    private object RenameSavedAtStep : SaveMigration {
        override val fromVersion: Int = 1

        override fun migrate(save: JsonObject): JsonObject {
            val savedAt = save.getValue("savedAt")
            return JsonObject(save - "savedAt" + ("lastSavedAt" to savedAt))
        }
    }

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun currentVersionIsOneAndChainIsEmpty() {
        assertEquals(1, GameState.CURRENT_SAVE_VERSION)
        assertEquals(GameState.CURRENT_SAVE_VERSION, SaveMigrator.DEFAULT.currentVersion)
        assertTrue(SaveMigrator.MIGRATIONS.isEmpty())
    }

    @Test
    fun currentVersionIsReturnedUnchanged() {
        val save = obj("""{ "saveVersion": 1, "x": 5 }""")
        assertSame(save, SaveMigrator.DEFAULT.migrate(save))
    }

    @Test
    fun chainRunsInOrderAndSetsVersion() {
        val migrator = SaveMigrator(listOf(TraceStep(3), TraceStep(1), TraceStep(2)), currentVersion = 4)
        val fromOne = migrator.migrate(obj("""{ "saveVersion": 1 }"""))
        assertEquals(JsonPrimitive(4), fromOne.getValue("saveVersion"))
        assertEquals(listOf(1, 2, 3), fromOne.getValue("trace").jsonArray.map { it.jsonPrimitive.content.toInt() })

        val fromThree = migrator.migrate(obj("""{ "saveVersion": 3 }"""))
        assertEquals(JsonPrimitive(4), fromThree.getValue("saveVersion"))
        assertEquals(listOf(3), fromThree.getValue("trace").jsonArray.map { it.jsonPrimitive.content.toInt() })
    }

    @Test
    fun incompleteOrDuplicateChainIsRejected() {
        assertThrows<IllegalArgumentException> { SaveMigrator(listOf(TraceStep(1)), currentVersion = 3) }
        assertThrows<IllegalArgumentException> { SaveMigrator(listOf(TraceStep(1), TraceStep(1)), currentVersion = 2) }
        assertThrows<IllegalArgumentException> { SaveMigrator(listOf(TraceStep(2)), currentVersion = 2) }
        assertThrows<IllegalArgumentException> { SaveMigrator(emptyList(), currentVersion = 0) }
    }

    @Test
    fun tooNewVersionIsRejected() {
        val error = assertThrows<SaveException.UnsupportedVersion> {
            SaveMigrator.DEFAULT.migrate(obj("""{ "saveVersion": 2 }"""))
        }
        assertEquals(2, error.foundVersion)
        assertEquals(1, error.supportedVersion)
        assertTrue(error.isTooNew)
    }

    @Test
    fun versionBelowOneIsRejected() {
        val error = assertThrows<SaveException.UnsupportedVersion> {
            SaveMigrator.DEFAULT.migrate(obj("""{ "saveVersion": 0 }"""))
        }
        assertEquals(false, error.isTooNew)
    }

    @Test
    fun missingOrInvalidVersionIsRejected() {
        for (text in listOf("""{}""", """{ "saveVersion": "1" }""", """{ "saveVersion": 1.5 }""", """{ "saveVersion": null }""", """{ "saveVersion": [1] }""")) {
            assertThrows<SaveException.MissingVersion>(text) { SaveMigrator.DEFAULT.migrate(obj(text)) }
        }
    }

    @Test
    fun failingStepIsReported() {
        val failing = object : SaveMigration {
            override val fromVersion = 1
            override fun migrate(save: JsonObject): JsonObject = error("kaputt")
        }
        val error = assertThrows<SaveException.MigrationFailed> {
            SaveMigrator(listOf(failing), currentVersion = 2).migrate(obj("""{ "saveVersion": 1 }"""))
        }
        assertEquals(1, error.fromVersion)
        assertTrue(error.cause is IllegalStateException)
    }

    @Test
    fun codecMigratesFixtureWithTestMigration() {
        val codec = SaveCodec(SaveMigrator(listOf(RenameSavedAtStep), currentVersion = 2))
        val loaded = codec.decode(SaveFixtures.read("migration/renamed_field_v1.json"))
        assertEquals(2, loaded.saveVersion)
        assertEquals(1762351200000L, loaded.lastSavedAt)
        assertEquals(LanguageSetting.DE, loaded.settings.language)
    }

    @Test
    fun codecWithoutMigrationRejectsOldStructure() {
        assertThrows<SaveException.InvalidContent> {
            SaveCodec.DEFAULT.decode(SaveFixtures.read("migration/renamed_field_v1.json"))
        }
    }
}

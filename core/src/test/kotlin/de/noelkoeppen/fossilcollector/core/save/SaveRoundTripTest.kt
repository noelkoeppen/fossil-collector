package de.noelkoeppen.fossilcollector.core.save

import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.model.MetaState
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.RunStartValues
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.number.NumberNotation
import de.noelkoeppen.fossilcollector.core.save.SaveFixtures.n
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Kapitel 10.4: speichern -> laden -> identisch. */
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
        val start = RunStartValues(
            money = GameNumber.ZERO,
            researchPoints = GameNumber.ZERO,
            teamsInFirstLayer = GameNumber.ZERO,
            preparators = GameNumber.ZERO,
            halls = GameNumber.ONE,
            labSlots = GameNumber.ONE,
            toolLevel = GameNumber.ZERO,
        )
        val state = GameState.newGame("quaternary", start, now = 42L)
        assertEquals(state, codec.decode(codec.encode(state)))
    }

    @Test
    fun pieceKeysWithAndWithoutAgeAndSexSurvive() {
        val loaded = codec.decode(codec.encode(SaveFixtures.rich))
        val bookKeys = loaded.meta.book.keys
        assertTrue(PieceKey("parasaurolophus", "dorsal", null, null) in bookKeys)
        assertTrue(PieceKey("cave_bear", "skull", "adult", "female") in bookKeys)
        assertTrue(PieceKey("t_rex", "skull", null, "male") in bookKeys)
        assertTrue(PieceKey("t_rex", "jaw", "subadult", null) in bookKeys)

        val jaw = loaded.run.pieces.keys.single { it.boneId == "jaw" }
        assertEquals("juvenile", jaw.age)
        assertNull(jaw.sex)

        val spareKeys = loaded.run.spareZones.keys.map { it.toString() }.toSet()
        assertEquals(
            setOf("cave_bear:skull:_:_:snout", "cave_bear:skull:adult:male:occiput", "t_rex:skull:_:female:snout"),
            spareKeys,
        )
    }

    @Test
    fun mapsWithPieceKeysAreStoredWithStringKeys() {
        val tree = Json.parseToJsonElement(codec.encode(SaveFixtures.fullV1)).jsonObject
        val meta = tree.getValue("meta").jsonObject
        val run = tree.getValue("run").jsonObject
        assertEquals(
            setOf("parasaurolophus:dorsal:_:_", "cave_bear:skull:adult:female"),
            meta.getValue("book").jsonObject.keys,
        )
        assertEquals(setOf("cave_bear:skull:_:_", "cave_bear:jaw:juvenile:_"), run.getValue("pieces").jsonObject.keys)
        assertEquals(
            setOf("cave_bear:skull:_:_:snout", "cave_bear:skull:adult:male:occiput"),
            run.getValue("spareZones").jsonObject.keys,
        )
        assertEquals(JsonPrimitive(1), tree.getValue("saveVersion"))
        // encodeDefaults: auch null-Felder und Standardwerte werden geschrieben.
        assertEquals(JsonNull, run.getValue("specialExhibit"))
        assertEquals(JsonPrimitive("short"), tree.getValue("settings").jsonObject.getValue("numberFormat"))
    }

    @Test
    fun fullV1FixtureLoadsAsExpected() {
        val loaded = codec.decode(SaveFixtures.read("v1_full.json"))
        assertEquals(SaveFixtures.fullV1, loaded)
        assertEquals(GameNumber.ZERO, loaded.run.prepProgress)
        assertEquals(loaded, codec.decode(codec.encode(loaded)))
    }

    @Test
    fun minimalV1FixtureUsesDefaults() {
        val loaded = codec.decode(SaveFixtures.read("v1_minimal.json"))
        assertEquals(MetaState(), loaded.meta)
        assertEquals(Settings(), loaded.settings)
        assertTrue(loaded.run.pieces.isEmpty())
        assertTrue(loaded.run.labJobs.isEmpty())
        assertNull(loaded.run.specialExhibit)
        assertEquals(n(1.0), loaded.run.halls)
        // AP-08: Feld ohne Eintrag im alten Spielstand -> Standardwert, keine Migration noetig.
        assertEquals(GameNumber.ZERO, loaded.run.prepProgress)
    }

    @Test
    fun prepProgressRoundTrip() {
        val state = SaveFixtures.fullV1.copy(run = SaveFixtures.fullV1.run.copy(prepProgress = n(0.375)))
        val text = codec.encode(state)
        assertEquals(JsonPrimitive(0.375), Json.parseToJsonElement(text).jsonObject.getValue("run").jsonObject.getValue("prepProgress"))
        assertEquals(state, codec.decode(text))
    }

    @Test
    fun unknownKeysAreIgnored() {
        val loaded = codec.decode(SaveFixtures.read("v1_unknown_keys.json"))
        assertEquals(n(1.0), loaded.meta.fame)
        assertEquals(n(10.0), loaded.run.money)
        assertEquals(LanguageSetting.EN, loaded.settings.language)
        assertEquals(NumberNotation.SCIENTIFIC, loaded.settings.numberFormat)
        assertFalse(codec.encode(loaded).contains("futureTopLevel"))
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
        val wrapped = "  " + exported.chunked(60).joinToString("\r\n") + "\n\t"
        assertEquals(SaveFixtures.fullV1, codec.importBase64(wrapped))
    }
}

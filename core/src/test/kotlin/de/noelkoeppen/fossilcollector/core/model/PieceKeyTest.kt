package de.noelkoeppen.fossilcollector.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PieceKeyTest {

    @Test
    fun textFormWritesUnderscoreForNull() {
        assertEquals("parasaurolophus:dorsal:_:_", PieceKey("parasaurolophus", "dorsal").toString())
        assertEquals("cave_bear:skull:adult:_", PieceKey("cave_bear", "skull", age = "adult").toString())
        assertEquals("cave_bear:skull:_:female", PieceKey("cave_bear", "skull", sex = "female").toString())
        assertEquals(
            "cave_bear:skull:juvenile:male",
            PieceKey("cave_bear", "skull", "juvenile", "male").toString(),
        )
    }

    @Test
    fun parseReadsUnderscoreAsNull() {
        val key = PieceKey.parse("parasaurolophus:dorsal:_:_")
        assertEquals("parasaurolophus", key.speciesId)
        assertEquals("dorsal", key.boneId)
        assertNull(key.age)
        assertNull(key.sex)
    }

    @Test
    fun parseKeepsAgeAndSex() {
        assertEquals(PieceKey("cave_bear", "skull", "juvenile", "male"), PieceKey.parse("cave_bear:skull:juvenile:male"))
        assertEquals(PieceKey("cave_bear", "skull", null, "male"), PieceKey.parse("cave_bear:skull:_:male"))
        assertEquals(PieceKey("cave_bear", "skull", "adult", null), PieceKey.parse("cave_bear:skull:adult:_"))
    }

    @Test
    fun roundTripForAllNullCombinations() {
        for (age in listOf(null, "adult")) {
            for (sex in listOf(null, "female")) {
                val key = PieceKey("woolly_mammoth", "hindlimb", age, sex)
                assertEquals(key, PieceKey.parse(key.toString()))
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "cave_bear",
            "cave_bear:skull",
            "cave_bear:skull:_",
            "cave_bear:skull:_:_:snout",
            "cave_bear::_:_",
            ":skull:_:_",
            "_:skull:_:_",
            "cave_bear:_:_:_",
            "cave_bear:skull::_",
        ],
    )
    fun parseRejectsInvalidText(text: String) {
        assertThrows<IllegalArgumentException> { PieceKey.parse(text) }
        assertNull(PieceKey.parseOrNull(text))
    }

    @Test
    fun constructorRejectsReservedCharacters() {
        assertThrows<IllegalArgumentException> { PieceKey("cave:bear", "skull") }
        assertThrows<IllegalArgumentException> { PieceKey("cave_bear", "") }
        assertThrows<IllegalArgumentException> { PieceKey("cave_bear", "skull", age = "_") }
        assertThrows<IllegalArgumentException> { PieceKey("cave_bear", "skull", sex = "a:b") }
    }

    @Test
    fun spareZoneKeyRoundTrip() {
        val plain = SpareZoneKey(PieceKey("cave_bear", "skull"), "snout")
        assertEquals("cave_bear:skull:_:_:snout", plain.toString())
        assertEquals(plain, SpareZoneKey.parse(plain.toString()))

        val full = SpareZoneKey(PieceKey("cave_bear", "skull", "adult", "male"), "occiput")
        assertEquals("cave_bear:skull:adult:male:occiput", full.toString())
        assertEquals(full, SpareZoneKey.parse(full.toString()))
    }

    @ParameterizedTest
    @ValueSource(strings = ["cave_bear:skull:_:_", "cave_bear:skull:_:_:", "cave_bear:skull:_:_:_", "a:b:c:d:e:f"])
    fun spareZoneKeyRejectsInvalidText(text: String) {
        assertThrows<IllegalArgumentException> { SpareZoneKey.parse(text) }
        assertNull(SpareZoneKey.parseOrNull(text))
    }

    @Serializable
    private data class KeyHolder(
        val single: PieceKey,
        val byKey: Map<PieceKey, Int>,
        val spares: Map<SpareZoneKey, Double>,
    )

    @Test
    fun keysAreSerializedAsStringsAlsoAsMapKeys() {
        val holder = KeyHolder(
            single = PieceKey("a", "b", "adult", null),
            byKey = mapOf(PieceKey("a", "b") to 1, PieceKey("a", "b", "juvenile", "female") to 2),
            spares = mapOf(SpareZoneKey(PieceKey("a", "b"), "z") to 0.5),
        )
        val text = Json.encodeToString(KeyHolder.serializer(), holder)
        val tree = Json.parseToJsonElement(text).jsonObject
        assertEquals("\"a:b:adult:_\"", tree["single"].toString())
        assertEquals(setOf("a:b:_:_", "a:b:juvenile:female"), tree["byKey"]!!.jsonObject.keys)
        assertEquals(setOf("a:b:_:_:z"), tree["spares"]!!.jsonObject.keys)
        assertEquals(holder, Json.decodeFromString(KeyHolder.serializer(), text))
    }

    @Test
    fun invalidKeyInJsonFailsAsSerializationError() {
        val error = assertThrows<IllegalArgumentException> {
            Json.decodeFromString(PieceKey.serializer(), "\"a:b\"")
        }
        assertTrue(error is kotlinx.serialization.SerializationException)
    }
}

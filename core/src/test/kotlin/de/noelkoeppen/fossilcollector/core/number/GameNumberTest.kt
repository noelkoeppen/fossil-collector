package de.noelkoeppen.fossilcollector.core.number

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GameNumberTest {

    private fun n(value: Double) = GameNumber(value)

    @Test
    fun arithmeticWithGameNumber() {
        assertEquals(n(5.0), n(2.0) + n(3.0))
        assertEquals(n(-1.0), n(2.0) - n(3.0))
        assertEquals(n(6.0), n(2.0) * n(3.0))
        assertEquals(n(1.5), n(3.0) / n(2.0))
        assertEquals(n(-2.0), -n(2.0))
    }

    @Test
    fun arithmeticWithDouble() {
        assertEquals(n(5.0), n(2.0) + 3.0)
        assertEquals(n(-1.0), n(2.0) - 3.0)
        assertEquals(n(6.0), n(2.0) * 3.0)
        assertEquals(n(1.5), n(3.0) / 2.0)
        assertEquals(n(5.0), 3.0 + n(2.0))
        assertEquals(n(1.0), 3.0 - n(2.0))
        assertEquals(n(6.0), 3.0 * n(2.0))
        assertEquals(n(1.5), 3.0 / n(2.0))
        assertEquals(n(2.5), 2.5.toGameNumber())
    }

    @Test
    fun comparison() {
        assertTrue(n(1.0) < n(2.0))
        assertTrue(n(2.0) > n(1.0))
        assertTrue(n(2.0) >= n(2.0))
        assertTrue(n(1.0) < 1.5)
        assertEquals(0, n(3.0).compareTo(n(3.0)))
        assertEquals(listOf(n(-1.0), n(0.0), n(5.0)), listOf(n(5.0), n(-1.0), n(0.0)).sorted())
    }

    @Test
    fun helpers() {
        assertEquals(n(0.0), GameNumber.ZERO)
        assertEquals(n(1.0), GameNumber.ONE)
        assertEquals(n(8.0), n(2.0).pow(3.0))
        assertEquals(n(9.0), n(3.0).pow(n(2.0)))
        assertEquals(n(2.0), n(2.9).floor())
        assertEquals(n(-3.0), n(-2.1).floor())
        assertEquals(n(4.0), n(-4.0).abs())
        assertEquals(n(1.0), GameNumber.min(n(1.0), n(2.0)))
        assertEquals(n(2.0), GameNumber.max(n(1.0), n(2.0)))
        assertEquals(1.25, n(1.25).toDouble())
    }

    @Test
    fun finiteCheck() {
        assertTrue(n(1e300).isFinite)
        assertFalse(n(Double.NaN).isFinite)
        assertFalse(n(Double.POSITIVE_INFINITY).isFinite)
        assertFalse(n(Double.NEGATIVE_INFINITY).isFinite)
        assertFalse((n(1e308) * 10.0).isFinite)
        assertFalse((GameNumber.ZERO / GameNumber.ZERO).isFinite)
        assertTrue((GameNumber.ZERO / GameNumber.ZERO).isNaN)
        assertFalse(GameNumber.ONE.isNaN)
    }

    @Serializable
    private data class Holder(val money: GameNumber, val rate: GameNumber)

    @Test
    fun serializesAsJsonNumber() {
        assertEquals("1500.0", Json.encodeToString(GameNumber.serializer(), n(1500.0)))
        assertEquals("""{"money":1.0E20,"rate":0.25}""", Json.encodeToString(Holder.serializer(), Holder(n(1e20), n(0.25))))
    }

    @Test
    fun serializationRoundTrip() {
        val values = listOf(n(0.0), n(-3.5), n(0.1), n(123456.789), n(1e15), n(1.7976931348623157e308), n(4.9e-324))
        val serializer = ListSerializer(GameNumber.serializer())
        val json = Json.encodeToString(serializer, values)
        assertEquals(values, Json.decodeFromString(serializer, json))

        val holder = Holder(n(9.87654321e12), n(1.0 / 3.0))
        assertEquals(holder, Json.decodeFromString(Holder.serializer(), Json.encodeToString(Holder.serializer(), holder)))
    }

    @Test
    fun deserializesIntegerJson() {
        assertEquals(n(42.0), Json.decodeFromString(GameNumber.serializer(), "42"))
    }

    @Test
    fun nonFiniteValuesAreRejectedByDefaultJson() {
        assertThrows<SerializationException> { Json.encodeToString(GameNumber.serializer(), n(Double.NaN)) }
        assertThrows<SerializationException> { Json.encodeToString(GameNumber.serializer(), n(Double.POSITIVE_INFINITY)) }
    }
}

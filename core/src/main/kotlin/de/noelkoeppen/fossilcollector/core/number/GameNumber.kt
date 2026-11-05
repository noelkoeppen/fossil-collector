package de.noelkoeppen.fossilcollector.core.number

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.floor as kFloor
import kotlin.math.pow as kPow

/**
 * Zahlentyp fuer alle Spielwerte (GAME_DESIGN.md, Kapitel 0 Regel 6 und Kapitel 6.6).
 *
 * Intern ein [Double]. Code ausserhalb dieser Datei soll nur die oeffentliche API verwenden,
 * damit ein spaeterer Austausch gegen Mantisse plus Exponent nur diese Klasse betrifft.
 *
 * Verhalten bei Sonderwerten: Die Arithmetik folgt IEEE 754, d. h. `NaN` und `Infinity`
 * werden nicht abgefangen, sondern weitergereicht. Erkannt werden sie ueber [isFinite]
 * (laut Kapitel 6.6 in `advance()`).
 *
 * Gleichheit: Als Value-Class gilt die Gleichheit von [Double.equals]. Dadurch ist
 * `NaN == NaN` wahr und `0.0 != -0.0`. Fuer Groessenvergleiche [compareTo] verwenden.
 *
 * Serialisierung: als JSON-Zahl (siehe [GameNumberSerializer]).
 */
@Serializable(with = GameNumberSerializer::class)
@JvmInline
value class GameNumber(val value: Double) : Comparable<GameNumber> {

    /** `true`, wenn der Wert weder `NaN` noch `+/-Infinity` ist. */
    val isFinite: Boolean get() = value.isFinite()

    /** `true`, wenn der Wert `NaN` ist. */
    val isNaN: Boolean get() = value.isNaN()

    operator fun plus(other: GameNumber): GameNumber = GameNumber(value + other.value)
    operator fun plus(other: Double): GameNumber = GameNumber(value + other)

    operator fun minus(other: GameNumber): GameNumber = GameNumber(value - other.value)
    operator fun minus(other: Double): GameNumber = GameNumber(value - other)

    operator fun times(other: GameNumber): GameNumber = GameNumber(value * other.value)
    operator fun times(other: Double): GameNumber = GameNumber(value * other)

    operator fun div(other: GameNumber): GameNumber = GameNumber(value / other.value)
    operator fun div(other: Double): GameNumber = GameNumber(value / other)

    operator fun unaryMinus(): GameNumber = GameNumber(-value)

    override operator fun compareTo(other: GameNumber): Int = value.compareTo(other.value)

    operator fun compareTo(other: Double): Int = value.compareTo(other)

    /** Potenz `this ^ exponent`. */
    fun pow(exponent: Double): GameNumber = GameNumber(value.kPow(exponent))

    /** Potenz `this ^ exponent`. */
    fun pow(exponent: GameNumber): GameNumber = GameNumber(value.kPow(exponent.value))

    /** Abrunden auf die naechste ganze Zahl (Richtung minus unendlich). */
    fun floor(): GameNumber = GameNumber(kFloor(value))

    /** Betrag. */
    fun abs(): GameNumber = GameNumber(kotlin.math.abs(value))

    /** Rohwert fuer Formeln, die `kotlin.math` benoetigen (z. B. `log` in Kapitel 6.3). */
    fun toDouble(): Double = value

    override fun toString(): String = value.toString()

    companion object {
        val ZERO: GameNumber = GameNumber(0.0)
        val ONE: GameNumber = GameNumber(1.0)

        /** Kleinerer der beiden Werte; ist einer `NaN`, ist das Ergebnis `NaN`. */
        fun min(a: GameNumber, b: GameNumber): GameNumber = GameNumber(kotlin.math.min(a.value, b.value))

        /** Groesserer der beiden Werte; ist einer `NaN`, ist das Ergebnis `NaN`. */
        fun max(a: GameNumber, b: GameNumber): GameNumber = GameNumber(kotlin.math.max(a.value, b.value))
    }
}

operator fun Double.plus(other: GameNumber): GameNumber = GameNumber(this + other.value)
operator fun Double.minus(other: GameNumber): GameNumber = GameNumber(this - other.value)
operator fun Double.times(other: GameNumber): GameNumber = GameNumber(this * other.value)
operator fun Double.div(other: GameNumber): GameNumber = GameNumber(this / other.value)

/** Wandelt einen [Double] in eine [GameNumber] um. */
fun Double.toGameNumber(): GameNumber = GameNumber(this)

/**
 * Serialisiert [GameNumber] als JSON-Zahl, z. B. `1500.0`.
 *
 * Nicht-endliche Werte werden vom Standard-`Json` beim Schreiben mit einer
 * `SerializationException` abgelehnt (es sei denn, `allowSpecialFloatingPointValues` ist gesetzt).
 * So landet ein `NaN` nie unbemerkt im Spielstand.
 */
object GameNumberSerializer : KSerializer<GameNumber> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("de.noelkoeppen.fossilcollector.core.number.GameNumber", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: GameNumber) {
        encoder.encodeDouble(value.value)
    }

    override fun deserialize(decoder: Decoder): GameNumber = GameNumber(decoder.decodeDouble())
}

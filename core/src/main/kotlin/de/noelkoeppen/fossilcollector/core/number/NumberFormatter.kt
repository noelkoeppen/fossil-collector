package de.noelkoeppen.fossilcollector.core.number

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Sprache fuer die Zahlenformatierung (Kapitel 12: "Zahlenformat folgt der gewaehlten Sprache").
 * Bewusst unabhaengig von der System-Locale, damit die Ausgabe deterministisch ist.
 */
enum class NumberLanguage(
    /** Dezimaltrennzeichen. */
    val decimalSeparator: Char,
    /** Kurzform-Suffixe fuer 10^3, 10^6, 10^9 und 10^12 (inklusive fuehrendem Leerzeichen, falls noetig). */
    val suffixes: List<String>,
) {
    /** Deutsch: `1,5 K`, `2,3 Mio.`, `4,1 Mrd.`, `1,2 Bio.` */
    DE(',', listOf(" K", " Mio.", " Mrd.", " Bio.")),

    /** Englisch: `1.5K`, `2.3M`, `4.1B`, `1.2T` */
    EN('.', listOf("K", "M", "B", "T")),
}

/** Darstellungsart (Einstellung "Zahlenformat", Kapitel 6.6 und AP-24). */
enum class NumberNotation {
    /** Kurzformen (K, Mio. ...), ab 10^15 automatisch wissenschaftlich. */
    SHORT,

    /** Ab 1000 immer wissenschaftlich, z. B. `1,5e9`. */
    SCIENTIFIC,
}

/**
 * Formatiert [GameNumber]-Werte fuer die Anzeige (Kapitel 6.6).
 *
 * Regeln:
 * - Betrag unter 1000: ganzzahlig, kaufmaennisch gerundet (`999.4` -> `999`).
 * - Ab 1000: Kurzform mit hoechstens 2 Nachkommastellen, Nullen am Ende entfallen
 *   (`1500` -> `1,5 K`, `999 999` -> `1 Mio.`).
 * - Ab 10^15 (nach Rundung) oder bei [NumberNotation.SCIENTIFIC]: Mantisse mit hoechstens
 *   2 Nachkommastellen und Exponent, z. B. `1,5e9` bzw. `1.5e9`.
 * - Es wird immer zuerst gerundet und danach die Groessenklasse bestimmt. Dadurch wird
 *   `999.6` zu `1 K` und `999 999` zu `1 Mio.` statt `1000 K`.
 * - Negative Zahlen: Minuszeichen vor dem Betrag (`-1,5 K`). Rundet ein negativer Wert auf 0,
 *   wird `0` ohne Vorzeichen ausgegeben.
 * - Nicht-endliche Werte: `NaN`, `∞` bzw. `-∞`. Diese sollten im Spiel nie auftreten
 *   (siehe `advance()`), werden aber ohne Absturz angezeigt.
 *
 * Die Suffixe sind reine Formatierung (wie das Dezimaltrennzeichen), keine Inhaltstexte, und
 * stehen deshalb als Konstanten in [NumberLanguage].
 *
 * Rundung: [RoundingMode.HALF_UP] auf der kuerzesten Dezimaldarstellung des Doubles
 * ([BigDecimal.valueOf]), damit z. B. `1.005e6` wie erwartet zu `1,01 Mio.` wird.
 */
object NumberFormatter {

    /** Ab diesem Betrag wird auch im Modus [NumberNotation.SHORT] wissenschaftlich formatiert. */
    private val SCIENTIFIC_THRESHOLD: BigDecimal = BigDecimal.TEN.pow(15)

    private val THOUSAND: BigDecimal = BigDecimal.valueOf(1000L)

    private const val MAX_DECIMALS = 2

    private const val NAN_TEXT = "NaN"
    private const val INFINITY_TEXT = "∞"

    fun format(
        number: GameNumber,
        language: NumberLanguage,
        notation: NumberNotation = NumberNotation.SHORT,
    ): String = format(number.value, language, notation)

    fun format(
        value: Double,
        language: NumberLanguage,
        notation: NumberNotation = NumberNotation.SHORT,
    ): String {
        if (value.isNaN()) return NAN_TEXT
        if (value.isInfinite()) return if (value > 0) INFINITY_TEXT else "-$INFINITY_TEXT"

        val magnitude = formatMagnitude(BigDecimal.valueOf(kotlin.math.abs(value)), language, notation)
        return if (value < 0 && magnitude != "0") "-$magnitude" else magnitude
    }

    private fun formatMagnitude(abs: BigDecimal, language: NumberLanguage, notation: NumberNotation): String {
        val integer = abs.setScale(0, RoundingMode.HALF_UP)
        if (integer < THOUSAND) return integer.toPlainString()
        if (notation == NumberNotation.SCIENTIFIC || integer >= SCIENTIFIC_THRESHOLD) {
            return formatScientific(abs, language)
        }

        // Groessenklasse 1 = Tausend, 2 = Million, ...; nach Rundung ggf. eine Klasse hoeher.
        var tier = (integer.precision() - 1) / 3
        while (tier <= language.suffixes.size) {
            val mantissa = abs.movePointLeft(3 * tier).setScale(MAX_DECIMALS, RoundingMode.HALF_UP)
            if (mantissa < THOUSAND) {
                return decimal(mantissa, language) + language.suffixes[tier - 1]
            }
            tier++
        }
        // Betrag >= 10^15 (SCIENTIFIC_THRESHOLD), ggf. erst durch Rundung erreicht.
        return formatScientific(abs, language)
    }

    private fun formatScientific(abs: BigDecimal, language: NumberLanguage): String {
        var exponent = abs.precision() - abs.scale() - 1
        var mantissa = abs.movePointLeft(exponent).setScale(MAX_DECIMALS, RoundingMode.HALF_UP)
        if (mantissa >= BigDecimal.TEN) {
            exponent++
            mantissa = abs.movePointLeft(exponent).setScale(MAX_DECIMALS, RoundingMode.HALF_UP)
        }
        return decimal(mantissa, language) + "e" + exponent
    }

    /** Dezimalzahl ohne Nullen am Ende und mit dem Trennzeichen der Sprache. */
    private fun decimal(number: BigDecimal, language: NumberLanguage): String {
        val stripped = number.stripTrailingZeros()
        val plain = if (stripped.scale() < 0) stripped.setScale(0).toPlainString() else stripped.toPlainString()
        return plain.replace('.', language.decimalSeparator)
    }
}

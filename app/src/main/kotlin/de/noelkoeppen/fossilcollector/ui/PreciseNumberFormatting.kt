package de.noelkoeppen.fossilcollector.ui

import de.noelkoeppen.fossilcollector.core.number.GameNumber
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Wie [GameNumberFormat.format], aber Betraege unter 1000 mit hoechstens 2 Nachkommastellen
 * (Nullen am Ende entfallen, kaufmaennisch gerundet), z. B. `0,25` bzw. `0.25`.
 *
 * Fuer kleine Raten und Lagerbestaende (Bloecke/s, Bloecke im Lager), die sonst als `0` erschienen
 * (Praezisierung AP-17 zu Kapitel 6.6). Ab 1000 und fuer nicht endliche Werte gilt unveraendert
 * [de.noelkoeppen.fossilcollector.core.number.NumberFormatter].
 */
fun GameNumberFormat.formatPrecise(number: GameNumber): String {
    val value = number.toDouble()
    if (!value.isFinite()) return format(number)
    val rounded = BigDecimal.valueOf(kotlin.math.abs(value)).setScale(PRECISE_DECIMALS, RoundingMode.HALF_UP)
    if (rounded >= THOUSAND) return format(number)
    if (rounded.signum() == 0) return ZERO_TEXT
    val stripped = rounded.stripTrailingZeros()
    val plain = (if (stripped.scale() < 0) stripped.setScale(0) else stripped).toPlainString()
    val text = plain.replace('.', language.decimalSeparator)
    return if (value < 0) "-$text" else text
}

private const val PRECISE_DECIMALS = 2
private const val ZERO_TEXT = "0"
private val THOUSAND: BigDecimal = BigDecimal.valueOf(1000L)

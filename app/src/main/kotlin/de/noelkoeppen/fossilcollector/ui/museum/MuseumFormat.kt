package de.noelkoeppen.fossilcollector.ui.museum

import de.noelkoeppen.fossilcollector.core.number.NumberLanguage
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

/**
 * Faktor wie `2`, `2,5` bzw. `2.5` (hoechstens 2 Nachkommastellen, ohne Nullen am Ende).
 * `NumberFormatter` rundet Werte unter 1000 auf ganze Zahlen und passt daher nicht fuer Faktoren.
 */
fun formatFactor(value: Double, language: NumberLanguage): String {
    if (!value.isFinite()) return value.toString()
    val text = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    return text.replace('.', language.decimalSeparator)
}

/** Qualitaet 0..1 als ganze Prozentzahl (0 bis 100). */
fun qualityPercent(quality: Double): Int = (quality.coerceIn(0.0, 1.0) * 100).roundToInt()

package de.noelkoeppen.fossilcollector.ui.book

import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.number.NumberLanguage
import de.noelkoeppen.fossilcollector.ui.museum.formatFactor

/**
 * Zahl eines Bonus mit Vorzeichen fuer die Anzeige, ohne Einheit (die steht in `strings_book.xml`):
 * - `mult` als Prozentwert, z. B. `0.01` -> `+1`, `0.025` -> `+2,5` (Vorlage `book_bonus_percent`)
 * - `add` als Zahl, z. B. `0.02` -> `+0,02` (Vorlage `book_bonus_add`)
 *
 * Hoechstens 2 Nachkommastellen, Nullen am Ende entfallen.
 */
fun formatBonusNumber(value: Double, op: ModifierOp, language: NumberLanguage): String {
    val shown = when (op) {
        ModifierOp.MULT -> value * PERCENT
        ModifierOp.ADD -> value
    }
    val text = formatFactor(kotlin.math.abs(shown), language)
    return if (shown < 0) "-$text" else "+$text"
}

private const val PERCENT = 100.0

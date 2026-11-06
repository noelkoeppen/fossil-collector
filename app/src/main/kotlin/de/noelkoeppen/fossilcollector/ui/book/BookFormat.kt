package de.noelkoeppen.fossilcollector.ui.book

import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.NumberLanguage
import de.noelkoeppen.fossilcollector.ui.museum.formatFactor

/**
 * `true`, wenn ein Bonus als Prozent angezeigt wird: `mult` immer (relativer Bonus), `add` nur auf
 * Stats, die Anteile bzw. Wahrscheinlichkeiten sind ([StatId.isFraction], AP-25), z. B.
 * `guaranteed_fossil_chance +0.02` -> `+2 %`. Andere `add`-Boni (Startteams, Skelett-Bonus ...)
 * bleiben Zahlen.
 */
fun bonusShownAsPercent(op: ModifierOp, stat: StatId): Boolean = op == ModifierOp.MULT || stat.isFraction

/**
 * Zahl eines Bonus mit Vorzeichen fuer die Anzeige, ohne Einheit (die Vorlage waehlt der Aufrufer
 * ueber [bonusShownAsPercent], z. B. `book_bonus_percent` bzw. `book_bonus_add`):
 * - Prozent ([bonusShownAsPercent]): `0.01` -> `+1`, `0.025` -> `+2,5`
 * - sonst als Zahl: `0.5` -> `+0,5`
 *
 * Hoechstens 2 Nachkommastellen, Nullen am Ende entfallen.
 */
fun formatBonusNumber(value: Double, op: ModifierOp, stat: StatId, language: NumberLanguage): String {
    val shown = if (bonusShownAsPercent(op, stat)) value * PERCENT else value
    val text = formatFactor(kotlin.math.abs(shown), language)
    return if (shown < 0) "-$text" else "+$text"
}

/** Anteil 0..1 als Prozentzahl ohne Vorzeichen und Einheit, z. B. `0.07` -> `7`, `0.025` -> `2,5` (AP-25). */
fun formatFractionPercent(value: Double, language: NumberLanguage): String = formatFactor(value * PERCENT, language)

private const val PERCENT = 100.0

package de.noelkoeppen.fossilcollector.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.noelkoeppen.fossilcollector.R
import de.noelkoeppen.fossilcollector.core.data.Currency
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseQuote
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.number.NumberLanguage
import de.noelkoeppen.fossilcollector.core.number.NumberNotation
import de.noelkoeppen.fossilcollector.ui.GameNumberFormat
import de.noelkoeppen.fossilcollector.ui.theme.FossilCollectorTheme

/*
 * Gemeinsame Bausteine fuer Kaeufe mit Kaufmodus x1/x10/Max (Kapitel 6.3), angelegt in AP-17 und
 * gedacht fuer die Screens Museum (Hallen, AP-18) und Labor (Laborplaetze, Werkzeugstufen, AP-19):
 *
 * - [rememberBuyMode]: Kaufmodus als Zustand eines Screens (ueberlebt Konfigurationswechsel).
 * - [BuyModeSelector]: Auswahl x1/x10/Max als Segment-Leiste.
 * - [BuyButton]: Kauf-Button aus einer [PurchaseQuote] (`Purchases.quote`), zeigt Menge und Preis
 *   in der Waehrung der Vorschau und ist deaktiviert, wenn der Kauf nicht bezahlbar ist.
 *
 * Die Vorschauen berechnet der Aufrufer im ViewModel (z. B. je Modus eine Map `BuyMode -> PurchaseQuote?`),
 * die Bausteine selbst rechnen nichts.
 */

/** Kaufmodus eines Screens, Standard [BuyMode.X1]. */
@Composable
fun rememberBuyMode(initial: BuyMode = BuyMode.X1): MutableState<BuyMode> = rememberSaveable { mutableStateOf(initial) }

/** Auswahl des Kaufmodus x1/x10/Max. */
@Composable
fun BuyModeSelector(selected: BuyMode, onSelect: (BuyMode) -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.buy_mode_label)
    SingleChoiceSegmentedButtonRow(modifier = modifier.semantics { contentDescription = description }) {
        BuyMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = BuyMode.entries.size),
                label = { Text(stringResource(mode.labelRes), maxLines = 1) },
            )
        }
    }
}

/**
 * Kauf-Button: oben [title] mit der Menge aus [quote] (z. B. "Teams +10"; bei Max ohne bezahlbare
 * Einheit nur [title]), darunter der Preis (bei Menge 0 der Preis der naechsten Einheit als
 * Sparziel). Deaktiviert, wenn [quote] `null` oder nicht bezahlbar ist.
 *
 * @param title Name des Kaufs, z. B. "Teams" oder der Generatorname aus dem `TextProvider`
 * @param quote Vorschau aus `Purchases.quote`; `null` = gerade nicht kaufbar
 * @param format Zahlenformat der Anzeige
 */
@Composable
fun BuyButton(
    title: String,
    quote: PurchaseQuote?,
    format: GameNumberFormat,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = quote?.affordable == true,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val label = if (quote != null && quote.quantity > GameNumber.ZERO) {
                stringResource(R.string.buy_title_quantity, title, format.format(quote.quantity))
            } else {
                title
            }
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, textAlign = TextAlign.Center)
            Text(
                text = quote?.let { costText(it.cost, it.currency, format) } ?: stringResource(R.string.buy_unavailable),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

/** Preis mit Waehrung, z. B. `1,5 K $` oder `12 FP`. */
@Composable
fun costText(cost: GameNumber, currency: Currency, format: GameNumberFormat): String = when (currency) {
    Currency.MONEY -> stringResource(R.string.buy_cost_money, format.format(cost))
    Currency.RESEARCH_POINTS -> stringResource(R.string.buy_cost_research_points, format.format(cost))
}

private val BuyMode.labelRes: Int
    get() = when (this) {
        BuyMode.X1 -> R.string.buy_mode_x1
        BuyMode.X10 -> R.string.buy_mode_x10
        BuyMode.MAX -> R.string.buy_mode_max
    }

@Preview
@Composable
private fun BuyModePreview() {
    FossilCollectorTheme {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                var mode by rememberBuyMode(BuyMode.X10)
                val format = GameNumberFormat(NumberLanguage.DE, NumberNotation.SHORT)
                BuyModeSelector(selected = mode, onSelect = { mode = it })
                BuyButton(
                    title = stringResource(R.string.buy_mode_label),
                    quote = PurchaseQuote(GameNumber(10.0), GameNumber(1_754.0), Currency.MONEY, affordable = true),
                    format = format,
                    onClick = {},
                    modifier = Modifier.padding(top = 8.dp),
                )
                BuyButton(
                    title = stringResource(R.string.buy_mode_label),
                    quote = PurchaseQuote(GameNumber.ZERO, GameNumber(3.0), Currency.RESEARCH_POINTS, affordable = false),
                    format = format,
                    onClick = {},
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

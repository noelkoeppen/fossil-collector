package de.noelkoeppen.fossilcollector.ui.museum

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.engine.SpecialExhibitResult
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseResult
import de.noelkoeppen.fossilcollector.vm.GameViewModel

/**
 * Spieleraktionen des Museums-Screens. Die Composables kennen nur diese Callbacks; die Anbindung
 * an das ViewModel macht [museumActions].
 */
@Immutable
data class MuseumActions(
    val onBuyHalls: (BuyMode) -> Unit,
    val onSelectSpecialExhibit: (speciesId: String?) -> Unit,
) {
    companion object {
        /** Ohne Wirkung (Vorschauen). */
        val NONE = MuseumActions(onBuyHalls = {}, onSelectSpecialExhibit = {})
    }
}

/**
 * Aktionen ueber `GameViewModel.perform` (beide Aktionen erzeugen keine Ereignisse, daher nicht `performWithEvents`). Abgelehnte Aktionen (zu wenig Geld, ungueltige Auswahl)
 * lassen den Spielstand unveraendert; die UI verhindert sie ohnehin ueber deaktivierte Buttons.
 */
fun museumActions(viewModel: GameViewModel): MuseumActions = MuseumActions(
    onBuyHalls = { mode ->
        viewModel.perform { engine, state ->
            when (val result = engine.buyHalls(state, mode)) {
                is PurchaseResult.Success -> result.state
                is PurchaseResult.Failure -> state
            }
        }
    },
    onSelectSpecialExhibit = { speciesId ->
        viewModel.perform { engine, state ->
            when (val result = engine.setSpecialExhibit(state, speciesId)) {
                is SpecialExhibitResult.Success -> result.state
                is SpecialExhibitResult.Failure -> state
            }
        }
    },
)

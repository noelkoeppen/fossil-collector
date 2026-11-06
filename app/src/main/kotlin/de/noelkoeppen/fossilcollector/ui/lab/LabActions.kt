package de.noelkoeppen.fossilcollector.ui.lab

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.vm.GameViewModel

/**
 * Spieleraktionen des Labor-Screens. Die Composables kennen nur diese Callbacks; die Anbindung an
 * das ViewModel macht [labActions].
 */
@Immutable
data class LabActions(
    val onBuyLabSlots: (BuyMode) -> Unit,
    val onBuyToolLevel: (BuyMode) -> Unit,
    val onReconstruct: (PieceKey) -> Unit,
) {
    companion object {
        /** Ohne Wirkung (Vorschauen). */
        val NONE = LabActions(onBuyLabSlots = {}, onBuyToolLevel = {}, onReconstruct = {})
    }
}

/**
 * Aktionen ueber das ViewModel: Kaeufe ueber `GameViewModel.purchase` (wie alle Kaeufe, AP-25; abgelehnte Kaeufe lassen
 * den Spielstand unveraendert, die UI verhindert sie ohnehin ueber deaktivierte Buttons), die
 * Rekonstruktion ueber `GameViewModel.reconstruct` (`performWithEvents`, Hinweis bei Ablehnung).
 */
fun labActions(viewModel: GameViewModel): LabActions = LabActions(
    onBuyLabSlots = { mode -> viewModel.purchase { engine, state -> engine.buyLabSlots(state, mode) } },
    onBuyToolLevel = { mode -> viewModel.purchase { engine, state -> engine.buyToolLevel(state, mode) } },
    onReconstruct = viewModel::reconstruct,
)

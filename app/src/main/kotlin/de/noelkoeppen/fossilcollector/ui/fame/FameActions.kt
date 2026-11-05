package de.noelkoeppen.fossilcollector.ui.fame

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.engine.SkillPurchaseResult
import de.noelkoeppen.fossilcollector.vm.GameViewModel

/**
 * Spieleraktionen des Ruhm-Screens. Die Composables kennen nur diese Callbacks; die Anbindung an
 * das ViewModel macht [fameActions].
 */
@Immutable
data class FameActions(
    val onBuySkill: (skillId: String) -> Unit,
    val onOpenPrestige: () -> Unit,
    val onDismissPrestige: () -> Unit,
    val onSetRespec: (enabled: Boolean) -> Unit,
    val onToggleRespecSkill: (skillId: String) -> Unit,
    val onClearRespec: () -> Unit,
    val onConfirmPrestige: () -> Unit,
) {
    companion object {
        /** Ohne Wirkung (Vorschauen). */
        val NONE = FameActions(
            onBuySkill = {},
            onOpenPrestige = {},
            onDismissPrestige = {},
            onSetRespec = {},
            onToggleRespecSkill = {},
            onClearRespec = {},
            onConfirmPrestige = {},
        )
    }
}

/**
 * Aktionen ueber das ViewModel. Der Skillkauf laeuft ueber `perform` mit `GameEngine.buySkill`
 * (keine Ereignisse; ein abgelehnter Kauf laesst den Spielstand unveraendert, die UI verhindert ihn
 * ueber deaktivierte Buttons). Dialog und Prestige verwaltet das ViewModel ([GameViewModel.confirmPrestige]).
 */
fun fameActions(viewModel: GameViewModel): FameActions = FameActions(
    onBuySkill = { skillId ->
        viewModel.perform { engine, state ->
            when (val result = engine.buySkill(state, skillId)) {
                is SkillPurchaseResult.Success -> result.state
                is SkillPurchaseResult.Failure -> state
            }
        }
    },
    onOpenPrestige = viewModel::openPrestigeDialog,
    onDismissPrestige = viewModel::dismissPrestigeDialog,
    onSetRespec = viewModel::setPrestigeRespec,
    onToggleRespecSkill = viewModel::toggleRespecSkill,
    onClearRespec = viewModel::clearRespecSelection,
    onConfirmPrestige = viewModel::confirmPrestige,
)

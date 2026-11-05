package de.noelkoeppen.fossilcollector.ui.fame

import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.SkillBranchView
import de.noelkoeppen.fossilcollector.core.engine.SkillNodeView
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Baut den [FameUiState] aus Engine, Spielstand und Dialogzustand (ausserhalb der Composables,
 * testbar). Alle Werte stammen aus `core` (`fameGainPreview`, `skillOverview`, `prestigeSummary`,
 * `respecPreview`, `skillTree.overview`); hier wird nichts berechnet, was Spiellogik ist.
 *
 * Die Prestige-Uebersicht und die Respec-Vorschau werden nur bei offenem Dialog gebaut.
 */
object FameUiMapper {

    fun map(engine: GameEngine, state: GameState, dialog: PrestigeDialogState?): FameUiState {
        val preview = engine.fameGainPreview(state)
        val meta = state.meta
        return FameUiState(
            fame = meta.fame,
            fameTotal = meta.fameTotal,
            prestigeCount = meta.prestigeCount,
            fameGainPreview = preview,
            canPrestige = preview >= GameNumber.ONE,
            branches = engine.skillOverview(state).map(::branchUi),
            dialog = dialog?.let { mapDialog(engine, state, it) },
        )
    }

    private fun mapDialog(engine: GameEngine, state: GameState, dialog: PrestigeDialogState): PrestigeDialogUi {
        val summary = engine.prestigeSummary(state)
        val respec = if (dialog.respec) {
            val plan = engine.respecPreview(state, dialog.selection)
            RespecUi(
                selection = dialog.selection,
                refundedFame = plan.refundedFame,
                fameAvailable = summary.fameAfter + plan.refundedFame,
                fameLeft = plan.meta.fame,
                bought = plan.bought,
                rejected = plan.rejected.map { (id, reason) -> RejectedSkillUi(id, reason) },
                branches = engine.skillTree.overview(plan.meta).map(::branchUi),
            )
        } else {
            null
        }
        return PrestigeDialogUi(summary, respec)
    }

    private fun branchUi(view: SkillBranchView): SkillBranchUi =
        SkillBranchUi(view.branch, view.nodes.map(::nodeUi), view.ownedCount)

    private fun nodeUi(view: SkillNodeView): SkillNodeUi = SkillNodeUi(
        id = view.id,
        depth = view.definition.depth,
        capstone = view.definition.capstone,
        status = view.status,
        price = view.price,
        affordable = view.affordable,
        missingRequirements = view.missingRequirements,
        effects = view.definition.effects,
    )
}

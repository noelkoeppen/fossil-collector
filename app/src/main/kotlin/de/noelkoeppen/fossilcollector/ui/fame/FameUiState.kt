package de.noelkoeppen.fossilcollector.ui.fame

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.data.SkillBranch
import de.noelkoeppen.fossilcollector.core.engine.PrestigeSummary
import de.noelkoeppen.fossilcollector.core.engine.SkillNodeStatus
import de.noelkoeppen.fossilcollector.core.engine.SkillPurchaseFailure
import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Ruhm-Ausschnitt des UI-Zustands (AP-22, Kapitel 5.3, 5.4 und 8.3). Wird von [FameUiMapper]
 * ausserhalb der Composables aus Engine, Spielstand und [PrestigeDialogState] gebaut; enthaelt
 * Rohwerte und IDs. Namen und Beschreibungen loest die UI ueber den `TextProvider` auf.
 *
 * @property fame unverteilter Ruhm
 * @property fameTotal insgesamt verdienter Ruhm
 * @property prestigeCount Anzahl bisheriger Prestiges
 * @property fameGainPreview Ruhm bei Neustart jetzt (`GameEngine.fameGainPreview`)
 * @property canPrestige Vorschau >= 1 (`GameEngine.canPrestige`), Prestige-Button aktiv
 * @property branches Skilltree-Aeste (`GameEngine.skillOverview`), je Ast Knoten nach Tiefe
 * @property dialog Prestige-Bestaetigungsdialog oder `null`, wenn er geschlossen ist
 */
@Immutable
data class FameUiState(
    val fame: GameNumber,
    val fameTotal: GameNumber,
    val prestigeCount: GameNumber,
    val fameGainPreview: GameNumber,
    val canPrestige: Boolean,
    val branches: List<SkillBranchUi>,
    val dialog: PrestigeDialogUi?,
) {
    /** Knoten [skillId] aus allen Aesten oder `null`. */
    fun node(skillId: String): SkillNodeUi? = branches.firstNotNullOfOrNull { b -> b.nodes.firstOrNull { it.id == skillId } }

    companion object {
        /** Leerer Zustand (z. B. fuer Vorschauen anderer Screens). */
        val EMPTY = FameUiState(
            fame = GameNumber.ZERO,
            fameTotal = GameNumber.ZERO,
            prestigeCount = GameNumber.ZERO,
            fameGainPreview = GameNumber.ZERO,
            canPrestige = false,
            branches = emptyList(),
            dialog = null,
        )
    }
}

/**
 * Ein Ast des Skilltrees.
 *
 * @property branch Ast (Name ueber `LocaleKeys.skillBranchName`)
 * @property nodes Knoten nach Tiefe
 * @property ownedCount gekaufte Knoten des Astes
 */
@Immutable
data class SkillBranchUi(
    val branch: SkillBranch,
    val nodes: List<SkillNodeUi>,
    val ownedCount: Int,
) {
    val total: Int get() = nodes.size
}

/**
 * Ein Skill-Knoten.
 *
 * @property id Skill-ID (Name und Beschreibung ueber `LocaleKeys.skillName`/`skillDescription`)
 * @property depth Tiefe im Ast (1 bis 5)
 * @property capstone `true` fuer den Capstone (T5)
 * @property status gekauft, verfuegbar oder gesperrt
 * @property price Preis bei Kauf jetzt, `null` wenn gekauft
 * @property affordable verfuegbar und Ruhm reicht (Kauf wuerde gelingen)
 * @property missingRequirements fehlende Voraussetzungen (Skill-IDs)
 * @property effects Wirkung aus `skilltree.json` (fuer die Anzeige "Fundchance: +5 %")
 */
@Immutable
data class SkillNodeUi(
    val id: String,
    val depth: Int,
    val capstone: Boolean,
    val status: SkillNodeStatus,
    val price: GameNumber?,
    val affordable: Boolean,
    val missingRequirements: List<String>,
    val effects: List<Modifier>,
)

/**
 * Daten des Prestige-Bestaetigungsdialogs.
 *
 * @property summary Uebersicht aus `GameEngine.prestigeSummary` (Gewinn, Verlust, was bleibt)
 * @property respec Respec-Vorschau, wenn "Skills neu verteilen" gewaehlt ist, sonst `null`
 *   ("Skills behalten")
 */
@Immutable
data class PrestigeDialogUi(
    val summary: PrestigeSummary,
    val respec: RespecUi?,
)

/**
 * Vorschau "Skills neu verteilen" aus `GameEngine.respecPreview(state, selection)`.
 *
 * @property selection gewuenschte Skills in Kaufreihenfolge
 * @property refundedFame erstatteter Ruhm
 * @property fameAvailable Ruhm nach Prestige und Erstattung, vor dem Neukauf
 * @property fameLeft Ruhm nach dem Neukauf
 * @property bought tatsaechlich gekaufte Skills in Kaufreihenfolge
 * @property rejected uebersprungene Wuensche mit Grund
 * @property branches Skilltree im Zustand nach dem Neukauf (gekauft = wird gekauft); Grundlage fuer
 *   die Auswahl
 */
@Immutable
data class RespecUi(
    val selection: List<String>,
    val refundedFame: GameNumber,
    val fameAvailable: GameNumber,
    val fameLeft: GameNumber,
    val bought: List<String>,
    val rejected: List<RejectedSkillUi>,
    val branches: List<SkillBranchUi>,
) {
    /** `true`, wenn [skillId] in der Auswahl steht, aber nicht gekauft werden kann. */
    fun isRejected(skillId: String): Boolean = rejected.any { it.skillId == skillId }
}

/** Ein abgelehnter Wunsch beim Respec mit Grund. */
@Immutable
data class RejectedSkillUi(val skillId: String, val reason: SkillPurchaseFailure)

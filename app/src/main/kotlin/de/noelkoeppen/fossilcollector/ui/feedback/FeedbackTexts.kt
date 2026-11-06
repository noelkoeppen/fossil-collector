package de.noelkoeppen.fossilcollector.ui.feedback

import android.content.Context
import de.noelkoeppen.fossilcollector.R
import de.noelkoeppen.fossilcollector.core.data.TextProvider
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.ui.GameNumberFormat
import de.noelkoeppen.fossilcollector.vm.GameTab
import de.noelkoeppen.fossilcollector.vm.TabBadge

/** Welche Teile der Belohnung ein Meilenstein-Toast nennt (Kapitel 5.2, AP-25). */
enum class MilestoneRewardKind {
    NONE,
    MONEY,
    RESEARCH,
    BOTH,
}

/** Texte fuer Meilenstein-Toasts, Hinweistexte und Badges (AP-25). */
object FeedbackTexts {

    /** Nennenswert ist eine Belohnung nur, wenn sie groesser als 0 ist. */
    fun rewardKind(money: GameNumber, researchPoints: GameNumber): MilestoneRewardKind {
        val hasMoney = money > GameNumber.ZERO
        val hasResearch = researchPoints > GameNumber.ZERO
        return when {
            hasMoney && hasResearch -> MilestoneRewardKind.BOTH
            hasMoney -> MilestoneRewardKind.MONEY
            hasResearch -> MilestoneRewardKind.RESEARCH
            else -> MilestoneRewardKind.NONE
        }
    }

    /**
     * Toast "Meilenstein erreicht: <Name> (+50 $, +2 FP)". Der Name kommt aus den Inhaltstexten
     * ([TextProvider.milestoneName], bereits in der Anzeigesprache), Zahlen im Format [format].
     */
    fun milestoneText(
        context: Context,
        milestoneId: String,
        money: GameNumber,
        researchPoints: GameNumber,
        texts: TextProvider,
        format: GameNumberFormat,
    ): String {
        val name = texts.milestoneName(milestoneId)
        val reward = when (rewardKind(money, researchPoints)) {
            MilestoneRewardKind.NONE -> return context.getString(R.string.feedback_milestone, name)
            MilestoneRewardKind.MONEY -> context.getString(R.string.feedback_reward_money, format.format(money))
            MilestoneRewardKind.RESEARCH -> context.getString(R.string.feedback_reward_research, format.format(researchPoints))
            MilestoneRewardKind.BOTH -> context.getString(
                R.string.feedback_reward_money_research,
                format.format(money),
                format.format(researchPoints),
            )
        }
        return context.getString(R.string.feedback_milestone_with_reward, name, reward)
    }

    /** Hinweistext beim ersten Erscheinen von [tab] (Kapitel 8.2, `strings_hints.xml`). */
    fun hintRes(tab: GameTab): Int = when (tab) {
        GameTab.DIG -> R.string.hint_tab_dig
        GameTab.LAB -> R.string.hint_tab_lab
        GameTab.MUSEUM -> R.string.hint_tab_museum
        GameTab.BOOK -> R.string.hint_tab_book
        GameTab.FAME -> R.string.hint_tab_fame
    }

    /** Beschreibung eines Badges fuer Bedienungshilfen. */
    fun badgeDescriptionRes(badge: TabBadge): Int = when (badge) {
        TabBadge.RESEARCH_DONE -> R.string.feedback_badge_research_done
        TabBadge.NEW_BOOK_ENTRY -> R.string.feedback_badge_new_book_entry
        TabBadge.AFFORDABLE -> R.string.feedback_badge_affordable
        TabBadge.PRESTIGE -> R.string.feedback_badge_prestige
    }
}

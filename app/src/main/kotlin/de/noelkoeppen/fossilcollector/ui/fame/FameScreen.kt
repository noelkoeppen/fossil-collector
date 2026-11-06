package de.noelkoeppen.fossilcollector.ui.fame

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.noelkoeppen.fossilcollector.R
import de.noelkoeppen.fossilcollector.ui.theme.AppIcons
import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.LocaleKeys
import de.noelkoeppen.fossilcollector.core.data.TextProvider
import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.PrestigeSummary
import de.noelkoeppen.fossilcollector.core.engine.RunStart
import de.noelkoeppen.fossilcollector.core.engine.SkillNodeStatus
import de.noelkoeppen.fossilcollector.core.engine.SkillPurchaseFailure
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.modifier.Modifier as SkillModifier
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.number.NumberLanguage
import de.noelkoeppen.fossilcollector.ui.GameNumberFormat
import de.noelkoeppen.fossilcollector.ui.book.bonusShownAsPercent
import de.noelkoeppen.fossilcollector.ui.book.formatBonusNumber
import de.noelkoeppen.fossilcollector.ui.rememberGameNumberFormat
import de.noelkoeppen.fossilcollector.ui.rememberTextProvider
import de.noelkoeppen.fossilcollector.ui.theme.FossilCollectorTheme

/**
 * Ruhm-Screen (AP-22, Kapitel 5.3, 5.4 und 8.3): Ruhm verfuegbar/gesamt, Prestige mit
 * Live-Vorschau, Skilltree als 4 vertikale Aeste und der Prestige-Dialog mit Reset-Uebersicht und
 * Respec-Option.
 *
 * Kauf eines Skills: Tippen auf einen Knoten oeffnet die Details (Name, Beschreibung, Wirkung,
 * Preis) mit dem Button "Kaufen" (Bestaetigung in einem Schritt). Der gewaehlte Knoten steht im
 * Screen-Zustand, der Prestige-Dialog im ViewModel ([FameUiState.dialog]).
 *
 * @param state Ruhm-Ausschnitt des UI-Zustands ([FameUiMapper])
 * @param settings fuer Zahlenformat und Sprache der Inhaltstexte
 * @param texts Inhaltstexte (`GameUiState.Ready.texts`), Sprache per [rememberTextProvider]
 * @param actions Spieleraktionen ([fameActions])
 */
@Composable
fun FameScreen(
    state: FameUiState,
    settings: Settings,
    texts: TextProvider,
    actions: FameActions,
    modifier: Modifier = Modifier,
) {
    val localTexts = rememberTextProvider(texts, settings)
    val format = rememberGameNumberFormat(settings)
    var detailSkill by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FameHeader(state, format)
        PrestigeCard(state, format, onPrestige = actions.onOpenPrestige)
        Text(stringResource(R.string.fame_skills_title), style = MaterialTheme.typography.titleMedium)
        SkillTreeColumns(
            branches = state.branches,
            texts = localTexts,
            format = format,
            onNodeClick = { detailSkill = it.id },
        )
    }

    val detail = detailSkill?.let(state::node)
    if (detail != null) {
        SkillDetailDialog(
            node = detail,
            texts = localTexts,
            format = format,
            onBuy = {
                actions.onBuySkill(detail.id)
                detailSkill = null
            },
            onDismiss = { detailSkill = null },
        )
    }

    state.dialog?.let { dialog ->
        PrestigeDialog(dialog = dialog, texts = localTexts, format = format, actions = actions)
    }
}

// --- Kopf und Prestige ---

@Composable
private fun FameHeader(state: FameUiState, format: GameNumberFormat) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.fame_available),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(format.format(state.fame), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.fame_total, format.format(state.fameTotal)), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.fame_prestige_count, format.format(state.prestigeCount)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun PrestigeCard(state: FameUiState, format: GameNumberFormat, onPrestige: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(
            1.dp,
            if (state.canPrestige) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.fame_prestige_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.fame_prestige_preview, format.format(state.fameGainPreview)),
                style = MaterialTheme.typography.titleLarge,
                color = if (state.canPrestige) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (!state.canPrestige) {
                Text(
                    stringResource(R.string.fame_prestige_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onPrestige, enabled = state.canPrestige, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.fame_prestige_button))
            }
        }
    }
}

// --- Skilltree ---

/** Darstellung eines Knotens in der Respec-Auswahl (nur im Prestige-Dialog). */
private enum class SelectionMark { NONE, SELECTED, REJECTED }

/**
 * Skilltree als nebeneinanderliegende vertikale Aeste (Kopf: Astname und Fortschritt, darunter die
 * Knoten nach Tiefe, verbunden durch kurze Linien).
 *
 * @param mark Markierung je Knoten (Respec-Auswahl); im Screen immer [SelectionMark.NONE]
 * @param clickable ob ein Knoten antippbar ist
 */
@Composable
private fun SkillTreeColumns(
    branches: List<SkillBranchUi>,
    texts: TextProvider,
    format: GameNumberFormat,
    onNodeClick: (SkillNodeUi) -> Unit,
    modifier: Modifier = Modifier,
    mark: (SkillNodeUi) -> SelectionMark = { SelectionMark.NONE },
    clickable: (SkillNodeUi) -> Boolean = { true },
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (branch in branches) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = texts.text(LocaleKeys.skillBranchName(branch.branch)),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.fame_branch_progress, branch.ownedCount, branch.total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                branch.nodes.forEachIndexed { index, node ->
                    if (index > 0) {
                        val linked = node.status == SkillNodeStatus.OWNED || node.status == SkillNodeStatus.AVAILABLE
                        Box(
                            Modifier
                                .width(2.dp)
                                .height(10.dp)
                                .background(
                                    if (linked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                ),
                        )
                    }
                    SkillNodeCard(
                        node = node,
                        name = texts.text(LocaleKeys.skillName(node.id)),
                        format = format,
                        mark = mark(node),
                        onClick = if (clickable(node)) ({ onNodeClick(node) }) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun SkillNodeCard(
    node: SkillNodeUi,
    name: String,
    format: GameNumberFormat,
    mark: SelectionMark,
    onClick: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val owned = node.status == SkillNodeStatus.OWNED
    val locked = node.status == SkillNodeStatus.LOCKED
    val border = when {
        mark == SelectionMark.REJECTED -> BorderStroke(2.dp, colors.error)
        owned -> BorderStroke(1.dp, colors.primary)
        node.affordable -> BorderStroke(2.dp, colors.primary)
        else -> BorderStroke(1.dp, colors.outlineVariant)
    }
    val container = if (owned) colors.primaryContainer else colors.surface
    val content = if (owned) colors.onPrimaryContainer else colors.onSurface
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .alpha(if (locked && mark != SelectionMark.REJECTED) LOCKED_ALPHA else 1f)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        border = border,
        colors = CardDefaults.outlinedCardColors(containerColor = container, contentColor = content),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val icon = when {
                mark == SelectionMark.REJECTED -> AppIcons.Warning
                owned -> AppIcons.Check
                locked -> AppIcons.Lock
                node.capstone -> AppIcons.Star
                else -> null
            }
            if (icon != null) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (mark == SelectionMark.REJECTED) colors.error else content,
                )
            }
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (node.capstone) FontWeight.Bold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val price = node.price
            Text(
                text = if (price == null) {
                    stringResource(R.string.fame_node_owned)
                } else {
                    stringResource(R.string.fame_node_price, format.format(price))
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    price == null -> content
                    node.affordable -> colors.primary
                    else -> colors.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}

/** Details eines Knotens mit Kauf-Button (Bestaetigung des Kaufs). */
@Composable
private fun SkillDetailDialog(
    node: SkillNodeUi,
    texts: TextProvider,
    format: GameNumberFormat,
    onBuy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val price = node.price
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(texts.text(LocaleKeys.skillName(node.id))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (node.capstone) {
                    Text(
                        stringResource(R.string.fame_node_capstone),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(texts.text(LocaleKeys.skillDescription(node.id)), style = MaterialTheme.typography.bodyMedium)
                for (effect in node.effects) {
                    Text(
                        effectText(effect, texts, format.language),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (node.missingRequirements.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.fame_skill_requires,
                            node.missingRequirements.joinToString { texts.text(LocaleKeys.skillName(it)) },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (price != null) {
                    Text(stringResource(R.string.fame_skill_price, format.format(price)), style = MaterialTheme.typography.bodyMedium)
                    if (node.status == SkillNodeStatus.AVAILABLE && !node.affordable) {
                        Text(
                            stringResource(R.string.fame_skill_not_enough),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        stringResource(R.string.fame_skill_price_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        stringResource(R.string.fame_node_owned),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            if (price != null) {
                Button(onClick = onBuy, enabled = node.affordable) { Text(stringResource(R.string.fame_skill_buy)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.fame_close)) } },
    )
}

/** Wirkung eines Skills, z. B. "Fundchance: +5 %" (`mult` und `add` auf Anteile als Prozent, sonst Zahl, wie im Sammelbuch). */
@Composable
private fun effectText(effect: SkillModifier, texts: TextProvider, language: NumberLanguage): String {
    val number = formatBonusNumber(effect.value, effect.op, effect.stat, language)
    val value = if (bonusShownAsPercent(effect.op, effect.stat)) {
        stringResource(R.string.fame_effect_percent, number)
    } else {
        stringResource(R.string.fame_effect_add, number)
    }
    return stringResource(R.string.fame_effect_line, texts.statName(effect.stat), value)
}

// --- Prestige-Dialog ---

/**
 * Bestaetigungsdialog (Vollbild): Ruhm-Gewinn, was verloren geht, was bleibt, Wahl "Skills
 * behalten" / "Skills neu verteilen" mit Auswahl und Vorschau, Bestaetigen.
 */
@Composable
private fun PrestigeDialog(
    dialog: PrestigeDialogUi,
    texts: TextProvider,
    format: GameNumberFormat,
    actions: FameActions,
) {
    Dialog(
        onDismissRequest = actions.onDismissPrestige,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            PrestigeDialogContent(dialog, texts, format, actions)
        }
    }
}

@Composable
private fun PrestigeDialogContent(
    dialog: PrestigeDialogUi,
    texts: TextProvider,
    format: GameNumberFormat,
    actions: FameActions,
) {
    val summary = dialog.summary
    val respec = dialog.respec
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.fame_dialog_title), style = MaterialTheme.typography.headlineSmall)
            Column {
                Text(
                    stringResource(R.string.fame_dialog_gain, format.format(summary.fameGain)),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    pluralStringResource(R.plurals.fame_dialog_skeletons, summary.skeletonCount, summary.skeletonCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            LostSection(summary, texts, format)
            HorizontalDivider()
            KeptSection(summary, respec, format)
            HorizontalDivider()
            SkillChoice(respec != null, actions.onSetRespec)
            if (respec != null) RespecSection(respec, texts, format, actions)
        }
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = actions.onDismissPrestige) { Text(stringResource(R.string.fame_dialog_cancel)) }
            Button(onClick = actions.onConfirmPrestige, enabled = summary.canPrestige) {
                Text(stringResource(R.string.fame_dialog_confirm))
            }
        }
    }
}

@Composable
private fun LostSection(summary: PrestigeSummary, texts: TextProvider, format: GameNumberFormat) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.fame_dialog_lost_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        // Nur Posten mit Bestand; Geld steht immer da, damit der Abschnitt nie leer ist.
        SummaryLine(R.string.fame_dialog_money, stringResource(R.string.top_bar_money_value, format.format(summary.money)))
        NumberLine(R.string.fame_dialog_research, summary.researchPoints, format)
        NumberLine(R.string.fame_dialog_blocks, summary.blocks, format)
        NumberLine(R.string.fame_dialog_teams, summary.teams, format)
        NumberLine(R.string.fame_dialog_preparators, summary.preparators, format)
        NumberLine(R.string.fame_dialog_halls, summary.halls, format)
        NumberLine(R.string.fame_dialog_lab_slots, summary.labSlots, format)
        NumberLine(R.string.fame_dialog_tool_level, summary.toolLevel, format)
        NumberLine(R.string.fame_dialog_layers, summary.lockedLayers, format)
        CountLine(R.string.fame_dialog_pieces, summary.runPieces)
        CountLine(R.string.fame_dialog_spares, summary.spareZones)
        CountLine(R.string.fame_dialog_lab_items, summary.labItems)
        summary.specialExhibit?.let { SummaryLine(R.string.fame_dialog_special_exhibit, texts.speciesName(it)) }
    }
}

@Composable
private fun KeptSection(summary: PrestigeSummary, respec: RespecUi?, format: GameNumberFormat) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.fame_dialog_kept_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        SummaryLine(R.string.fame_dialog_fame_after, format.format(respec?.fameLeft ?: summary.fameAfter))
        SummaryLine(R.string.fame_dialog_prestige_after, format.format(summary.prestigeCountAfter))
        SummaryLine(R.string.fame_dialog_book, summary.bookEntries.toString())
        SummaryLine(R.string.fame_dialog_skills, (respec?.bought?.size ?: summary.skills).toString())
        SummaryLine(R.string.fame_dialog_milestones, summary.milestonesDone.toString())
        Text(
            stringResource(R.string.fame_dialog_kept_other),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NumberLine(label: Int, value: GameNumber, format: GameNumberFormat) {
    if (value > GameNumber.ZERO) SummaryLine(label, format.format(value))
}

@Composable
private fun CountLine(label: Int, value: Int) {
    if (value > 0) SummaryLine(label, value.toString())
}

@Composable
private fun SummaryLine(label: Int, value: String) {
    Text(stringResource(R.string.fame_dialog_line, stringResource(label), value), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SkillChoice(respec: Boolean, onSetRespec: (Boolean) -> Unit) {
    Column(modifier = Modifier.selectableGroup()) {
        Text(stringResource(R.string.fame_dialog_skills_title), style = MaterialTheme.typography.titleSmall)
        ChoiceRow(stringResource(R.string.fame_dialog_keep_skills), selected = !respec) { onSetRespec(false) }
        ChoiceRow(stringResource(R.string.fame_dialog_respec), selected = respec) { onSetRespec(true) }
    }
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RespecSection(respec: RespecUi, texts: TextProvider, format: GameNumberFormat, actions: FameActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.fame_dialog_respec_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.fame_dialog_refund, format.format(respec.refundedFame)), style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.fame_dialog_respec_fame, format.format(respec.fameAvailable), format.format(respec.fameLeft)),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.onClearRespec, enabled = respec.selection.isNotEmpty()) {
                Text(stringResource(R.string.fame_dialog_clear))
            }
        }
        SkillTreeColumns(
            branches = respec.branches,
            texts = texts,
            format = format,
            onNodeClick = { actions.onToggleRespecSkill(it.id) },
            mark = { node ->
                when {
                    respec.isRejected(node.id) -> SelectionMark.REJECTED
                    node.status == SkillNodeStatus.OWNED -> SelectionMark.SELECTED
                    else -> SelectionMark.NONE
                }
            },
            // Abwaehlen geht immer, Anwaehlen nur, wenn der Kauf in der Vorschau gelingt.
            clickable = { node -> node.id in respec.selection || node.affordable },
        )
        if (respec.rejected.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.fame_dialog_rejected_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                for (rejected in respec.rejected) {
                    Text(
                        stringResource(
                            R.string.fame_dialog_rejected_line,
                            texts.text(LocaleKeys.skillName(rejected.skillId)),
                            stringResource(rejected.reason.textRes),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private val SkillPurchaseFailure.textRes: Int
    get() = when (this) {
        SkillPurchaseFailure.UNKNOWN_SKILL -> R.string.fame_reject_unknown
        SkillPurchaseFailure.ALREADY_OWNED -> R.string.fame_reject_owned
        SkillPurchaseFailure.REQUIREMENTS_MISSING -> R.string.fame_reject_requirements
        SkillPurchaseFailure.NOT_ENOUGH_FAME -> R.string.fame_reject_fame
    }

/** Deckkraft gesperrter Knoten. */
private const val LOCKED_ALPHA = 0.55f

// --- Vorschauen ---

/** Spieldaten fuer die Vorschauen (echte Texte und Werte). */
private val previewData by lazy { runCatching { DataRepository().load() }.getOrNull() }

private fun previewTexts(): TextProvider = previewData?.let(TextProvider::fromGameData) ?: TextProvider.EMPTY

/**
 * Beispiel: Hoehlenbaer vollstaendig im Durchlauf (Prestige moeglich), 6 Ruhm, Grabung T1-T2 und
 * Labor T1 gekauft.
 */
private fun previewState(dialog: PrestigeDialogState?): FameUiState {
    val data = previewData ?: return FameUiState.EMPTY
    val modifiers = ModifierEngine.fromGameData(data)
    val engine = GameEngine(data, modifiers)
    val base = GameState.newGame(data.layers.first().id, RunStart.values(data, modifiers), 0L)
    val pieces = data.species("cave_bear").bones.associate { PieceKey("cave_bear", it) to Piece(listOf(0.8, 0.7, 0.9, 0.75)) }
    val state = base.copy(
        meta = base.meta.copy(
            fame = GameNumber(6.0),
            fameTotal = GameNumber(11.0),
            fameSpent = GameNumber(5.0),
            prestigeCount = GameNumber(2.0),
            skills = setOf("dig_1", "dig_2", "lab_1"),
        ),
        run = base.run.copy(pieces = pieces, money = GameNumber(12_345.0), halls = GameNumber(3.0)),
    )
    return FameUiMapper.map(engine, state, dialog)
}

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    FossilCollectorTheme { Surface { content() } }
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun FameScreenPreview() {
    PreviewFrame { FameScreen(previewState(null), Settings(), previewTexts(), FameActions.NONE) }
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun PrestigeDialogKeepPreview() {
    val dialog = previewState(PrestigeDialogState()).dialog ?: return
    PreviewFrame { PrestigeDialogContent(dialog, previewTexts(), GameNumberFormat(NumberLanguage.DE, Settings().numberFormat), FameActions.NONE) }
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun PrestigeDialogRespecPreview() {
    val dialog = previewState(PrestigeDialogState(respec = true, selection = listOf("dig_1", "lab_2", "prep_1"))).dialog ?: return
    PreviewFrame { PrestigeDialogContent(dialog, previewTexts(), GameNumberFormat(NumberLanguage.EN, Settings().numberFormat), FameActions.NONE) }
}

@Preview(showBackground = true)
@Composable
private fun SkillDetailPreview() {
    val node = previewState(null).node("dig_3") ?: return
    PreviewFrame {
        SkillDetailDialog(node, previewTexts(), GameNumberFormat(NumberLanguage.DE, Settings().numberFormat), onBuy = {}, onDismiss = {})
    }
}

package de.noelkoeppen.fossilcollector.ui.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.noelkoeppen.fossilcollector.R
import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.LocaleKeys
import de.noelkoeppen.fossilcollector.core.data.TextProvider
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.ui.GameNumberFormat
import de.noelkoeppen.fossilcollector.ui.museum.qualityPercent
import de.noelkoeppen.fossilcollector.ui.rememberGameNumberFormat
import de.noelkoeppen.fossilcollector.ui.rememberTextProvider
import de.noelkoeppen.fossilcollector.ui.theme.FossilCollectorTheme
import de.noelkoeppen.fossilcollector.ui.theme.LocalQualityColors
import de.noelkoeppen.fossilcollector.ui.theme.QualityColors

/**
 * Willkommen-zurueck-Dialog (AP-23, Kapitel 7.1) als Overlay der Shell: waehrend der Berechnung
 * ein Lade-Indikator (nicht schliessbar), danach Abwesenheit (roh und angerechnet), Geld, FP,
 * Anzahl Funde, die besten neuen Funde in Qualitaetsfarbe und erreichte Meilensteine.
 *
 * @param state Dialogzustand ([WelcomeUiMapper])
 * @param settings fuer Zahlenformat und Sprache der Inhaltstexte
 * @param texts Inhaltstexte (`GameUiState.Ready.texts`), Sprache per [rememberTextProvider]
 * @param onDismiss Schliessen (nur bei [WelcomeUiState.Summary])
 */
@Composable
fun WelcomeBackDialog(
    state: WelcomeUiState,
    settings: Settings,
    texts: TextProvider,
    onDismiss: () -> Unit,
) {
    val calculating = state is WelcomeUiState.Calculating
    Dialog(
        onDismissRequest = { if (!calculating) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !calculating, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            when (state) {
                is WelcomeUiState.Calculating -> CalculatingContent()
                is WelcomeUiState.Summary -> SummaryContent(
                    state = state,
                    format = rememberGameNumberFormat(settings),
                    texts = rememberTextProvider(texts, settings),
                    units = rememberDurationUnits(),
                    onDismiss = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun CalculatingContent() {
    Column(
        modifier = Modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineSmall)
        CircularProgressIndicator()
        Text(
            stringResource(R.string.welcome_calculating),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryContent(
    state: WelcomeUiState.Summary,
    format: GameNumberFormat,
    texts: TextProvider,
    units: DurationUnits,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp, bottom = 8.dp)) {
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineSmall)
            AbsenceSection(state, units)
            HorizontalDivider()
            GainRow(stringResource(R.string.welcome_money_label), stringResource(R.string.welcome_money_value, format.format(state.money)))
            GainRow(
                stringResource(R.string.welcome_research_label),
                stringResource(R.string.welcome_research_value, format.format(state.researchPoints)),
            )
            GainRow(stringResource(R.string.welcome_finds_label), format.format(state.finds))
            if (state.bestFinds.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.welcome_best_title), style = MaterialTheme.typography.titleSmall)
                state.bestFinds.forEach { FindRow(it, texts) }
            }
            if (state.milestoneIds.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.welcome_milestones_title), style = MaterialTheme.typography.titleSmall)
                state.milestoneIds.forEach { id ->
                    Text(texts.text(LocaleKeys.milestoneName(id)), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.welcome_close))
        }
    }
}

@Composable
private fun AbsenceSection(state: WelcomeUiState.Summary, units: DurationUnits) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.welcome_away, formatDuration(state.elapsedSeconds, units)),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(
                R.string.welcome_credit,
                formatDuration(state.creditedSeconds, units),
                state.efficiencyPercent,
                formatDuration(state.effectiveSeconds, units),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.capped) {
            Text(
                stringResource(R.string.welcome_capped, formatDuration(state.capSeconds, units)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GainRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun FindRow(find: WelcomeFindUi, texts: TextProvider) {
    val color = LocalQualityColors.current.forTier(find.tierId) ?: MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Column {
            Text(
                stringResource(R.string.welcome_find_name, texts.speciesName(find.speciesId), texts.boneName(find.boneId)),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.welcome_find_quality, texts.qualityTierName(find.tierId), qualityPercent(find.quality)),
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
    }
}

// --- Vorschauen ---

private val previewData by lazy { runCatching { DataRepository().load() }.getOrNull() }

private fun previewTexts(): TextProvider = previewData?.let(TextProvider::fromGameData) ?: TextProvider.EMPTY

private val previewSummary = WelcomeUiState.Summary(
    elapsedSeconds = 6.5 * 3600,
    creditedSeconds = 4.0 * 3600,
    effectiveSeconds = 2.0 * 3600,
    capSeconds = 4.0 * 3600,
    efficiencyPercent = 50,
    capped = true,
    money = GameNumber(123_456.0),
    researchPoints = GameNumber(42.0),
    finds = GameNumber(1_234.0),
    bestFinds = listOf(
        WelcomeFindUi("cave_bear", "skull", 0.93, "pristine"),
        WelcomeFindUi("giant_deer", "hindlimb", 0.74, "excellent"),
        WelcomeFindUi("cave_bear", "pelvis", 0.55, "good"),
    ),
    milestoneIds = listOf("pieces_1000"),
)

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    val colors = previewData?.balance?.qualityTiers?.let(QualityColors::fromTiers) ?: QualityColors.EMPTY
    FossilCollectorTheme(qualityColors = colors) { Surface(color = Color.Transparent) { content() } }
}

@Preview(showBackground = true)
@Composable
private fun WelcomeSummaryPreview() {
    PreviewFrame {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            SummaryContent(previewSummary, rememberGameNumberFormat(Settings()), previewTexts(), rememberDurationUnits(), onDismiss = {})
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WelcomeCalculatingPreview() {
    PreviewFrame {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            CalculatingContent()
        }
    }
}

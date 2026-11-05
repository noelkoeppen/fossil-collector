package de.noelkoeppen.fossilcollector.ui.lab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.noelkoeppen.fossilcollector.R
import de.noelkoeppen.fossilcollector.core.data.Currency
import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.DefinitionIds
import de.noelkoeppen.fossilcollector.core.data.TextProvider
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseQuote
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.ui.GameNumberFormat
import de.noelkoeppen.fossilcollector.ui.components.BuyButton
import de.noelkoeppen.fossilcollector.ui.components.BuyModeSelector
import de.noelkoeppen.fossilcollector.ui.components.rememberBuyMode
import de.noelkoeppen.fossilcollector.ui.formatPrecise
import de.noelkoeppen.fossilcollector.ui.museum.qualityPercent
import de.noelkoeppen.fossilcollector.ui.rememberGameNumberFormat
import de.noelkoeppen.fossilcollector.ui.rememberTextProvider
import de.noelkoeppen.fossilcollector.ui.theme.FossilCollectorTheme
import de.noelkoeppen.fossilcollector.ui.theme.LocalQualityColors
import de.noelkoeppen.fossilcollector.ui.theme.QualityColors
import kotlin.math.ceil

/**
 * Labor-Screen (AP-19, Kapitel 8.3): Forschungsplaetze mit Fortschrittsbalken und freien Plaetzen,
 * Warteschlange, Laborplaetze kaufen, Werkzeugstufen (FP) und die Liste rekonstruierbarer Knochen
 * mit dem Button "Rekonstruieren" (nur aktiv, wenn die Rekonstruktion eine Verbesserung waere).
 *
 * Der Kaufmodus x1/x10/Max gilt fuer beide Kaeufe des Screens (wie in der Grabung).
 *
 * @param state Labor-Ausschnitt des UI-Zustands ([LabUiMapper])
 * @param settings fuer Zahlenformat und Sprache der Inhaltstexte
 * @param texts Inhaltstexte (`GameUiState.Ready.texts`), Sprache per [rememberTextProvider]
 * @param actions Spieleraktionen ([labActions])
 */
@Composable
fun LabScreen(
    state: LabUiState,
    settings: Settings,
    texts: TextProvider,
    actions: LabActions,
    modifier: Modifier = Modifier,
) {
    val format = rememberGameNumberFormat(settings)
    val localTexts = rememberTextProvider(texts, settings)
    var buyMode by rememberBuyMode()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "slots_header") { SlotsHeader(state, format) }
        slotItems(state, localTexts, format)
        item(key = "queue") { QueueCard(state, localTexts) }
        item(key = "buy_mode") {
            BuyModeSelector(selected = buyMode, onSelect = { buyMode = it }, modifier = Modifier.fillMaxWidth())
        }
        item(key = "buy_slots") {
            LabSlotPurchaseCard(state, localTexts, format, buyMode, actions.onBuyLabSlots)
        }
        item(key = "tools") { ToolsCard(state, localTexts, format, buyMode, actions.onBuyToolLevel) }
        item(key = "recon_header") { ReconstructionHeader(state) }
        items(count = state.reconstructions.size, key = { "recon_" + state.reconstructions[it].key }) { index ->
            ReconstructionCard(state.reconstructions[index], localTexts, actions.onReconstruct)
        }
    }
}

@Composable
private fun SlotsHeader(state: LabUiState, format: GameNumberFormat) {
    Column {
        Text(stringResource(R.string.lab_slots_title), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(R.string.lab_slots_summary, state.jobs.size, state.slotCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.lab_duration, format.formatPrecise(GameNumber(state.jobDuration))),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Ein Eintrag je Forschungsplatz: laufende Forschungen, danach die freien Plaetze. Schluessel sind
 * die Platznummern, damit ein Platz beim Wechsel auf das naechste Stueck nicht neu aufgebaut wird.
 */
private fun LazyListScope.slotItems(state: LabUiState, texts: TextProvider, format: GameNumberFormat) {
    if (state.slotCount == 0 && state.jobs.isEmpty()) {
        item(key = "no_slots") {
            Text(
                text = stringResource(R.string.lab_no_slots),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    items(count = state.jobs.size, key = { "slot_$it" }) { index ->
        JobCard(state.jobs[index], texts, format)
    }
    items(count = state.freeSlots, key = { "slot_${state.jobs.size + it}" }) {
        FreeSlotCard()
    }
}

/** Laufende Forschung: Art, Knochen, Stufe, Fortschrittsbalken (ohne Animation), Restzeit, FP. */
@Composable
private fun JobCard(job: LabJobUi, texts: TextProvider, format: GameNumberFormat) {
    val tierColor = tierColor(job.tierId)
    OutlinedCard(modifier = Modifier.fillMaxWidth(), border = BorderStroke(2.dp, tierColor)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PieceTitle(job.speciesId, job.boneId, texts)
            Text(
                text = stringResource(R.string.lab_piece_quality, texts.qualityTierName(job.tierId), qualityPercent(job.quality)),
                style = MaterialTheme.typography.labelSmall,
                color = tierColor,
            )
            // Fortschritt direkt aus remaining / duration; bewusst keine Animation (kein Flackern
            // bzw. Zurueckschwingen, wenn ein Platz das naechste Stueck beginnt).
            LinearProgressIndicator(progress = { job.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
            Row {
                Text(
                    text = stringResource(R.string.lab_job_remaining, ceil(job.remainingSeconds).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.lab_job_points, format.formatPrecise(job.researchPoints)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun FreeSlotCard() {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(stringResource(R.string.lab_slot_free), style = MaterialTheme.typography.labelLarge)
            Text(
                text = stringResource(R.string.lab_slot_free_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QueueCard(state: LabUiState, texts: TextProvider) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.lab_queue_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.lab_queue_summary, state.queue.size, state.queueCapacity),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.queue.isEmpty()) {
                Text(
                    text = stringResource(R.string.lab_queue_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.queue.forEach { queued ->
                val tierColor = tierColor(queued.tierId)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.lab_piece_name, texts.speciesName(queued.speciesId), texts.boneName(queued.boneId)),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(
                            R.string.lab_piece_quality,
                            texts.qualityTierName(queued.tierId),
                            qualityPercent(queued.quality),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = tierColor,
                        maxLines = 1,
                    )
                }
            }
            if (state.queueFull && state.queueCapacity > 0) {
                Text(
                    text = stringResource(R.string.lab_queue_full),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun LabSlotPurchaseCard(
    state: LabUiState,
    texts: TextProvider,
    format: GameNumberFormat,
    mode: BuyMode,
    onBuy: (BuyMode) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.lab_buy_slots_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.lab_slots_owned, format.format(state.labSlots)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            BuyButton(
                title = texts.generatorName(DefinitionIds.LAB_SLOT),
                quote = state.labSlotQuotes[mode],
                format = format,
                onClick = { onBuy(mode) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ToolsCard(
    state: LabUiState,
    texts: TextProvider,
    format: GameNumberFormat,
    mode: BuyMode,
    onBuy: (BuyMode) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.lab_tools_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.lab_tools_level, format.format(state.toolLevel)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = stringResource(
                    R.string.lab_tools_effect,
                    format.formatPrecise(GameNumber(state.damageReduction)),
                    format.formatPrecise(GameNumber(state.damageReductionPerLevel)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            val quote = state.toolQuotes[mode]
            BuyButton(
                title = texts.upgradeName(DefinitionIds.TOOLS),
                quote = quote,
                format = format,
                onClick = { onBuy(mode) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.toolQuotes.isNotEmpty() && state.toolQuotes.values.all { it == null }) {
                Text(
                    text = stringResource(R.string.lab_tools_max),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReconstructionHeader(state: LabUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.lab_recon_title), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(R.string.lab_recon_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.reconstructions.isEmpty()) {
            Text(
                text = stringResource(R.string.lab_recon_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Ein rekonstruierbarer Knochen: erwartete Qualitaet, fehlende Zonen, Verbesserung, Button. */
@Composable
private fun ReconstructionCard(item: ReconstructionUi, texts: TextProvider, onReconstruct: (PieceKey) -> Unit) {
    val tierColor = tierColor(item.tierId)
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PieceTitle(item.speciesId, item.boneId, texts)
            Text(
                text = stringResource(R.string.lab_recon_expected, texts.qualityTierName(item.tierId), qualityPercent(item.quality)),
                style = MaterialTheme.typography.labelMedium,
                color = tierColor,
            )
            if (item.missingZones.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.lab_recon_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (zoneId in item.missingZones) {
                        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                text = texts.zoneName(item.boneId, zoneId),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            Text(
                text = stringResource(improvementRes(item)),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.isImprovement) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { onReconstruct(item.pieceKey) },
                enabled = item.isImprovement,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.lab_recon_button))
            }
        }
    }
}

private fun improvementRes(item: ReconstructionUi): Int = when {
    item.improvesRun && item.improvesBook -> R.string.lab_recon_improves_both
    item.improvesRun -> R.string.lab_recon_improves_run
    item.improvesBook -> R.string.lab_recon_improves_book
    else -> R.string.lab_recon_no_improvement
}

@Composable
private fun PieceTitle(speciesId: String, boneId: String, texts: TextProvider) {
    Column {
        Text(
            text = texts.speciesName(speciesId),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = texts.boneName(boneId),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun tierColor(tierId: String): Color =
    LocalQualityColors.current.forTier(tierId) ?: MaterialTheme.colorScheme.outline

// --- Vorschauen ---

/** Spieldaten fuer die Vorschauen (echte Farben und Texte, keine doppelten Werte). */
private val previewData by lazy { runCatching { DataRepository().load() }.getOrNull() }

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    val colors = remember { previewData?.let { QualityColors.fromTiers(it.balance.qualityTiers) } ?: QualityColors.EMPTY }
    FossilCollectorTheme(qualityColors = colors) { Surface { content() } }
}

private fun previewTexts(): TextProvider = previewData?.let(TextProvider::fromGameData) ?: TextProvider.EMPTY

private fun previewState(): LabUiState = LabUiState(
    slotCount = 3,
    jobs = listOf(
        LabJobUi("cave_bear", "skull", "good", 0.62, 0.4, 18.0, GameNumber(2.0)),
        LabJobUi("giant_deer", "jaw", "excellent", 0.81, 0.85, 4.5, GameNumber(2.0)),
    ),
    jobDuration = 30.0,
    queue = listOf(
        QueuedPieceUi("woolly_rhino", "skull", "damaged", 0.41),
        QueuedPieceUi("cave_bear", "jaw", "fragment", 0.18),
    ),
    queueCapacity = 9,
    labSlots = GameNumber(3.0),
    labSlotQuotes = mapOf(
        BuyMode.X1 to PurchaseQuote(GameNumber.ONE, GameNumber(4_800.0), Currency.MONEY, true),
        BuyMode.X10 to PurchaseQuote(GameNumber(10.0), GameNumber(1.0e9), Currency.MONEY, false),
        BuyMode.MAX to PurchaseQuote(GameNumber.ONE, GameNumber(4_800.0), Currency.MONEY, true),
    ),
    toolLevel = GameNumber(2.0),
    damageReduction = 0.07,
    damageReductionPerLevel = 0.03,
    toolQuotes = mapOf(
        BuyMode.X1 to PurchaseQuote(GameNumber.ONE, GameNumber(7.68), Currency.RESEARCH_POINTS, true),
        BuyMode.X10 to PurchaseQuote(GameNumber(10.0), GameNumber(2_200.0), Currency.RESEARCH_POINTS, false),
        BuyMode.MAX to PurchaseQuote(GameNumber.ONE, GameNumber(7.68), Currency.RESEARCH_POINTS, true),
    ),
    reconstructions = listOf(
        ReconstructionUi(
            pieceKey = PieceKey("cave_bear", "skull"),
            key = PieceKey("cave_bear", "skull").toString(),
            speciesId = "cave_bear",
            boneId = "skull",
            tierId = "excellent",
            quality = 0.86,
            missingZones = emptyList(),
            improvesRun = true,
            improvesBook = true,
        ),
        ReconstructionUi(
            pieceKey = PieceKey("giant_deer", "jaw"),
            key = PieceKey("giant_deer", "jaw").toString(),
            speciesId = "giant_deer",
            boneId = "jaw",
            tierId = "good",
            quality = 0.64,
            missingZones = listOf("tip"),
            improvesRun = false,
            improvesBook = false,
        ),
    ),
)

@Preview(showBackground = true, heightDp = 1800)
@Composable
private fun LabScreenPreview() {
    PreviewFrame { LabScreen(previewState(), Settings(), previewTexts(), LabActions.NONE) }
}

@Preview(showBackground = true, heightDp = 1000)
@Composable
private fun LabScreenEmptyPreview() {
    PreviewFrame {
        LabScreen(
            previewState().copy(slotCount = 1, jobs = emptyList(), queue = emptyList(), queueCapacity = 3, reconstructions = emptyList()),
            Settings(),
            previewTexts(),
            LabActions.NONE,
        )
    }
}

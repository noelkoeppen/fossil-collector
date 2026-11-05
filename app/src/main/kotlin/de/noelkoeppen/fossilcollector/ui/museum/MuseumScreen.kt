package de.noelkoeppen.fossilcollector.ui.museum

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.ui.GameNumberFormat
import de.noelkoeppen.fossilcollector.ui.components.BuyButton
import de.noelkoeppen.fossilcollector.ui.components.BuyModeSelector
import de.noelkoeppen.fossilcollector.ui.components.rememberBuyMode
import de.noelkoeppen.fossilcollector.ui.rememberGameNumberFormat
import de.noelkoeppen.fossilcollector.ui.rememberTextProvider
import de.noelkoeppen.fossilcollector.ui.theme.FossilCollectorTheme
import de.noelkoeppen.fossilcollector.ui.theme.LocalQualityColors
import de.noelkoeppen.fossilcollector.ui.theme.QualityColors

/** Spalten des Hallen-Rasters (Hochformat, kleine Karten). */
private const val GRID_COLUMNS = 2

/**
 * Museum-Screen (AP-18, Kapitel 8.3): Einnahmen/s, Hallen kaufen, Skelett-Boni, Sonderausstellung
 * (nur mit Capstone) und Hallen als Raster der ausgestellten Stuecke inkl. leerer Plaetze.
 *
 * @param state Museums-Ausschnitt des UI-Zustands ([MuseumUiMapper])
 * @param settings fuer Zahlenformat und Sprache der Inhaltstexte
 * @param texts Inhaltstexte (`GameUiState.Ready.texts`), Sprache per [rememberTextProvider]
 * @param actions Spieleraktionen ([museumActions])
 */
@Composable
fun MuseumScreen(
    state: MuseumUiState,
    settings: Settings,
    texts: TextProvider,
    actions: MuseumActions,
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
        item(key = "income") { IncomeHeader(state, format) }
        item(key = "buy") {
            HallPurchaseCard(
                state = state,
                texts = localTexts,
                format = format,
                mode = buyMode,
                onModeChange = { buyMode = it },
                onBuy = actions.onBuyHalls,
            )
        }
        item(key = "skeletons") { SkeletonBonusCard(state, localTexts, format) }
        state.specialExhibit?.let { special ->
            item(key = "special") { SpecialExhibitCard(special, localTexts, format, actions.onSelectSpecialExhibit) }
        }
        if (state.exhibits.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = stringResource(R.string.museum_no_exhibits),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(count = state.hallCount, key = { "hall_$it" }) { hallIndex ->
            HallGrid(hallIndex = hallIndex, slots = state.hallSlots(hallIndex), texts = localTexts, format = format)
        }
    }
}

@Composable
private fun IncomeHeader(state: MuseumUiState, format: GameNumberFormat) {
    Column {
        Text(
            text = stringResource(R.string.museum_income_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.museum_income_value, format.format(state.incomePerSecond)),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.museum_slots_summary, state.exhibits.size, state.slotCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HallPurchaseCard(
    state: MuseumUiState,
    texts: TextProvider,
    format: GameNumberFormat,
    mode: BuyMode,
    onModeChange: (BuyMode) -> Unit,
    onBuy: (BuyMode) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.museum_buy_halls_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.museum_halls_owned, format.format(state.halls)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            BuyModeSelector(selected = mode, onSelect = onModeChange, modifier = Modifier.fillMaxWidth())
            BuyButton(
                title = texts.generatorName(DefinitionIds.HALL),
                quote = state.hallQuotes[mode],
                format = format,
                onClick = { onBuy(mode) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SkeletonBonusCard(state: MuseumUiState, texts: TextProvider, format: GameNumberFormat) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.museum_skeletons_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.museum_skeletons_hint, formatFactor(state.skeletonFactor, format.language)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (state.completeSkeletons.isEmpty()) {
                Text(
                    text = stringResource(R.string.museum_skeletons_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (speciesId in state.completeSkeletons) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = texts.speciesName(speciesId),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpecialExhibitCard(
    special: SpecialExhibitUi,
    texts: TextProvider,
    format: GameNumberFormat,
    onSelect: (String?) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.museum_special_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.museum_special_hint, formatFactor(special.factor, format.language)),
                style = MaterialTheme.typography.bodyMedium,
            )
            special.selectedSpeciesId?.let {
                Text(
                    text = stringResource(R.string.museum_special_current, texts.speciesName(it)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (special.selectedSpeciesId != null && !special.active) {
                Text(
                    text = stringResource(R.string.museum_special_inactive),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (special.options.isEmpty()) {
                Text(stringResource(R.string.museum_special_no_options), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = special.selectedSpeciesId == null,
                    onClick = { onSelect(null) },
                    label = { Text(stringResource(R.string.museum_special_none)) },
                )
                for (speciesId in special.options) {
                    FilterChip(
                        selected = speciesId == special.selectedSpeciesId,
                        onClick = { onSelect(speciesId) },
                        label = { Text(texts.speciesName(speciesId)) },
                    )
                }
            }
        }
    }
}

/** Eine Halle als Raster aus [GRID_COLUMNS] Spalten; `null` = leerer Platz. */
@Composable
private fun HallGrid(hallIndex: Int, slots: List<ExhibitUi?>, texts: TextProvider, format: GameNumberFormat) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.museum_hall_title, hallIndex + 1),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (row in slots.chunked(GRID_COLUMNS)) {
            Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (slot in row) {
                    val cellModifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 96.dp)
                    if (slot == null) EmptySlotCard(cellModifier) else ExhibitCard(slot, texts, format, cellModifier)
                }
                repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Kleine Karte eines ausgestellten Stuecks: Art, Knochen, Qualitaetsstufe in Farbe, $/s. */
@Composable
private fun ExhibitCard(exhibit: ExhibitUi, texts: TextProvider, format: GameNumberFormat, modifier: Modifier = Modifier) {
    val tierColor = LocalQualityColors.current.forTier(exhibit.tierId) ?: MaterialTheme.colorScheme.outline
    OutlinedCard(modifier = modifier, border = BorderStroke(2.dp, tierColor)) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = texts.speciesName(exhibit.speciesId),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = texts.boneName(exhibit.boneId),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.museum_piece_quality,
                    texts.qualityTierName(exhibit.tierId),
                    qualityPercent(exhibit.quality),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = tierColor,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.museum_piece_income, format.format(exhibit.incomePerSecond)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            when {
                exhibit.specialExhibit -> Badge(stringResource(R.string.museum_piece_special))
                exhibit.skeletonComplete -> Badge(stringResource(R.string.museum_piece_skeleton))
            }
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
        maxLines = 1,
    )
}

@Composable
private fun EmptySlotCard(modifier: Modifier = Modifier) {
    OutlinedCard(
        modifier = modifier,
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.museum_empty_slot),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Vorschauen ---

/** Spieldaten fuer die Vorschauen (echte Farben und Texte, keine doppelten Werte). */
private val previewData by lazy { runCatching { DataRepository().load() }.getOrNull() }

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    val colors = remember { previewData?.let { QualityColors.fromTiers(it.balance.qualityTiers) } ?: QualityColors.EMPTY }
    FossilCollectorTheme(qualityColors = colors) { Surface { content() } }
}

private fun previewTexts(): TextProvider = previewData?.let(TextProvider::fromGameData) ?: TextProvider.EMPTY

private fun previewExhibit(
    index: Int,
    species: String,
    bone: String,
    tierId: String,
    quality: Double,
    income: Double,
    skeleton: Boolean = false,
    special: Boolean = false,
) = ExhibitUi(
    key = "preview_$index",
    speciesId = species,
    boneId = bone,
    tierId = tierId,
    quality = quality,
    incomePerSecond = GameNumber(income),
    skeletonComplete = skeleton,
    specialExhibit = special,
)

private fun previewState(withCapstone: Boolean): MuseumUiState {
    val exhibits = listOf(
        previewExhibit(0, "cave_bear", "skull", "pristine", 0.93, 7.2, skeleton = true, special = withCapstone),
        previewExhibit(1, "cave_bear", "jaw", "excellent", 0.8, 6.5, skeleton = true, special = withCapstone),
        previewExhibit(2, "giant_deer", "cervical", "good", 0.6, 1.1),
        previewExhibit(3, "woolly_rhino", "skull", "damaged", 0.4, 0.9),
        previewExhibit(4, "woolly_mammoth", "jaw", "fragment", 0.2, 0.7),
    )
    return MuseumUiState(
        incomePerSecond = GameNumber(16.4),
        halls = GameNumber(2.0),
        slotsPerHall = 8,
        slotCount = 16,
        exhibits = exhibits,
        hallQuotes = mapOf(
            BuyMode.X1 to PurchaseQuote(GameNumber.ONE, GameNumber(150.0), Currency.MONEY, true),
            BuyMode.X10 to PurchaseQuote(GameNumber(10.0), GameNumber(4_428_000.0), Currency.MONEY, false),
            BuyMode.MAX to PurchaseQuote(GameNumber.ONE, GameNumber(150.0), Currency.MONEY, true),
        ),
        skeletonFactor = 2.5,
        completeSkeletons = listOf("cave_bear"),
        specialExhibit = if (withCapstone) SpecialExhibitUi(5.0, "cave_bear", true, listOf("cave_bear")) else null,
    )
}

@Preview(showBackground = true, heightDp = 1200)
@Composable
private fun MuseumScreenPreview() {
    PreviewFrame { MuseumScreen(previewState(withCapstone = false), Settings(), previewTexts(), MuseumActions.NONE) }
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun MuseumScreenCapstonePreview() {
    PreviewFrame { MuseumScreen(previewState(withCapstone = true), Settings(), previewTexts(), MuseumActions.NONE) }
}

@Preview(showBackground = true)
@Composable
private fun MuseumScreenEmptyPreview() {
    PreviewFrame {
        MuseumScreen(
            previewState(withCapstone = false).copy(
                incomePerSecond = GameNumber.ZERO,
                halls = GameNumber.ONE,
                slotCount = 8,
                exhibits = emptyList(),
                completeSkeletons = emptyList(),
            ),
            Settings(),
            previewTexts(),
            MuseumActions.NONE,
        )
    }
}

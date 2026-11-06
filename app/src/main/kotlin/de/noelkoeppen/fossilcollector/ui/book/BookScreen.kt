package de.noelkoeppen.fossilcollector.ui.book

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.noelkoeppen.fossilcollector.R
import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.TextProvider
import de.noelkoeppen.fossilcollector.core.engine.CompletionLevel
import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.RunStart
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.number.NumberLanguage
import de.noelkoeppen.fossilcollector.ui.museum.formatFactor
import de.noelkoeppen.fossilcollector.ui.museum.qualityPercent
import de.noelkoeppen.fossilcollector.ui.rememberGameNumberFormat
import de.noelkoeppen.fossilcollector.ui.rememberTextProvider
import de.noelkoeppen.fossilcollector.ui.theme.FossilCollectorTheme
import de.noelkoeppen.fossilcollector.ui.theme.LocalQualityColors
import de.noelkoeppen.fossilcollector.ui.theme.QualityColors
import de.noelkoeppen.fossilcollector.ui.theme.Rock500

/** Spalten des Arten-Rasters (Hochformat). */
private const val GRID_COLUMNS = 3

/**
 * Sammelbuch-Screen (AP-20, Kapitel 4.7 und 8.3): Reiter pro Schicht (auch gesperrte), Raster der
 * Arten mit Completion-Symbol und, nach Antippen einer entdeckten Art, das Art-Detail mit
 * Skelett-Grafik ([SkeletonView]), Knochenliste mit Zonen-Balken und Boni.
 *
 * Navigation innerhalb des Tabs: die gewaehlte Art steht im Screen-Zustand; die Zurueck-Taste
 * (bzw. der Pfeil) fuehrt vom Detail zurueck zur Liste.
 *
 * @param state Sammelbuch-Ausschnitt des UI-Zustands ([BookUiMapper])
 * @param settings fuer Zahlenformat und Sprache der Inhaltstexte
 * @param texts Inhaltstexte (`GameUiState.Ready.texts`), Sprache per [rememberTextProvider]
 */
@Composable
fun BookScreen(
    state: BookUiState,
    settings: Settings,
    texts: TextProvider,
    modifier: Modifier = Modifier,
) {
    val localTexts = rememberTextProvider(texts, settings)
    val language = rememberGameNumberFormat(settings).language
    var selectedLayer by rememberSaveable { mutableIntStateOf(0) }
    var selectedSpecies by rememberSaveable { mutableStateOf<String?>(null) }
    val detail = selectedSpecies?.let(state::species)

    BackHandler(enabled = detail != null) { selectedSpecies = null }

    if (detail != null) {
        SpeciesDetail(
            species = detail,
            texts = localTexts,
            language = language,
            onBack = { selectedSpecies = null },
            modifier = modifier,
        )
    } else {
        BookList(
            state = state,
            texts = localTexts,
            selectedLayer = selectedLayer.coerceIn(0, (state.layers.size - 1).coerceAtLeast(0)),
            onSelectLayer = { selectedLayer = it },
            onSelectSpecies = { selectedSpecies = it },
            modifier = modifier,
        )
    }
}

// --- Liste ---

@Composable
private fun BookList(
    state: BookUiState,
    texts: TextProvider,
    selectedLayer: Int,
    onSelectLayer: (Int) -> Unit,
    onSelectSpecies: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layer = state.layers.getOrNull(selectedLayer)
    Column(modifier = modifier.fillMaxSize()) {
        if (layer == null) {
            Text(
                text = stringResource(R.string.book_no_layers),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }
        TabRow(selectedTabIndex = selectedLayer) {
            state.layers.forEachIndexed { index, tabLayer ->
                Tab(
                    selected = index == selectedLayer,
                    onClick = { onSelectLayer(index) },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!tabLayer.unlocked) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = stringResource(R.string.book_layer_locked),
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(texts.layerName(tabLayer.layerId), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) { LayerHeader(layer) }
            items(layer.species, key = { it.speciesId }) { species ->
                SpeciesCell(species, texts, onClick = { onSelectSpecies(species.speciesId) })
            }
        }
    }
}

@Composable
private fun LayerHeader(layer: BookLayerUi) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.book_layer_progress, layer.discoveredCount, layer.species.size, layer.completeCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!layer.unlocked) {
            Text(
                text = stringResource(R.string.book_layer_locked_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Kachel einer Art: unbekannt = dunkel mit "?", sonst Name, Knochen-Fortschritt und Stufen-Symbol. */
@Composable
private fun SpeciesCell(species: BookSpeciesUi, texts: TextProvider, onClick: () -> Unit) {
    val cellModifier = Modifier.fillMaxWidth().heightIn(min = 104.dp)
    if (!species.discovered) {
        Card(
            modifier = cellModifier,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 104.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.book_unknown_mark),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Rock500,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        return
    }
    val tierColor = species.minTierIndex?.let { LocalQualityColors.current.forTierIndex(it) }
    OutlinedCard(
        onClick = onClick,
        modifier = cellModifier,
        border = BorderStroke(if (tierColor != null) 2.dp else 1.dp, tierColor ?: MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CompletionSymbol(species.level, tierColor, Modifier.align(Alignment.End))
            Text(
                text = texts.speciesName(species.speciesId),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.book_bones_progress, species.bonesInBook, species.bonesTotal),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Symbol der Completion-Stufe: Haken (Stufe 1), Stern in Farbe der schlechtesten Stufe (Stufe 2). */
@Composable
private fun CompletionSymbol(level: CompletionLevel, tierColor: Color?, modifier: Modifier = Modifier) {
    val size = Modifier.size(18.dp)
    when (level) {
        CompletionLevel.NONE -> Spacer(modifier.then(size))
        CompletionLevel.RESEARCHED -> Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = stringResource(R.string.book_level_researched),
            tint = MaterialTheme.colorScheme.secondary,
            modifier = modifier.then(size),
        )
        CompletionLevel.SKELETON_COMPLETE -> Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = stringResource(R.string.book_level_complete),
            tint = tierColor ?: MaterialTheme.colorScheme.primary,
            modifier = modifier.then(size),
        )
    }
}

// --- Art-Detail ---

@Composable
private fun SpeciesDetail(
    species: BookSpeciesUi,
    texts: TextProvider,
    language: NumberLanguage,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 8.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") { DetailHeader(species, texts, onBack) }
        item(key = "skeleton") {
            SkeletonView(
                speciesId = species.speciesId,
                bodyPlan = species.bodyPlan,
                bones = species.skeleton,
                modifier = Modifier.padding(start = 8.dp),
                contentDescription = stringResource(R.string.book_skeleton_description, species.bonesInBook, species.bonesTotal),
            )
        }
        item(key = "bonus") {
            BonusCard(species, texts, language, Modifier.padding(start = 8.dp))
        }
        item(key = "bones_title") {
            Text(
                text = stringResource(R.string.book_bones_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        items(species.bones, key = { it.boneId }) { bone ->
            BoneRow(bone, texts, Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun DetailHeader(species: BookSpeciesUi, texts: TextProvider, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.book_back))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = texts.speciesName(species.speciesId),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(levelText(species.level)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            species.minTierId?.let {
                Text(
                    text = stringResource(R.string.book_min_tier, texts.qualityTierName(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalQualityColors.current.forTier(it) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val tierColor = species.minTierIndex?.let { LocalQualityColors.current.forTierIndex(it) }
        CompletionSymbol(species.level, tierColor, Modifier.padding(end = 4.dp))
    }
}

private fun levelText(level: CompletionLevel): Int = when (level) {
    CompletionLevel.NONE -> R.string.book_level_none
    CompletionLevel.RESEARCHED -> R.string.book_level_researched
    CompletionLevel.SKELETON_COMPLETE -> R.string.book_level_complete
}

/** Buch-Bonus (Stufe 2) und Forschungs-Bonus (Stufe 1) der Art. */
@Composable
private fun BonusCard(species: BookSpeciesUi, texts: TextProvider, language: NumberLanguage, modifier: Modifier = Modifier) {
    val bonus = species.bookBonus
    val researched = species.researchedBonus
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BonusTitle(stringResource(R.string.book_bonus_title), bonus.active)
            val statName = texts.statName(bonus.stat)
            if (bonus.active && bonus.value != null) {
                Text(
                    text = stringResource(R.string.book_bonus_stat_value, statName, bonusText(bonus.value, bonus.op, bonus.stat, language)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                val tierId = species.minTierId
                if (bonus.factor != null && tierId != null) {
                    Text(
                        text = stringResource(
                            R.string.book_bonus_factor,
                            formatFactor(bonus.factor, language),
                            texts.qualityTierName(tierId),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(text = statName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(
                        R.string.book_bonus_inactive,
                        bonusText(bonus.minValue, bonus.op, bonus.stat, language),
                        bonusText(bonus.maxValue, bonus.op, bonus.stat, language),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            BonusTitle(stringResource(R.string.book_researched_title), researched.active)
            Text(
                text = stringResource(
                    R.string.book_bonus_stat_value,
                    texts.statName(researched.stat),
                    bonusText(researched.value, researched.op, researched.stat, language),
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = if (researched.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (!researched.active) {
                Text(
                    text = stringResource(R.string.book_researched_inactive),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BonusTitle(title: String, active: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(
            text = stringResource(if (active) R.string.book_bonus_active_label else R.string.book_bonus_inactive_label),
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun bonusText(value: Double, op: ModifierOp, stat: StatId, language: NumberLanguage): String {
    val number = formatBonusNumber(value, op, stat, language)
    return if (bonusShownAsPercent(op, stat)) {
        stringResource(R.string.book_bonus_percent, number)
    } else {
        stringResource(R.string.book_bonus_add, number)
    }
}

/** Ein Knochen: Name, Stufe und Qualitaet in %, Flag "rekonstruiert", Zonen-Balken. */
@Composable
private fun BoneRow(bone: BookBoneUi, texts: TextProvider, modifier: Modifier = Modifier) {
    val colors = LocalQualityColors.current
    val tierColor = bone.tierId?.let { colors.forTier(it) }
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, tierColor ?: MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = texts.boneName(bone.boneId),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                val quality = bone.quality
                if (quality != null && bone.tierId != null) {
                    Text(
                        text = stringResource(R.string.book_bone_quality, texts.qualityTierName(bone.tierId), qualityPercent(quality)),
                        style = MaterialTheme.typography.labelMedium,
                        color = tierColor ?: MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.book_bone_missing),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (bone.reconstructed) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        text = stringResource(R.string.book_bone_reconstructed),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (bone.inBook) {
                for (zone in bone.zones) ZoneBar(bone.boneId, zone, texts)
            }
        }
    }
}

@Composable
private fun ZoneBar(boneId: String, zone: BookZoneUi, texts: TextProvider) {
    val quality = zone.quality
    val color = quality?.let { LocalQualityColors.current.forQuality(it) } ?: MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = texts.zoneName(boneId, zone.zoneId),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.45f),
        )
        LinearProgressIndicator(
            progress = { quality?.toFloat()?.coerceIn(0f, 1f) ?: 0f },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = quality?.let { stringResource(R.string.book_zone_quality, qualityPercent(it)) }
                ?: stringResource(R.string.book_zone_unknown),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.15f),
        )
    }
}

// --- Vorschauen ---

/** Spieldaten fuer die Vorschauen (echte Farben, Texte und Werte). */
private val previewData by lazy { runCatching { DataRepository().load() }.getOrNull() }

@Composable
private fun PreviewFrame(content: @Composable () -> Unit) {
    val colors = remember { previewData?.let { QualityColors.fromTiers(it.balance.qualityTiers) } ?: QualityColors.EMPTY }
    FossilCollectorTheme(qualityColors = colors) { Surface { content() } }
}

private fun previewTexts(): TextProvider = previewData?.let(TextProvider::fromGameData) ?: TextProvider.EMPTY

/** Beispiel-Sammelbuch: Hoehlenbaer vollstaendig (ein Knochen rekonstruiert), Riesenhirsch teilweise. */
private fun previewState(): BookUiState {
    val data = previewData ?: return BookUiState.EMPTY
    val modifiers = ModifierEngine.fromGameData(data)
    val engine = GameEngine(data, modifiers)
    val base = GameState.newGame(data.layers.first().id, RunStart.values(data, modifiers), 0L)
    val qualities = listOf(0.95, 0.82, 0.6, 0.74, 0.55, 0.91, 0.66, 0.78)
    val book = buildMap {
        data.species("cave_bear").bones.forEachIndexed { i, boneId ->
            val q = qualities[i % qualities.size]
            put(
                PieceKey("cave_bear", boneId),
                BookEntry(q, reconstructed = i == 2, zones = listOf(q + 0.04, q - 0.06, q + 0.02, q)),
            )
        }
        data.species("giant_deer").bones.take(3).forEach { put(PieceKey("giant_deer", it), BookEntry(0.3)) }
    }
    val state = base.copy(
        meta = base.meta.copy(book = book, researchedSpecies = setOf("cave_bear")),
        run = base.run.copy(unlockedLayers = GameNumber(2.0)),
    )
    return BookUiMapper(data).map(engine, state)
}

@Preview(showBackground = true, heightDp = 700)
@Composable
private fun BookListPreview() {
    PreviewFrame { BookScreen(previewState(), Settings(), previewTexts()) }
}

@Preview(showBackground = true, heightDp = 1600)
@Composable
private fun SpeciesDetailCompletePreview() {
    val species = previewState().species("cave_bear") ?: return
    PreviewFrame { SpeciesDetail(species, previewTexts(), NumberLanguage.DE, onBack = {}) }
}

@Preview(showBackground = true, heightDp = 1600)
@Composable
private fun SpeciesDetailPartialPreview() {
    val species = previewState().species("giant_deer") ?: return
    PreviewFrame { SpeciesDetail(species, previewTexts(), NumberLanguage.EN, onBack = {}) }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun SkeletonViewPreview() {
    val species = previewState().species("cave_bear") ?: return
    PreviewFrame { SkeletonView(species.speciesId, species.bodyPlan, species.skeleton, Modifier.padding(8.dp)) }
}

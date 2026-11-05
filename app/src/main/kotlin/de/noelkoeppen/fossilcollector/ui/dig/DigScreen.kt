package de.noelkoeppen.fossilcollector.ui.dig

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.noelkoeppen.fossilcollector.R
import de.noelkoeppen.fossilcollector.core.data.Currency
import de.noelkoeppen.fossilcollector.core.data.DataRepository
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
import de.noelkoeppen.fossilcollector.ui.rememberGameNumberFormat
import de.noelkoeppen.fossilcollector.ui.rememberTextProvider
import de.noelkoeppen.fossilcollector.ui.theme.FossilCollectorTheme
import de.noelkoeppen.fossilcollector.ui.theme.LocalQualityColors
import de.noelkoeppen.fossilcollector.ui.theme.QualityColors
import de.noelkoeppen.fossilcollector.vm.DigActions
import de.noelkoeppen.fossilcollector.vm.DigLayerUi
import de.noelkoeppen.fossilcollector.vm.DigUiState
import de.noelkoeppen.fossilcollector.vm.FindPopup
import de.noelkoeppen.fossilcollector.vm.FindPopupKind
import de.noelkoeppen.fossilcollector.vm.GameUiState
import de.noelkoeppen.fossilcollector.vm.PrepUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Screen Grabung (Kapitel 8.3, AP-17): Schicht-Querschnitt (oben jung, unten alt), Kaufmodus,
 * darunter die Praeparation. Fund-Popups aus [findPopups] erscheinen kurz oben als Karte.
 *
 * Alle Werte kommen fertig aus [GameUiState.Ready.dig]; hier wird nur formatiert und angezeigt.
 */
@Composable
fun DigScreen(
    state: GameUiState.Ready,
    actions: DigActions,
    findPopups: Flow<FindPopup>,
    modifier: Modifier = Modifier,
) {
    val format = rememberGameNumberFormat(state.settings)
    val texts = rememberTextProvider(state.texts, state.settings)
    var buyMode by rememberBuyMode()
    var popup by remember { mutableStateOf<FindPopup?>(null) }

    LaunchedEffect(findPopups) { findPopups.collect { popup = it } }
    LaunchedEffect(popup) {
        if (popup != null) {
            delay(POPUP_VISIBLE_MILLIS)
            popup = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        DigContent(
            dig = state.dig,
            buyMode = buyMode,
            onBuyModeChange = { buyMode = it },
            format = format,
            texts = texts,
            actions = actions,
        )
        popup?.let {
            FindPopupCard(
                popup = it,
                texts = texts,
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
            )
        }
    }
}

/** Anzeigedauer eines Fund-Popups; neue Popups (hoechstens 1/s) ersetzen das aktuelle. */
private const val POPUP_VISIBLE_MILLIS = 2_500L

@Composable
private fun DigContent(
    dig: DigUiState,
    buyMode: BuyMode,
    onBuyModeChange: (BuyMode) -> Unit,
    format: GameNumberFormat,
    texts: TextProvider,
    actions: DigActions,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BuyModeSelector(selected = buyMode, onSelect = onBuyModeChange, modifier = Modifier.fillMaxWidth())
        Column(modifier = Modifier.clip(RoundedCornerShape(12.dp))) {
            for (layer in dig.layers) {
                when (layer) {
                    is DigLayerUi.Unlocked -> UnlockedLayerBand(layer, buyMode, format, texts, actions)
                    is DigLayerUi.Locked -> LockedLayerBand(layer, format, texts, actions)
                }
            }
        }
        PrepSection(dig.prep, buyMode, format, actions)
    }
}

/** Farbband einer offenen Schicht; Tippen auf das Band graebt. */
@Composable
private fun UnlockedLayerBand(
    layer: DigLayerUi.Unlocked,
    buyMode: BuyMode,
    format: GameNumberFormat,
    texts: TextProvider,
    actions: DigActions,
) {
    val background = remember(layer.color) { QualityColors.parseHexColor(layer.color) }
    val content = contentColorFor(background)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.dig_tap_hint)) {
                actions.tapDig(layer.layerId)
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(texts.layerName(layer.layerId), style = MaterialTheme.typography.titleMedium, color = content)
            Text(
                stringResource(R.string.dig_layer_blocks, format.formatPrecise(layer.blocks)),
                style = MaterialTheme.typography.bodyMedium,
                color = content,
            )
            Text(
                stringResource(
                    R.string.dig_layer_rate_teams,
                    format.formatPrecise(layer.blocksPerSecond),
                    format.format(layer.teams),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = content,
            )
            Text(stringResource(R.string.dig_tap_hint), style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.75f))
        }
        BuyButton(
            title = stringResource(R.string.dig_buy_teams),
            quote = layer.teamQuotes[buyMode],
            format = format,
            onClick = { actions.buyTeams(layer.layerId, buyMode) },
        )
    }
}

/** Farbband einer gesperrten Schicht (abgedunkelt): Kosten, Spar-Fortschritt, Freischalten. */
@Composable
private fun LockedLayerBand(
    layer: DigLayerUi.Locked,
    format: GameNumberFormat,
    texts: TextProvider,
    actions: DigActions,
) {
    val background = remember(layer.color) { QualityColors.parseHexColor(layer.color).copy(alpha = LOCKED_ALPHA) }
    val content = MaterialTheme.colorScheme.onSurface
    val percent = (layer.savingsProgress * PERCENT).toInt()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(texts.layerName(layer.layerId), style = MaterialTheme.typography.titleMedium, color = content)
                Text(
                    stringResource(R.string.dig_locked_cost, format.format(layer.unlockCost)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = content,
                )
                Text(
                    if (layer.isNext) {
                        stringResource(R.string.dig_locked_progress, percent)
                    } else {
                        stringResource(R.string.dig_locked_not_next)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = content,
                )
            }
            if (layer.isNext) {
                Button(onClick = { actions.unlockLayer(layer.layerId) }, enabled = layer.canUnlock) {
                    Text(stringResource(R.string.dig_unlock))
                }
            }
        }
        if (layer.isNext) {
            LinearProgressIndicator(
                progress = { layer.savingsProgress.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Bereich Praeparation: Rate, Bestand, Praeparatoren kaufen und grosser Freilege-Button. */
@Composable
private fun PrepSection(prep: PrepUi, buyMode: BuyMode, format: GameNumberFormat, actions: DigActions) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.dig_prep_title), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.dig_prep_rate, format.formatPrecise(prep.ratePerSecond)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.dig_prep_preparators, format.format(prep.preparators)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.dig_prep_available, format.format(prep.availableBlocks)),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                BuyButton(
                    title = stringResource(R.string.dig_buy_preparators),
                    quote = prep.preparatorQuotes[buyMode],
                    format = format,
                    onClick = { actions.buyPreparators(buyMode) },
                )
            }
            OutlinedButton(
                onClick = actions::tapPrep,
                enabled = prep.canTap,
                modifier = Modifier.fillMaxWidth().height(PREP_BUTTON_HEIGHT),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.dig_prep_button), style = MaterialTheme.typography.headlineSmall)
                    if (!prep.canTap) {
                        Text(stringResource(R.string.dig_prep_no_blocks), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            LinearProgressIndicator(progress = { prep.progress.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Kurze Karte fuer einen neuen oder besseren Fund: Art, Knochen, Qualitaetsstufe in Farbe. */
@Composable
fun FindPopupCard(popup: FindPopup, texts: TextProvider, modifier: Modifier = Modifier) {
    val tierColor = LocalQualityColors.current.forTier(popup.tierId)
        ?: MaterialTheme.colorScheme.onSurface
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                stringResource(
                    when (popup.kind) {
                        FindPopupKind.NEW -> R.string.dig_popup_new
                        FindPopupKind.IMPROVED -> R.string.dig_popup_improved
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(
                    R.string.dig_popup_piece,
                    texts.speciesName(popup.pieceKey.speciesId),
                    texts.boneName(popup.pieceKey.boneId),
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    R.string.dig_popup_quality,
                    texts.qualityTierName(popup.tierId),
                    (popup.quality * PERCENT).toInt(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = tierColor,
            )
        }
    }
}

/** Lesbare Textfarbe auf einem Schichtband. */
private fun contentColorFor(background: Color): Color =
    if (background.luminance() > LIGHT_BACKGROUND_LUMINANCE) Color.Black else Color.White

private const val LOCKED_ALPHA = 0.35f
private const val LIGHT_BACKGROUND_LUMINANCE = 0.35f
private const val PERCENT = 100
private val PREP_BUTTON_HEIGHT = 72.dp

// --- Vorschauen ---

private val previewData by lazy { DataRepository().load() }

private fun previewQuotes(affordable: Boolean): Map<BuyMode, PurchaseQuote?> = mapOf(
    BuyMode.X1 to PurchaseQuote(GameNumber.ONE, GameNumber(12.5), Currency.MONEY, affordable),
    BuyMode.X10 to PurchaseQuote(GameNumber(10.0), GameNumber(219.0), Currency.MONEY, false),
    BuyMode.MAX to PurchaseQuote(if (affordable) GameNumber(3.0) else GameNumber.ZERO, GameNumber(40.0), Currency.MONEY, affordable),
)

private fun previewState(dig: DigUiState): GameUiState.Ready = GameUiState.Ready(
    money = GameNumber(2_400.0),
    moneyPerSecond = GameNumber(3.5),
    researchPoints = GameNumber.ZERO,
    fame = GameNumber.ZERO,
    visibleTabs = emptyList(),
    settings = Settings(),
    qualityTiers = previewData.balance.qualityTiers,
    texts = TextProvider.fromGameData(previewData),
    dig = dig,
)

/** Vorschau-Querschnitt aus den echten Schichtdaten: erste Schicht offen, die weiteren gesperrt. */
private fun previewDig(started: Boolean): DigUiState {
    val layers = previewData.layers.mapIndexed { index, layer ->
        if (index == 0) {
            DigLayerUi.Unlocked(
                layerId = layer.id,
                color = layer.color,
                blocks = if (started) GameNumber(3.75) else GameNumber.ZERO,
                blocksPerSecond = if (started) GameNumber(1.2) else GameNumber.ZERO,
                teams = if (started) GameNumber(12.0) else GameNumber.ZERO,
                teamQuotes = previewQuotes(started),
            )
        } else {
            val progress = if (started) 2_400.0 / layer.unlockCost else 0.0
            DigLayerUi.Locked(
                layerId = layer.id,
                color = layer.color,
                unlockCost = GameNumber(layer.unlockCost),
                savingsProgress = progress.coerceIn(0.0, 1.0),
                isNext = index == 1,
                canUnlock = false,
            )
        }
    }
    val prep = if (started) {
        PrepUi(GameNumber(4.0), GameNumber(0.8), GameNumber(3.0), 0.25, canTap = true, preparatorQuotes = previewQuotes(false))
    } else {
        PrepUi.EMPTY.copy(preparatorQuotes = previewQuotes(false))
    }
    return DigUiState(layers, prep)
}

@Composable
private fun PreviewFrame(state: GameUiState.Ready, content: @Composable () -> Unit) {
    FossilCollectorTheme(qualityColors = QualityColors.fromTiers(state.qualityTiers)) {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) { content() }
    }
}

@Preview(heightDp = 760)
@Composable
private fun DigScreenPreview() {
    val state = previewState(previewDig(started = true))
    PreviewFrame(state) { DigScreen(state, DigActions.NONE, emptyFlow()) }
}

@Preview(heightDp = 760)
@Composable
private fun DigScreenStartPreview() {
    val state = previewState(previewDig(started = false))
    PreviewFrame(state) { DigScreen(state, DigActions.NONE, emptyFlow()) }
}

@Preview
@Composable
private fun FindPopupCardPreview() {
    val state = previewState(DigUiState.EMPTY)
    PreviewFrame(state) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FindPopupCard(
                FindPopup(PieceKey("smilodon", "skull"), 0.93, "pristine", 4, FindPopupKind.NEW),
                rememberTextProvider(state.texts, state.settings),
            )
            FindPopupCard(
                FindPopup(PieceKey("cave_bear", "ribs"), 0.55, "good", 2, FindPopupKind.IMPROVED),
                rememberTextProvider(state.texts, state.settings),
            )
        }
    }
}

package de.noelkoeppen.fossilcollector.ui.dig

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.noelkoeppen.fossilcollector.R
import de.noelkoeppen.fossilcollector.core.data.QualityTierDefinition
import de.noelkoeppen.fossilcollector.core.data.TextProvider
import de.noelkoeppen.fossilcollector.core.engine.QualityTiers
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.ui.theme.LocalQualityColors
import de.noelkoeppen.fossilcollector.vm.DigUiState
import kotlin.math.roundToInt

/** Aktionen des Grabungs-Screens; [NONE] fuer Vorschauen. */
data class DigActions(
    val onDigStart: (Double, Double) -> Unit,
    val onDigMove: (Double, Double) -> Unit,
    val onDigEnd: () -> Unit,
    val onSelectTool: (String) -> Unit,
    val onEndExcavation: () -> Unit,
    /** Nahansicht eines entdeckten Knochens oeffnen (feines Freilegen, AP2-08). */
    val onOpenBone: (BoneRef) -> Unit,
) {
    companion object {
        val NONE = DigActions({ _, _ -> }, { _, _ -> }, {}, {}, {}, {})
    }
}

/**
 * Screen grobes Buddeln (AP2-07): Grabungsflaeche, Werkzeugwahl, Rueckmeldung zum zuletzt
 * getroffenen oder entdeckten Knochen, Meldung „keine Knochen mehr“ und „Ausgrabung beenden“ (mit
 * Bestaetigung, solange noch Knochen in der Flaeche liegen). Feines Freilegen (AP2-08): langes
 * Druecken auf einen entdeckten Knochen oder „Freilegen“ beim zuletzt entdeckten Knochen oeffnet
 * dessen Nahansicht. Bergen (AP2-09): geborgene Knochen sind aus der Flaeche verschwunden und
 * werden gezaehlt; „Ausgrabung beenden“ ist hervorgehoben, wenn alle Knochen geborgen sind, und
 * fragt sonst nach (nicht geborgene Knochen bleiben zurueck).
 *
 * @param texts Inhaltstexte in der UI-Sprache (Werkzeug-, Knochen- und Stufennamen)
 */
@Composable
fun DigScreen(
    dig: DigUiState,
    texts: TextProvider,
    qualityTiers: List<QualityTierDefinition>,
    actions: DigActions,
) {
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            DigField(
                dig = dig,
                onDigStart = actions.onDigStart,
                onDigMove = actions.onDigMove,
                onDigEnd = actions.onDigEnd,
                onOpenBone = actions.onOpenBone,
                modifier = Modifier.fillMaxSize(),
            )
            StatusPanel(dig, texts, qualityTiers, actions.onOpenBone, Modifier.align(Alignment.TopCenter).padding(12.dp))
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                dig.tools.forEach { toolId ->
                    FilterChip(
                        selected = toolId == dig.selectedTool,
                        onClick = { actions.onSelectTool(toolId) },
                        label = { Text(texts.toolName(toolId)) },
                    )
                }
                Spacer(Modifier.weight(1f))
                val onEnd = { if (dig.allRecovered) actions.onEndExcavation() else confirmEnd = true }
                if (dig.allRecovered) {
                    Button(onClick = onEnd) { Text(stringResource(R.string.dig_end)) }
                } else {
                    OutlinedButton(onClick = onEnd) { Text(stringResource(R.string.dig_end)) }
                }
            }
        }
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(stringResource(R.string.dig_end_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!dig.nothingLeftToFind) Text(stringResource(R.string.dig_end_confirm_text))
                    if (dig.unrecoveredDiscoveredCount > 0) {
                        Text(stringResource(R.string.dig_end_confirm_unrecovered, dig.unrecoveredDiscoveredCount))
                    }
                    Text(stringResource(R.string.dig_end_confirm_question))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    actions.onEndExcavation()
                }) { Text(stringResource(R.string.dig_end_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.dig_end_cancel)) }
            },
        )
    }
}

/** Oben ueber der Flaeche: Anzahl entdeckter Knochen, zuletzt betroffener Knochen, Meldung „keine Knochen mehr“. */
@Composable
private fun StatusPanel(
    dig: DigUiState,
    texts: TextProvider,
    qualityTiers: List<QualityTierDefinition>,
    onOpenBone: (BoneRef) -> Unit,
    modifier: Modifier,
) {
    val focus = dig.feedback.focus?.let { dig.bone(it) }?.takeIf { it.discovered }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = PANEL_ALPHA),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = if (dig.recoveredCount > 0) {
                        stringResource(
                            R.string.dig_bone_status,
                            stringResource(R.string.dig_found_count, dig.discoveredCount),
                            stringResource(R.string.dig_recovered_count, dig.recoveredCount),
                        )
                    } else {
                        stringResource(R.string.dig_found_count, dig.discoveredCount)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
                if (focus != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BoneQualityLine(
                            speciesId = focus.speciesId,
                            boneId = focus.boneId,
                            quality = focus.quality,
                            hit = focus.ref in dig.feedback.damaged,
                            texts = texts,
                            qualityTiers = qualityTiers,
                            status = when {
                                focus.recovered -> stringResource(R.string.dig_bone_recovered)
                                focus.prepared -> stringResource(R.string.dig_bone_prepared)
                                else -> null
                            },
                        )
                        if (!focus.recovered) {
                            Spacer(Modifier.width(12.dp))
                            TextButton(onClick = { onOpenBone(focus.ref) }) { Text(stringResource(R.string.prep_open)) }
                        }
                    }
                    if (!focus.recovered) {
                        Text(
                            text = stringResource(R.string.prep_open_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (dig.discoveredCount == 0) {
                    Text(
                        text = stringResource(R.string.dig_controls_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (dig.nothingLeftToFind) {
            val allRecovered = dig.allRecovered
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    text = stringResource(if (allRecovered) R.string.dig_all_recovered else R.string.dig_nothing_left),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Name und Qualitaet (Stufe, Prozent, Stufenfarbe) eines Knochens; [hit] zeigt einen Treffer an,
 * [status] haengt einen Zustand an (z. B. „freigelegt“). Auch in der Nahansicht (AP2-08) verwendet.
 */
@Composable
internal fun BoneQualityLine(
    speciesId: String,
    boneId: String,
    quality: Double,
    hit: Boolean,
    texts: TextProvider,
    qualityTiers: List<QualityTierDefinition>,
    status: String? = null,
) {
    val tiers = remember(qualityTiers) { qualityTiers.takeIf { it.isNotEmpty() }?.let(::QualityTiers) }
    val tier = tiers?.tierOf(quality)
    val color = LocalQualityColors.current.forQuality(quality) ?: MaterialTheme.colorScheme.outline
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(texts.boneName(speciesId, boneId), style = MaterialTheme.typography.bodyMedium)
            val percent = (quality * PERCENT).roundToInt()
            val base = stringResource(R.string.dig_bone_quality, tier?.let { texts.qualityTierName(it.id) }.orEmpty(), percent)
            val line = if (status != null) stringResource(R.string.dig_bone_status, base, status) else base
            Text(
                text = if (hit) stringResource(R.string.dig_bone_hit, line) else line,
                style = MaterialTheme.typography.bodySmall,
                color = if (hit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val PANEL_ALPHA = 0.88f
private const val PERCENT = 100.0

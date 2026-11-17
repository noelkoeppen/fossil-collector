package de.noelkoeppen.fossilcollector.ui.prep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import de.noelkoeppen.fossilcollector.ui.dig.BoneQualityLine
import de.noelkoeppen.fossilcollector.vm.PrepUiState

/** Aktionen der Nahansicht; [NONE] fuer Vorschauen. */
data class PrepActions(
    val onStart: (Double, Double) -> Unit,
    val onMove: (Double, Double) -> Unit,
    val onEnd: () -> Unit,
    val onSelectTool: (String) -> Unit,
    val onBack: () -> Unit,
) {
    companion object {
        val NONE = PrepActions({ _, _ -> }, { _, _ -> }, {}, {}, {})
    }
}

/**
 * Screen feines Freilegen (AP2-08, Kapitel 5.1): Nahansicht des Knochens, Wahl von Pinsel und
 * Kratzer, oben Name und Qualitaet des Knochens (mit Treffer-Anzeige) und die Fortschrittsanzeige,
 * Meldung „fertig freigelegt“, unten „Zur Flaeche“ (zurueck zur Grabungsflaeche).
 */
@Composable
fun PrepScreen(
    prep: PrepUiState,
    texts: TextProvider,
    qualityTiers: List<QualityTierDefinition>,
    actions: PrepActions,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            PrepField(
                prep = prep,
                onStart = actions.onStart,
                onMove = actions.onMove,
                onEnd = actions.onEnd,
                modifier = Modifier.fillMaxSize(),
            )
            PrepStatus(prep, texts, qualityTiers, Modifier.align(Alignment.TopCenter).padding(12.dp))
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                prep.tools.forEach { toolId ->
                    FilterChip(
                        selected = toolId == prep.selectedTool,
                        onClick = { actions.onSelectTool(toolId) },
                        label = { Text(texts.toolName(toolId)) },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (prep.finished) {
                    Button(onClick = actions.onBack) { Text(stringResource(R.string.prep_back)) }
                } else {
                    OutlinedButton(onClick = actions.onBack) { Text(stringResource(R.string.prep_back)) }
                }
            }
        }
    }
}

/** Oben ueber der Nahansicht: Knochen mit Qualitaet, Fortschritt, Bedienhilfe bzw. „fertig freigelegt“. */
@Composable
private fun PrepStatus(prep: PrepUiState, texts: TextProvider, qualityTiers: List<QualityTierDefinition>, modifier: Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = PANEL_ALPHA),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = PANEL_MAX_WIDTH.dp)) {
                BoneQualityLine(
                    speciesId = prep.speciesId,
                    boneId = prep.boneId,
                    quality = prep.quality,
                    hit = prep.feedback.damaged,
                    texts = texts,
                    qualityTiers = qualityTiers,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    LinearProgressIndicator(
                        progress = { prep.progress.toFloat() },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.prep_progress, PrepTexts.percent(prep.progress)),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (!prep.finished) {
                    val gentle = prep.tools.firstOrNull { it !in prep.hardTools }
                    val hard = prep.tools.firstOrNull { it in prep.hardTools }
                    if (gentle != null && hard != null) {
                        Text(
                            text = stringResource(R.string.prep_controls_hint, texts.toolName(gentle), texts.toolName(hard)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        if (prep.finished) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(
                    text = stringResource(R.string.prep_finished),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/** Zahlen fuer die Anzeige der Nahansicht (ohne Android, testbar). */
object PrepTexts {
    /** Fortschritt als ganze Prozent, abgerundet: 100 % erst, wenn wirklich alles frei liegt. */
    fun percent(progress: Double): Int = (progress.coerceIn(0.0, 1.0) * PERCENT + ROUNDING_SLACK).toInt()

    private const val PERCENT = 100.0

    /** Gleicht Rundungsfehler der Gleitkommazahl aus (z. B. 0.57 * 100 = 56.99...). */
    private const val ROUNDING_SLACK = 1e-9
}

private const val PANEL_ALPHA = 0.88f
private const val PANEL_MAX_WIDTH = 360

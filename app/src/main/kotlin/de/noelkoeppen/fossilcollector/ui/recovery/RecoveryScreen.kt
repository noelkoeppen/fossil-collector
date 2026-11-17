package de.noelkoeppen.fossilcollector.ui.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
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
import de.noelkoeppen.fossilcollector.vm.RecoveryOutcome
import de.noelkoeppen.fossilcollector.vm.RecoveryUiState
import kotlin.math.roundToInt

/**
 * Screen Bergen (AP2-09, Kapitel 5.2): Knochen mit bruechigen Stellen, oben Name und Qualitaet, wie
 * viele Stellen gesichert sind, Bedienhilfe und Rueckmeldung (gesichert, Ruck, losgelassen). Nach
 * dem Herausheben das Ergebnis mit Qualitaetsstufe, danach „Zur Flaeche“. Vorher fuehrt „Zurueck“
 * zur Nahansicht.
 */
@Composable
fun RecoveryScreen(
    recovery: RecoveryUiState,
    texts: TextProvider,
    qualityTiers: List<QualityTierDefinition>,
    actions: RecoveryActions,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            RecoveryField(recovery = recovery, actions = actions, modifier = Modifier.fillMaxSize())
            val outcome = recovery.outcome
            if (outcome == null) {
                RecoveryStatus(recovery, texts, qualityTiers, Modifier.align(Alignment.TopCenter).padding(12.dp))
            } else {
                RecoveryResult(recovery, outcome, texts, qualityTiers, Modifier.align(Alignment.Center).padding(24.dp))
            }
        }
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (recovery.recovered) {
                    Button(onClick = actions.onClose) { Text(stringResource(R.string.prep_back)) }
                } else {
                    OutlinedButton(onClick = actions.onClose) { Text(stringResource(R.string.back)) }
                }
            }
        }
    }
}

/** Oben ueber dem Knochen: Name und Qualitaet, gesicherte Stellen, Bedienhilfe, letzte Rueckmeldung. */
@Composable
private fun RecoveryStatus(rec: RecoveryUiState, texts: TextProvider, qualityTiers: List<QualityTierDefinition>, modifier: Modifier) {
    val feedback = rec.feedback
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = PANEL_ALPHA),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).widthIn(max = PANEL_MAX_WIDTH.dp)) {
            BoneQualityLine(
                speciesId = rec.speciesId,
                boneId = rec.boneId,
                quality = rec.quality,
                hit = feedback.newJerk || (feedback.dropped && feedback.loss > 0.0),
                texts = texts,
                qualityTiers = qualityTiers,
            )
            Text(
                text = stringResource(R.string.recovery_spots, rec.securedCount, rec.spots.size),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                text = stringResource(R.string.recovery_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            val message = when {
                feedback.dropped -> stringResource(R.string.recovery_dropped, RecoveryTexts.percent(feedback.loss))
                feedback.jerks > 0 -> stringResource(R.string.recovery_jerk, feedback.jerks)
                feedback.securedSpot != null -> stringResource(R.string.recovery_secured)
                feedback.missedTap -> stringResource(R.string.recovery_missed)
                else -> null
            }
            if (message != null) {
                val warn = feedback.dropped || feedback.jerks > 0
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** Ergebnis nach dem Herausheben: Qualitaetsstufe, Rucke, ungesicherte Stellen und Verlust. */
@Composable
private fun RecoveryResult(
    rec: RecoveryUiState,
    outcome: RecoveryOutcome,
    texts: TextProvider,
    qualityTiers: List<QualityTierDefinition>,
    modifier: Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(
            modifier = Modifier.padding(20.dp).widthIn(max = PANEL_MAX_WIDTH.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.recovery_done_title), style = MaterialTheme.typography.titleLarge)
            BoneQualityLine(
                speciesId = rec.speciesId,
                boneId = rec.boneId,
                quality = outcome.piece.quality,
                hit = false,
                texts = texts,
                qualityTiers = qualityTiers,
            )
            Text(
                text = stringResource(R.string.recovery_done_details, outcome.jerks, outcome.unsecuredSpots, RecoveryTexts.percent(outcome.loss)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(top = 2.dp))
            Text(stringResource(R.string.recovery_done_inventory), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Zahlen fuer die Anzeige des Bergens (ohne Android, testbar). */
object RecoveryTexts {
    /** Qualitaetsverlust als ganze Prozentpunkte, gerundet, nie negativ. */
    fun percent(loss: Double): Int = (loss.coerceAtLeast(0.0) * PERCENT).roundToInt()

    private const val PERCENT = 100.0
}

private const val PANEL_ALPHA = 0.88f
private const val PANEL_MAX_WIDTH = 360

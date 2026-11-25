package de.noelkoeppen.fossilcollector.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.noelkoeppen.fossilcollector.R
import de.noelkoeppen.fossilcollector.core.data.QualityTierDefinition
import de.noelkoeppen.fossilcollector.core.data.TextProvider
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.ui.dig.BoneQualityLine

/**
 * Hauptbildschirm ohne laufende Ausgrabung (AP2-07): ein Button startet eine neue Ausgrabung.
 * Auswahl von Schicht und Fundort kommt spaeter (im ersten Stand gibt es nur eine Schicht).
 *
 * Inventar (AP2-09, nur minimal): Anzahl und Liste der geborgenen Knochen mit Qualitaet, neueste
 * zuerst. Zuordnen und Verkauf kommen mit AP2-11 und AP2-14.
 */
@Composable
fun HomeScreen(
    onStartExcavation: () -> Unit,
    inventory: List<Piece> = emptyList(),
    texts: TextProvider = TextProvider.EMPTY,
    qualityTiers: List<QualityTierDefinition> = emptyList(),
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.home_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onStartExcavation) { Text(stringResource(R.string.home_start)) }
        Spacer(Modifier.height(24.dp))
        Text(
            text = if (inventory.isEmpty()) stringResource(R.string.home_inventory_empty) else stringResource(R.string.home_inventory, inventory.size),
            style = MaterialTheme.typography.titleSmall,
        )
        if (inventory.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val newestFirst = inventory.asReversed()
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).widthIn(max = LIST_MAX_WIDTH.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(newestFirst) { _, piece ->
                    BoneQualityLine(
                        speciesId = piece.speciesId,
                        boneId = piece.boneId,
                        quality = piece.quality,
                        hit = false,
                        texts = texts,
                        qualityTiers = qualityTiers,
                    )
                }
            }
        }
    }
}

private const val LIST_MAX_WIDTH = 360

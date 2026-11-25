package de.noelkoeppen.fossilcollector.ui.model3d

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.noelkoeppen.fossilcollector.R
import de.noelkoeppen.fossilcollector.core.data.QualityTierDefinition
import de.noelkoeppen.fossilcollector.core.data.SpeciesDefinition
import de.noelkoeppen.fossilcollector.core.data.TextProvider
import de.noelkoeppen.fossilcollector.core.engine.BoneQualities
import de.noelkoeppen.fossilcollector.core.engine.QualityTiers
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.ui.dig.BoneQualityLine

/**
 * Testansicht der 3D-Darstellung (AP2-10, nur in Debug-Builds vom Hauptbildschirm erreichbar):
 * ganzes Skelett oder ein Einzelknochen, eingefaerbt mit Beispielqualitaeten (alle Stufen der
 * Reihe nach) oder mit den besten Knochen aus dem Inventar. Kein Zuordnen (AP2-11), kein
 * Sammelbuch (AP2-12), kein Museum (AP2-13).
 */
@Composable
fun ModelPreviewScreen(
    species: List<SpeciesDefinition>,
    inventory: List<Piece>,
    texts: TextProvider,
    qualityTiers: List<QualityTierDefinition>,
) {
    if (species.isEmpty() || qualityTiers.isEmpty()) return
    var speciesIndex by rememberSaveable { mutableIntStateOf(0) }
    var singleBone by rememberSaveable { mutableStateOf(false) }
    var fromInventory by rememberSaveable { mutableStateOf(false) }
    var hideMissing by rememberSaveable { mutableStateOf(false) }
    var boneIndex by rememberSaveable { mutableIntStateOf(0) }

    val current = species[speciesIndex.coerceIn(species.indices)]
    val bones = current.bones
    val tiers = remember(qualityTiers) { QualityTiers(qualityTiers) }
    val qualities = remember(current, inventory, tiers, fromInventory) {
        if (fromInventory) BoneQualities.bestByBone(inventory, current.id) else BoneQualities.tierSamples(bones, tiers)
    }
    val speciesName = texts.speciesName(current.id)

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.model_preview_title), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (species.size > 1) {
                species.forEachIndexed { i, s ->
                    FilterChip(
                        selected = i == speciesIndex,
                        onClick = { speciesIndex = i; boneIndex = 0 },
                        label = { Text(texts.speciesName(s.id)) },
                    )
                }
            }
            FilterChip(selected = !singleBone, onClick = { singleBone = false }, label = { Text(stringResource(R.string.model_preview_skeleton)) })
            FilterChip(selected = singleBone, onClick = { singleBone = true }, label = { Text(stringResource(R.string.model_preview_bone)) })
            FilterChip(selected = !fromInventory, onClick = { fromInventory = false }, label = { Text(stringResource(R.string.model_preview_samples)) })
            FilterChip(selected = fromInventory, onClick = { fromInventory = true }, label = { Text(stringResource(R.string.model_preview_inventory)) })
            if (!singleBone) {
                FilterChip(
                    selected = hideMissing,
                    onClick = { hideMissing = !hideMissing },
                    label = { Text(stringResource(R.string.model_preview_hide_missing)) },
                )
            }
        }
        if (singleBone) {
            val boneId = bones[boneIndex.coerceIn(bones.indices)]
            val quality = qualities[boneId]
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { boneIndex = (boneIndex - 1 + bones.size) % bones.size }) {
                    Text(stringResource(R.string.model_preview_previous))
                }
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (quality != null) {
                        BoneQualityLine(current.id, boneId, quality, hit = false, texts = texts, qualityTiers = qualityTiers)
                    } else {
                        Text(texts.boneName(current.id, boneId), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.model_preview_not_in_inventory),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = { boneIndex = (boneIndex + 1) % bones.size }) {
                    Text(stringResource(R.string.model_preview_next))
                }
            }
            BoneView(
                speciesId = current.id,
                boneId = boneId,
                quality = quality,
                description = texts.boneName(current.id, boneId),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            Text(
                stringResource(R.string.model_preview_colored, qualities.keys.count { it in bones }, bones.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SkeletonView(
                speciesId = current.id,
                boneIds = bones,
                boneQualities = qualities,
                missingBones = if (hideMissing) MissingBoneStyle.HIDDEN else MissingBoneStyle.NEUTRAL,
                description = speciesName,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        Text(
            stringResource(R.string.model_preview_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

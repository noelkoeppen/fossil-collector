package de.noelkoeppen.fossilcollector.vm

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.data.BoneFootprintDefinition
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.LayerTexture
import de.noelkoeppen.fossilcollector.core.model.BonePreparation
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite

/**
 * Anzeigezustand des Bergens (AP2-09, Kapitel 5.2). Rohwerte, keine Texte. Das Raster ist das der
 * Nahansicht ([preparation]); die bruechigen Stellen sind feine Zellen darin.
 *
 * @property spots bruechige Stellen (feine Zellen)
 * @property secured je Stelle, ob sie mit Gips gesichert ist
 * @property quality aktuelle Qualitaet des Knochens
 * @property secureRadius Trefferradius beim Sichern in feinen Zellen (fuer die Groesse der Markierung)
 * @property outcome Ergebnis, sobald der Knochen geborgen ist
 */
@Immutable
class RecoveryUiState(
    val ref: BoneRef,
    val speciesId: String,
    val boneId: String,
    val preparation: BonePreparation,
    val cells: List<Int>,
    val siteWidth: Int,
    val footprint: BoneFootprintDefinition?,
    val pxPerCell: Int,
    val quality: Double,
    val spots: List<Int>,
    val secured: List<Boolean>,
    val secureRadius: Double,
    val feedback: RecoveryFeedback,
    val outcome: RecoveryOutcome?,
    val layerId: String,
    val layerColor: String,
    val layerTexture: LayerTexture,
) {
    /** Anzahl gesicherter Stellen. */
    val securedCount: Int get() = secured.count { it }

    /** Der Knochen ist geborgen. */
    val recovered: Boolean get() = outcome != null

    companion object {
        /**
         * Baut den Anzeigezustand fuer [ref] oder `null`, wenn das Bergen in [site] nicht begonnen
         * hat. Reine Funktion.
         */
        fun from(site: ExcavationSite, ref: BoneRef, data: GameData, feedback: RecoveryFeedback, outcome: RecoveryOutcome?): RecoveryUiState? {
            val recovery = site.recovery(ref) ?: return null
            val preparation = site.preparation(ref) ?: return null
            val piece = site.piece(ref)
            val footprints = data.footprints[piece.speciesId]
            val layer = data.layer(site.layerId)
            return RecoveryUiState(
                ref = ref,
                speciesId = piece.speciesId,
                boneId = ref.boneId,
                preparation = preparation,
                cells = site.cellsOf(ref),
                siteWidth = site.width,
                footprint = footprints?.bonesById?.get(ref.boneId),
                pxPerCell = footprints?.pxPerCell ?: 1,
                quality = outcome?.piece?.quality ?: piece.quality,
                spots = recovery.spots,
                secured = recovery.secured,
                secureRadius = data.balance.recovery.secureRadius,
                feedback = feedback,
                outcome = outcome,
                layerId = site.layerId,
                layerColor = layer.color,
                layerTexture = layer.texture,
            )
        }
    }
}

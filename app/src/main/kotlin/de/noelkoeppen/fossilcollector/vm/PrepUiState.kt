package de.noelkoeppen.fossilcollector.vm

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.data.BoneFootprintDefinition
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.LayerTexture
import de.noelkoeppen.fossilcollector.core.data.ToolStage
import de.noelkoeppen.fossilcollector.core.model.BonePreparation
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite

/**
 * Anzeigezustand der Nahansicht beim feinen Freilegen (AP2-08, Kapitel 5.1). Rohwerte, keine Texte.
 *
 * @property preparation Nahansicht (unveraenderlich); die UI liest daraus Restschicht und Knochenzellen
 * @property cells Zellen des Knochens in der Flaeche (fuer die Lage des Knochenbilds)
 * @property siteWidth Breite der Flaeche in Zellen
 * @property footprint Umriss aus den Daten oder `null`
 * @property pxPerCell Bildpixel je Zelle der Art
 * @property quality aktuelle Qualitaet des Knochens
 * @property tools Werkzeug-IDs des feinen Freilegens (`stage` = `prepare`) in Datenreihenfolge
 * @property hardTools Werkzeuge, die harte Stellen loesen (fuer die Bedienhilfe)
 * @property finishedShare Schwelle fuer „fertig freigelegt“ (Anzeige der Fortschrittsmarke)
 * @property maxDepth groesste Dicke der Restschicht laut Daten (Bezug fuer die Darstellung)
 */
@Immutable
class PrepUiState(
    val ref: BoneRef,
    val speciesId: String,
    val boneId: String,
    val preparation: BonePreparation,
    val cells: List<Int>,
    val siteWidth: Int,
    val footprint: BoneFootprintDefinition?,
    val pxPerCell: Int,
    val quality: Double,
    val tools: List<String>,
    val hardTools: Set<String>,
    val selectedTool: String,
    val feedback: PrepFeedback,
    val finishedShare: Double,
    val maxDepth: Int,
    val layerId: String,
    val layerColor: String,
    val layerTexture: LayerTexture,
) {
    /** Fortschritt (Anteil freiliegender Knochenzellen, 0..1). */
    val progress: Double get() = preparation.progress

    /** Fertig freigelegt. */
    val finished: Boolean get() = preparation.finished

    companion object {
        /**
         * Baut den Anzeigezustand fuer [ref] oder `null`, wenn das Freilegen in [site] nicht begonnen
         * hat. Reine Funktion.
         */
        fun from(site: ExcavationSite, ref: BoneRef, data: GameData, selectedTool: String, feedback: PrepFeedback): PrepUiState? {
            val preparation = site.preparation(ref) ?: return null
            val piece = site.piece(ref)
            val footprints = data.footprints[piece.speciesId]
            val layer = data.layer(site.layerId)
            val prepTools = data.tools.filter { it.stage == ToolStage.PREPARE }
            val balance = data.balance.preparation
            return PrepUiState(
                ref = ref,
                speciesId = piece.speciesId,
                boneId = ref.boneId,
                preparation = preparation,
                cells = site.cellsOf(ref),
                siteWidth = site.width,
                footprint = footprints?.bonesById?.get(ref.boneId),
                pxPerCell = footprints?.pxPerCell ?: 1,
                quality = piece.quality,
                tools = prepTools.map { it.id },
                hardTools = prepTools.filter { it.removesHard }.map { it.id }.toSet(),
                selectedTool = selectedTool,
                feedback = feedback,
                finishedShare = balance.finishedShare,
                maxDepth = maxOf(balance.looseDepthMax, balance.hardDepthMax),
                layerId = site.layerId,
                layerColor = layer.color,
                layerTexture = layer.texture,
            )
        }
    }
}

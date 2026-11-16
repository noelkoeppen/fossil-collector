package de.noelkoeppen.fossilcollector.vm

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.data.BoneFootprintDefinition
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.LayerTexture
import de.noelkoeppen.fossilcollector.core.engine.Excavator
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite

/**
 * Ein Knochen der Flaeche fuer die Anzeige. Unentdeckte Knochen werden mitgeliefert, weil sie unter
 * der Erde gezeichnet werden (die Erde verdeckt sie); Name und Qualitaet zeigt die UI nur fuer
 * entdeckte Knochen.
 *
 * @property footprint Umriss aus den Daten (fuer die Lage des Bilds) oder `null`
 * @property pxPerCell Bildpixel je Zelle der Art
 */
@Immutable
data class DigBone(
    val ref: BoneRef,
    val speciesId: String,
    val boneId: String,
    val cells: List<Int>,
    val footprint: BoneFootprintDefinition?,
    val pxPerCell: Int,
    val quality: Double,
    val discovered: Boolean,
)

/**
 * Anzeigezustand des groben Buddelns (AP2-07). Rohwerte, keine Texte.
 *
 * @property site laufende Flaeche (unveraenderlich); die UI liest daraus die Tiefen
 * @property hinted je Zelle, ob sie einen Hinweis zeigt (aus `Excavator.isHinted`); nicht veraendern
 * @property tools Werkzeug-IDs in Datenreihenfolge
 * @property selectedTool gewaehltes Werkzeug
 * @property discoveredCount entdeckte Knochen
 * @property nothingLeftToFind alle Knochen entdeckt (Meldung „keine Knochen mehr“)
 */
@Immutable
class DigUiState(
    val site: ExcavationSite,
    val hinted: BooleanArray,
    val bones: List<DigBone>,
    val tools: List<String>,
    val selectedTool: String,
    val discoveredCount: Int,
    val nothingLeftToFind: Boolean,
    val feedback: DigFeedback,
    val layerColor: String,
    val layerTexture: LayerTexture,
    val depthMin: Int,
    val hintDepth: Int,
) {
    /** Knochen nach Referenz. */
    fun bone(ref: BoneRef): DigBone? = bones.firstOrNull { it.ref == ref }

    companion object {
        /** Baut den Anzeigezustand aus Flaeche und Daten. Reine Funktion. */
        fun from(
            site: ExcavationSite,
            data: GameData,
            excavator: Excavator,
            selectedTool: String,
            feedback: DigFeedback,
        ): DigUiState {
            val hinted = BooleanArray(site.cellCount) { excavator.isHinted(site, it) }
            val bones = site.bones.map { ref ->
                val piece = site.piece(ref)
                val footprints = data.footprints[piece.speciesId]
                DigBone(
                    ref = ref,
                    speciesId = piece.speciesId,
                    boneId = ref.boneId,
                    cells = site.cellsOf(ref),
                    footprint = footprints?.bonesById?.get(ref.boneId),
                    pxPerCell = footprints?.pxPerCell ?: 1,
                    quality = piece.quality,
                    discovered = site.isDiscovered(ref),
                )
            }
            val layer = data.layer(site.layerId)
            return DigUiState(
                site = site,
                hinted = hinted,
                bones = bones,
                tools = data.tools.map { it.id },
                selectedTool = selectedTool,
                discoveredCount = bones.count { it.discovered },
                nothingLeftToFind = site.nothingLeftToFind,
                feedback = feedback,
                layerColor = layer.color,
                layerTexture = layer.texture,
                depthMin = data.balance.excavation.depthMin,
                hintDepth = data.balance.excavation.hintDepth,
            )
        }
    }
}

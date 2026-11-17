package de.noelkoeppen.fossilcollector.vm

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.data.BoneFootprintDefinition
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.LayerTexture
import de.noelkoeppen.fossilcollector.core.data.ToolStage
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
 * @property preparationProgress Fortschritt des feinen Freilegens (AP2-08) oder `null`, wenn es
 *   noch nicht begonnen hat
 * @property prepared fertig freigelegt (Kapitel 5.1)
 * @property recovered geborgen (Kapitel 5.2, AP2-09): liegt im Inventar und wird nicht mehr gezeichnet
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
    val preparationProgress: Double? = null,
    val prepared: Boolean = false,
    val recovered: Boolean = false,
)

/**
 * Anzeigezustand des groben Buddelns (AP2-07). Rohwerte, keine Texte.
 *
 * @property site laufende Flaeche (unveraenderlich); die UI liest daraus die Tiefen
 * @property hinted je Zelle, ob sie einen Hinweis zeigt (aus `Excavator.isHinted`); nicht veraendern
 * @property tools Werkzeug-IDs des groben Buddelns (`stage` = `dig`) in Datenreihenfolge
 * @property selectedTool gewaehltes Werkzeug
 * @property discoveredCount entdeckte Knochen
 * @property nothingLeftToFind alle Knochen entdeckt (Meldung „keine Knochen mehr“)
 * @property recoveredCount geborgene Knochen (AP2-09)
 * @property unrecoveredDiscoveredCount entdeckte, aber noch nicht geborgene Knochen
 * @property allRecovered alle Knochen der Flaeche sind geborgen (Beenden ohne Rueckfrage)
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
    val recoveredCount: Int,
    val unrecoveredDiscoveredCount: Int,
    val allRecovered: Boolean,
    val feedback: DigFeedback,
    val layerColor: String,
    val layerTexture: LayerTexture,
    val depthMin: Int,
    val hintDepth: Int,
) {
    /** Knochen nach Referenz. */
    fun bone(ref: BoneRef): DigBone? = bones.firstOrNull { it.ref == ref }

    companion object {
        /**
         * Entdeckter, noch nicht geborgener Knochen an Position ([x], [y]) in Zellkoordinaten oder
         * `null` (Ziel fuer das Oeffnen der Nahansicht, AP2-08).
         */
        fun discoveredBoneAt(dig: DigUiState, x: Double, y: Double): BoneRef? {
            if (!x.isFinite() || !y.isFinite() || x < 0.0 || y < 0.0) return null
            val cx = x.toInt()
            val cy = y.toInt()
            if (!dig.site.contains(cx, cy)) return null
            val ref = dig.site.activeBoneAt(dig.site.cellIndex(cx, cy)) ?: return null
            return ref.takeIf { dig.site.isDiscovered(it) }
        }

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
                    preparationProgress = site.preparation(ref)?.progress,
                    prepared = site.isPrepared(ref),
                    recovered = site.isRecovered(ref),
                )
            }
            val layer = data.layer(site.layerId)
            return DigUiState(
                site = site,
                hinted = hinted,
                bones = bones,
                tools = data.tools.filter { it.stage == ToolStage.DIG }.map { it.id },
                selectedTool = selectedTool,
                discoveredCount = bones.count { it.discovered },
                nothingLeftToFind = site.nothingLeftToFind,
                recoveredCount = bones.count { it.recovered },
                unrecoveredDiscoveredCount = bones.count { it.discovered && !it.recovered },
                allRecovered = site.allRecovered,
                feedback = feedback,
                layerColor = layer.color,
                layerTexture = layer.texture,
                depthMin = data.balance.excavation.depthMin,
                hintDepth = data.balance.excavation.hintDepth,
            )
        }
    }
}

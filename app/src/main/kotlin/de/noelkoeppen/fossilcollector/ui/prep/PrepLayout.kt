package de.noelkoeppen.fossilcollector.ui.prep

import de.noelkoeppen.fossilcollector.core.data.BoneFootprintDefinition
import de.noelkoeppen.fossilcollector.core.model.BonePreparation
import de.noelkoeppen.fossilcollector.ui.dig.BoneImageLayout
import de.noelkoeppen.fossilcollector.ui.dig.CellRect

/**
 * Lage des Knochenbilds in der Nahansicht (AP2-08): dieselbe Lage wie in der Flaeche
 * ([BoneImageLayout]), umgerechnet in feine Zellen der Nahansicht. Damit liegt das Bild
 * deckungsgleich mit den Knochenzellen aus dem feinen Umriss.
 */
object PrepLayout {

    /**
     * @param cells Zellen des Knochens in der Flaeche
     * @param siteWidth Breite der Flaeche in Zellen
     */
    fun imageRect(
        preparation: BonePreparation,
        cells: List<Int>,
        siteWidth: Int,
        footprint: BoneFootprintDefinition?,
        pxPerCell: Int,
        imageWidth: Int,
        imageHeight: Int,
    ): CellRect {
        val rect = BoneImageLayout.imageRect(cells, siteWidth, footprint, pxPerCell, imageWidth, imageHeight)
        val s = preparation.subdivision.toDouble()
        return CellRect((rect.left - preparation.originX) * s, (rect.top - preparation.originY) * s, rect.width * s, rect.height * s)
    }
}

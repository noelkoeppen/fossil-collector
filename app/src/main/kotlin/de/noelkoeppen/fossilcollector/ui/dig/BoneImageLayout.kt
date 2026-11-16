package de.noelkoeppen.fossilcollector.ui.dig

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.data.BoneFootprintDefinition

/** Rechteck in Zellkoordinaten (Zelle (x, y) reicht von x bis x + 1). */
@Immutable
data class CellRect(val left: Double, val top: Double, val width: Double, val height: Double)

/**
 * Lage eines Knochenbilds in der Flaeche (AP2-07): Das Bild wird so gezeichnet, dass es zu den
 * Zellen passt, die der Knochen belegt.
 *
 * Die Zellen eines Knochens sind sein Umriss aus `data/footprints/<speciesId>.json`, verschoben an
 * seine Stelle. Die linke obere Ecke des umschliessenden Rechtecks der Zellen ist damit die Ecke des
 * Umrisses, und das Bild liegt um `imageLeft`/`imageTop` Bildpixel (`pxPerCell` je Zelle) daneben.
 *
 * Passen die gespeicherten Zellen nicht zum Umriss (Spielstand aus der Zeit vor den echten Umrissen
 * mit dem Platzhalter-Rechteck), steht das Bild mittig auf dem Rechteck der Zellen.
 */
object BoneImageLayout {

    /**
     * @param cells belegte Zellen (Zellindex `y * siteWidth + x`), nicht leer
     * @param siteWidth Breite der Flaeche in Zellen
     * @param footprint Umriss des Knochens oder `null`, wenn keiner bekannt ist
     * @param pxPerCell Bildpixel je Zelle (> 0)
     * @param imageWidth Breite des Bilds in Pixeln
     * @param imageHeight Hoehe des Bilds in Pixeln
     */
    fun imageRect(
        cells: List<Int>,
        siteWidth: Int,
        footprint: BoneFootprintDefinition?,
        pxPerCell: Int,
        imageWidth: Int,
        imageHeight: Int,
    ): CellRect {
        require(cells.isNotEmpty() && siteWidth >= 1 && pxPerCell >= 1)
        val xs = cells.map { it % siteWidth }
        val ys = cells.map { it / siteWidth }
        val x0 = xs.min()
        val y0 = ys.min()
        val width = imageWidth.toDouble() / pxPerCell
        val height = imageHeight.toDouble() / pxPerCell
        return if (footprint != null && matches(cells, siteWidth, x0, y0, footprint)) {
            CellRect(x0 + footprint.imageLeft.toDouble() / pxPerCell, y0 + footprint.imageTop.toDouble() / pxPerCell, width, height)
        } else {
            val cx = (x0 + xs.max() + 1) / 2.0
            val cy = (y0 + ys.max() + 1) / 2.0
            CellRect(cx - width / 2, cy - height / 2, width, height)
        }
    }

    /** `true`, wenn [cells] genau der Umriss an der Ecke ([x0], [y0]) sind. */
    fun matches(cells: List<Int>, siteWidth: Int, x0: Int, y0: Int, footprint: BoneFootprintDefinition): Boolean {
        val expected = footprint.occupiedCells().map { (dx, dy) -> (y0 + dy) * siteWidth + x0 + dx }
        return expected.size == cells.size && expected.toSet() == cells.toSet()
    }
}

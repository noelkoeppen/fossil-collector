package de.noelkoeppen.fossilcollector.core.data

import kotlinx.serialization.Serializable

/**
 * Knochenumrisse einer Art im Raster der Grabungsflaeche aus `data/footprints/<speciesId>.json`
 * (AP2-07). Nicht von Hand gepflegt: `tools/models3d/bone_footprints.py` rechnet sie aus den
 * Umrissmasken der Knochenbilder (`bone_images.py`, AP2-06) mit `excavation.cellsPerUnit` Zellen pro
 * Modelleinheit, ohne Drehung. Eine neue Art braucht dafuer nur einen erneuten Aufruf des Skripts.
 *
 * @property species ID der Art
 * @property cellsPerUnit Zellen pro Modelleinheit, mit dem die Umrisse gerechnet sind (muss zu
 *   `balance.json: excavation.cellsPerUnit` passen)
 * @property pxPerUnit Pixel pro Modelleinheit der Knochenbilder (aus deren Manifest)
 * @property subdivision feine Zellen je Zelle und Achse der feinen Umrisse (AP2-08, muss zu
 *   `balance.json: preparation.subdivision` passen)
 * @property params Parameter des Skripts (nur zur Nachvollziehbarkeit, im Spiel unbenutzt)
 * @property bones ein Eintrag je Knochen der Art
 */
@Serializable
data class SpeciesFootprints(
    val formatVersion: Int,
    val species: String,
    val cellsPerUnit: Int,
    val pxPerUnit: Int,
    val subdivision: Int,
    val params: FootprintParams,
    val bones: List<BoneFootprintDefinition>,
) {
    /** Bildpixel je Zelle (Hoehlenbaer 384 / 8 = 48). */
    val pxPerCell: Int get() = if (cellsPerUnit > 0) pxPerUnit / cellsPerUnit else 0

    val bonesById: Map<String, BoneFootprintDefinition> by lazy { bones.associateBy { it.id } }

    companion object {
        /** Unterstuetzte Version des Dateiformats. */
        const val FORMAT_VERSION: Int = 2
    }
}

/**
 * Skript-Parameter: Mindestanteil Knochen je belegter Zelle, Schrittweite der Rasterverschiebung und
 * Mindestanteil Knochen je feiner Zelle (AP2-08).
 */
@Serializable
data class FootprintParams(
    val minCoverage: Double,
    val offsetStep: Int,
    val fineCoverage: Double,
)

/**
 * Umriss eines Knochens: [rows] von oben nach unten, je Zeile ein Zeichen pro Spalte, [OCCUPIED] fuer
 * eine belegte Zelle, [FREE] fuer eine freie. Die Zeilen bilden das umschliessende Rechteck.
 *
 * [imageLeft] und [imageTop] sind die linke obere Ecke des Knochenbilds in Bildpixeln relativ zur
 * linken oberen Ecke dieses Rechtecks; damit zeichnet die App Bild und Zellen deckungsgleich.
 *
 * [fineRows] (AP2-08) ist der feine Umriss fuer das feine Freilegen: dasselbe Rechteck, geteilt in
 * `subdivision` x `subdivision` feine Zellen je Zelle, `#` dort, wo der Knochen im Bild liegt.
 */
@Serializable
data class BoneFootprintDefinition(
    val id: String,
    val rows: List<String>,
    val imageLeft: Int,
    val imageTop: Int,
    val fineRows: List<String> = emptyList(),
) {
    /** Belegte Zellen als (Spalte, Zeile), zeilenweise. */
    fun occupiedCells(): List<Pair<Int, Int>> =
        rows.flatMapIndexed { dy, row -> row.mapIndexedNotNull { dx, c -> if (c == OCCUPIED) dx to dy else null } }

    /** Knochenzellen des feinen Umrisses als (Spalte, Zeile) in feinen Zellen, zeilenweise. */
    fun fineCells(): List<Pair<Int, Int>> =
        fineRows.flatMapIndexed { dy, row -> row.mapIndexedNotNull { dx, c -> if (c == OCCUPIED) dx to dy else null } }

    /** Breite des Rechtecks in Zellen. */
    val width: Int get() = rows.maxOfOrNull { it.length } ?: 0

    /** Hoehe des Rechtecks in Zellen. */
    val height: Int get() = rows.size

    companion object {
        const val OCCUPIED: Char = '#'
        const val FREE: Char = '.'
    }
}

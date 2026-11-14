package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.FootprintSize
import de.noelkoeppen.fossilcollector.core.data.GameData

/**
 * Umriss eines Knochens im Raster der Grabungsflaeche: die belegten Zellen relativ zu einem Ankerpunkt
 * (Spalte [dx], Zeile [dy], jeweils >= 0).
 */
data class BoneFootprint(val cells: List<Offset>) {
    /** Zellversatz relativ zum Ankerpunkt. */
    data class Offset(val dx: Int, val dy: Int)

    init {
        require(cells.isNotEmpty()) { "Umriss ohne Zellen" }
        require(cells.all { it.dx >= 0 && it.dy >= 0 }) { "Versatz muss >= 0 sein" }
        require(cells.toSet().size == cells.size) { "Umriss enthaelt eine Zelle doppelt" }
    }

    /** Breite des umschliessenden Rechtecks in Zellen. */
    val width: Int = cells.maxOf { it.dx } + 1

    /** Hoehe des umschliessenden Rechtecks in Zellen. */
    val height: Int = cells.maxOf { it.dy } + 1

    companion object {
        /** Volles Rechteck aus [width] x [height] Zellen. */
        fun rectangle(width: Int, height: Int): BoneFootprint {
            require(width >= 1 && height >= 1) { "Rechteck muss mindestens 1x1 sein" }
            return BoneFootprint((0 until height).flatMap { dy -> (0 until width).map { dx -> Offset(dx, dy) } })
        }
    }
}

/**
 * Liefert den Umriss eines Knochens fuer die Platzierung in der Grabungsflaeche.
 *
 * Uebergang: [PlaceholderFootprints] gibt jedem Knochen dasselbe Rechteck aus
 * `balance.json: excavation.placeholderFootprint`. Die Knochenmasken liegen seit AP2-06 vor; sobald
 * die Knochengroesse in Zellen entschieden ist (GAME_DESIGN.md Kapitel 13), werden daraus Umrisse,
 * indem dem `ExcavationGenerator` eine andere Implementierung uebergeben wird. Die Platzierung selbst
 * arbeitet mit beliebigen Zellmengen.
 */
fun interface BoneFootprints {
    fun footprint(speciesId: String, boneId: String): BoneFootprint
}

/** Platzhalter: dasselbe Rechteck fuer jeden Knochen jeder Art. */
class PlaceholderFootprints(size: FootprintSize) : BoneFootprints {
    private val footprint = BoneFootprint.rectangle(size.width, size.height)

    override fun footprint(speciesId: String, boneId: String): BoneFootprint = footprint

    companion object {
        /** Platzhalter aus `balance.json: excavation.placeholderFootprint`. */
        fun fromGameData(data: GameData): PlaceholderFootprints =
            PlaceholderFootprints(data.balance.excavation.placeholderFootprint)
    }
}

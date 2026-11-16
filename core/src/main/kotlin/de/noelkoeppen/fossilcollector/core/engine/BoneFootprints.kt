package de.noelkoeppen.fossilcollector.core.engine

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
 * Liefert den Umriss eines Knochens fuer die Platzierung in der Grabungsflaeche. Die Platzierung im
 * `ExcavationGenerator` arbeitet mit beliebigen Zellmengen; Standard sind die Umrisse aus den Daten
 * ([DataFootprints]), Tests koennen eigene Umrisse einsetzen.
 */
fun interface BoneFootprints {
    fun footprint(speciesId: String, boneId: String): BoneFootprint
}

/**
 * Umrisse aus `data/footprints/<speciesId>.json` (AP2-07): aus den Knochenmasken mit
 * `excavation.cellsPerUnit` Zellen pro Modelleinheit, ohne Drehung (GAME_DESIGN.md Kapitel 3/10).
 */
class DataFootprints(data: GameData) : BoneFootprints {
    private val footprints: Map<String, Map<String, BoneFootprint>> =
        data.footprints.mapValues { (_, species) ->
            species.bones.associate { bone ->
                bone.id to BoneFootprint(bone.occupiedCells().map { (dx, dy) -> BoneFootprint.Offset(dx, dy) })
            }
        }

    /** @throws NoSuchElementException wenn Art oder Knochen keinen Umriss haben */
    override fun footprint(speciesId: String, boneId: String): BoneFootprint =
        footprints[speciesId]?.get(boneId) ?: throw NoSuchElementException("Kein Umriss fuer $speciesId/$boneId")
}

package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.BoneFootprintDefinition
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.BonePreparation
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite
import de.noelkoeppen.fossilcollector.core.random.GameRandom
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Beginnt das feine Freilegen eines Knochens (Kapitel 5.1, AP2-08): legt die Nahansicht mit
 * feinem Raster, Knochenzellen und Restschicht an. Alle Werte kommen aus `balance.json: preparation`,
 * Zufall nur ueber das uebergebene [GameRandom].
 *
 * Ablauf (in dieser Reihenfolge, damit gleicher Seed dieselbe Nahansicht ergibt):
 * 1. Raster: das umschliessende Rechteck der Zellen des Knochens in der Flaeche plus `margin` Zellen
 *    Rand, jede Zelle in `subdivision` x `subdivision` feine Zellen geteilt.
 * 2. Knochenzellen: der feine Umriss aus `data/footprints/<speciesId>.json` (`fineRows`), wenn die
 *    gespeicherten Zellen des Knochens genau seinem Umriss entsprechen; sonst (z. B. Spielstand mit
 *    Platzhalter-Zellen) alle feinen Zellen der belegten Zellen.
 * 3. Lose Erde: jede feine Zelle (zeilenweise) bekommt eine Dicke gleichverteilt in
 *    `[looseDepthMin, looseDepthMax]`.
 * 4. Harte Stellen: Anzahl `floor(n * hardSpotsPerCell + u)` mit `n` = Zellen des Knochens in der
 *    Flaeche und `u` gleichverteilt in `[0, 1)` (im Mittel also `n * hardSpotsPerCell`). Jede Stelle
 *    ist ein Kreis um eine gleichverteilt gewaehlte Knochenzelle mit Radius gleichverteilt in
 *    `[hardSpotRadiusMin, hardSpotRadiusMax]`; jede feine Zelle, deren Mittelpunkt im Kreis liegt,
 *    wird hart mit einer Dicke gleichverteilt in `[hardDepthMin, hardDepthMax]`.
 * 5. Die restliche grobe Erde ueber den Zellen des Knochens in der Flaeche faellt weg (Tiefe 0): Ab
 *    jetzt liegt ueber dem Knochen nur noch die duenne Restschicht der Nahansicht.
 *
 * @param data Spieldaten (validiert)
 */
class PreparationGenerator(private val data: GameData) {
    private val balance = data.balance.preparation

    /**
     * Neue Flaeche, in der das Freilegen von [ref] begonnen hat. Hat es schon begonnen, kommt [site]
     * unveraendert zurueck.
     *
     * @throws NoSuchElementException wenn [ref] nicht in der Flaeche liegt
     * @throws IllegalStateException wenn der Knochen noch nicht entdeckt ist
     */
    fun start(site: ExcavationSite, ref: BoneRef, random: GameRandom): ExcavationSite {
        if (ref !in site.bones) throw NoSuchElementException("Knochen $ref liegt nicht in der Flaeche")
        if (site.preparation(ref) != null) return site
        check(site.isDiscovered(ref)) { "Knochen $ref ist noch nicht entdeckt" }
        val preparation = create(site, ref, random)
        val depths = site.depthsCopy()
        site.cellsOf(ref).forEach { depths[it] = 0 }
        return site.with(depths, site.finds, site.preparations + preparation)
    }

    /** Nahansicht von [ref] nach den Schritten 1 bis 4 (ohne die Flaeche zu aendern). */
    fun create(site: ExcavationSite, ref: BoneRef, random: GameRandom): BonePreparation {
        val sub = balance.subdivision
        val margin = balance.margin
        val cells = site.cellsOf(ref)
        val xs = cells.map { site.xOf(it) }
        val ys = cells.map { site.yOf(it) }
        val x0 = xs.min()
        val y0 = ys.min()
        val rectW = xs.max() - x0 + 1
        val rectH = ys.max() - y0 + 1
        val width = (rectW + 2 * margin) * sub
        val height = (rectH + 2 * margin) * sub
        val inset = margin * sub

        val bone = BooleanArray(width * height)
        fineBoneCells(site, ref, x0, y0, rectW, rectH).forEach { (fx, fy) -> bone[(fy + inset) * width + fx + inset] = true }

        val depths = IntArray(width * height) { rollInt(random, balance.looseDepthMin, balance.looseDepthMax) }
        val hard = BooleanArray(width * height)
        val boneCells = bone.indices.filter { bone[it] }
        val spots = floor(cells.size * balance.hardSpotsPerCell + random.nextDouble()).toInt()
        repeat(spots) {
            val center = boneCells[random.nextInt(boneCells.size)]
            val radius = random.nextDouble(balance.hardSpotRadiusMin, balance.hardSpotRadiusMax)
            val cx = center % width + CELL_CENTER
            val cy = center / width + CELL_CENTER
            for (y in maxOf(0, floor(cy - radius).toInt())..minOf(height - 1, ceil(cy + radius).toInt())) {
                for (x in maxOf(0, floor(cx - radius).toInt())..minOf(width - 1, ceil(cx + radius).toInt())) {
                    val dx = x + CELL_CENTER - cx
                    val dy = y + CELL_CENTER - cy
                    if (dx * dx + dy * dy > radius * radius) continue
                    val i = y * width + x
                    hard[i] = true
                    depths[i] = rollInt(random, balance.hardDepthMin, balance.hardDepthMax)
                }
            }
        }
        return BonePreparation(
            findIndex = ref.findIndex,
            boneId = ref.boneId,
            originX = x0 - margin,
            originY = y0 - margin,
            subdivision = sub,
            width = width,
            height = height,
            depths = depths,
            hard = hard,
            bone = bone,
        )
    }

    /**
     * Knochenzellen in feinen Zellen relativ zum Rechteck der Zellen des Knochens: feiner Umriss aus
     * den Daten, wenn die Zellen zum Umriss passen, sonst alle feinen Zellen der belegten Zellen.
     */
    private fun fineBoneCells(site: ExcavationSite, ref: BoneRef, x0: Int, y0: Int, rectW: Int, rectH: Int): List<Pair<Int, Int>> {
        val sub = balance.subdivision
        val cells = site.cellsOf(ref)
        val speciesId = site.piece(ref).speciesId
        val definition = data.footprints[speciesId]?.takeIf { it.subdivision == sub }?.bonesById?.get(ref.boneId)
        if (definition != null && matches(site, cells, x0, y0, rectW, rectH, definition)) {
            val fine = definition.fineCells()
            if (fine.isNotEmpty()) return fine
        }
        return cells.flatMap { cell ->
            val bx = (site.xOf(cell) - x0) * sub
            val by = (site.yOf(cell) - y0) * sub
            (0 until sub).flatMap { dy -> (0 until sub).map { dx -> bx + dx to by + dy } }
        }
    }

    private fun matches(
        site: ExcavationSite,
        cells: List<Int>,
        x0: Int,
        y0: Int,
        rectW: Int,
        rectH: Int,
        definition: BoneFootprintDefinition,
    ): Boolean {
        if (definition.width != rectW || definition.height != rectH) return false
        if (definition.fineRows.size != rectH * balance.subdivision) return false
        if (definition.fineRows.any { it.length != rectW * balance.subdivision }) return false
        val expected = definition.occupiedCells().map { (dx, dy) -> (y0 + dy) * site.width + x0 + dx }.toSet()
        return expected == cells.toSet()
    }

    private fun rollInt(random: GameRandom, min: Int, max: Int): Int =
        floor(random.nextDouble(min.toDouble(), max + 1.0)).toInt().coerceIn(min, max)

    private companion object {
        /** Mittelpunkt einer Zelle relativ zu ihrer Ecke (Geometrie, kein Spielwert). */
        const val CELL_CENTER: Double = 0.5
    }
}

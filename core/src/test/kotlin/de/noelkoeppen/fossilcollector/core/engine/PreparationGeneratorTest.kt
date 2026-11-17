package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite
import de.noelkoeppen.fossilcollector.core.model.Find
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PlacedFind
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Beginn des feinen Freilegens (Kapitel 5.1, AP2-08): Raster, Knochenzellen, Restschicht. */
class PreparationGeneratorTest {

    private val data: GameData = DataRepository().load()
    private val prep = data.balance.preparation

    /** Echte Flaeche mit festem Seed; der erste Knochen ist durch Tiefe 0 an einer Zelle entdeckt. */
    private fun realSite(seed: Long = 3): Pair<ExcavationSite, BoneRef> {
        val site = ExcavationGenerator(data).generate("quaternary", SeededRandom(seed))
        val bone = site.bones.maxBy { site.cellsOf(it).size }
        val depths = site.depthsCopy()
        depths[site.cellsOf(bone).first()] = 0
        return site.with(depths, site.finds) to bone
    }

    /** 10x10-Flaeche mit einem Oberschenkel auf Platzhalter-Zellen (2,2) und (3,2), entdeckt. */
    private fun placeholderSite(): Pair<ExcavationSite, BoneRef> {
        val find = Find("cave_bear", listOf(Piece("cave_bear", "femur_l", 0.6)))
        val placed = PlacedFind(find, mapOf("femur_l" to listOf(22, 23)))
        val depths = IntArray(100) { 50 }
        depths[22] = 0
        return ExcavationSite("quaternary", 10, 10, depths, listOf(placed)) to BoneRef(0, "femur_l")
    }

    @Test
    fun sameSeedGivesSamePreparationOtherSeedDiffers() {
        val (site, bone) = realSite()
        val a = PreparationGenerator(data).start(site, bone, SeededRandom(11))
        val b = PreparationGenerator(data).start(site, bone, SeededRandom(11))
        val c = PreparationGenerator(data).start(site, bone, SeededRandom(12))
        assertEquals(a, b)
        assertNotEquals(a.preparation(bone), c.preparation(bone))
    }

    @Test
    fun gridCoversBoneRectangleWithMarginAndUsesFineFootprint() {
        val (site, bone) = realSite()
        val result = PreparationGenerator(data).start(site, bone, SeededRandom(1))
        val p = checkNotNull(result.preparation(bone))
        val cells = site.cellsOf(bone)
        val x0 = cells.minOf { site.xOf(it) }
        val y0 = cells.minOf { site.yOf(it) }
        val definition = data.footprints.getValue("cave_bear").bonesById.getValue(bone.boneId)
        assertEquals(x0 - prep.margin, p.originX)
        assertEquals(y0 - prep.margin, p.originY)
        assertEquals(prep.subdivision, p.subdivision)
        assertEquals((definition.width + 2 * prep.margin) * prep.subdivision, p.width)
        assertEquals((definition.height + 2 * prep.margin) * prep.subdivision, p.height)
        val inset = prep.margin * prep.subdivision
        val expected = definition.fineCells().map { (fx, fy) -> (fy + inset) * p.width + fx + inset }.toSet()
        assertEquals(expected, (0 until p.cellCount).filter { p.isBone(it) }.toSet())
        assertFalse(p.finished)
        assertEquals(0.0, p.progress)
    }

    @Test
    fun placeholderCellsUseWholeCellsAsBone() {
        val (site, bone) = placeholderSite()
        val p = checkNotNull(PreparationGenerator(data).start(site, bone, SeededRandom(1)).preparation(bone))
        val sub = prep.subdivision
        val inset = prep.margin * sub
        assertEquals((2 + 2 * prep.margin) * sub, p.width)
        val expected = (0 until sub).flatMap { dy -> (0 until 2 * sub).map { dx -> (dy + inset) * p.width + dx + inset } }.toSet()
        assertEquals(expected, (0 until p.cellCount).filter { p.isBone(it) }.toSet())
    }

    @Test
    fun residueCoversEverythingWithLooseAndHardDepthsInRange() {
        repeat(20) { seed ->
            val (site, bone) = realSite(seed.toLong())
            val p = checkNotNull(PreparationGenerator(data).start(site, bone, SeededRandom(seed + 100L)).preparation(bone))
            for (cell in 0 until p.cellCount) {
                val range = if (p.isHard(cell)) prep.hardDepthMin..prep.hardDepthMax else prep.looseDepthMin..prep.looseDepthMax
                assertTrue(p.depthAt(cell) in range, "Zelle $cell: ${p.depthAt(cell)} nicht in $range")
            }
        }
    }

    @Test
    fun hardSpotsLieAroundBoneCells() {
        val (site, bone) = realSite()
        var hardSeen = 0
        repeat(30) { seed ->
            val p = checkNotNull(PreparationGenerator(data).start(site, bone, SeededRandom(seed.toLong())).preparation(bone))
            val boneCells = (0 until p.cellCount).filter { p.isBone(it) }
            for (cell in (0 until p.cellCount).filter { p.isHard(it) }) {
                hardSeen++
                val near = boneCells.any { b ->
                    val dx = (p.xOf(b) - p.xOf(cell)).toDouble()
                    val dy = (p.yOf(b) - p.yOf(cell)).toDouble()
                    dx * dx + dy * dy <= prep.hardSpotRadiusMax * prep.hardSpotRadiusMax
                }
                assertTrue(near, "harte Zelle $cell liegt weiter als der groesste Radius von jedem Knochen entfernt")
            }
        }
        assertTrue(hardSeen > 0, "harte Stellen kommen vor")
    }

    @Test
    fun hardSpotCountFollowsParameter() {
        val (site, bone) = realSite()
        val none = data.copy(balance = data.balance.copy(preparation = prep.copy(hardSpotsPerCell = 0.0)))
        repeat(10) { seed ->
            val p = checkNotNull(PreparationGenerator(none).start(site, bone, SeededRandom(seed.toLong())).preparation(bone))
            assertTrue((0 until p.cellCount).none { p.isHard(it) })
        }
        // Mittlere Anzahl Stellen = Zellen * hardSpotsPerCell: mit vielen Stellen ist fast alles um den Knochen hart.
        val many = data.copy(balance = data.balance.copy(preparation = prep.copy(hardSpotsPerCell = 20.0)))
        val p = checkNotNull(PreparationGenerator(many).start(site, bone, SeededRandom(5)).preparation(bone))
        val boneCells = (0 until p.cellCount).filter { p.isBone(it) }
        assertTrue(boneCells.count { p.isHard(it) } > boneCells.size * 0.9)
    }

    @Test
    fun startClearsRemainingEarthOverBoneOnly() {
        val (site, bone) = realSite()
        val result = PreparationGenerator(data).start(site, bone, SeededRandom(1))
        val boneCells = site.cellsOf(bone).toSet()
        for (cell in 0 until site.cellCount) {
            if (cell in boneCells) assertEquals(0, result.depthAt(cell)) else assertEquals(site.depthAt(cell), result.depthAt(cell))
        }
        assertEquals(site.finds, result.finds, "Qualitaet unveraendert")
    }

    @Test
    fun startNeedsDiscoveredBoneAndHappensOnce() {
        val (site, bone) = realSite()
        val other = site.bones.first { it != bone && !site.isDiscovered(it) }
        assertThrows<IllegalStateException> { PreparationGenerator(data).start(site, other, SeededRandom(1)) }
        assertThrows<NoSuchElementException> { PreparationGenerator(data).start(site, BoneRef(9, "femur_l"), SeededRandom(1)) }
        val started = PreparationGenerator(data).start(site, bone, SeededRandom(1))
        assertSame(started, PreparationGenerator(data).start(started, bone, SeededRandom(2)))
        assertNotNull(started.preparation(bone))
        assertNull(started.preparation(other))
        assertEquals(1, started.preparations.size)
    }
}

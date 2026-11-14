package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.ExcavationBalance
import de.noelkoeppen.fossilcollector.core.data.FindCountWeight
import de.noelkoeppen.fossilcollector.core.data.FindSizeWeight
import de.noelkoeppen.fossilcollector.core.data.FootprintSize
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite
import de.noelkoeppen.fossilcollector.core.model.ReferenceValidator
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs
import kotlin.math.max

/** Erzeugung der Grabungsflaeche (Kapitel 3, AP2-05) mit festem Seed. */
class ExcavationGeneratorTest {

    private val data: GameData = DataRepository().load()
    private val layerId = data.layers.first().id
    private val balance = data.balance.excavation

    private fun withExcavation(excavation: ExcavationBalance, sizes: List<FindSizeWeight> = data.balance.finds.sizeWeights): GameData =
        data.copy(balance = data.balance.copy(excavation = excavation, finds = data.balance.finds.copy(sizeWeights = sizes)))

    private fun sites(generator: ExcavationGenerator, count: Int, seed: Long): List<ExcavationSite> {
        val random = SeededRandom(seed)
        return List(count) { generator.generate(layerId, random) }
    }

    // --- Groesse, Tiefen, Anzahl ---

    @Test
    fun siteHasConfiguredSizeAndDepthsInRange() {
        sites(ExcavationGenerator(data), 50, seed = 1).forEach { site ->
            assertEquals(layerId, site.layerId)
            assertEquals(balance.width, site.width)
            assertEquals(balance.height, site.height)
            assertEquals(balance.width * balance.height, site.cellCount)
            assertTrue(site.depthsCopy().all { it in balance.depthMin..balance.depthMax })
            assertFalse(site.nothingLeftToFind, "Neue Flaeche hat schon alle Knochen entdeckt")
            assertTrue(site.discoveredBones.isEmpty())
        }
    }

    @Test
    fun depthsCoverWholeRange() {
        val depths = sites(ExcavationGenerator(data), 5, seed = 2).flatMap { it.depthsCopy().toList() }.toSet()
        assertEquals((balance.depthMin..balance.depthMax).toSet(), depths)
    }

    @Test
    fun findCountFollowsTableWithDefaultData() {
        val allowed = balance.findCountWeights.map { it.count }.toSet()
        val generated = sites(ExcavationGenerator(data), 300, seed = 3)
        // Mit den Startwerten passt jeder Fund in die Flaeche.
        generated.forEach { assertTrue(it.finds.size in allowed, "Anzahl ${it.finds.size}") }
        assertEquals(allowed, generated.map { it.finds.size }.toSet())
    }

    @Test
    fun findCountDistributionMatchesWeights() {
        val total = balance.findCountWeights.sumOf { it.weight }
        val generated = sites(ExcavationGenerator(data), 2_000, seed = 4)
        val counts = generated.groupingBy { it.finds.size }.eachCount()
        balance.findCountWeights.forEach { entry ->
            assertEquals(entry.weight / total, (counts[entry.count] ?: 0) / 2_000.0, SHARE_TOLERANCE, "Anzahl ${entry.count}")
        }
    }

    // --- Platzierung ---

    @Test
    fun findsAreValidPlacedWithFootprintAndInsideArea() {
        val footprint = balance.placeholderFootprint
        sites(ExcavationGenerator(data), 200, seed = 5).forEach { site ->
            assertEquals(emptyList<String>(), ReferenceValidator.validateExcavation(site, data))
            site.finds.forEach { placed ->
                placed.cells.values.forEach { cells ->
                    assertEquals(footprint.width * footprint.height, cells.size)
                    assertTrue(cells.all { it in 0 until site.cellCount })
                    val xs = cells.map { site.xOf(it) }
                    val ys = cells.map { site.yOf(it) }
                    assertEquals(footprint.width, xs.max() - xs.min() + 1)
                    assertEquals(footprint.height, ys.max() - ys.min() + 1)
                }
            }
        }
    }

    @Test
    fun bonesOfAFindLieTogether() {
        // Groessere Funde erzwingen, damit die Nachbarschaft oft geprueft wird.
        val big = listOf(FindSizeWeight(size = 6, weight = 1.0))
        val generator = ExcavationGenerator(withExcavation(balance, big))
        sites(generator, 100, seed = 6).forEach { site ->
            site.finds.forEach { placed ->
                val species = data.species(placed.find.speciesId)
                val bones = placed.find.boneIds
                // Graph aus Knochen, die im Graph benachbart sind UND in der Flaeche aneinandergrenzen.
                val touching = bones.associateWith { a ->
                    bones.filter { b -> b != a && b in species.neighborsOf(a) && touches(site, placed.cells.getValue(a), placed.cells.getValue(b)) }
                }
                assertTrue(isConnected(bones, touching), "Fund ${placed.find.boneIds} liegt nicht zusammen")
            }
        }
    }

    @Test
    fun noOverlapAndSpacingBetweenFinds() {
        sites(ExcavationGenerator(data), 200, seed = 7).forEach { site ->
            val all = site.finds.flatMap { it.cells.values.flatten() }
            assertEquals(all.size, all.toSet().size, "Zellen doppelt belegt")
            for (i in site.finds.indices) {
                for (j in i + 1 until site.finds.size) {
                    val a = site.finds[i].cells.values.flatten()
                    val b = site.finds[j].cells.values.flatten()
                    val distance = a.minOf { ca -> b.minOf { cb -> chebyshev(site, ca, cb) } }
                    assertTrue(distance > balance.findSpacing, "Funde $i und $j nur $distance Zellen entfernt")
                }
            }
        }
    }

    @Test
    fun sameSeedGivesSameSite() {
        val a = sites(ExcavationGenerator(data), 20, seed = 8)
        val b = sites(ExcavationGenerator(data), 20, seed = 8)
        assertEquals(a, b)
        assertNotEquals(a, sites(ExcavationGenerator(data), 20, seed = 9))
    }

    @Test
    fun findsThatDoNotFitAreDropped() {
        // Flaeche fuer genau einen Einzelknochen: von drei Funden passt nur einer, die anderen fallen weg.
        val tiny = balance.copy(
            width = 4,
            height = 3,
            findCountWeights = listOf(FindCountWeight(count = 3, weight = 1.0)),
            placementAttempts = 5,
        )
        val single = listOf(FindSizeWeight(size = 1, weight = 1.0))
        sites(ExcavationGenerator(withExcavation(tiny, single)), 20, seed = 10).forEach { site ->
            assertEquals(1, site.finds.size)
            assertEquals(12, site.finds.single().cells.values.single().size)
        }
    }

    @Test
    fun findLargerThanAreaIsDroppedAndSiteStaysUsable() {
        // Fuenf Knochen passen nie in 4x3 Zellen: Fund weglassen, Flaeche ohne Funde.
        val tiny = balance.copy(width = 4, height = 3, findCountWeights = listOf(FindCountWeight(count = 1, weight = 1.0)))
        val five = listOf(FindSizeWeight(size = 5, weight = 1.0))
        val site = ExcavationGenerator(withExcavation(tiny, five)).generate(layerId, SeededRandom(11))
        assertTrue(site.finds.isEmpty())
        assertTrue(site.nothingLeftToFind)
    }

    @Test
    fun customFootprintsAreUsed() {
        // L-foermiger Umriss: Die Platzierung arbeitet mit beliebigen Zellmengen (Vorbereitung AP2-06).
        val l = BoneFootprint(listOf(BoneFootprint.Offset(0, 0), BoneFootprint.Offset(0, 1), BoneFootprint.Offset(1, 1)))
        val generator = ExcavationGenerator(data, footprints = { _, _ -> l })
        sites(generator, 50, seed = 12).forEach { site ->
            site.finds.flatMap { it.cells.values }.forEach { cells ->
                assertEquals(3, cells.size)
                val x0 = cells.minOf { site.xOf(it) }
                val y0 = cells.minOf { site.yOf(it) }
                assertEquals(setOf(x0 to y0, x0 to y0 + 1, x0 + 1 to y0 + 1), cells.map { site.xOf(it) to site.yOf(it) }.toSet())
            }
        }
    }

    @Test
    fun zeroSpacingStillAvoidsOverlap() {
        val dense = balance.copy(findSpacing = 0, findCountWeights = listOf(FindCountWeight(count = 12, weight = 1.0)))
        sites(ExcavationGenerator(withExcavation(dense)), 30, seed = 13).forEach { site ->
            val all = site.finds.flatMap { it.cells.values.flatten() }
            assertEquals(all.size, all.toSet().size)
        }
    }

    @Test
    fun placeholderFootprintComesFromData() {
        val placeholder = PlaceholderFootprints(FootprintSize(width = 2, height = 5))
        val footprint = placeholder.footprint("cave_bear", "femur_l")
        assertEquals(2, footprint.width)
        assertEquals(5, footprint.height)
        assertEquals(10, footprint.cells.size)
        assertEquals(footprint, placeholder.footprint("cave_bear", "sternum"))
        assertThrows<IllegalArgumentException> { BoneFootprint(emptyList()) }
        assertThrows<IllegalArgumentException> { BoneFootprint(listOf(BoneFootprint.Offset(-1, 0))) }
    }

    @Test
    fun unknownLayerIsRejected() {
        assertThrows<NoSuchElementException> { ExcavationGenerator(data).generate("jurassic", SeededRandom(1)) }
    }

    // --- Hilfen ---

    private fun chebyshev(site: ExcavationSite, a: Int, b: Int): Int =
        max(abs(site.xOf(a) - site.xOf(b)), abs(site.yOf(a) - site.yOf(b)))

    private fun touches(site: ExcavationSite, a: List<Int>, b: List<Int>): Boolean =
        a.any { ca -> b.any { cb -> abs(site.xOf(ca) - site.xOf(cb)) + abs(site.yOf(ca) - site.yOf(cb)) == 1 } }

    private fun isConnected(nodes: List<String>, edges: Map<String, List<String>>): Boolean {
        val seen = mutableSetOf(nodes.first())
        val stack = ArrayDeque(listOf(nodes.first()))
        while (stack.isNotEmpty()) edges.getValue(stack.removeLast()).filter { seen.add(it) }.forEach { stack.addLast(it) }
        return seen.size == nodes.size
    }

    private companion object {
        const val SHARE_TOLERANCE = 0.04
    }
}

package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.ToolDefinition
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite
import de.noelkoeppen.fossilcollector.core.model.Find
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PlacedFind
import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import de.noelkoeppen.fossilcollector.core.modifier.ModifierCategory
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Aktion „Werkzeug wirkt auf Bereich“ (Kapitel 3, AP2-05): Abtragen, Hinweise, Schaden, Entdecken. */
class ExcavatorTest {

    private val realData: GameData = DataRepository().load()

    /** Testwerkzeuge mit Radius 0.5: wirken genau auf die Zelle unter dem Ansatzpunkt (Zellmitte). */
    private val rough = ToolDefinition("test_rough", removal = REMOVAL, radius = 0.5, rough = true, boneDamage = DAMAGE)
    private val gentle = ToolDefinition("test_gentle", removal = REMOVAL, radius = 0.5, rough = false, boneDamage = 0.0)
    private val wide = ToolDefinition("test_wide", removal = REMOVAL, radius = 1.5, rough = true, boneDamage = DAMAGE)

    private val data: GameData = realData.copy(
        tools = listOf(rough, gentle, wide),
        balance = realData.balance.copy(excavation = realData.balance.excavation.copy(hintDepth = HINT)),
    )

    private val femur = BoneRef(0, "femur_l")
    private val tibia = BoneRef(0, "tibia_l")

    /** 10x10-Flaeche, ueberall Tiefe [depth]; Oberschenkel in (2,2) und (3,2), Schienbein in (4,2). */
    private fun site(depth: Int = START_DEPTH, femurQuality: Double = 0.5, tibiaQuality: Double = 0.05): ExcavationSite {
        val find = Find("cave_bear", listOf(Piece("cave_bear", "femur_l", femurQuality), Piece("cave_bear", "tibia_l", tibiaQuality)))
        val placed = PlacedFind(find, mapOf("femur_l" to listOf(cell(2, 2), cell(3, 2)), "tibia_l" to listOf(cell(4, 2))))
        return ExcavationSite("quaternary", SIZE, SIZE, IntArray(SIZE * SIZE) { depth }, listOf(placed))
    }

    private fun cell(x: Int, y: Int) = y * SIZE + x

    private fun Excavator.at(site: ExcavationSite, tool: ToolDefinition, x: Int, y: Int): ToolResult =
        applyTool(site, tool.id, x + 0.5, y + 0.5)

    /** Wendet [tool] [times]-mal auf Zelle ([x], [y]) an und gibt alle Ergebnisse zurueck. */
    private fun Excavator.repeat(site: ExcavationSite, tool: ToolDefinition, x: Int, y: Int, times: Int): List<ToolResult> {
        var current = site
        return List(times) { at(current, tool, x, y).also { current = it.site } }
    }

    // --- Abtragen ---

    @Test
    fun removesEarthOnlyInsideRadius() {
        val excavator = Excavator(data)
        val result = excavator.at(site(), rough, 6, 6)
        assertEquals(listOf(cell(6, 6)), result.changedCells)
        assertEquals(REMOVAL, result.removedEarth)
        assertEquals(START_DEPTH - REMOVAL, result.site.depthAt(6, 6))
        assertEquals(START_DEPTH, result.site.depthAt(6, 7))

        val wideResult = excavator.at(site(), wide, 6, 6)
        // Radius 1.5: 3x3 Zellen (Ecken mit Abstand 1.41), nicht aber (6, 8) mit Abstand 2.
        val expected = (5..7).flatMap { y -> (5..7).map { x -> cell(x, y) } }
        assertEquals(expected, wideResult.changedCells)
        assertEquals(9 * REMOVAL, wideResult.removedEarth)
        assertEquals(START_DEPTH, wideResult.site.depthAt(6, 8))
    }

    @Test
    fun originalSiteIsUnchanged() {
        val before = site()
        Excavator(data).at(before, rough, 6, 6)
        assertEquals(START_DEPTH, before.depthAt(6, 6))
    }

    @Test
    fun removalStopsAtZero() {
        val excavator = Excavator(data)
        val shallow = site(depth = REMOVAL / 2)
        val first = excavator.at(shallow, gentle, 6, 6)
        assertEquals(REMOVAL / 2, first.removedEarth)
        assertEquals(0, first.site.depthAt(6, 6))
        val second = excavator.at(first.site, gentle, 6, 6)
        assertEquals(0, second.removedEarth)
        assertTrue(second.changedCells.isEmpty())
        assertSame(first.site, second.site)
    }

    @Test
    fun pointsOutsideTheAreaAffectOnlyCellsInside() {
        val excavator = Excavator(data)
        val outside = excavator.applyTool(site(), wide.id, -5.0, -5.0)
        assertTrue(outside.changedCells.isEmpty())
        val corner = excavator.applyTool(site(), wide.id, 0.5, 0.5)
        assertEquals(listOf(cell(0, 0), cell(1, 0), cell(0, 1), cell(1, 1)), corner.changedCells)
        assertThrows<IllegalArgumentException> { excavator.applyTool(site(), wide.id, Double.NaN, 1.0) }
    }

    @Test
    fun unknownToolIsRejected() {
        assertThrows<NoSuchElementException> { Excavator(data).applyTool(site(), "pickaxe", 1.0, 1.0) }
    }

    // --- Hinweise ---

    @Test
    fun hintAppearsOnceWhenEarthOverBoneFallsToHintDepth() {
        val excavator = Excavator(data)
        // 30 -> 20 (= hintDepth): Hinweis; 20 -> 10: kein neuer Hinweis.
        val results = excavator.repeat(site(), gentle, 2, 2, times = 2)
        assertEquals(listOf(BoneHint(femur, listOf(cell(2, 2)))), results[0].newHints)
        assertTrue(results[1].newHints.isEmpty())
        assertTrue(excavator.isHinted(results[0].site, cell(2, 2)))
        assertFalse(excavator.isHinted(site(), cell(2, 2)))
        assertEquals(listOf(cell(2, 2)), excavator.hintedCells(results[1].site))
    }

    @Test
    fun noHintOutsideBones() {
        val result = Excavator(data).at(site(), gentle, 6, 6)
        assertTrue(result.newHints.isEmpty())
        assertFalse(Excavator(data).isHinted(result.site, cell(6, 6)))
    }

    @Test
    fun hintsAreGroupedPerBone() {
        // Radius 1.5 um (3, 2) erfasst beide Oberschenkel-Zellen und das Schienbein.
        val result = Excavator(data).at(site(), wide, 3, 2)
        assertEquals(
            listOf(BoneHint(femur, listOf(cell(2, 2), cell(3, 2))), BoneHint(tibia, listOf(cell(4, 2)))),
            result.newHints,
        )
    }

    @Test
    fun exposedCellIsNoLongerHinted() {
        val excavator = Excavator(data)
        val exposed = excavator.repeat(site(), gentle, 2, 2, times = 3).last().site
        assertEquals(0, exposed.depthAt(2, 2))
        assertFalse(excavator.isHinted(exposed, cell(2, 2)))
    }

    @Test
    fun hintAlwaysComesBeforeDamageWithRealTools() {
        val excavator = Excavator(realData)
        val generated = ExcavationGenerator(realData).generate("quaternary", SeededRandom(77))
        val shovel = realData.tools.first { it.rough }
        generated.bones.take(5).forEach { bone ->
            val target = generated.cellsOf(bone).first()
            var current = generated
            var hintAt = -1
            var damageAt = -1
            for (step in 0 until 50) {
                val result = excavator.applyTool(current, shovel.id, generated.xOf(target) + 0.5, generated.yOf(target) + 0.5)
                if (hintAt < 0 && result.newHints.any { it.bone == bone }) hintAt = step
                if (damageAt < 0 && result.damage.any { it.bone == bone }) damageAt = step
                current = result.site
            }
            assertTrue(hintAt >= 0 && damageAt > hintAt, "Hinweis $hintAt, Schaden $damageAt fuer $bone")
        }
    }

    // --- Schaden ---

    @Test
    fun roughToolDamagesOnlyWhenDiggingIntoBone() {
        val excavator = Excavator(data)
        // 30 -> 20 -> 10 -> 0: Erde bis genau auf den Knochen abtragen trifft ihn noch nicht.
        val results = excavator.repeat(site(), rough, 2, 2, times = 4)
        assertTrue(results.take(3).all { it.damage.isEmpty() })
        assertEquals(listOf(BoneDamage(femur, 0.5, 0.5 - DAMAGE)), results[3].damage)
        assertEquals(0.5 - DAMAGE, results[3].site.piece(femur).quality, EPS)
        assertEquals(0.05, results[3].site.piece(tibia).quality)
    }

    @Test
    fun roughToolDamagesWhenRemovalExceedsRemainingEarth() {
        val result = Excavator(data).at(site(depth = REMOVAL - 1), rough, 2, 2)
        assertEquals(1, result.damage.size)
        assertEquals(0, result.site.depthAt(2, 2))
    }

    @Test
    fun gentleToolNeverDamages() {
        val results = Excavator(data).repeat(site(depth = 0), gentle, 2, 2, times = 5)
        assertTrue(results.all { it.damage.isEmpty() })
        assertEquals(0.5, results.last().site.piece(femur).quality)
    }

    @Test
    fun damageCountsOncePerBoneAndAction() {
        val result = Excavator(data).at(site(depth = 0), wide, 3, 2)
        assertEquals(listOf(femur, tibia), result.damage.map { it.bone })
        assertEquals(DAMAGE, result.damage.first().loss, EPS)
    }

    @Test
    fun qualityNeverFallsBelowZeroAndBoneIsKept() {
        val excavator = Excavator(data)
        val results = excavator.repeat(site(depth = 0), rough, 4, 2, times = 3)
        assertEquals(0.0, results[0].site.piece(tibia).quality)
        assertEquals(0.05, results[0].damage.single().loss, EPS)
        assertEquals(BoneDamage(tibia, 0.0, 0.0), results[1].damage.single())
        val last = results.last().site
        assertEquals(Piece.MIN_QUALITY, last.piece(tibia).quality)
        assertEquals(listOf("femur_l", "tibia_l"), last.finds.single().find.boneIds)
    }

    @Test
    fun damageReductionLowersDamage() {
        val engine = ModifierEngine.fromGameData(data)
        engine.setSource("upgrade:test", listOf(Modifier(StatId.DAMAGE_REDUCTION, ModifierOp.ADD, 0.25, ModifierCategory.UPGRADE)))
        val excavator = Excavator(data, engine)
        assertEquals(DAMAGE * 0.75, excavator.damagePerHit(rough), EPS)
        assertEquals(0.0, excavator.damagePerHit(gentle))
        val result = excavator.at(site(depth = 0), rough, 2, 2)
        assertEquals(DAMAGE * 0.75, result.damage.single().loss, EPS)
    }

    @Test
    fun fullDamageReductionPreventsDamage() {
        val engine = ModifierEngine.fromGameData(data)
        engine.setSource("upgrade:test", listOf(Modifier(StatId.DAMAGE_REDUCTION, ModifierOp.ADD, 1.5, ModifierCategory.UPGRADE)))
        val result = Excavator(data, engine).at(site(depth = 0), rough, 2, 2)
        assertEquals(BoneDamage(femur, 0.5, 0.5), result.damage.single())
    }

    @Test
    fun realShovelIsRoughAndTrowelIsNot() {
        val excavator = Excavator(realData)
        val shovel = realData.tool("shovel")
        val trowel = realData.tool("trowel")
        assertTrue(excavator.damagePerHit(shovel) > 0.0)
        assertEquals(0.0, excavator.damagePerHit(trowel))
        assertTrue(shovel.removal > trowel.removal, "Schaufel traegt mehr ab als die Kelle")
    }

    // --- Entdecken und „nichts mehr zu finden“ ---

    @Test
    fun boneIsDiscoveredWhenOneCellIsExposed() {
        val excavator = Excavator(data)
        val results = excavator.repeat(site(), gentle, 3, 2, times = 4)
        assertTrue(results.take(2).all { it.newlyDiscovered.isEmpty() })
        assertEquals(listOf(femur), results[2].newlyDiscovered)
        assertTrue(results[3].newlyDiscovered.isEmpty())
        assertTrue(results[2].site.isDiscovered(femur))
        assertFalse(results[2].site.isDiscovered(tibia))
        assertFalse(results[2].nothingLeftToFind)
    }

    @Test
    fun nothingLeftToFindWhenAllBonesDiscovered() {
        val excavator = Excavator(data)
        var current = site()
        assertFalse(current.nothingLeftToFind)
        val discovered = mutableListOf<BoneRef>()
        repeat(3) {
            val result = excavator.at(current, gentle, 2, 2)
            discovered += result.newlyDiscovered
            current = result.site
        }
        assertFalse(current.nothingLeftToFind)
        repeat(3) {
            val result = excavator.at(current, gentle, 4, 2)
            discovered += result.newlyDiscovered
            current = result.site
        }
        assertEquals(listOf(femur, tibia), discovered)
        assertTrue(current.nothingLeftToFind)
        assertEquals(listOf(femur, tibia), current.discoveredBones)
        assertNotNull(current.boneAt(3, 2))
    }

    private companion object {
        const val SIZE = 10
        const val START_DEPTH = 30
        const val HINT = 20
        const val REMOVAL = 10
        const val DAMAGE = 0.1
        const val EPS = 1e-9
    }
}

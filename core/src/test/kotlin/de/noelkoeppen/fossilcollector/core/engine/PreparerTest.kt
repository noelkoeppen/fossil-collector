package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.ToolDefinition
import de.noelkoeppen.fossilcollector.core.data.ToolStage
import de.noelkoeppen.fossilcollector.core.model.BonePreparation
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Aktion „Werkzeug wirkt auf Bereich“ beim feinen Freilegen (Kapitel 5.1, AP2-08): Pinsel nur lose
 * Erde, Kratzer auch harte Stellen, Schaden-Regel, `damage_reduction`, Qualitaet >= 0, Fortschritt
 * und Zustand „fertig freigelegt“.
 */
class PreparerTest {

    private val realData: GameData = DataRepository().load()

    /** Testwerkzeuge mit Radius 0.5: wirken genau auf die feine Zelle unter dem Ansatzpunkt. */
    private val brush = ToolDefinition("test_brush", ToolStage.PREPARE, removal = 1, radius = 0.5, rough = false, boneDamage = 0.0, strokeSpacing = 1.0)
    private val scraper = ToolDefinition(
        "test_scraper", ToolStage.PREPARE, removal = SCRAPE, radius = 0.5, rough = true, boneDamage = DAMAGE, strokeSpacing = 1.0, removesHard = true,
    )
    private val wideScraper = scraper.copy(id = "test_wide_scraper", radius = 1.5)

    private val data: GameData = realData.copy(
        tools = realData.tools + listOf(brush, scraper, wideScraper),
        balance = realData.balance.copy(preparation = realData.balance.preparation.copy(finishedShare = FINISHED)),
    )

    private val femur = BoneRef(0, "femur_l")

    /**
     * Flaeche mit einem Oberschenkel und begonnener Nahansicht 4 x 1 feine Zellen: Zellen 0 und 1
     * sind Knochen, 2 und 3 nicht. Dicken und harte Stellen wie angegeben.
     */
    private fun site(depths: IntArray, hard: BooleanArray = BooleanArray(4), quality: Double = 0.5, finished: Boolean = false): ExcavationSite {
        val find = Find("cave_bear", listOf(Piece("cave_bear", "femur_l", quality)))
        val placed = PlacedFind(find, mapOf("femur_l" to listOf(0)))
        val prep = BonePreparation(0, "femur_l", 0, 0, 4, 4, 1, depths, hard, booleanArrayOf(true, true, false, false), finished)
        return ExcavationSite("quaternary", 2, 2, IntArray(4), listOf(placed), listOf(prep))
    }

    private fun Preparer.at(site: ExcavationSite, tool: ToolDefinition, x: Int, strokeExposed: Set<Int> = emptySet()) =
        applyTool(site, femur, tool.id, x + 0.5, 0.5, strokeExposed)

    // --- Pinsel und Kratzer ---

    @Test
    fun brushRemovesLooseEarthSlowlyButNotHardSpots() {
        val preparer = Preparer(data)
        val start = site(intArrayOf(3, 3, 3, 3), booleanArrayOf(false, true, false, false))
        val loose = preparer.at(start, brush, 0)
        assertEquals(2, loose.preparation.depthAt(0))
        assertEquals(1, loose.removed)
        assertEquals(listOf(0), loose.changedCells)
        val onHard = preparer.at(start, brush, 1)
        assertSame(start, onHard.site, "Pinsel laesst harte Stellen unberuehrt")
        assertEquals(0, onHard.removed)
        assertTrue(onHard.preparation.isHard(1))
    }

    @Test
    fun scraperRemovesHardSpotsAndLooseEarthFast() {
        val preparer = Preparer(data)
        val start = site(intArrayOf(3, 6, 3, 3), booleanArrayOf(false, true, false, false))
        val hard = preparer.at(start, scraper, 1)
        assertEquals(0, hard.preparation.depthAt(1))
        assertFalse(hard.preparation.isHard(1), "freie Zelle ist nicht mehr hart")
        assertEquals(listOf(1), hard.exposedCells)
        val loose = preparer.at(start, scraper, 0)
        assertEquals(0, loose.preparation.depthAt(0))
        // Dicker als der Abtrag: bleibt teilweise liegen und bleibt hart.
        val thick = preparer.at(site(intArrayOf(3, SCRAPE + 2, 3, 3), booleanArrayOf(false, true, false, false)), scraper, 1)
        assertEquals(2, thick.preparation.depthAt(1))
        assertTrue(thick.preparation.isHard(1))
    }

    @Test
    fun realBrushAndScraperFollowTheRules() {
        val brushTool = realData.tool("brush")
        val scraperTool = realData.tool("scraper")
        val preparer = Preparer(realData)
        assertEquals(0.0, preparer.damagePerHit(brushTool))
        assertTrue(preparer.damagePerHit(scraperTool) > 0.0)
        assertTrue(scraperTool.removal > brushTool.removal, "Kratzer ist schneller als der Pinsel")
        assertThrows<IllegalArgumentException> {
            Excavator(realData).applyTool(ExcavationSite("quaternary", 2, 2, IntArray(4) { 50 }, emptyList()), "brush", 0.5, 0.5)
        }
    }

    // --- Schaden-Regel ---

    @Test
    fun scraperOnResidueDoesNotDamage() {
        val result = Preparer(data).at(site(intArrayOf(3, 3, 3, 3)), scraper, 0)
        assertNull(result.damage)
        assertEquals(0.5, result.site.piece(femur).quality)
    }

    @Test
    fun scraperOnBareBoneDamagesOncePerApplication() {
        val preparer = Preparer(data)
        val bare = site(intArrayOf(0, 0, 3, 3))
        val one = preparer.at(bare, scraper, 0)
        assertEquals(BoneDamage(femur, 0.5, 0.5 - DAMAGE), one.damage)
        assertEquals(listOf(0), one.hitCells)
        assertEquals(0.5 - DAMAGE, one.site.piece(femur).quality, EPS)
        // Zwei freie Knochenzellen im Wirkbereich: trotzdem nur einmal Schaden.
        val wide = preparer.at(bare, wideScraper, 1)
        assertEquals(listOf(0, 1), wide.hitCells)
        assertEquals(DAMAGE, checkNotNull(wide.damage).loss, EPS)
    }

    @Test
    fun scraperOnBareNonBoneCellDoesNotDamage() {
        val result = Preparer(data).at(site(intArrayOf(3, 3, 0, 0)), scraper, 2)
        assertNull(result.damage)
        assertSame(result.site, result.site)
    }

    @Test
    fun cellsExposedInTheSameStrokeDoNotCount() {
        val preparer = Preparer(data)
        val start = site(intArrayOf(3, 3, 3, 3))
        // Erster Zug: Zelle 0 wird frei, eine weitere Anwendung im selben Zug beruehrt sie wieder.
        val first = preparer.at(start, scraper, 0)
        val sameStroke = preparer.at(first.site, wideScraper, 1, strokeExposed = first.exposedCells.toSet())
        assertNull(sameStroke.damage, "eben im selben Zug freigelegt: kein Treffer")
        // Naechster Zug ueber dieselbe Stelle: Treffer.
        val nextStroke = preparer.at(sameStroke.site, scraper, 0)
        assertEquals(DAMAGE, checkNotNull(nextStroke.damage).loss, EPS)
    }

    @Test
    fun brushNeverDamages() {
        val result = Preparer(data).at(site(intArrayOf(0, 0, 0, 0)), brush, 0)
        assertNull(result.damage)
        assertEquals(0.5, result.site.piece(femur).quality)
    }

    @Test
    fun damageReductionLowersDamage() {
        val engine = ModifierEngine.fromGameData(data)
        engine.setSource("upgrade:test", listOf(Modifier(StatId.DAMAGE_REDUCTION, ModifierOp.ADD, 0.25, ModifierCategory.UPGRADE)))
        val preparer = Preparer(data, engine)
        assertEquals(DAMAGE * 0.75, preparer.damagePerHit(scraper), EPS)
        assertEquals(DAMAGE * 0.75, checkNotNull(preparer.at(site(intArrayOf(0, 0, 3, 3)), scraper, 0).damage).loss, EPS)
        engine.setSource("upgrade:test", listOf(Modifier(StatId.DAMAGE_REDUCTION, ModifierOp.ADD, 1.5, ModifierCategory.UPGRADE)))
        assertEquals(BoneDamage(femur, 0.5, 0.5), Preparer(data, engine).at(site(intArrayOf(0, 0, 3, 3)), scraper, 0).damage)
    }

    @Test
    fun qualityNeverDropsBelowZero() {
        val preparer = Preparer(data)
        var s = site(intArrayOf(0, 0, 3, 3), quality = DAMAGE / 2)
        repeat(3) { s = preparer.at(s, scraper, 0).site }
        assertEquals(0.0, s.piece(femur).quality)
        assertEquals(BoneDamage(femur, 0.0, 0.0), preparer.at(s, scraper, 0).damage)
    }

    // --- Fortschritt und fertig ---

    @Test
    fun progressIsShareOfExposedBoneCells() {
        val preparer = Preparer(data)
        val start = site(intArrayOf(1, 1, 1, 1))
        assertEquals(0.0, start.preparation(femur)!!.progress)
        // Zelle 2 ist kein Knochen: kein Fortschritt.
        assertEquals(0.0, preparer.at(start, brush, 2).progress)
        val half = preparer.at(start, brush, 0)
        assertEquals(0.5, half.progress)
        assertFalse(half.preparation.finished)
        assertFalse(half.newlyFinished)
    }

    @Test
    fun finishedOnceThresholdIsReachedAndStaysFinished() {
        val preparer = Preparer(data)
        val half = preparer.at(site(intArrayOf(1, 1, 1, 1)), brush, 0)
        val done = preparer.at(half.site, brush, 1)
        assertEquals(1.0, done.progress)
        assertTrue(done.newlyFinished)
        assertTrue(done.preparation.finished)
        assertTrue(done.site.isPrepared(femur))
        val again = preparer.at(done.site, brush, 3)
        assertFalse(again.newlyFinished, "nur einmal neu fertig")
        assertTrue(again.preparation.finished)
        // Gesetzt bleibt es auch, wenn die Schwelle in den Daten spaeter steigt.
        val stricter = data.copy(balance = data.balance.copy(preparation = data.balance.preparation.copy(finishedShare = 1.0)))
        assertTrue(Preparer(stricter).at(site(intArrayOf(0, 1, 1, 1), finished = true), brush, 3).preparation.finished)
    }

    @Test
    fun realThresholdFinishesAfterBrushingEverything() {
        val site0 = ExcavationGenerator(realData).generate("quaternary", SeededRandom(4))
        val bone = site0.bones.first()
        val depths = site0.depthsCopy().also { d -> site0.cellsOf(bone).forEach { d[it] = 0 } }
        var s = PreparationGenerator(realData).start(site0.with(depths, site0.finds), bone, SeededRandom(9))
        val preparer = Preparer(realData)
        val p = checkNotNull(s.preparation(bone))
        // Pinsel ueberall, bis sich nichts mehr tut: harte Stellen bleiben, also noch nicht fertig, wenn sie auf dem Knochen liegen.
        repeat(realData.balance.preparation.looseDepthMax) {
            for (y in 0 until p.height) for (x in 0 until p.width) s = preparer.applyTool(s, bone, "brush", x + 0.5, y + 0.5).site
        }
        val brushed = checkNotNull(s.preparation(bone))
        val hardOnBone = (0 until brushed.cellCount).count { brushed.isBone(it) && brushed.isHard(it) }
        assertEquals(brushed.boneCellCount - hardOnBone, brushed.exposedBoneCellCount)
        // Kratzer auf die harten Stellen (ein Zug je Zelle): danach fertig.
        for (cell in (0 until brushed.cellCount).filter { brushed.isHard(it) }) {
            s = preparer.applyTool(s, bone, "scraper", brushed.xOf(cell) + 0.5, brushed.yOf(cell) + 0.5).site
        }
        val scraped = checkNotNull(s.preparation(bone))
        assertEquals(1.0, scraped.progress)
        assertTrue(scraped.finished)
    }

    // --- Eingaben ---

    @Test
    fun rejectsDigToolsUnknownToolsAndUnstartedBones() {
        val preparer = Preparer(data)
        val s = site(intArrayOf(1, 1, 1, 1))
        assertThrows<IllegalArgumentException> { preparer.applyTool(s, femur, "shovel", 0.5, 0.5) }
        assertThrows<NoSuchElementException> { preparer.applyTool(s, femur, "dynamite", 0.5, 0.5) }
        val noPrep = ExcavationSite("quaternary", 2, 2, IntArray(4), s.finds)
        assertThrows<NoSuchElementException> { preparer.applyTool(noPrep, femur, brush.id, 0.5, 0.5) }
        assertThrows<IllegalArgumentException> { preparer.applyTool(s, femur, brush.id, Double.NaN, 0.5) }
        // Ausserhalb des Rasters: nichts passiert.
        assertSame(s, preparer.applyTool(s, femur, brush.id, -5.0, -5.0).site)
    }

    private companion object {
        const val SCRAPE = 8
        const val DAMAGE = 0.1
        const val FINISHED = 1.0
        const val EPS = 1e-9
    }
}

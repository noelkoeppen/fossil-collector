package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.PreparationGenerator
import de.noelkoeppen.fossilcollector.core.engine.Preparer
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Verdrahtung des feinen Freilegens zwischen UI und core (AP2-08), mit festem Seed. */
class PreparationSessionTest {

    private val data: GameData = DataRepository().load()

    /** Laufende Ausgrabung, in der der groesste Knochen an einer Zelle frei liegt (entdeckt). */
    private fun discovered(seed: Long = 3): Pair<GameState, BoneRef> {
        val state = ExcavationSession(data).start(GameState.newGame(0L), SeededRandom(seed))
        val site = checkNotNull(state.excavation)
        val bone = site.bones.maxBy { site.cellsOf(it).size }
        val depths = site.depthsCopy().also { it[site.cellsOf(bone).first()] = 0 }
        return state.copy(excavation = site.with(depths, site.finds)) to bone
    }

    private fun opened(seed: Long = 3): Triple<PreparationSession, GameState, BoneRef> {
        val (state, bone) = discovered(seed)
        val session = PreparationSession(data)
        return Triple(session, session.open(state, bone, SeededRandom(21)), bone)
    }

    private fun prepOf(state: GameState, bone: BoneRef) = checkNotNull(checkNotNull(state.excavation).preparation(bone))

    @Test
    fun openStartsPreparationOnceAndOnlyForDiscoveredBones() {
        val (state, bone) = discovered()
        val session = PreparationSession(data)
        val site = checkNotNull(state.excavation)
        val hidden = site.bones.first { !site.isDiscovered(it) }
        assertSame(state, session.open(state, hidden, SeededRandom(1)), "unentdeckter Knochen")
        assertNull(session.openBone)
        val opened = session.open(state, bone, SeededRandom(21))
        assertEquals(bone, session.openBone)
        assertEquals(PreparationGenerator(data).start(site, bone, SeededRandom(21)), opened.excavation, "Restschicht aus core, gleicher Seed")
        // Schliessen und wieder oeffnen: der Stand bleibt (kein neues Wuerfeln).
        session.close()
        assertNull(session.openBone)
        assertSame(opened, session.open(opened, bone, SeededRandom(99)))
        assertEquals(bone, session.openBone)
        assertNull(session.open(GameState.newGame(0L), bone, SeededRandom(1)).excavation, "ohne Ausgrabung nichts")
    }

    @Test
    fun onlyPrepareToolsCanBeSelected() {
        val session = PreparationSession(data)
        assertEquals("brush", session.toolId, "Pinsel zuerst")
        assertTrue(session.selectTool("scraper"))
        assertEquals("scraper", session.toolId)
        assertFalse(session.selectTool("scraper"))
        assertFalse(session.selectTool("shovel"))
        assertFalse(session.selectTool("dynamite"))
        assertEquals("scraper", session.toolId)
    }

    @Test
    fun swipeAppliesToolPerStrokeSpacing() {
        val (session, state, bone) = opened()
        val tool = data.tool(session.toolId)
        val p = prepOf(state, bone)
        val y = p.height / 2 + 0.5
        var s = session.strokeStart(state, 1.0, y)
        s = session.strokeMove(s, 9.0, y)
        session.endStroke()
        val expected = run {
            var site: ExcavationSite = checkNotNull(state.excavation)
            val preparer = Preparer(data)
            val steps = (8.0 / tool.strokeSpacing).toInt()
            site = preparer.applyTool(site, bone, tool.id, 1.0, y).site
            for (i in 1..steps) site = preparer.applyTool(site, bone, tool.id, 1.0 + i * tool.strokeSpacing, y).site
            site
        }
        assertEquals(expected, s.excavation)
        assertSame(s, session.strokeMove(s, 20.0, y), "nach dem Abheben passiert nichts")
    }

    @Test
    fun scraperDamagesOnlyBoneThatWasBareBeforeTheStroke() {
        val (session, state, bone) = opened()
        session.selectTool("scraper")
        val p = prepOf(state, bone)
        val boneCells = (0 until p.cellCount).filter { p.isBone(it) }
        // Eine Zeile mit moeglichst vielen Knochenzellen.
        val row = boneCells.groupBy { p.yOf(it) }.maxBy { it.value.size }
        val xs = row.value.map { p.xOf(it) }
        val y = row.key + 0.5
        val quality = checkNotNull(state.excavation).piece(bone).quality
        // Erster Zug ueber die Restschicht: legt frei, kein Schaden.
        var s = session.strokeStart(state, xs.min() + 0.5, y)
        s = session.strokeMove(s, xs.max() + 0.5, y)
        session.endStroke()
        assertEquals(quality, checkNotNull(s.excavation).piece(bone).quality, "erster Zug beschaedigt nicht")
        assertFalse(session.feedback.damaged)
        // Zweiter Zug ueber denselben, jetzt freien Knochen: Schaden mit Rueckmeldung.
        val sequence = session.feedback.sequence
        s = session.strokeStart(s, xs.min() + 0.5, y)
        s = session.strokeMove(s, xs.max() + 0.5, y)
        session.endStroke()
        assertTrue(checkNotNull(s.excavation).piece(bone).quality < quality)
        assertTrue(session.feedback.damaged)
        assertTrue(session.feedback.sequence > sequence)
        assertTrue(session.feedback.hitCells.all { p.isBone(it) })
    }

    @Test
    fun brushingEverythingAndScrapingHardSpotsFinishes() {
        val (session, state, bone) = opened()
        var s = state
        val p = prepOf(state, bone)
        repeat(data.balance.preparation.looseDepthMax) {
            for (y in 0 until p.height) {
                s = session.strokeStart(s, 0.5, y + 0.5)
                s = session.strokeMove(s, p.width - 0.5, y + 0.5)
                session.endStroke()
            }
        }
        val brushed = prepOf(s, bone)
        assertEquals(checkNotNull(state.excavation).piece(bone).quality, checkNotNull(s.excavation).piece(bone).quality, "Pinsel ist ungefaehrlich")
        session.selectTool("scraper")
        (0 until brushed.cellCount).filter { brushed.isHard(it) }.forEach { cell ->
            s = session.strokeStart(s, brushed.xOf(cell) + 0.5, brushed.yOf(cell) + 0.5)
            session.endStroke()
        }
        val done = prepOf(s, bone)
        assertTrue(done.finished)
        val ui = checkNotNull(PrepUiState.from(checkNotNull(s.excavation), bone, data, session.toolId, session.feedback))
        assertTrue(ui.finished)
        assertEquals(1.0, ui.progress)
    }

    @Test
    fun strokeWithoutOpenBoneDoesNothing() {
        val (state, _) = discovered()
        val session = PreparationSession(data)
        assertSame(state, session.strokeStart(state, 1.0, 1.0))
        assertSame(state, session.strokeMove(state, 3.0, 1.0))
    }

    @Test
    fun uiStateMapsPreparation() {
        val (session, state, bone) = opened()
        val site = checkNotNull(state.excavation)
        assertNull(PrepUiState.from(site, site.bones.first { it != bone }, data, "brush", PrepFeedback.NONE))
        val ui = checkNotNull(PrepUiState.from(site, bone, data, session.toolId, session.feedback))
        assertEquals(listOf("brush", "scraper"), ui.tools)
        assertEquals(setOf("scraper"), ui.hardTools)
        assertEquals("brush", ui.selectedTool)
        assertEquals(0.0, ui.progress)
        assertFalse(ui.finished)
        assertEquals(site.piece(bone).quality, ui.quality)
        assertNotNull(ui.footprint)
        assertEquals(48, ui.pxPerCell)
        assertEquals(site.cellsOf(bone), ui.cells)
        assertEquals(data.balance.preparation.finishedShare, ui.finishedShare)
        assertEquals(data.balance.preparation.hardDepthMax, ui.maxDepth)
        // Flaeche zeigt den Fortschritt am Knochen.
        val dig = DigUiState.from(site, data, de.noelkoeppen.fossilcollector.core.engine.Excavator(data), "shovel", DigFeedback.NONE)
        assertEquals(0.0, dig.bone(bone)?.preparationProgress)
        assertEquals(false, dig.bone(bone)?.prepared)
    }

    @Test
    fun discoveredBoneAtFindsOnlyDiscoveredBones() {
        val (state, bone) = discovered()
        val site = checkNotNull(state.excavation)
        val dig = DigUiState.from(site, data, de.noelkoeppen.fossilcollector.core.engine.Excavator(data), "shovel", DigFeedback.NONE)
        val cell = site.cellsOf(bone).last()
        assertEquals(bone, DigUiState.discoveredBoneAt(dig, site.xOf(cell) + 0.3, site.yOf(cell) + 0.7))
        val hidden = site.bones.first { !site.isDiscovered(it) }
        val hiddenCell = site.cellsOf(hidden).first()
        assertNull(DigUiState.discoveredBoneAt(dig, site.xOf(hiddenCell) + 0.5, site.yOf(hiddenCell) + 0.5))
        assertNull(DigUiState.discoveredBoneAt(dig, -1.0, 2.0))
        assertNull(DigUiState.discoveredBoneAt(dig, site.width + 0.5, 2.0))
        assertNull(DigUiState.discoveredBoneAt(dig, Double.NaN, 2.0))
    }
}

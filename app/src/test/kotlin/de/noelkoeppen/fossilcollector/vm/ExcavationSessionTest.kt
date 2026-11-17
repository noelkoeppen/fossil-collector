package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.Excavator
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

/** Verdrahtung des groben Buddelns zwischen UI und core (AP2-07), mit festem Seed. */
class ExcavationSessionTest {

    private val data: GameData = DataRepository().load()
    private val fresh = GameState.newGame(now = 0L)

    private fun started(seed: Long = 1): Pair<ExcavationSession, GameState> {
        val session = ExcavationSession(data)
        return session to session.start(fresh, SeededRandom(seed))
    }

    /** Zellmitte (Zellkoordinaten) einer Knochenzelle. */
    private fun center(site: ExcavationSite, cell: Int) = site.xOf(cell) + 0.5 to site.yOf(cell) + 0.5

    @Test
    fun startCreatesSiteInFirstLayerOnlyOnce() {
        val (session, state) = started()
        val site = checkNotNull(state.excavation)
        assertEquals(data.layers.first().id, site.layerId)
        assertEquals(data.balance.excavation.width, site.width)
        assertSame(state, session.start(state, SeededRandom(2)), "laufende Ausgrabung bleibt")
        assertEquals(state.excavation, ExcavationSession(data).start(fresh, SeededRandom(1)).excavation, "gleicher Seed, gleiche Flaeche")
    }

    @Test
    fun firstToolIsSelectedAndUnknownToolsAreIgnored() {
        val session = ExcavationSession(data)
        assertEquals(data.tools.first().id, session.toolId)
        assertTrue(session.selectTool("trowel"))
        assertEquals("trowel", session.toolId)
        assertFalse(session.selectTool("trowel"))
        assertFalse(session.selectTool("dynamite"))
        assertFalse(session.selectTool("brush"), "Pinsel gehoert zum feinen Freilegen")
        assertEquals("trowel", session.toolId)
    }

    @Test
    fun swipeAppliesToolPerStrokeSpacing() {
        val (session, state) = started()
        val tool = data.tool(session.toolId)
        // Wischen ueber 10 Zellen: Start plus 10 / strokeSpacing Anwendungen.
        var s = session.digStart(state, 2.0, 2.0)
        s = session.digMove(s, 12.0, 2.0)
        session.endStroke()
        val expected = run {
            var site = checkNotNull(state.excavation)
            val excavator = Excavator(data)
            val steps = (10.0 / tool.strokeSpacing).toInt()
            site = excavator.applyTool(site, tool.id, 2.0, 2.0).site
            for (i in 1..steps) site = excavator.applyTool(site, tool.id, 2.0 + i * tool.strokeSpacing, 2.0).site
            site
        }
        assertEquals(expected, s.excavation)
        // Nach dem Abheben bewirkt eine Bewegung nichts mehr.
        assertSame(s, session.digMove(s, 20.0, 20.0))
    }

    @Test
    fun digWithoutExcavationDoesNothing() {
        val session = ExcavationSession(data)
        assertSame(fresh, session.digStart(fresh, 1.0, 1.0))
        assertSame(fresh, session.digMove(fresh, 3.0, 1.0))
        assertSame(fresh, session.end(fresh))
    }

    @Test
    fun hittingABoneWithShovelGivesDamageFeedback() {
        val (session, state) = started(seed = 3)
        val site = checkNotNull(state.excavation)
        val bone = site.bones.first()
        val (x, y) = center(site, site.cellsOf(bone).first())
        var s = state
        // So lange an derselben Stelle tippen, bis der Knochen getroffen ist.
        var hit = false
        repeat(20) {
            if (hit) return@repeat
            s = session.digStart(s, x, y)
            session.endStroke()
            hit = bone in session.feedback.damaged
        }
        assertTrue(hit, "Knochen nie getroffen")
        val after = checkNotNull(s.excavation)
        assertTrue(after.isDiscovered(bone))
        assertTrue(after.piece(bone).quality < site.piece(bone).quality, "Schaufel beschaedigt")
        assertTrue(session.feedback.focus in session.feedback.damaged, "Anzeige zeigt einen getroffenen Knochen")
    }

    @Test
    fun trowelNeverDamages() {
        val (session, state) = started(seed = 3)
        session.selectTool("trowel")
        val site = checkNotNull(state.excavation)
        val bone = site.bones.first()
        val (x, y) = center(site, site.cellsOf(bone).first())
        var s = state
        val discovered = mutableSetOf<de.noelkoeppen.fossilcollector.core.model.BoneRef>()
        repeat(40) {
            s = session.digStart(s, x, y)
            session.endStroke()
            discovered += session.feedback.discovered
            assertTrue(session.feedback.damaged.isEmpty())
        }
        assertTrue(checkNotNull(s.excavation).isDiscovered(bone))
        assertEquals(site.piece(bone).quality, checkNotNull(s.excavation).piece(bone).quality)
        assertTrue(bone in discovered, "Entdeckung gibt Rueckmeldung")
    }

    @Test
    fun endRemovesExcavation() {
        val (session, state) = started()
        val ended = session.end(state)
        assertNull(ended.excavation)
        assertEquals(DigFeedback(session.feedback.sequence, emptyList(), emptyList(), null), session.feedback)
        assertNotNull(session.start(ended, SeededRandom(5)).excavation, "neue Ausgrabung danach moeglich")
    }

    @Test
    fun uiStateMapsHintsBonesAndNothingLeft() {
        val (session, state) = started(seed = 4)
        val site = checkNotNull(state.excavation)
        val excavator = Excavator(data)
        val ui = DigUiState.from(site, data, excavator, session.toolId, session.feedback)
        assertEquals(site.bones.size, ui.bones.size)
        assertEquals(listOf("shovel", "trowel"), ui.tools, "nur Werkzeuge des groben Buddelns")
        assertEquals(0, ui.discoveredCount)
        assertFalse(ui.nothingLeftToFind)
        assertTrue(ui.hinted.none { it })
        ui.bones.forEach { bone ->
            assertNotNull(bone.footprint, bone.boneId)
            assertEquals(48, bone.pxPerCell)
            assertFalse(bone.discovered)
        }
        // Alles bis auf den Grund freilegen (Kelle, keine Schaeden): nichts mehr zu finden.
        var s = state
        session.selectTool("trowel")
        val removal = data.tool("trowel").removal
        val rounds = data.balance.excavation.depthMin / removal + 1
        val cells = site.bones.flatMap { site.cellsOf(it) }
        repeat(rounds) {
            cells.forEach { cell ->
                val (x, y) = center(site, cell)
                s = session.digStart(s, x, y)
                session.endStroke()
            }
        }
        val done = DigUiState.from(checkNotNull(s.excavation), data, excavator, session.toolId, session.feedback)
        assertTrue(done.nothingLeftToFind)
        assertEquals(site.bones.size, done.discoveredCount)
        assertEquals("trowel", done.selectedTool)
    }

    @Test
    fun hintedCellsComeFromCore() {
        val (session, state) = started(seed = 6)
        session.selectTool("trowel")
        val site = checkNotNull(state.excavation)
        val cell = site.cellsOf(site.bones.first()).first()
        val (x, y) = center(site, cell)
        var s = state
        val excavator = Excavator(data)
        while (!excavator.isHinted(checkNotNull(s.excavation), cell)) {
            s = session.digStart(s, x, y)
            session.endStroke()
        }
        val ui = DigUiState.from(checkNotNull(s.excavation), data, excavator, session.toolId, session.feedback)
        assertTrue(ui.hinted[cell])
        assertEquals(excavator.hintedCells(checkNotNull(s.excavation)).toSet(), ui.hinted.indices.filter { ui.hinted[it] }.toSet())
    }
}

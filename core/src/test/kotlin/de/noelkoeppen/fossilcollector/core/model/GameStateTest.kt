package de.noelkoeppen.fossilcollector.core.model

import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.number.NumberNotation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameStateTest {

    private fun n(value: Double) = GameNumber(value)

    // Beliebige Testwerte, keine Balancing-Werte.
    private val start = RunStartValues(
        money = n(7.0),
        researchPoints = n(2.0),
        teamsInFirstLayer = n(5.0),
        preparators = n(3.0),
        halls = n(1.0),
        labSlots = n(2.0),
        toolLevel = n(4.0),
    )

    @Test
    fun newGameUsesStartValuesFromCaller() {
        val state = GameState.newGame(firstLayerId = "layer_a", start = start, now = 1234L)
        val run = state.run
        assertEquals(n(7.0), run.money)
        assertEquals(n(2.0), run.researchPoints)
        assertEquals(mapOf("layer_a" to n(5.0)), run.teams)
        assertEquals(n(3.0), run.preparators)
        assertEquals(n(1.0), run.halls)
        assertEquals(n(2.0), run.labSlots)
        assertEquals(n(4.0), run.toolLevel)
    }

    @Test
    fun newGameCreatesEmptyStructure() {
        val state = GameState.newGame(firstLayerId = "layer_a", start = start, now = 1234L)
        assertEquals(GameState.CURRENT_SAVE_VERSION, state.saveVersion)
        assertEquals(1234L, state.lastSavedAt)
        assertEquals(MetaState(), state.meta)
        assertEquals(GameNumber.ZERO, state.meta.fame)
        assertTrue(state.meta.book.isEmpty())

        val run = state.run
        assertEquals(GameNumber.ONE, run.unlockedLayers)
        assertEquals(mapOf("layer_a" to GameNumber.ZERO), run.blocks)
        assertTrue(run.pieces.isEmpty())
        assertTrue(run.spareZones.isEmpty())
        assertTrue(run.labQueue.isEmpty())
        assertTrue(run.labJobs.isEmpty())
        assertNull(run.specialExhibit)

        assertEquals(LanguageSetting.SYSTEM, state.settings.language)
        assertEquals(NumberNotation.SHORT, state.settings.numberFormat)
    }

    @Test
    fun pieceQualityIsMeanOfZones() {
        assertEquals(0.75, Piece(listOf(0.9, 0.7, 0.8, 0.6)).quality, 1e-12)
        assertEquals(0.5, Piece(listOf(0.25, 0.75)).quality, 1e-12)
        assertEquals(0.0, Piece(emptyList()).quality)
    }
}

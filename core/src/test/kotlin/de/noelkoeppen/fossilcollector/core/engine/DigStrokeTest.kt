package de.noelkoeppen.fossilcollector.core.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.hypot

/** Ausloesen der Aktion beim Wischen (AP2-07): einmal beim Aufsetzen, dann je `strokeSpacing` Strecke. */
class DigStrokeTest {

    private fun assertPoint(x: Double, y: Double, p: DigPoint) {
        assertEquals(x, p.x, EPS)
        assertEquals(y, p.y, EPS)
    }

    @Test
    fun startAppliesOnceAtTouchPoint() {
        val stroke = DigStroke(spacing = 1.0)
        assertFalse(stroke.isActive)
        assertEquals(listOf(DigPoint(3.5, 4.25)), stroke.start(3.5, 4.25))
        assertTrue(stroke.isActive)
    }

    @Test
    fun appliesEverySpacingAlongThePath() {
        val stroke = DigStroke(spacing = 1.0)
        stroke.start(0.0, 0.0)
        val points = stroke.moveTo(3.5, 0.0)
        assertEquals(3, points.size)
        points.forEachIndexed { i, p -> assertPoint(i + 1.0, 0.0, p) }
        // Der Rest (0.5) wird mitgenommen: nach weiteren 0.5 folgt die naechste Anwendung.
        assertPoint(4.0, 0.0, stroke.moveTo(4.2, 0.0).single())
    }

    @Test
    fun manySmallMovesGiveSameCountAsOneLongMove() {
        // Wie oft das Geraet Bewegungen meldet, aendert nichts an der Anzahl der Anwendungen.
        val coarse = DigStroke(spacing = 0.75)
        coarse.start(0.0, 0.0)
        val one = coarse.moveTo(6.0, 8.0).size
        val fine = DigStroke(spacing = 0.75)
        fine.start(0.0, 0.0)
        var many = 0
        for (i in 1..1000) many += fine.moveTo(6.0 * i / 1000, 8.0 * i / 1000).size
        assertEquals(13, one) // 10 Zellen Strecke / 0.75
        assertEquals(one, many)
    }

    @Test
    fun pointsFollowCurvedPathWithEqualSpacing() {
        val stroke = DigStroke(spacing = 0.5)
        stroke.start(0.0, 0.0)
        val points = stroke.moveTo(2.0, 0.0) + stroke.moveTo(2.0, 2.0)
        assertEquals(8, points.size)
        val all = listOf(DigPoint(0.0, 0.0)) + points
        // Entlang des Weges liegen die Punkte immer 0.5 auseinander (der Knick liegt genau auf einem Punkt).
        all.zipWithNext().forEach { (a, b) -> assertEquals(0.5, hypot(b.x - a.x, b.y - a.y), EPS) }
    }

    @Test
    fun noMovementNoAction() {
        val stroke = DigStroke(spacing = 1.0)
        assertTrue(stroke.moveTo(5.0, 5.0).isEmpty(), "ohne start keine Anwendung")
        stroke.start(1.0, 1.0)
        assertTrue(stroke.moveTo(1.0, 1.0).isEmpty())
        assertTrue(stroke.moveTo(1.4, 1.0).isEmpty())
        stroke.end()
        assertFalse(stroke.isActive)
        assertTrue(stroke.moveTo(9.0, 9.0).isEmpty(), "nach end keine Anwendung")
    }

    @Test
    fun restartResetsTravelledDistance() {
        val stroke = DigStroke(spacing = 1.0)
        stroke.start(0.0, 0.0)
        stroke.moveTo(0.9, 0.0)
        stroke.end()
        stroke.start(10.0, 0.0)
        assertTrue(stroke.moveTo(10.9, 0.0).isEmpty())
        assertEquals(1, stroke.moveTo(11.0, 0.0).size)
    }

    @Test
    fun rejectsInvalidInput() {
        assertThrows<IllegalArgumentException> { DigStroke(0.0) }
        assertThrows<IllegalArgumentException> { DigStroke(Double.NaN) }
        assertThrows<IllegalArgumentException> { DigStroke(1.0).start(Double.POSITIVE_INFINITY, 0.0) }
    }

    @Test
    fun strokeSpacingOfDataToolsIsUsable() {
        val data = de.noelkoeppen.fossilcollector.core.data.DataRepository().load()
        data.tools.forEach { tool ->
            // Abstand hoechstens der Wirkradius, damit eine Wischspur keine Luecken hat.
            assertTrue(tool.strokeSpacing <= tool.radius, tool.id)
            DigStroke(tool.strokeSpacing)
        }
    }

    private companion object {
        const val EPS = 1e-9
    }
}

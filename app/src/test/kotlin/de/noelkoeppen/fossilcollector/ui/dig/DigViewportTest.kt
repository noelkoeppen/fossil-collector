package de.noelkoeppen.fossilcollector.ui.dig

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Verschieben/Zoomen und Umrechnung Geste -> Zellen (AP2-07). */
class DigViewportTest {

    /** Telefon hochkant: 1080 x 1800 Pixel fuer die Flaeche, 32 x 48 Zellen. */
    private val fit = DigViewport.fit(1080f, 1800f, 32, 48)

    @Test
    fun fitShowsWholeSiteCentered() {
        // Die Breite begrenzt: 1080 / 32 Pixel je Zelle, oben und unten gleich viel Rand.
        assertEquals(1080f / 32, fit.baseCellPx, EPS)
        assertEquals(1f, fit.zoom)
        assertEquals(0f, fit.panX, EPS)
        assertEquals((1800f - 48 * fit.baseCellPx) / 2, fit.panY, EPS)
    }

    @Test
    fun screenToCellRoundTrip() {
        val (cx, cy) = fit.screenToCell(fit.panX + 3.5f * fit.cellPx, fit.panY + 10.25f * fit.cellPx)
        assertEquals(3.5, cx, 1e-4)
        assertEquals(10.25, cy, 1e-4)
        val (sx, sy) = fit.cellToScreen(cx, cy)
        assertEquals(fit.panX + 3.5f * fit.cellPx, sx, EPS)
        assertEquals(fit.panY + 10.25f * fit.cellPx, sy, EPS)
    }

    @Test
    fun zoomKeepsPointUnderFingersFixed() {
        val before = fit.screenToCell(540f, 900f)
        val zoomed = fit.transformed(540f, 900f, 0f, 0f, 2f)
        assertEquals(2f, zoomed.zoom)
        val after = zoomed.screenToCell(540f, 900f)
        assertEquals(before.first, after.first, 1e-3)
        assertEquals(before.second, after.second, 1e-3)
    }

    @Test
    fun zoomIsLimited() {
        assertEquals(DigViewport.MAX_ZOOM, fit.transformed(0f, 0f, 0f, 0f, 100f).zoom)
        assertEquals(DigViewport.MIN_ZOOM, fit.transformed(0f, 0f, 0f, 0f, 0.01f).zoom)
    }

    @Test
    fun panStaysInsideSite() {
        val zoomed = fit.transformed(540f, 900f, 0f, 0f, 3f)
        val far = zoomed.transformed(0f, 0f, 100_000f, 100_000f, 1f)
        assertEquals(0f, far.panX, EPS)
        assertEquals(0f, far.panY, EPS)
        val other = zoomed.transformed(0f, 0f, -100_000f, -100_000f, 1f)
        assertEquals(1080f - other.siteWidthPx, other.panX, EPS)
        assertEquals(1800f - other.siteHeightPx, other.panY, EPS)
        // Ungezoomt laesst sich nicht verschieben (alles ist sichtbar).
        assertEquals(fit, fit.transformed(0f, 0f, 300f, 300f, 1f))
    }

    @Test
    fun resizeKeepsZoom() {
        val zoomed = fit.transformed(540f, 900f, 0f, 0f, 2f)
        val turned = zoomed.resized(1800f, 1080f)
        assertEquals(2f, turned.zoom)
        assertTrue(turned.panY <= 0f && turned.panY >= 1080f - turned.siteHeightPx)
    }

    @Test
    fun benchmarkGestureToCell() {
        // Kritischer Pfad je Bewegungsereignis: Umrechnung in Zellen. Muss im Nanosekundenbereich liegen.
        var sum = 0.0
        val runs = 1_000_000
        val start = System.nanoTime()
        for (i in 0 until runs) sum += fit.screenToCell((i % 1080).toFloat(), (i % 1800).toFloat()).first
        val perCall = (System.nanoTime() - start).toDouble() / runs
        assertTrue(sum.isFinite())
        assertTrue(perCall < 2_000.0, "Umrechnung zu langsam: $perCall ns")
    }

    private companion object {
        const val EPS = 1e-3f
    }
}

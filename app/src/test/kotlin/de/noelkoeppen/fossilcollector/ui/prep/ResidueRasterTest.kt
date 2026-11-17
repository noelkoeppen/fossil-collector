package de.noelkoeppen.fossilcollector.ui.prep

import de.noelkoeppen.fossilcollector.core.model.BonePreparation
import de.noelkoeppen.fossilcollector.ui.dig.PixelRect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Darstellung der Restschicht (AP2-08): frei, lose, hart erkennbar; nur geaenderte Ausschnitte. */
class ResidueRasterTest {

    private fun center(raster: ResidueRaster, x: Int, y: Int) = raster.pixels[(y * raster.sub + raster.sub / 2) * raster.width + x * raster.sub + raster.sub / 2]

    @Test
    fun freeLooseAndHardCellsLookDifferent() {
        // 9 x 3: links frei, Mitte lose, rechts hart (je 3 Spalten), damit die Mitten unbeeinflusst sind.
        val depths = IntArray(27) { i -> if (i % 9 < 3) 0 else 6 }
        val hard = BooleanArray(27) { i -> i % 9 >= 6 }
        val raster = ResidueRaster(9, 3, maxDepth = 8)
        raster.update(depths, hard)
        val free = center(raster, 1, 1)
        val loose = center(raster, 4, 1)
        val crust = center(raster, 7, 1)
        assertEquals(0, ResidueRaster.alphaOf(free), "frei ist durchsichtig")
        assertTrue(ResidueRaster.alphaOf(loose) in 120..240, "lose Erde ist leicht durchscheinend: ${ResidueRaster.alphaOf(loose)}")
        assertEquals(255, ResidueRaster.alphaOf(crust), "harte Stelle deckt")
        assertTrue(ResidueRaster.redOf(loose) - ResidueRaster.redOf(crust) > 50, "harte Stelle ist deutlich dunkler")
    }

    @Test
    fun onlyChangedCellsAreRedrawn() {
        val raster = ResidueRaster(10, 10, maxDepth = 8)
        val depths = IntArray(100) { 4 }
        val hard = BooleanArray(100)
        assertEquals(PixelRect(0, 0, 10 * raster.sub, 10 * raster.sub), raster.update(depths, hard))
        assertNull(raster.update(depths, hard))
        depths[55] = 0
        val rect = checkNotNull(raster.update(depths, hard))
        assertEquals(PixelRect(4 * raster.sub, 4 * raster.sub, 7 * raster.sub, 7 * raster.sub), rect)
    }

    @Test
    fun deterministic() {
        val depths = IntArray(16) { it % 5 }
        val hard = BooleanArray(16) { it % 3 == 0 }
        val a = ResidueRaster(4, 4, 8).apply { update(depths, hard) }
        val b = ResidueRaster(4, 4, 8).apply { update(depths, hard) }
        assertTrue(a.pixels.contentEquals(b.pixels))
    }

    @Test
    fun imageLiesOnFineCellsOfBone() {
        // Knochen auf Zellen (2,2) und (3,2) einer 10 breiten Flaeche, Nahansicht ab (1,1), 4 feine Zellen je Zelle.
        val prep = BonePreparation(0, "femur_l", 1, 1, 4, 16, 12, IntArray(192), BooleanArray(192), BooleanArray(192) { true })
        val rect = PrepLayout.imageRect(prep, listOf(22, 23), 10, null, 48, 96, 48)
        // Ohne Umriss mittig auf dem Rechteck der Zellen: 2 x 1 Zellen = 8 x 4 feine Zellen ab (4, 4).
        assertEquals(4.0, rect.left, 1e-9)
        assertEquals(4.0, rect.top, 1e-9)
        assertEquals(8.0, rect.width, 1e-9)
        assertEquals(4.0, rect.height, 1e-9)
    }

    @Test
    fun percentIsFlooredAndClamped() {
        assertEquals(0, PrepTexts.percent(0.0))
        assertEquals(57, PrepTexts.percent(0.57))
        assertEquals(99, PrepTexts.percent(0.999))
        assertEquals(100, PrepTexts.percent(1.0))
        assertEquals(100, PrepTexts.percent(1.5))
    }
}

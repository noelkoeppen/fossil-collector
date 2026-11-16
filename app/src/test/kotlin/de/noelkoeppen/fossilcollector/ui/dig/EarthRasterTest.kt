package de.noelkoeppen.fossilcollector.ui.dig

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Erdmaske der Grabungsflaeche (AP2-07): Inhalt, Teilupdates und Aufwand der kritischen Pfade. */
class EarthRasterTest {

    private val cols = 32
    private val rows = 48
    private val depthMin = 60
    private val hintDepth = 20

    private fun raster() = EarthRaster(cols, rows, depthMin, hintDepth)

    private fun full(depth: Int) = IntArray(cols * rows) { depth }

    private fun noHints() = BooleanArray(cols * rows)

    private fun EarthRaster.alphaAtCellCenter(x: Int, y: Int): Int =
        EarthRaster.alphaOf(mask[(y * sub + sub / 2) * width + x * sub + sub / 2])

    @Test
    fun untouchedEarthIsOpaqueAndFirstUpdateCoversAll() {
        val r = raster()
        val rect = r.update(full(80), noHints())
        assertEquals(PixelRect(0, 0, cols * r.sub, rows * r.sub), rect)
        assertTrue(r.mask.all { EarthRaster.alphaOf(it) == 255 }, "dicke Erde muss deckend sein")
        assertNull(r.update(full(80), noHints()), "ohne Aenderung kein Neuzeichnen")
    }

    @Test
    fun dugCellsAreTransparentAndThinEarthTranslucent() {
        val r = raster()
        val depths = full(80)
        // Ein Loch aus 3 x 3 Zellen und daneben eine Zelle mit wenig Erde.
        for (y in 10..12) for (x in 10..12) depths[y * cols + x] = 0
        depths[20 * cols + 5] = 1
        depths[20 * cols + 6] = 1
        depths[21 * cols + 5] = 1
        depths[21 * cols + 6] = 1
        r.update(depths, noHints())
        assertEquals(0, r.alphaAtCellCenter(11, 11), "Mitte des Lochs")
        assertEquals(255, r.alphaAtCellCenter(20, 20), "unberuehrte Erde")
        val thin = r.alphaAtCellCenter(5, 20)
        assertTrue(thin in 100..220, "duenne Erde scheint durch: $thin")
    }

    @Test
    fun noVisibleGridAlphaChangesGradually() {
        // Kein hartes Raster: Innerhalb unberuehrter oder ganz ausgehobener Bereiche ist die Maske
        // gleichmaessig, und an Zellgrenzen im Inneren springt nichts.
        val r = raster()
        val depths = full(80)
        for (y in 0 until rows) for (x in 0 until cols / 2) depths[y * cols + x] = 0
        r.update(depths, noHints())
        val y = 20 * r.sub
        for (x in 0 until (cols / 2 - 2) * r.sub) assertEquals(0, EarthRaster.alphaOf(r.mask[y * r.width + x]))
        for (x in (cols / 2 + 2) * r.sub until r.width) assertEquals(255, EarthRaster.alphaOf(r.mask[y * r.width + x]))
        // Die Kante ist ausgefranst: nicht in jeder Pixelzeile an derselben Stelle.
        val edges = (0 until r.height).map { py -> (0 until r.width).first { px -> EarthRaster.alphaOf(r.mask[py * r.width + px]) > 127 } }.toSet()
        assertTrue(edges.size > 3, "Kante wirkt wie eine gerade Rasterlinie: $edges")
    }

    @Test
    fun randomStartDepthsLookUniform() {
        // Die Anfangstiefen sind zufaellig (depthMin..depthMax); das darf kein Zellmuster ergeben.
        val random = Random(9)
        val r = raster()
        r.update(IntArray(cols * rows) { depthMin + random.nextInt(41) }, noHints())
        assertEquals(1, r.mask.toSet().size)
        assertEquals(1, r.shade.toSet().size)
    }

    @Test
    fun deeperDiggingDarkensAndHintsTint() {
        val r = raster()
        val depths = full(depthMin)
        depths[5 * cols + 5] = 30
        val hinted = noHints()
        hinted[30 * cols + 20] = true
        depths[30 * cols + 20] = 10
        r.update(depths, hinted)
        fun shadeAt(x: Int, y: Int) = r.shade[(y * r.sub + r.sub / 2) * r.width + x * r.sub + r.sub / 2]
        assertEquals(0, EarthRaster.alphaOf(shadeAt(25, 40)), "volle Tiefe: keine Abdunklung")
        assertTrue(EarthRaster.alphaOf(shadeAt(5, 5)) > 0, "tiefer gegraben: dunkler")
        val hint = shadeAt(20, 30)
        val red = (hint shr 16) and 0xFF
        assertTrue(EarthRaster.alphaOf(hint) > 0 && red > 0, "Hinweis faerbt hell ein")
    }

    @Test
    fun incrementalUpdateEqualsFullRebuild() {
        val random = Random(3)
        val depths = IntArray(cols * rows) { 60 + random.nextInt(41) }
        val hinted = noHints()
        val incremental = raster()
        incremental.update(depths, hinted)
        repeat(50) {
            val cx = random.nextInt(cols)
            val cy = random.nextInt(rows)
            for (y in maxOf(0, cy - 2)..minOf(rows - 1, cy + 2)) {
                for (x in maxOf(0, cx - 2)..minOf(cols - 1, cx + 2)) {
                    val c = y * cols + x
                    depths[c] = maxOf(0, depths[c] - 20)
                    hinted[c] = depths[c] in 1..hintDepth && random.nextBoolean()
                }
            }
            incremental.update(depths, hinted)
        }
        val fresh = raster()
        fresh.update(depths, hinted)
        assertArrayEquals(fresh.mask, incremental.mask)
        assertArrayEquals(fresh.shade, incremental.shade)
    }

    @Test
    fun typicalToolApplicationRedrawsOnlySmallRegion() {
        // Schaufel: Radius 2.5 Zellen, also etwa 5 x 5 Zellen; neu gerechnet wird mit zwei Zellen Rand, also 9 x 9 Zellen.
        val r = raster()
        val depths = full(80)
        r.update(depths, noHints())
        for (y in 18..22) for (x in 10..14) depths[y * cols + x] = 60
        val rect = checkNotNull(r.update(depths, noHints()))
        assertEquals(PixelRect(8 * r.sub, 16 * r.sub, 17 * r.sub, 25 * r.sub), rect)
        assertTrue(rect.area * 15 < r.width * r.height, "Teilupdate zu gross: ${rect.area}")
    }

    @Test
    fun benchmarkCriticalPaths() {
        // Micro-Benchmark (JVM, kein Geraet): volles Neuzeichnen beim Start und typische Updates
        // waehrend des Wischens. Grenzen grosszuegig, damit der Test auf langsamen CI-Rechnern stabil
        // bleibt; auf einem Mittelklasse-Geraet ist mit einem Mehrfachen der JVM-Zeit zu rechnen.
        val depths = full(80)
        val hinted = noHints()
        repeat(WARMUP) { EarthRaster(cols, rows, depthMin, hintDepth).update(depths, hinted) }
        val fullNanos = measure(RUNS) { EarthRaster(cols, rows, depthMin, hintDepth).update(depths, hinted) }
        val r = raster()
        r.update(depths, hinted)
        var step = 0
        val stepNanos = measure(RUNS * 10) {
            val cx = step % (cols - 6) + 3
            val cy = (step / (cols - 6)) % (rows - 6) + 3
            for (y in cy - 2..cy + 2) for (x in cx - 2..cx + 2) depths[y * cols + x] = (depths[y * cols + x] + 79) % 81
            r.update(depths, hinted)
            step++
        }
        assertTrue(fullNanos < FULL_LIMIT_NANOS, "volles Neuzeichnen ${fullNanos / 1e6} ms")
        assertTrue(stepNanos < STEP_LIMIT_NANOS, "Update je Anwendung ${stepNanos / 1e6} ms")
        System.getProperty(PREVIEW_PROPERTY)?.let { dir ->
            java.io.File(dir, "ap2-07-benchmark.txt").writeText(
                "EarthRaster full: %.3f ms, update per tool application: %.3f ms%n".format(fullNanos / 1e6, stepNanos / 1e6),
            )
        }
    }

    private inline fun measure(runs: Int, block: () -> Unit): Long {
        val start = System.nanoTime()
        repeat(runs) { block() }
        return (System.nanoTime() - start) / runs
    }

    private companion object {
        const val WARMUP = 20
        const val RUNS = 20
        const val FULL_LIMIT_NANOS = 100_000_000L
        const val STEP_LIMIT_NANOS = 5_000_000L
        const val PREVIEW_PROPERTY = "fossil.previewDir"
    }
}

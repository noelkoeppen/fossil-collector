package de.noelkoeppen.fossilcollector.ui.prep

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import de.noelkoeppen.fossilcollector.ui.dig.BoneImageAssets
import de.noelkoeppen.fossilcollector.vm.ExcavationSession
import de.noelkoeppen.fossilcollector.vm.PrepUiState
import de.noelkoeppen.fossilcollector.vm.PreparationSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Daten fuer eine Vorschau der Nahansicht ohne Geraet (AP2-08), wie `DigPreviewExportTest`: Eine
 * Flaeche mit festem Seed, der groesste Knochen wird mit der Kelle entdeckt und in der Nahansicht in
 * mehreren Staenden bearbeitet (Start, nach dem Pinsel, nach einem Kratzer-Treffer, fertig). Je
 * Stand werden die Restschicht aus [ResidueRaster] und die Lage des Knochenbilds aus [PrepLayout]
 * exportiert; ein Skript setzt daraus PNG-Bilder zusammen. Laeuft nur mit
 * `-Pfossil.previewDir=<Ordner>`, sonst wird der Test uebersprungen.
 */
class PrepPreviewExportTest {

    @Test
    fun exportPreviewStates() {
        val dir = System.getProperty("fossil.previewDir")
        assumeTrue(dir != null, "keine Vorschau angefordert")
        val out = File(checkNotNull(dir)).apply { mkdirs() }
        val data = DataRepository().load()
        val dig = ExcavationSession(data)
        var state = dig.start(GameState.newGame(0L), SeededRandom(SEED))
        val site0 = checkNotNull(state.excavation)
        val bone = site0.bones.maxBy { site0.cellsOf(it).size }
        // Mit der Kelle auf den Knochen stossen.
        dig.selectTool("trowel")
        val cell = site0.cellsOf(bone)[site0.cellsOf(bone).size / 2]
        while (!checkNotNull(state.excavation).isDiscovered(bone)) {
            state = dig.digStart(state, site0.xOf(cell) + 0.5, site0.yOf(cell) + 0.5)
            dig.endStroke()
        }
        val prep = PreparationSession(data)
        state = prep.open(state, bone, SeededRandom(SEED + 1))
        val states = mutableListOf<Pair<String, PrepUiState>>()
        fun snapshot(name: String) {
            states += name to checkNotNull(PrepUiState.from(checkNotNull(state.excavation), bone, data, prep.toolId, prep.feedback))
        }
        fun swipe(y: Double, x0: Double, x1: Double) {
            state = prep.strokeStart(state, x0, y)
            var x = x0
            while (x < x1) {
                x = minOf(x1, x + 0.3)
                state = prep.strokeMove(state, x, y)
            }
            prep.endStroke()
        }
        val p0 = checkNotNull(checkNotNull(state.excavation).preparation(bone))
        snapshot("1-start")
        // Pinsel: obere Haelfte zweimal.
        repeat(2) { for (y in 0 until p0.height / 2 step 2) swipe(y + 1.0, 0.5, p0.width - 0.5) }
        snapshot("2-pinsel")
        // Kratzer: eine Zeile mitten durch den Knochen zweimal; der zweite Zug trifft freien Knochen.
        prep.selectTool("scraper")
        val boneRow = (0 until p0.cellCount).filter { p0.isBone(it) }.groupBy { p0.yOf(it) }.maxBy { it.value.size }
        val xs = boneRow.value.map { p0.xOf(it) }
        repeat(2) { swipe(boneRow.key + 0.5, xs.min() + 0.5, xs.max() + 0.5) }
        snapshot("3-kratzer-treffer")
        // Fertig: restliche harte Stellen mit dem Kratzer antippen, dann alles mit dem Pinsel.
        val scraped = checkNotNull(checkNotNull(state.excavation).preparation(bone))
        (0 until scraped.cellCount).filter { scraped.isHard(it) }.forEach { c ->
            if (checkNotNull(checkNotNull(state.excavation).preparation(bone)).depthAt(c) > 0) {
                state = prep.strokeStart(state, scraped.xOf(c) + 0.5, scraped.yOf(c) + 0.5)
                prep.endStroke()
            }
        }
        prep.selectTool("brush")
        repeat(data.balance.preparation.looseDepthMax) { for (y in 0 until p0.height) swipe(y + 0.5, 0.5, p0.width - 0.5) }
        snapshot("4-fertig")

        states.forEach { (name, ui) ->
            val p = ui.preparation
            val raster = ResidueRaster(p.width, p.height, ui.maxDepth)
            raster.update(p.depthsCopy(), p.hardCopy())
            writeInts(File(out, "ap2-08-$name-residue.bin"), raster.pixels)
            val (w, h) = imageSize(ui.speciesId, ui.boneId)
            val rect = PrepLayout.imageRect(p, ui.cells, ui.siteWidth, ui.footprint, ui.pxPerCell, w, h)
            val json = buildJsonObject {
                put("cols", p.width)
                put("rows", p.height)
                put("sub", raster.sub)
                put("layerColor", ui.layerColor)
                put("species", ui.speciesId)
                put("bone", ui.boneId)
                put("left", rect.left)
                put("top", rect.top)
                put("width", rect.width)
                put("height", rect.height)
                put("quality", ui.quality)
                put("progress", ui.progress)
                put("finished", ui.finished)
                put("tool", ui.selectedTool)
                put("hits", JsonArray(ui.feedback.hitCells.map { JsonPrimitive(it) }))
                put("hardCells", (0 until p.cellCount).count { p.isHard(it) })
            }
            File(out, "ap2-08-$name.json").writeText(json.toString())
        }
    }

    private fun imageSize(speciesId: String, boneId: String): Pair<Int, Int> {
        val file = File(checkNotNull(System.getProperty("fossil.assetsDir")), BoneImageAssets.imagePath(speciesId, boneId))
        val buffer = ByteBuffer.wrap(file.readBytes(), PNG_WIDTH_OFFSET, 8).order(ByteOrder.BIG_ENDIAN)
        return buffer.int to buffer.int
    }

    private fun writeInts(file: File, values: IntArray) {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.BIG_ENDIAN)
        values.forEach { buffer.putInt(it) }
        file.writeBytes(buffer.array())
    }

    private companion object {
        const val SEED = 7L
        const val PNG_WIDTH_OFFSET = 16
    }
}

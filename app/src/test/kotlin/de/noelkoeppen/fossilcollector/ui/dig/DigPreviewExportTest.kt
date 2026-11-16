package de.noelkoeppen.fossilcollector.ui.dig

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.engine.Excavator
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import de.noelkoeppen.fossilcollector.vm.DigUiState
import de.noelkoeppen.fossilcollector.vm.ExcavationSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Daten fuer eine Vorschau des Screens ohne Geraet (AP2-07): Eine Flaeche mit festem Seed wird in
 * mehreren Grabungsstaenden ueber [ExcavationSession] (also ueber `core`) bearbeitet; je Stand
 * werden die Erdmaske und der Ueberzug aus [EarthRaster] und die Lage der Knochenbilder aus
 * [BoneImageLayout] exportiert. Ein Skript setzt daraus PNG-Bilder zusammen. Laeuft nur mit
 * `-Pfossil.previewDir=<Ordner>`, sonst wird der Test uebersprungen.
 */
class DigPreviewExportTest {

    @Test
    fun exportPreviewStates() {
        val dir = System.getProperty("fossil.previewDir")
        assumeTrue(dir != null, "keine Vorschau angefordert")
        val out = File(checkNotNull(dir)).apply { mkdirs() }
        val data = DataRepository().load()
        val excavator = Excavator(data)
        val session = ExcavationSession(data)
        var state = session.start(GameState.newGame(0L), SeededRandom(SEED))
        val site0 = checkNotNull(state.excavation)
        val raster = EarthRaster(site0.width, site0.height, data.balance.excavation.depthMin, data.balance.excavation.hintDepth)
        val states = mutableListOf<Pair<String, DigUiState>>()
        fun snapshot(name: String) {
            states += name to DigUiState.from(checkNotNull(state.excavation), data, excavator, session.toolId, session.feedback)
        }
        fun swipe(y: Double, x0: Double, x1: Double) {
            state = session.digStart(state, x0, y)
            var x = x0
            while (x < x1) {
                x = minOf(x1, x + 0.3)
                state = session.digMove(state, x, y)
            }
            session.endStroke()
        }
        snapshot("1-start")
        // Ein Schaufel-Durchgang quer ueber die obere Haelfte, dazu einige Kellen-Striche: Erde wird
        // abgetragen, Hinweise erscheinen.
        for (y in 2..22 step 4) swipe(y + 0.5, 1.0, 31.0)
        session.selectTool("trowel")
        for (y in 2..22 step 2) swipe(y + 0.5, 1.0, 31.0)
        session.selectTool("shovel")
        snapshot("2-schaufel")
        // Weiter mit der Schaufel, bis etwas getroffen ist (Schadensanzeige).
        var guard = 0
        while (session.feedback.damaged.isEmpty() && guard++ < 10) for (y in 2..22 step 3) swipe(y + 0.5, 1.0, 31.0)
        snapshot("3-treffer")
        // Mit der Kelle alle Knochen freilegen: „keine Knochen mehr“.
        session.selectTool("trowel")
        val site = checkNotNull(state.excavation)
        repeat(20) {
            site.bones.flatMap { site.cellsOf(it) }.forEach { cell ->
                state = session.digStart(state, site.xOf(cell) + 0.5, site.yOf(cell) + 0.5)
                session.endStroke()
            }
        }
        snapshot("4-alles-gefunden")

        states.forEach { (name, ui) ->
            raster.update(ui.site.depthsCopy(), ui.hinted)
            writeInts(File(out, "ap2-07-$name-mask.bin"), raster.mask)
            writeInts(File(out, "ap2-07-$name-shade.bin"), raster.shade)
            val json = buildJsonObject {
                put("cols", ui.site.width)
                put("rows", ui.site.height)
                put("sub", raster.sub)
                put("layerColor", ui.layerColor)
                put("discovered", ui.discoveredCount)
                put("nothingLeft", ui.nothingLeftToFind)
                put("tool", ui.selectedTool)
                put("damaged", JsonArray(ui.feedback.damaged.flatMap { ref -> ui.bone(ref)?.cells.orEmpty() }.map { JsonPrimitive(it) }))
                put(
                    "bones",
                    buildJsonArray {
                        ui.bones.forEach { bone ->
                            val (w, h) = imageSize(bone.speciesId, bone.boneId)
                            val rect = BoneImageLayout.imageRect(bone.cells, ui.site.width, bone.footprint, bone.pxPerCell, w, h)
                            add(
                                buildJsonObject {
                                    put("species", bone.speciesId)
                                    put("bone", bone.boneId)
                                    put("left", rect.left)
                                    put("top", rect.top)
                                    put("width", rect.width)
                                    put("height", rect.height)
                                    put("quality", bone.quality)
                                    put("discovered", bone.discovered)
                                    put("cells", JsonArray(bone.cells.map { JsonPrimitive(it) }))
                                },
                            )
                        }
                    },
                )
            }
            File(out, "ap2-07-$name.json").writeText(json.toString())
        }
    }

    private fun imageSize(speciesId: String, boneId: String): Pair<Int, Int> {
        val file = File(checkNotNull(System.getProperty("fossil.assetsDir")), BoneImageAssets.imagePath(speciesId, boneId))
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes, PNG_WIDTH_OFFSET, 8).order(ByteOrder.BIG_ENDIAN)
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

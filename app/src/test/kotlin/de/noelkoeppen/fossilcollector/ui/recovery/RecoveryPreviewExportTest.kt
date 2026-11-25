package de.noelkoeppen.fossilcollector.ui.recovery

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import de.noelkoeppen.fossilcollector.ui.dig.BoneImageAssets
import de.noelkoeppen.fossilcollector.ui.prep.PrepLayout
import de.noelkoeppen.fossilcollector.vm.ExcavationSession
import de.noelkoeppen.fossilcollector.vm.PreparationSession
import de.noelkoeppen.fossilcollector.vm.RecoverySession
import de.noelkoeppen.fossilcollector.vm.RecoveryUiState
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
 * Daten fuer eine Vorschau des Bergens ohne Geraet (AP2-09), wie `PrepPreviewExportTest`: Eine
 * Flaeche mit festem Seed, der groesste Knochen wird mit der Kelle entdeckt, in der Nahansicht
 * fertig freigelegt und dann geborgen, in mehreren Staenden (bruechige Stellen, teilweise
 * gesichert, beim Heben mit Ruck, Ergebnis). Je Stand werden Stellen, Lage des Knochenbilds und
 * Rueckmeldung exportiert; ein Skript setzt daraus ein PNG zusammen. Laeuft nur mit
 * `-Pfossil.previewDir=<Ordner>`, sonst wird der Test uebersprungen.
 */
class RecoveryPreviewExportTest {

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
        dig.selectTool("trowel")
        val cell = site0.cellsOf(bone)[site0.cellsOf(bone).size / 2]
        while (!checkNotNull(state.excavation).isDiscovered(bone)) {
            state = dig.digStart(state, site0.xOf(cell) + 0.5, site0.yOf(cell) + 0.5)
            dig.endStroke()
        }
        // Fein freilegen: harte Stellen antippen, dann alles pinseln.
        val prep = PreparationSession(data)
        state = prep.open(state, bone, SeededRandom(SEED + 1))
        val p0 = checkNotNull(checkNotNull(state.excavation).preparation(bone))
        prep.selectTool("scraper")
        (0 until p0.cellCount).filter { p0.isHard(it) }.forEach { c ->
            if (checkNotNull(checkNotNull(state.excavation).preparation(bone)).depthAt(c) > 0) {
                state = prep.strokeStart(state, p0.xOf(c) + 0.5, p0.yOf(c) + 0.5)
                prep.endStroke()
            }
        }
        prep.selectTool("brush")
        repeat(data.balance.preparation.looseDepthMax) {
            for (y in 0 until p0.height) {
                state = prep.strokeStart(state, 0.5, y + 0.5)
                state = prep.strokeMove(state, p0.width - 0.5, y + 0.5)
                prep.endStroke()
            }
        }
        check(checkNotNull(state.excavation).isPrepared(bone))

        val rec = RecoverySession(data)
        state = rec.open(state, bone, SeededRandom(SEED + 2))
        val states = mutableListOf<Triple<String, RecoveryUiState, Pair<Double, Double>>>()
        fun snapshot(name: String, offset: Pair<Double, Double> = 0.0 to 0.0) {
            states += Triple(name, checkNotNull(RecoveryUiState.from(checkNotNull(state.excavation), bone, data, rec.feedback, rec.outcome)), offset)
        }
        snapshot("1-stellen")
        val spots = checkNotNull(checkNotNull(state.excavation).recovery(bone)).spots
        spots.take((spots.size + 1) / 2).forEach { spot ->
            rec.sample(0, 0.0, 0.0, down = true)
            rec.touchStart(p0.xOf(spot) + 0.5, p0.yOf(spot) + 0.5)
            state = rec.touchEnd(state)
        }
        snapshot("2-teilweise-gesichert")
        // Ruckartiges Ziehen: Stillstand, Satz um 60 dp, dann still. Bild 60 dp versetzt (hier in feinen Zellen).
        rec.sample(1_000, 100.0, 300.0, down = true)
        rec.touchStart(1.0, 1.0)
        for (i in 1..20) {
            rec.sample(1_000L + i * 10, 100.0, 300.0, down = false)
            state = rec.drag(state)
        }
        rec.sample(1_220, 160.0, 290.0, down = false)
        state = rec.drag(state)
        for (i in 1..5) {
            rec.sample(1_220L + i * 10, 160.0, 290.0, down = false)
            state = rec.drag(state)
        }
        snapshot("3-heben-ruck", PREVIEW_OFFSET_X to PREVIEW_OFFSET_Y)
        state = rec.touchEnd(state)
        // Rest sichern und ruhig herausziehen.
        spots.drop((spots.size + 1) / 2).forEach { spot ->
            rec.sample(2_000, 0.0, 0.0, down = true)
            rec.touchStart(p0.xOf(spot) + 0.5, p0.yOf(spot) + 0.5)
            state = rec.touchEnd(state)
        }
        rec.sample(3_000, 100.0, 300.0, down = true)
        rec.touchStart(1.0, 1.0)
        for (i in 1..120) {
            val t = i * 10L
            val f = (1 - kotlin.math.cos(Math.PI * t / 1_200)) / 2
            rec.sample(3_000 + t, 100.0 + 200.0 * f, 300.0, down = false)
            state = rec.drag(state)
        }
        state = rec.touchEnd(state)
        check(rec.outcome != null)
        snapshot("4-ergebnis")

        states.forEach { (name, ui, offset) ->
            val p = ui.preparation
            val (w, h) = imageSize(ui.speciesId, ui.boneId)
            val rect = PrepLayout.imageRect(p, ui.cells, ui.siteWidth, ui.footprint, ui.pxPerCell, w, h)
            val outcome = ui.outcome
            val json = buildJsonObject {
                put("cols", p.width)
                put("rows", p.height)
                put("layerColor", ui.layerColor)
                put("species", ui.speciesId)
                put("bone", ui.boneId)
                put("left", rect.left)
                put("top", rect.top)
                put("width", rect.width)
                put("height", rect.height)
                put("quality", ui.quality)
                put("secureRadius", ui.secureRadius)
                put("spots", JsonArray(ui.spots.map { JsonPrimitive(it) }))
                put("secured", JsonArray(ui.secured.map { JsonPrimitive(it) }))
                put("offsetX", offset.first)
                put("offsetY", offset.second)
                put("jerks", ui.feedback.jerks)
                put("newJerk", ui.feedback.newJerk)
                put("recovered", outcome != null)
                put("outcomeJerks", outcome?.jerks ?: 0)
                put("outcomeUnsecured", outcome?.unsecuredSpots ?: 0)
                put("outcomeLoss", RecoveryTexts.percent(outcome?.loss ?: 0.0))
                put("inventory", checkNotNull(state.inventory).size)
            }
            File(out, "ap2-09-$name.json").writeText(json.toString())
        }
    }

    private fun imageSize(speciesId: String, boneId: String): Pair<Int, Int> {
        val file = File(checkNotNull(System.getProperty("fossil.assetsDir")), BoneImageAssets.imagePath(speciesId, boneId))
        val buffer = ByteBuffer.wrap(file.readBytes(), PNG_WIDTH_OFFSET, 8).order(ByteOrder.BIG_ENDIAN)
        return buffer.int to buffer.int
    }

    private companion object {
        const val SEED = 7L
        const val PNG_WIDTH_OFFSET = 16
        const val PREVIEW_OFFSET_X = 7.0
        const val PREVIEW_OFFSET_Y = -1.5
    }
}

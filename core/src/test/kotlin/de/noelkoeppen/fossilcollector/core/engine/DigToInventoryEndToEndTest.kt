package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.ReferenceValidator
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import de.noelkoeppen.fossilcollector.core.save.SaveCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Durchgang Graben bis Bergen (AP2-09, „ab hier durchspielbar“) nur mit Spieleraktionen und festem
 * Seed: Flaeche erzeugen, mit der Kelle graben, bis der Knochen entdeckt ist, in der Nahansicht
 * harte Stellen abkratzen und den Rest abpinseln, bruechige Stellen sichern, ruhig herausheben.
 * Danach liegt der Knochen mit unveraenderter Qualitaet im Inventar.
 */
class DigToInventoryEndToEndTest {

    private val data: GameData = DataRepository().load()

    @Test
    fun digPrepareRecoverPutsBoneIntoInventory() {
        val random = SeededRandom(2026)
        var state = GameState.newGame(0L).copy(excavation = ExcavationGenerator(data).generate("quaternary", random))
        var site = checkNotNull(state.excavation)
        val bone = site.bones.maxBy { site.cellsOf(it).size }
        val quality = site.piece(bone).quality

        // 1. Grob buddeln mit der Kelle (nicht grob, beschaedigt nie), bis man auf den Knochen stoesst.
        val excavator = Excavator(data)
        val cell = site.cellsOf(bone)[site.cellsOf(bone).size / 2]
        var applications = 0
        while (!site.isDiscovered(bone)) {
            site = excavator.applyTool(site, "trowel", site.xOf(cell) + 0.5, site.yOf(cell) + 0.5).site
            applications++
            check(applications < 1_000)
        }
        assertEquals(quality, site.piece(bone).quality)

        // 2. Fein freilegen: harte Stellen mit dem Kratzer antippen (lose Erde drumherum), dann pinseln.
        site = PreparationGenerator(data).start(site, bone, random)
        val preparer = Preparer(data)
        val p0 = checkNotNull(site.preparation(bone))
        (0 until p0.cellCount).filter { p0.isHard(it) }.forEach { c ->
            if (checkNotNull(site.preparation(bone)).depthAt(c) > 0) {
                site = preparer.applyTool(site, bone, "scraper", p0.xOf(c) + 0.5, p0.yOf(c) + 0.5).site
            }
        }
        repeat(data.balance.preparation.looseDepthMax) {
            for (y in 0 until p0.height) for (x in 0 until p0.width) {
                site = preparer.applyTool(site, bone, "brush", x + 0.5, y + 0.5).site
            }
        }
        assertTrue(site.isPrepared(bone))
        val prepared = site.piece(bone).quality
        assertTrue(prepared <= quality)

        // 3. Bergen: alle bruechigen Stellen sichern, ruhig herausheben.
        site = RecoveryGenerator(data).start(site, bone, random)
        val recoverer = Recoverer(data)
        val recovery = checkNotNull(site.recovery(bone))
        assertTrue(recovery.spots.isNotEmpty())
        recovery.spots.forEach { spot -> site = recoverer.secure(site, bone, p0.xOf(spot) + 0.5, p0.yOf(spot) + 0.5).site }
        assertEquals(0, checkNotNull(site.recovery(bone)).unsecuredCount)
        state = state.copy(excavation = site)
        val result = recoverer.lift(state, bone, RecoveryTestSupport.calmPull(data.balance.recovery.liftDistance + 20))
        state = result.state

        assertTrue(result.recovered)
        assertEquals(0, result.evaluation.jerks)
        val piece = state.inventory.single()
        assertEquals(bone.boneId, piece.boneId)
        assertEquals(prepared, piece.quality, "ruhig und gesichert: keine Einbusse beim Bergen")
        assertTrue(checkNotNull(state.excavation).isRecovered(bone))
        assertFalse(checkNotNull(state.excavation).allRecovered)
        assertEquals(emptyList<String>(), ReferenceValidator.validate(state, data))
        assertEquals(state, SaveCodec.DEFAULT.decode(SaveCodec.DEFAULT.encode(state)), "Stand nach dem Bergen ist speicherbar")
    }
}

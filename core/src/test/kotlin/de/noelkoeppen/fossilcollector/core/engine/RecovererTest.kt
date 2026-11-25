package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.BonePreparation
import de.noelkoeppen.fossilcollector.core.model.BoneRecovery
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite
import de.noelkoeppen.fossilcollector.core.model.Find
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PlacedFind
import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import de.noelkoeppen.fossilcollector.core.modifier.ModifierCategory
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Bergen (Kapitel 5.2, AP2-09): Sichern mit Trefferradius, Bewertung ruhiger und ruckartiger
 * Bewegungen, Schaden fuer Rucke und ungesicherte Stellen, `damage_reduction`, Qualitaet >= 0,
 * Knochen im Inventar und Flaeche aktualisiert.
 */
class RecovererTest {

    private val data: GameData = DataRepository().load()
    private val balance = data.balance.recovery
    private val femur = BoneRef(0, "femur_l")
    private val patella = BoneRef(0, "patella_l")

    /**
     * Flaeche mit Oberschenkel und Kniescheibe; der Oberschenkel ist fertig freigelegt (Nahansicht
     * 8 x 1 feine Zellen, Knochen in 0..5) und hat bruechige Stellen bei 0 und 5.
     */
    private fun state(quality: Double = 0.8, secured: List<Boolean> = listOf(false, false)): GameState {
        val find = Find("cave_bear", listOf(Piece("cave_bear", "femur_l", quality), Piece("cave_bear", "patella_l", 0.6)))
        val placed = PlacedFind(find, mapOf("femur_l" to listOf(0), "patella_l" to listOf(1)))
        val bone = BooleanArray(8) { it < 6 }
        val prep = BonePreparation(0, "femur_l", 0, 0, 4, 8, 1, IntArray(8), BooleanArray(8), bone, finished = true)
        val rec = BoneRecovery(0, "femur_l", listOf(0, 5), secured)
        val site = ExcavationSite("quaternary", 2, 2, IntArray(4), listOf(placed), listOf(prep), listOf(rec))
        return GameState.newGame(0L).copy(excavation = site, inventory = listOf(Piece("cave_bear", "sternum", 0.5)))
    }

    private fun site(state: GameState): ExcavationSite = checkNotNull(state.excavation)

    // --- Sichern ---

    @Test
    fun tapSecuresNearestUnsecuredSpotWithinRadius() {
        val recoverer = Recoverer(data)
        val s = site(state())
        // Stelle 5 liegt bei x = 5.5; Tippen bei 5.0 trifft sie.
        val first = recoverer.secure(s, femur, 5.0, 0.5)
        assertEquals(1, first.securedSpot)
        assertEquals(listOf(false, true), first.site.recovery(femur)?.secured)
        // Gleiche Stelle noch einmal: schon gesichert, die andere ist zu weit weg (5 Zellen > Radius).
        val again = recoverer.secure(first.site, femur, 5.0, 0.5)
        assertNull(again.securedSpot)
        assertSame(first.site, again.site)
        // Ausserhalb des Radius: nichts.
        val far = recoverer.secure(s, femur, 0.5 - balance.secureRadius - 0.1, 0.5)
        assertNull(far.securedSpot)
        assertSame(s, far.site)
        // Genau auf dem Radius: trifft.
        assertEquals(0, recoverer.secure(s, femur, 0.5 - balance.secureRadius, 0.5).securedSpot)
        // Nicht endliche Eingabe: nichts.
        assertNull(recoverer.secure(s, femur, Double.NaN, 0.5).securedSpot)
        // Sonst bleibt alles gleich.
        assertEquals(s.finds, first.site.finds)
        assertTrue(s.depthsCopy().contentEquals(first.site.depthsCopy()))
    }

    @Test
    fun secureNeedsRunningRecovery() {
        val recoverer = Recoverer(data)
        assertThrows<NoSuchElementException> { recoverer.secure(site(state()), patella, 0.5, 0.5) }
        val lifted = recoverer.lift(state(), femur, RecoveryTestSupport.calmPull(balance.liftDistance + 10)).state
        assertThrows<IllegalStateException> { recoverer.secure(site(lifted), femur, 0.5, 0.5) }
    }

    // --- Bewertung der Bewegung ---

    @Test
    fun calmPullHasNoJerksAndCompletes() {
        val recoverer = Recoverer(data)
        val samples = RecoveryTestSupport.calmPull(balance.liftDistance + 10)
        val eval = recoverer.evaluate(samples)
        assertTrue(eval.completed)
        assertEquals(0, eval.jerks, "ruhig: $eval")
        assertTrue(eval.peakSpeed < balance.maxSpeed)
        assertTrue(eval.peakSpeedChange < balance.jerkSpeedChange)
        assertTrue(eval.distance >= balance.liftDistance)
        assertTrue(eval.usedSamples < samples.size, "Samples nach dem Herausheben zaehlen nicht")
        assertEquals(eval, recoverer.evaluate(samples), "reine Funktion")
    }

    @Test
    fun jerkyPullCountsOneJerkPerJolt() {
        val recoverer = Recoverer(data)
        val eval = recoverer.evaluate(RecoveryTestSupport.jerkyPull(balance.liftDistance + 10))
        assertTrue(eval.completed)
        assertEquals(1, eval.jerks, "ein Satz ist ein Ruck: $eval")
        assertTrue(eval.peakSpeedChange > balance.jerkSpeedChange)
    }

    @Test
    fun suddenSpeedChangeWithoutExceedingMaxSpeedIsAJerk() {
        val recoverer = Recoverer(data)
        // Konstant 100 dp/s, dann schlagartig 100 + jerkSpeedChange + 50 dp/s (unter maxSpeed).
        val slow = 100.0
        val fast = slow + balance.jerkSpeedChange + 50.0
        assertTrue(fast < balance.maxSpeed)
        val samples = mutableListOf(LiftSample(0, 0.0, 0.0))
        var x = 0.0
        // Anfahren in kleinen Schritten, damit das Anfahren selbst kein Ruck ist.
        for (t in 1..50) {
            x += slow * t / 50.0 * 0.01
            samples += LiftSample(t * 10L, x, 0.0)
        }
        for (t in 51..80) {
            x += slow * 0.01
            samples += LiftSample(t * 10L, x, 0.0)
        }
        for (t in 81..90) {
            x += fast * 0.01
            samples += LiftSample(t * 10L, x, 0.0)
        }
        val eval = recoverer.evaluate(samples)
        assertFalse(eval.completed)
        assertEquals(1, eval.jerks, "$eval")
        assertTrue(eval.peakSpeed < balance.maxSpeed)
    }

    @Test
    fun tooFastPullIsAJerkEvenIfSmooth() {
        val recoverer = Recoverer(data)
        // Weich angefahren, aber in 150 ms ueber den ganzen Weg: Spitzengeschwindigkeit ueber maxSpeed.
        val samples = RecoveryTestSupport.calmPull(balance.liftDistance * 2, durationMillis = 300)
        val eval = recoverer.evaluate(samples)
        assertTrue(eval.peakSpeed > balance.maxSpeed, "$eval")
        assertTrue(eval.jerks >= 1)
    }

    @Test
    fun evaluationHandlesEdgeCasesAndRejectsInvalidInput() {
        val recoverer = Recoverer(data)
        assertEquals(LiftEvaluation.NONE, recoverer.evaluate(emptyList()))
        val one = recoverer.evaluate(listOf(LiftSample(5, 1.0, 1.0)))
        assertEquals(0, one.jerks)
        assertFalse(one.completed)
        // Gleiche Zeitstempel sind erlaubt.
        recoverer.evaluate(listOf(LiftSample(5, 1.0, 1.0), LiftSample(5, 2.0, 1.0), LiftSample(60, 3.0, 1.0)))
        assertThrows<IllegalArgumentException> { recoverer.evaluate(listOf(LiftSample(5, 0.0, 0.0), LiftSample(4, 0.0, 0.0))) }
        assertThrows<IllegalArgumentException> { recoverer.evaluate(listOf(LiftSample(5, Double.NaN, 0.0))) }
        // Geschwindigkeit ist unabhaengig davon, wie oft das Geraet Samples liefert.
        val dense = recoverer.evaluate(RecoveryTestSupport.calmPull(balance.liftDistance + 10, stepMillis = 5))
        val sparse = recoverer.evaluate(RecoveryTestSupport.calmPull(balance.liftDistance + 10, stepMillis = 25))
        assertEquals(dense.jerks, sparse.jerks)
        assertEquals(dense.peakSpeed, sparse.peakSpeed, dense.peakSpeed * 0.05)
    }

    // --- Heben und Schaden ---

    @Test
    fun calmLiftWithAllSpotsSecuredKeepsQualityAndPutsBoneIntoInventory() {
        val recoverer = Recoverer(data)
        val start = state(secured = listOf(true, true))
        val result = recoverer.lift(start, femur, RecoveryTestSupport.calmPull(balance.liftDistance + 10))
        assertTrue(result.recovered)
        assertEquals(0.8, result.qualityAfter)
        assertEquals(0.0, result.loss)
        assertEquals(0, result.unsecuredSpots)
        assertEquals(Piece("cave_bear", "femur_l", 0.8), result.piece)
        assertEquals(start.inventory + Piece("cave_bear", "femur_l", 0.8), result.state.inventory)
        val after = site(result.state)
        assertTrue(after.isRecovered(femur))
        assertFalse(after.isRecovered(patella))
        assertEquals(listOf(femur), after.recoveredBones)
        assertEquals(listOf(patella), after.unrecoveredBones)
        assertFalse(after.allRecovered)
        // Der Fund bleibt vollstaendig in der Flaeche (Zusammenhang im Graph).
        assertEquals(site(start).finds, after.finds)
    }

    @Test
    fun unsecuredSpotsCostQualityOnlyWhenLifted() {
        val recoverer = Recoverer(data)
        val result = recoverer.lift(state(secured = listOf(true, false)), femur, RecoveryTestSupport.calmPull(balance.liftDistance + 10))
        assertEquals(1, result.unsecuredSpots)
        assertEquals(balance.unsecuredDamage, result.unsecuredLoss, 1e-12)
        assertEquals(0.8 - balance.unsecuredDamage, result.qualityAfter, 1e-12)
        assertEquals(result.qualityAfter, checkNotNull(result.piece).quality)
        assertEquals(result.qualityAfter, site(result.state).piece(femur).quality, "Fund und Inventar stimmen ueberein")
        // Losgelassen vor dem Herausheben, ohne Ruck: nichts passiert.
        val start = state()
        val short = recoverer.lift(start, femur, RecoveryTestSupport.calmPull(balance.liftDistance / 2))
        assertFalse(short.recovered)
        assertSame(start, short.state)
        assertEquals(0, short.unsecuredSpots)
    }

    @Test
    fun jerksCostQualityAlsoWhenReleasedEarly() {
        val recoverer = Recoverer(data)
        val start = state(secured = listOf(true, true))
        val samples = RecoveryTestSupport.jerkyPull(balance.liftDistance + 10)
        val early = samples.takeWhile { it.x < 100.0 + balance.liftDistance / 2 }
        val aborted = recoverer.lift(start, femur, early)
        assertFalse(aborted.recovered)
        assertEquals(1, aborted.evaluation.jerks)
        assertEquals(0.8 - balance.jerkDamage, site(aborted.state).piece(femur).quality, 1e-12)
        assertEquals(start.inventory, aborted.state.inventory)
        assertFalse(site(aborted.state).isRecovered(femur))
        // Ganzer ruckartiger Zug mit allem gesichert: nur der Ruck kostet.
        val full = recoverer.lift(start, femur, samples)
        assertTrue(full.recovered)
        assertEquals(balance.jerkDamage, full.loss, 1e-12)
        assertEquals(0.0, full.unsecuredLoss)
    }

    @Test
    fun damageReductionReducesBothDamages() {
        val engine = ModifierEngine.fromGameData(data)
        engine.setSource("upgrade", listOf(Modifier(StatId.DAMAGE_REDUCTION, ModifierOp.ADD, 0.5, ModifierCategory.UPGRADE)))
        val recoverer = Recoverer(data, engine)
        val result = recoverer.lift(state(), femur, RecoveryTestSupport.jerkyPull(balance.liftDistance + 10))
        assertEquals(1, result.evaluation.jerks)
        assertEquals(2, result.unsecuredSpots)
        assertEquals(0.5 * balance.jerkDamage, result.jerkLoss, 1e-12)
        assertEquals(0.5 * 2 * balance.unsecuredDamage, result.unsecuredLoss, 1e-12)
        assertEquals(0.8 - 0.5 * (balance.jerkDamage + 2 * balance.unsecuredDamage), result.qualityAfter, 1e-12)
        // Volle Reduktion (auch ueber 1 begrenzt): kein Schaden.
        engine.setSource("upgrade", listOf(Modifier(StatId.DAMAGE_REDUCTION, ModifierOp.ADD, 3.0, ModifierCategory.UPGRADE)))
        assertEquals(0.8, Recoverer(data, engine).lift(state(), femur, RecoveryTestSupport.jerkyPull(balance.liftDistance + 10)).qualityAfter)
    }

    @Test
    fun qualityNeverDropsBelowZeroAndBoneIsNeverLost() {
        val recoverer = Recoverer(data)
        val result = recoverer.lift(state(quality = 0.03), femur, RecoveryTestSupport.jerkyPull(balance.liftDistance + 10))
        assertTrue(result.recovered)
        assertEquals(0.0, result.qualityAfter)
        assertEquals(Piece("cave_bear", "femur_l", 0.0), result.piece)
        assertEquals(0.03, result.loss, 1e-12)
    }

    @Test
    fun liftNeedsRunningRecovery() {
        val recoverer = Recoverer(data)
        assertThrows<NoSuchElementException> { recoverer.lift(state(), patella, RecoveryTestSupport.calmPull(200.0)) }
        assertThrows<IllegalStateException> { recoverer.lift(GameState.newGame(0L), femur, RecoveryTestSupport.calmPull(200.0)) }
        val lifted = recoverer.lift(state(), femur, RecoveryTestSupport.calmPull(200.0)).state
        assertThrows<IllegalStateException> { recoverer.lift(lifted, femur, RecoveryTestSupport.calmPull(200.0)) }
    }

    // --- Geborgene Knochen in der Flaeche ---

    @Test
    fun recoveredBonesAreIgnoredByDiggingAndPreparation() {
        val lifted = Recoverer(data).lift(state(), femur, RecoveryTestSupport.calmPull(200.0)).state
        val s = site(lifted)
        assertNull(s.activeBoneAt(0), "geborgener Knochen liegt nicht mehr in der Flaeche")
        assertEquals(patella, s.activeBoneAt(1))
        val result = Excavator(data).applyTool(s, "shovel", 0.5, 0.5)
        assertTrue(result.damage.none { it.bone == femur }, "Schaufel trifft geborgenen Knochen nicht")
        assertEquals(s.piece(femur).quality, result.site.piece(femur).quality)
        assertFalse(Excavator(data).isHinted(s, 0))
        assertThrows<IllegalStateException> { Preparer(data).applyTool(s, femur, "brush", 0.5, 0.5) }
    }
}

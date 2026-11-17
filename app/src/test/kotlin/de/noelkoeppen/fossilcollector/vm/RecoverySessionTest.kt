package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.Excavator
import de.noelkoeppen.fossilcollector.core.engine.Recoverer
import de.noelkoeppen.fossilcollector.core.engine.RecoveryGenerator
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import de.noelkoeppen.fossilcollector.ui.recovery.RecoveryTexts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos

/** Verdrahtung des Bergens zwischen UI und core (AP2-09), mit festem Seed. */
class RecoverySessionTest {

    private val data: GameData = DataRepository().load()
    private val liftDistance = data.balance.recovery.liftDistance

    /** Laufende Ausgrabung, in der der groesste Knochen fertig freigelegt ist. */
    private fun prepared(seed: Long = 3): Pair<GameState, BoneRef> {
        val state = ExcavationSession(data).start(GameState.newGame(0L), SeededRandom(seed))
        var site = checkNotNull(state.excavation)
        val bone = site.bones.maxBy { site.cellsOf(it).size }
        site = site.with(site.depthsCopy().also { it[site.cellsOf(bone).first()] = 0 }, site.finds)
        val opened = PreparationSession(data).open(state.copy(excavation = site), bone, SeededRandom(21))
        site = checkNotNull(opened.excavation)
        val prep = checkNotNull(site.preparation(bone))
        val done = prep.with(IntArray(prep.cellCount), BooleanArray(prep.cellCount), finished = true)
        return opened.copy(excavation = site.with(site.depthsCopy(), site.finds, listOf(done))) to bone
    }

    private fun opened(): Triple<RecoverySession, GameState, BoneRef> {
        val (state, bone) = prepared()
        val session = RecoverySession(data)
        return Triple(session, session.open(state, bone, SeededRandom(5)), bone)
    }

    /** Spielt eine Beruehrung durch: Samples in dp, Ziehen nach dem ersten Sample, dann Abheben. */
    private fun pull(session: RecoverySession, state: GameState, points: List<Triple<Long, Double, Double>>): GameState {
        var s = state
        session.touchStart(1.0, 1.0)
        points.forEachIndexed { i, (t, x, y) ->
            session.sample(t, x, y, down = i == 0)
            if (i > 0) s = session.drag(s)
        }
        return session.touchEnd(s)
    }

    private fun calm(distance: Double, durationMillis: Long = 1_000): List<Triple<Long, Double, Double>> =
        (0..durationMillis / 10).map { i ->
            val t = i * 10L
            Triple(1_000 + t, 50.0 + distance * (1 - cos(PI * t / durationMillis)) / 2, 200.0)
        }

    private fun jerky(distance: Double): List<Triple<Long, Double, Double>> {
        val rest = (0..20).map { Triple(1_000L + it * 10, 50.0, 200.0) }
        val jump = Triple(1_220L, 110.0, 200.0)
        val tail = calm(distance - 59).map { (t, x, y) -> Triple(t + 300, x + 60, y) }
        return rest + jump + tail
    }

    @Test
    fun openStartsRecoveryOnlyForPreparedBones() {
        val (state, bone) = prepared()
        val session = RecoverySession(data)
        val site = checkNotNull(state.excavation)
        val other = site.bones.first { it != bone }
        assertSame(state, session.open(state, other, SeededRandom(1)), "nicht freigelegt")
        assertNull(session.openBone)
        val opened = session.open(state, bone, SeededRandom(5))
        assertEquals(bone, session.openBone)
        assertEquals(RecoveryGenerator(data).start(site, bone, SeededRandom(5)), opened.excavation, "Stellen aus core, gleicher Seed")
        session.close()
        assertNull(session.openBone)
        assertSame(opened, session.open(opened, bone, SeededRandom(99)), "zweites Oeffnen wuerfelt nicht neu")
    }

    @Test
    fun tapSecuresSpotAndMissIsReported() {
        val (session, state, bone) = opened()
        val site = checkNotNull(state.excavation)
        val prep = checkNotNull(site.preparation(bone))
        val spot = checkNotNull(site.recovery(bone)).spots.first()
        session.sample(10, 5.0, 5.0, down = true)
        session.touchStart(prep.xOf(spot) + 0.5, prep.yOf(spot) + 0.5)
        val s = session.touchEnd(state)
        assertEquals(0, session.feedback.securedSpot)
        assertTrue(checkNotNull(checkNotNull(s.excavation).recovery(bone)).secured[0])
        // Daneben (ausserhalb des Rasters weit weg): nichts, mit Rueckmeldung.
        session.sample(20, 5.0, 5.0, down = true)
        session.touchStart(-100.0, -100.0)
        assertSame(s, session.touchEnd(s))
        assertTrue(session.feedback.missedTap)
    }

    @Test
    fun calmPullRecoversBoneIntoInventory() {
        val (session, state, bone) = opened()
        var s = state
        // Alle Stellen sichern.
        val site = checkNotNull(s.excavation)
        val prep = checkNotNull(site.preparation(bone))
        checkNotNull(site.recovery(bone)).spots.forEach { spot ->
            session.sample(1, 0.0, 0.0, down = true)
            session.touchStart(prep.xOf(spot) + 0.5, prep.yOf(spot) + 0.5)
            s = session.touchEnd(s)
        }
        val quality = checkNotNull(s.excavation).piece(bone).quality
        s = pull(session, s, calm(liftDistance + 20))
        val outcome = checkNotNull(session.outcome)
        assertEquals(quality, outcome.piece.quality)
        assertEquals(0, outcome.jerks)
        assertEquals(0, outcome.unsecuredSpots)
        assertEquals(listOf(outcome.piece), s.inventory)
        assertTrue(checkNotNull(s.excavation).isRecovered(bone))
        // Weitere Eingaben aendern nichts mehr.
        assertSame(s, pull(session, s, calm(liftDistance + 20)))
        val ui = checkNotNull(RecoveryUiState.from(checkNotNull(s.excavation), bone, data, session.feedback, session.outcome))
        assertTrue(ui.recovered)
        assertEquals(quality, ui.quality)
    }

    @Test
    fun boneIsLiftedAsSoonAsDistanceIsReachedEvenBeforeRelease() {
        val (session, state, _) = opened()
        var s = state
        val points = calm(liftDistance * 2)
        session.touchStart(1.0, 1.0)
        points.forEachIndexed { i, (t, x, y) ->
            session.sample(t, x, y, down = i == 0)
            if (i > 0) s = session.drag(s)
        }
        assertNotNull(session.outcome, "heraus, bevor der Finger abhebt")
        assertEquals(1, s.inventory.size)
        assertSame(s, session.touchEnd(s))
    }

    @Test
    fun jerkGivesFeedbackAndUnsecuredSpotsCost() {
        val (session, state, bone) = opened()
        val quality = checkNotNull(state.excavation).piece(bone).quality
        val spots = checkNotNull(checkNotNull(state.excavation).recovery(bone)).spots.size
        var s = state
        val points = jerky(liftDistance + 20)
        session.touchStart(1.0, 1.0)
        var sawJerk = false
        points.forEachIndexed { i, (t, x, y) ->
            session.sample(t, x, y, down = i == 0)
            if (i > 0) {
                s = session.drag(s)
                if (session.feedback.newJerk) sawJerk = true
            }
        }
        s = session.touchEnd(s)
        assertTrue(sawJerk, "Ruck wird waehrend des Ziehens gemeldet")
        val outcome = checkNotNull(session.outcome)
        assertEquals(1, outcome.jerks)
        assertEquals(spots, outcome.unsecuredSpots)
        val balance = data.balance.recovery
        assertEquals(maxOf(0.0, quality - balance.jerkDamage - spots * balance.unsecuredDamage), outcome.piece.quality, 1e-9)
        assertEquals(RecoveryTexts.percent(outcome.loss), Math.round(outcome.loss * 100).toInt())
    }

    @Test
    fun releasingEarlyDropsBoneAndOnlyJerksCost() {
        val (session, state, bone) = opened()
        val quality = checkNotNull(state.excavation).piece(bone).quality
        val calmShort = pull(session, state, calm(liftDistance / 2))
        assertSame(state, calmShort, "ruhig losgelassen: nichts passiert")
        assertTrue(session.feedback.dropped)
        assertNull(session.outcome)
        val jerkyShort = pull(session, state, jerky(liftDistance + 20).take(40))
        assertTrue(session.feedback.dropped)
        assertEquals(1, session.feedback.jerks)
        assertEquals(quality - data.balance.recovery.jerkDamage, checkNotNull(jerkyShort.excavation).piece(bone).quality, 1e-9)
        assertTrue(jerkyShort.inventory.isEmpty())
        // Das Ergebnis zaehlt seit dem Oeffnen, also auch den Ruck des zurueckgefallenen Versuchs.
        val spots = checkNotNull(checkNotNull(jerkyShort.excavation).recovery(bone)).spots.size
        pull(session, jerkyShort, calm(liftDistance + 20))
        val outcome = checkNotNull(session.outcome)
        assertEquals(1, outcome.jerks)
        assertEquals(quality, outcome.qualityBefore)
        val balance = data.balance.recovery
        assertEquals(maxOf(0.0, quality - balance.jerkDamage - spots * balance.unsecuredDamage), outcome.piece.quality, 1e-9)
    }

    @Test
    fun recoveredBoneDisappearsFromSiteAndCannotBeReopened() {
        val (session, state, bone) = opened()
        val s = pull(session, state, calm(liftDistance + 20))
        val site = checkNotNull(s.excavation)
        val dig = DigUiState.from(site, data, Excavator(data), "shovel", DigFeedback.NONE)
        assertEquals(true, dig.bone(bone)?.recovered)
        assertEquals(1, dig.recoveredCount)
        assertEquals(dig.discoveredCount - 1, dig.unrecoveredDiscoveredCount)
        assertFalse(dig.allRecovered)
        val cell = site.cellsOf(bone).first()
        assertNull(DigUiState.discoveredBoneAt(dig, site.xOf(cell) + 0.5, site.yOf(cell) + 0.5), "geborgen: kein Ziel fuer die Nahansicht")
        val prep = PreparationSession(data)
        assertSame(s, prep.open(s, bone, SeededRandom(1)))
        assertNull(prep.openBone)
        val again = RecoverySession(data)
        assertSame(s, again.open(s, bone, SeededRandom(1)))
        assertNull(again.openBone)
        assertTrue(Recoverer(data).evaluate(emptyList()).jerks == 0)
    }

    @Test
    fun uiStateMapsSpotsAndSecuring() {
        val (session, state, bone) = opened()
        val site = checkNotNull(state.excavation)
        val ui = checkNotNull(RecoveryUiState.from(site, bone, data, session.feedback, session.outcome))
        val rec = checkNotNull(site.recovery(bone))
        assertEquals(rec.spots, ui.spots)
        assertEquals(0, ui.securedCount)
        assertEquals(data.balance.recovery.secureRadius, ui.secureRadius)
        assertEquals(site.piece(bone).quality, ui.quality)
        assertEquals(site.preparation(bone), ui.preparation)
        assertFalse(ui.recovered)
        assertNull(RecoveryUiState.from(site, site.bones.first { it != bone }, data, RecoveryFeedback.NONE, null))
    }
}

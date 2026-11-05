package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LabJob
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.QueuedPiece
import de.noelkoeppen.fossilcollector.core.model.SpareZoneKey
import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import de.noelkoeppen.fossilcollector.core.modifier.ModifierCategory
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests fuer [LabSystem] (Kapitel 4.5) mit den echten Daten: labDuration 30, Schicht-`order`
 * 0 / 1 / 2, Skills `lab_1` (lab_speed -10 %), `lab_2` (research_points +30 %), `lab_4`
 * (lab_speed -25 %), Clamp `lab_speed` min 0.1.
 */
class LabSystemTest {

    private val data: GameData = DataRepository().load()

    private fun n(v: Double) = GameNumber(v)

    private val skull = PieceKey("cave_bear", "skull")
    private val jaw = PieceKey("cave_bear", "jaw")
    private val ribs = PieceKey("giant_deer", "ribs")

    private fun state(
        queue: List<QueuedPiece> = emptyList(),
        jobs: List<LabJob> = emptyList(),
        slots: Double = 1.0,
        skills: Set<String> = emptySet(),
        spare: Map<SpareZoneKey, Double> = emptyMap(),
        researched: Set<String> = emptySet(),
    ): GameState {
        val base = GameState.newGame(data.layers.first().id, RunStart.values(data, ModifierEngine.fromGameData(data)), 0L)
        return base.copy(
            meta = base.meta.copy(skills = skills, researchedSpecies = researched),
            run = base.run.copy(labQueue = queue, labJobs = jobs, labSlots = n(slots), spareZones = spare),
        )
    }

    private fun queued(key: PieceKey, q: Double = 0.5) = QueuedPiece(key, listOf(q, q, q, q))

    private fun lab(state: GameState, extra: List<Modifier> = emptyList()): LabSystem {
        val engine = ModifierEngine.fromGameData(data)
        ModifierSources.sync(engine, state, data)
        if (extra.isNotEmpty()) engine.setSource("test:extra", extra)
        return LabSystem(data, engine)
    }

    private fun run(state: GameState, dt: Double, extra: List<Modifier> = emptyList()): AdvanceResult {
        val context = AdvanceContext(SeededRandom(SEED))
        val result = lab(state, extra).advance(state, dt, context)
        return AdvanceResult(result, context.events)
    }

    private fun spare(key: PieceKey, zone: String) = SpareZoneKey(key, zone)

    // --- Warteschlange und Plaetze ---

    @Test
    fun `ohne Jobs und Warteschlange keine Aenderung`() {
        val s = state()
        assertSame(s, run(s, 10.0).state)
    }

    @Test
    fun `ohne Laborplatz bleibt die Warteschlange liegen`() {
        val s = state(queue = listOf(queued(skull)), slots = 0.0)
        assertSame(s, run(s, 100.0).state)
    }

    @Test
    fun `freier Platz nimmt das erste Stueck der Warteschlange`() {
        val s = state(queue = listOf(queued(skull), queued(jaw), queued(ribs)))
        val result = run(s, 1.0)
        val r = result.state.run
        assertEquals(listOf(skull), r.labJobs.map { it.piece })
        assertEquals(29.0, r.labJobs.single().remaining, EPS)
        assertEquals(listOf(jaw, ribs), r.labQueue.map { it.piece })
        assertEquals(listOf<GameEvent>(GameEvent.LabJobStarted(skull, 30.0)), result.events)
    }

    @Test
    fun `mehrere Plaetze laufen parallel`() {
        val s = state(queue = listOf(queued(skull), queued(jaw), queued(ribs)), slots = 2.0)
        val r = run(s, 10.0).state.run
        assertEquals(listOf(skull, jaw), r.labJobs.map { it.piece })
        r.labJobs.forEach { assertEquals(20.0, it.remaining, EPS) }
        assertEquals(listOf(ribs), r.labQueue.map { it.piece })
    }

    @Test
    fun `Bruchteile von Laborplaetzen zaehlen nicht`() {
        val s = state(queue = listOf(queued(skull), queued(jaw)), slots = 1.9)
        assertEquals(1, run(s, 1.0).state.run.labJobs.size)
    }

    // --- Dauer ---

    @Test
    fun `Dauer mit lab_1 und lab_4 ist 0_65 mal 30 s`() {
        val s = state(queue = listOf(queued(skull)), skills = setOf("lab_1", "lab_4"))
        assertEquals(19.5, lab(s).jobDuration(), EPS)
        val r = run(s, 1.0).state.run
        assertEquals(18.5, r.labJobs.single().remaining, EPS)
        val done = run(s, 19.5).state
        assertTrue(done.run.labJobs.isEmpty())
        assertEquals(1.0, done.run.researchPoints.toDouble(), EPS)
    }

    @Test
    fun `lab_speed wird auf statLimits begrenzt`() {
        val s = state()
        val huge = listOf(Modifier(StatId.LAB_SPEED, ModifierOp.MULT, -5.0, ModifierCategory.SKILL))
        assertEquals(3.0, lab(s, huge).jobDuration(), EPS) // Clamp min 0.1
    }

    @Test
    fun `Fortschritt fuer die UI`() {
        val s = state()
        assertEquals(0.5, lab(s).progress(LabJob(skull, listOf(0.5), 15.0)), EPS)
        assertEquals(1.0, lab(s).progress(LabJob(skull, listOf(0.5), -1.0)), EPS)
    }

    // --- grosse dt ---

    @Test
    fun `grosse dt arbeitet mehrere Jobs nacheinander ab und gibt die Restzeit weiter`() {
        val s = state(queue = listOf(queued(skull), queued(jaw), queued(ribs)))
        val result = run(s, 75.0)
        val r = result.state.run
        assertEquals(2.0, r.researchPoints.toDouble(), EPS)
        assertEquals(listOf(ribs), r.labJobs.map { it.piece })
        assertEquals(15.0, r.labJobs.single().remaining, EPS)
        assertTrue(r.labQueue.isEmpty())
        assertEquals(
            listOf(
                GameEvent.LabJobStarted(skull, 30.0),
                GameEvent.LabJobCompleted(skull, n(1.0)),
                GameEvent.SpeciesResearched("cave_bear"),
                GameEvent.LabJobStarted(jaw, 30.0),
                GameEvent.LabJobCompleted(jaw, n(1.0)),
                GameEvent.LabJobStarted(ribs, 30.0),
            ),
            result.events,
        )
    }

    @Test
    fun `grosse dt mit zwei Plaetzen und laufenden Jobs`() {
        // Platz 1: Job mit 5 s Rest, Platz 2: Job mit 40 s Rest; danach 2 Stuecke in der Warteschlange
        val s = state(
            jobs = listOf(LabJob(skull, listOf(0.5, 0.5, 0.5, 0.5), 5.0), LabJob(jaw, listOf(0.5, 0.5, 0.5, 0.5), 40.0)),
            queue = listOf(queued(ribs), queued(PieceKey("hipparion", "skull"))),
            slots = 2.0,
        )
        // t=5: skull fertig, ribs startet (fertig t=35); t=35: ribs fertig, hipparion startet (fertig t=65)
        // t=40: jaw fertig; bei dt=50 laeuft nur noch hipparion mit 15 s Rest
        val r = run(s, 50.0).state.run
        assertEquals(listOf(PieceKey("hipparion", "skull")), r.labJobs.map { it.piece })
        assertEquals(15.0, r.labJobs.single().remaining, EPS)
        assertEquals(3.0, r.researchPoints.toDouble(), EPS)
    }

    @Test
    fun `viele kleine Ticks ergeben dasselbe wie ein grosser Schritt`() {
        val s = state(queue = List(5) { queued(if (it % 2 == 0) skull else ribs, 0.1 * (it + 1)) }, slots = 2.0)
        val big = run(s, 100.0).state
        var small = s
        repeat(1000) { small = run(small, 0.1).state }
        assertEquals(big.run.researchPoints.toDouble(), small.run.researchPoints.toDouble(), EPS)
        assertEquals(big.run.labJobs.map { it.piece }, small.run.labJobs.map { it.piece })
        big.run.labJobs.zip(small.run.labJobs).forEach { (a, b) -> assertEquals(a.remaining, b.remaining, 1e-6) }
        assertEquals(big.run.spareZones, small.run.spareZones)
        assertEquals(big.meta.researchedSpecies, small.meta.researchedSpecies)
    }

    // --- FP ---

    @Test
    fun `FP je Schicht ist order plus 1`() {
        val keys = listOf(skull, PieceKey("hipparion", "skull"), PieceKey("triceratops", "skull"))
        val s = state(queue = keys.map { queued(it) }, slots = 3.0)
        val result = run(s, 30.0)
        assertEquals(6.0, result.state.run.researchPoints.toDouble(), EPS)
        val points = result.events.filterIsInstance<GameEvent.LabJobCompleted>().associate { it.pieceKey to it.researchPoints }
        assertEquals(mapOf(keys[0] to n(1.0), keys[1] to n(2.0), keys[2] to n(3.0)), points)
    }

    @Test
    fun `research_points-Skill lab_2 erhoeht die FP`() {
        val s = state(queue = listOf(queued(PieceKey("triceratops", "skull"))), skills = setOf("lab_2"))
        assertEquals(3.0 * 1.3, run(s, 30.0).state.run.researchPoints.toDouble(), EPS)
    }

    // --- Ersatzteile ---

    @Test
    fun `Ersatzteile werden je Zone nur bei Verbesserung ersetzt`() {
        val s = state(
            queue = listOf(QueuedPiece(skull, listOf(0.7, 0.9, 0.6, 0.6))),
            spare = mapOf(spare(skull, "snout") to 0.8, spare(skull, "orbit") to 0.5, spare(skull, "skull_roof") to 0.6),
        )
        val zones = run(s, 30.0).state.run.spareZones
        assertEquals(0.8, zones[spare(skull, "snout")]) // schlechter: bleibt
        assertEquals(0.9, zones[spare(skull, "orbit")]) // besser: ersetzt
        assertEquals(0.6, zones[spare(skull, "skull_roof")]) // gleich: bleibt
        assertEquals(0.6, zones[spare(skull, "occiput")]) // neu
        assertEquals(4, zones.size)
    }

    @Test
    fun `Ersatzteile sind an den vollen Schluessel gebunden`() {
        val s = state(queue = listOf(QueuedPiece(skull, listOf(0.7, 0.9, 0.6, 0.6))))
        val zones = run(s, 30.0).state.run.spareZones
        assertTrue(zones.keys.all { it.piece == skull })
        assertEquals(listOf("snout", "orbit", "skull_roof", "occiput"), zones.keys.map { it.zoneId })
    }

    // --- erforscht ---

    @Test
    fun `Art wird beim ersten Abschluss erforscht`() {
        val s = state(queue = listOf(queued(skull), queued(jaw)))
        val result = run(s, 60.0)
        assertEquals(setOf("cave_bear"), result.state.meta.researchedSpecies)
        assertEquals(1, result.events.count { it is GameEvent.SpeciesResearched })
    }

    @Test
    fun `bereits erforschte Art meldet kein neues Ereignis`() {
        val s = state(queue = listOf(queued(skull)), researched = setOf("cave_bear"))
        val result = run(s, 30.0)
        assertEquals(setOf("cave_bear"), result.state.meta.researchedSpecies)
        assertFalse(result.events.any { it is GameEvent.SpeciesResearched })
    }

    @Test
    fun `unbekannte Art bringt keine FP und bricht nicht ab`() {
        val s = state(queue = listOf(queued(PieceKey("unknown_species", "skull"))))
        val after = run(s, 30.0).state
        assertEquals(0.0, after.run.researchPoints.toDouble(), EPS)
        assertTrue(after.meta.researchedSpecies.isEmpty())
        assertTrue(after.run.labJobs.isEmpty())
    }

    // --- Integration ueber GameEngine.advance ---

    @Test
    fun `GameEngine advance verarbeitet die Laborwarteschlange`() {
        val modifiers = ModifierEngine.fromGameData(data)
        val engine = GameEngine(data, modifiers)
        assertTrue(engine.lab.slots(state(slots = 2.0)) == 2)
        val s = state(queue = listOf(queued(skull), queued(PieceKey("hipparion", "jaw"))), skills = setOf("lab_1", "lab_4"))
            .withAllMilestonesDone(data) // ohne FP-Belohnung von first_research
        val result = engine.advance(s, 19.5, SeededRandom(SEED))
        assertEquals(1.0, result.state.run.researchPoints.toDouble(), EPS)
        assertEquals(setOf("cave_bear"), result.state.meta.researchedSpecies)
        assertEquals(listOf(PieceKey("hipparion", "jaw")), result.state.run.labJobs.map { it.piece })
        assertTrue(result.events.any { it is GameEvent.LabJobCompleted })
    }

    @Test
    fun `voller Kern-Loop mit festem Seed fuellt Labor, FP und Ersatzteile`() {
        val modifiers = ModifierEngine.fromGameData(data)
        val engine = GameEngine(data, modifiers)
        val rng = SeededRandom(SEED)
        var s = state(slots = 2.0).withAllMilestonesDone(data) // ohne FP-Belohnungen der Meilensteine
        s = s.copy(run = s.run.copy(blocks = mapOf(Q to n(3000.0)), preparators = n(50.0)))
        var completed = 0
        repeat(600) {
            val result = engine.advance(s, 1.0, rng)
            s = result.state
            completed += result.events.count { it is GameEvent.LabJobCompleted }
            assertTrue(s.run.labJobs.size <= 2)
            assertTrue(s.run.labQueue.size <= 6)
        }
        assertTrue(completed > 0)
        assertEquals(completed.toDouble(), s.run.researchPoints.toDouble(), EPS) // Quartaer: 1 FP je Job
        assertTrue(s.meta.researchedSpecies.isNotEmpty())
        assertTrue(s.run.spareZones.isNotEmpty())
        assertTrue(s.run.spareZones.values.all { it in 0.0..1.0 })
    }

    private companion object {
        const val SEED = 20261105L
        const val EPS = 1e-9
        const val Q = "quaternary"
    }
}

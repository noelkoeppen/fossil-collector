package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.MilestoneCondition
import de.noelkoeppen.fossilcollector.core.data.MilestoneConditionType
import de.noelkoeppen.fossilcollector.core.data.MilestoneDefinition
import de.noelkoeppen.fossilcollector.core.data.MilestoneReward
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LabJob
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.SpareZoneKey
import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import de.noelkoeppen.fossilcollector.core.modifier.ModifierCategory
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Tests fuer [MilestoneSystem] und [MilestoneSources] (Kapitel 5.2) mit `milestones.json`:
 * first_find (pieces_found 1, 25 Geld), teams_10 (150 Geld), first_research (2 FP),
 * first_skeleton (1000 Geld), first_pristine (5 FP), first_reconstruction (5 FP),
 * layer_2 (2000 Geld), first_prestige (100 Geld).
 */
class MilestoneSystemTest {

    private val data: GameData = DataRepository().load()
    private val system = MilestoneSystem(data)

    private fun n(v: Double) = GameNumber(v)

    private fun newState(): GameState =
        GameState.newGame(data.layers.first().id, RunStart.values(data, ModifierEngine.fromGameData(data)), 0L)

    private fun check(state: GameState): AdvanceResult {
        val events = ArrayList<GameEvent>()
        val result = system.check(state) { events += it }
        return AdvanceResult(result, events)
    }

    private fun reached(result: AdvanceResult): List<String> =
        result.events.filterIsInstance<GameEvent.MilestoneReached>().map { it.milestoneId }

    private fun completeBook(speciesId: String, q: Double = 0.6) =
        data.species(speciesId).bones.associate { PieceKey(speciesId, it) to BookEntry(q) }

    /** Setzt den Zaehler [type] im Zustand auf [value]. */
    private fun GameState.withCounter(type: MilestoneConditionType, value: Int): GameState = when (type) {
        MilestoneConditionType.PIECES_FOUND -> copy(meta = meta.copy(stats = meta.stats.copy(piecesFound = n(value.toDouble()))))
        MilestoneConditionType.SKELETONS_COMPLETED -> {
            val book = data.species.take(value).fold(emptyMap<PieceKey, BookEntry>()) { acc, s -> acc + completeBook(s.id) }
            copy(meta = meta.copy(book = book))
        }
        MilestoneConditionType.PRISTINE_FOUND -> copy(meta = meta.copy(stats = meta.stats.copy(pristineFound = n(value.toDouble()))))
        MilestoneConditionType.TEAMS_TOTAL ->
            copy(run = run.copy(teams = mapOf("quaternary" to n(value / 2.0), "neogene" to n(value - value / 2.0))))
        MilestoneConditionType.LAYERS_UNLOCKED -> copy(run = run.copy(unlockedLayers = n(value.toDouble())))
        MilestoneConditionType.RESEARCH_COMPLETED ->
            copy(meta = meta.copy(stats = meta.stats.copy(researchCompleted = n(value.toDouble()))))
        MilestoneConditionType.RECONSTRUCTIONS ->
            copy(meta = meta.copy(stats = meta.stats.copy(reconstructions = n(value.toDouble()))))
        MilestoneConditionType.PRESTIGES -> copy(meta = meta.copy(prestigeCount = n(value.toDouble())))
    }

    // --- Bedingungstypen ---

    @ParameterizedTest(name = "{0}")
    @EnumSource(MilestoneConditionType::class)
    fun `jeder Bedingungstyp loest bei Erreichen des Zielwerts aus`(type: MilestoneConditionType) {
        val milestone = data.milestones.filter { it.condition.type == type }.minBy { it.condition.value }
        val target = milestone.condition.value.toInt()
        val base = newState()

        val below = check(base.withCounter(type, target - 1))
        assertTrue(milestone.id !in reached(below), "${milestone.id} darf bei ${target - 1} nicht ausloesen")

        val at = base.withCounter(type, target)
        assertEquals(target.toDouble(), system.counter(at, type), EPS)
        val result = check(at)
        assertTrue(milestone.id in reached(result), "${milestone.id} muss bei $target ausloesen")
        assertTrue(milestone.id in result.state.meta.milestonesDone)
    }

    @Test
    fun `alle Bedingungstypen kommen in milestones_json vor`() {
        assertEquals(MilestoneConditionType.entries.toSet(), data.milestones.map { it.condition.type }.toSet())
    }

    @Test
    fun `teams_total zaehlt alle Schichten`() {
        val s = newState().copy(run = newState().run.copy(teams = mapOf("quaternary" to n(7.0), "neogene" to n(3.0))))
        assertEquals(10.0, system.counter(s, MilestoneConditionType.TEAMS_TOTAL), EPS)
        assertTrue("teams_10" in reached(check(s)))
    }

    @Test
    fun `skeletons_completed zaehlt vollstaendige Arten im Buch`() {
        val book = completeBook("cave_bear") + (completeBook("giant_deer") - PieceKey("giant_deer", "skull"))
        val s = newState().copy(meta = newState().meta.copy(book = book))
        assertEquals(1.0, system.counter(s, MilestoneConditionType.SKELETONS_COMPLETED), EPS)
    }

    // --- Belohnung ---

    @Test
    fun `Belohnung Geld und FP und Ereignis`() {
        val s = newState().withCounter(MilestoneConditionType.PIECES_FOUND, 1)
            .withCounter(MilestoneConditionType.RESEARCH_COMPLETED, 1)
        val result = check(s)
        assertEquals(listOf("first_find", "first_research"), reached(result))
        assertEquals(25.0, result.state.run.money.toDouble(), EPS)
        assertEquals(2.0, result.state.run.researchPoints.toDouble(), EPS)
        assertEquals(
            GameEvent.MilestoneReached("first_find", n(25.0), n(0.0)),
            result.events.first(),
        )
    }

    @Test
    fun `Belohnung genau einmal`() {
        val s = newState().withCounter(MilestoneConditionType.PIECES_FOUND, 5)
        val first = check(s)
        assertEquals(listOf("first_find"), reached(first))
        val second = check(first.state)
        assertSame(first.state, second.state)
        assertTrue(second.events.isEmpty())
        // Auch wenn der Zaehler weiter steigt.
        val third = check(first.state.withCounter(MilestoneConditionType.PIECES_FOUND, 99))
        assertTrue(third.events.isEmpty())
        assertEquals(25.0, third.state.run.money.toDouble(), EPS)
    }

    @Test
    fun `mehrere Meilensteine auf einmal in Datenreihenfolge`() {
        val result = check(newState().withCounter(MilestoneConditionType.PIECES_FOUND, 1000))
        assertEquals(listOf("first_find", "pieces_100", "pieces_1000"), reached(result))
        assertEquals(525.0, result.state.run.money.toDouble(), EPS)
        assertEquals(10.0, result.state.run.researchPoints.toDouble(), EPS)
    }

    @Test
    fun `ohne neuen Meilenstein bleibt die Instanz`() {
        val s = newState()
        assertSame(s, check(s).state)
        val all = s.withAllMilestonesDone(data).withCounter(MilestoneConditionType.PIECES_FOUND, 5000)
        assertSame(all, check(all).state)
    }

    @Test
    fun `unbekannte IDs in milestonesDone werden ignoriert`() {
        val s = newState().let { it.copy(meta = it.meta.copy(milestonesDone = setOf("removed_milestone"))) }
            .withCounter(MilestoneConditionType.PIECES_FOUND, 1)
        val result = check(s)
        assertEquals(listOf("first_find"), reached(result))
        assertEquals(setOf("removed_milestone", "first_find"), result.state.meta.milestonesDone)
        val engine = ModifierEngine.fromGameData(data)
        ModifierSources.sync(engine, result.state, data)
        assertTrue(engine.sourceIds.isEmpty())
    }

    @Test
    fun `Fortschritt fuer die UI`() {
        val s = newState().withCounter(MilestoneConditionType.PIECES_FOUND, 40)
            .let { it.copy(meta = it.meta.copy(milestonesDone = setOf("first_find"))) }
        val progress = system.progress(s)
        assertEquals(data.milestones.map { it.id }, progress.map { it.milestone.id })
        val p100 = progress.first { it.milestone.id == "pieces_100" }
        assertEquals(40.0, p100.current, EPS)
        assertEquals(100.0, p100.target, EPS)
        assertTrue(!p100.done)
        assertTrue(progress.first { it.milestone.id == "first_find" }.done)
    }

    // --- Effekte als Modifier-Quelle ---

    private val effect = Modifier(StatId.DIG_RATE, ModifierOp.MULT, 0.5, ModifierCategory.MILESTONE)

    private val dataWithEffect: GameData = data.copy(
        milestones = data.milestones + MilestoneDefinition(
            id = "test_effect",
            condition = MilestoneCondition(MilestoneConditionType.TEAMS_TOTAL, 1.0),
            reward = MilestoneReward(effects = listOf(effect)),
        ),
    )

    @Test
    fun `Effekte erreichter Meilensteine sind Quelle milestone_id`() {
        val s = newState().let { it.copy(meta = it.meta.copy(milestonesDone = setOf("test_effect", "first_find"))) }
        val engine = ModifierEngine.fromGameData(dataWithEffect)
        ModifierSources.sync(engine, s, dataWithEffect)
        assertEquals(setOf("milestone:test_effect"), engine.sourceIds)
        assertEquals(listOf(effect), engine.source("milestone:test_effect"))
        assertEquals(1.5, engine.factor(StatId.DIG_RATE), EPS)
    }

    @Test
    fun `Effekt wirkt ab dem naechsten Tick ueber advance`() {
        val modifiers = ModifierEngine.fromGameData(dataWithEffect)
        val engine = GameEngine(
            dataWithEffect,
            modifiers,
            listOf(DigSystem(dataWithEffect, modifiers), MilestoneSystem(dataWithEffect)),
        )
        val base = newState().withAllMilestonesDone(data)
        val s = base.copy(run = base.run.copy(teams = mapOf("quaternary" to n(10.0))))
        val first = engine.advance(s, 1.0, SeededRandom(1L))
        assertEquals(listOf("test_effect"), reached(first))
        assertEquals(1.0, first.state.run.blocks.getValue("quaternary").toDouble(), EPS)
        val second = engine.advance(first.state, 1.0, SeededRandom(1L))
        assertTrue(second.events.none { it is GameEvent.MilestoneReached })
        // 1.0 + 10 * 0.1 * 1.5
        assertEquals(2.5, second.state.run.blocks.getValue("quaternary").toDouble(), EPS)
    }

    // --- Zaehler an den Systemen ---

    @Test
    fun `Labor zaehlt abgeschlossene Forschungen`() {
        val key = PieceKey("cave_bear", "skull")
        val base = newState()
        val s = base.copy(run = base.run.copy(labJobs = listOf(LabJob(key, listOf(0.5, 0.5, 0.5, 0.5), 1.0))))
        val modifiers = ModifierEngine.fromGameData(data)
        val context = AdvanceContext(SeededRandom(1L))
        val result = LabSystem(data, modifiers).advance(s, 2.0, context)
        assertEquals(1.0, result.meta.stats.researchCompleted.toDouble(), EPS)
    }

    @Test
    fun `Praeparation zaehlt Museumsreif-Funde`() {
        val base = newState()
        val s = base.copy(run = base.run.copy(blocks = mapOf("quaternary" to n(2000.0))))
        // Grundqualitaet mindestens 0.95 und kein Schaden -> jeder Fund ist Museumsreif.
        val modifiers = ModifierEngine.fromGameData(data)
        modifiers.setSource(
            "test:extra",
            listOf(
                Modifier(StatId.QUALITY_BASE_MIN, ModifierOp.ADD, 0.65, ModifierCategory.SKILL),
                Modifier(StatId.DAMAGE_REDUCTION, ModifierOp.ADD, 1.0, ModifierCategory.SKILL),
            ),
        )
        val prep = PrepSystem(data, modifiers)
        val rng = SeededRandom(SEED)
        var current = s
        repeat(400) { current = prep.tapPrep(current, rng).state }
        val stats = current.meta.stats
        assertTrue(stats.piecesFound.toDouble() > 0.0)
        assertEquals(stats.piecesFound, stats.pristineFound)
    }

    @Test
    fun `Praeparation zaehlt nur Funde der hoechsten Stufe`() {
        val base = newState()
        var s = base.copy(run = base.run.copy(blocks = mapOf("quaternary" to n(5000.0)), preparators = n(5000.0)))
        s = s.withAllMilestonesDone(data)
        val modifiers = ModifierEngine.fromGameData(data)
        val engine = GameEngine(data, modifiers)
        val result = engine.advance(s, 1.0, SeededRandom(SEED))
        val pristineEvents = result.events.filterIsInstance<GameEvent.FossilFound>().count { it.tierId == "pristine" }
        val found = result.events.count { it is GameEvent.FossilFound }
        assertTrue(found > pristineEvents)
        assertEquals(pristineEvents.toDouble(), result.state.meta.stats.pristineFound.toDouble(), EPS)
        assertEquals(found.toDouble(), result.state.meta.stats.piecesFound.toDouble(), EPS)
    }

    @Test
    fun `Rekonstruktion zaehlt und loest first_reconstruction aus`() {
        val key = PieceKey("cave_bear", "skull")
        val spares = data.bone("skull").zones.associate { SpareZoneKey(key, it) to 0.6 }
        val base = newState()
        val s = base.copy(run = base.run.copy(spareZones = spares))
        val engine = GameEngine(data, ModifierEngine.fromGameData(data))
        val result = engine.reconstruct(s, key) as ReconstructionResult.Success
        assertEquals(1.0, result.state.meta.stats.reconstructions.toDouble(), EPS)
        assertEquals(GameEvent.MilestoneReached("first_reconstruction", n(0.0), n(5.0)), result.events.last())
        assertTrue(result.events.first() is GameEvent.Reconstructed)
        assertEquals(5.0, result.state.run.researchPoints.toDouble(), EPS)
        assertTrue("first_reconstruction" in result.state.meta.milestonesDone)
    }

    // --- Integration ueber GameEngine ---

    @Test
    fun `advance prueft Meilensteine am Ende des Ticks`() {
        val engine = GameEngine(data, ModifierEngine.fromGameData(data))
        val base = newState()
        val s = base.copy(run = base.run.copy(teams = mapOf("quaternary" to n(10.0))))
        val result = engine.advance(s, 0.1, SeededRandom(SEED))
        assertEquals(GameEvent.MilestoneReached("teams_10", n(150.0), n(0.0)), result.events.last())
        assertEquals(150.0, result.state.run.money.toDouble(), EPS)
        assertTrue(engine.advance(result.state, 0.1, SeededRandom(SEED)).events.none { it is GameEvent.MilestoneReached })
    }

    @Test
    fun `Kern-Loop ueber advance erreicht first_find, first_research und first_skeleton genau einmal`() {
        val engine = GameEngine(data, ModifierEngine.fromGameData(data))
        val base = newState()
        var s = base.copy(
            run = base.run.copy(teams = mapOf("quaternary" to n(30.0)), preparators = n(20.0), labSlots = n(2.0)),
        )
        val rng = SeededRandom(SEED)
        val reachedIds = ArrayList<String>()
        repeat(3600) {
            val result = engine.advance(s, 1.0, rng)
            reachedIds += reached(result)
            s = result.state
        }
        assertTrue("first_find" in reachedIds)
        assertTrue("first_research" in reachedIds)
        assertTrue("first_skeleton" in reachedIds)
        assertEquals(reachedIds.size, reachedIds.toSet().size)
        assertEquals(reachedIds.toSet(), s.meta.milestonesDone)
        // Buch-Boni der vollstaendigen Arten sind nach dem Abgleich aktiv.
        engine.syncModifiers(s)
        assertTrue(engine.completion.overview(s).any { it.bookBonus != null })
    }

    @Test
    fun `tapPrep meldet first_find sofort`() {
        val engine = GameEngine(data, ModifierEngine.fromGameData(data))
        val base = newState()
        var s = base.copy(run = base.run.copy(blocks = mapOf("quaternary" to n(400.0))))
        val rng = SeededRandom(SEED)
        var events = emptyList<GameEvent>()
        while (s.meta.stats.piecesFound.toDouble() < 1.0) {
            val result = engine.tapPrep(s, rng)
            s = result.state
            events = result.events
        }
        assertTrue(events.any { it is GameEvent.FossilFound })
        assertEquals("first_find", (events.last() as GameEvent.MilestoneReached).milestoneId)
        assertTrue("first_find" in s.meta.milestonesDone)
    }

    @Test
    fun `checkMilestones fuer Aktionen ausserhalb der Engine`() {
        val engine = GameEngine(data, ModifierEngine.fromGameData(data))
        val s = newState()
        assertSame(s, engine.checkMilestones(s).state)
        // z. B. nach dem Freischalten einer Schicht (Purchases)
        val unlocked = s.copy(run = s.run.copy(unlockedLayers = n(2.0)))
        val result = engine.checkMilestones(unlocked)
        assertEquals(listOf("layer_2"), reached(result))
        assertEquals(2000.0, result.state.run.money.toDouble(), EPS)
    }

    @Test
    fun `erstes Prestige wird ueber prestigeCount erkannt`() {
        val s = newState().let { it.copy(meta = it.meta.copy(prestigeCount = n(1.0))) }
        assertEquals(listOf("first_prestige"), reached(check(s)))
    }

    private companion object {
        const val SEED = 20261105L
        const val EPS = 1e-9
    }
}

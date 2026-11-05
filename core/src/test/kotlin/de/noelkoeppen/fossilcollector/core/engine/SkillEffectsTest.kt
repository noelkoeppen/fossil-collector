package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.SkillBranch
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.Purchases
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.SpareZoneKey
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Jeder der 20 MVP-Knoten (Kapitel 5.4) wirkt tatsaechlich, und zwar ausschliesslich ueber die
 * Modifier-Quelle `skill:<id>` in den vorhandenen Systemen. Jeder Test vergleicht dieselbe Groesse
 * ohne und mit genau diesem Skill (Voraussetzungen sind fuer die Wirkung unerheblich). Je Capstone
 * gibt es einen eigenen Test mit einer echten Spielaktion.
 */
class SkillEffectsTest {

    private val data: GameData = DataRepository().load()

    private val quaternary = data.layers.first { it.order == 0 }
    private val neogene = data.layers.first { it.order == 1 }
    private val cretaceous = data.layers.maxBy { it.order }

    /** Neuer Spielstand mit [skills], ohne Meilenstein-Belohnungen. */
    private fun state(skills: Set<String> = emptySet()): GameState {
        val base = GameState.newGame(quaternary.id, RunStart.values(data, ModifierEngine.fromGameData(data)), 0L)
        return base.copy(meta = base.meta.copy(skills = skills)).withAllMilestonesDone(data)
    }

    /** Engine samt Modifier-Engine, abgeglichen mit [s]. */
    private fun engineFor(s: GameState): Pair<GameEngine, ModifierEngine> {
        val modifiers = ModifierEngine.fromGameData(data)
        val engine = GameEngine(data, modifiers)
        engine.syncModifiers(s)
        return engine to modifiers
    }

    /** Wert von [probe] ohne und mit dem Skill [skillId] (bei sonst gleichem Zustand [adjust]). */
    private fun <T> withAndWithout(
        skillId: String,
        adjust: (GameState) -> GameState = { it },
        probe: (GameEngine, ModifierEngine, GameState) -> T,
    ): Pair<T, T> {
        val without = adjust(state())
        val with = adjust(state(setOf(skillId)))
        val (e0, m0) = engineFor(without)
        val (e1, m1) = engineFor(with)
        return probe(e0, m0, without) to probe(e1, m1, with)
    }

    private fun skeleton(speciesId: String, q: Double): Map<PieceKey, Piece> =
        data.species(speciesId).bones.associate { PieceKey(speciesId, it) to Piece(listOf(q, q, q, q)) }

    /** Run-Zustand nach einem Prestige mit [skills] (Startwerte des neuen Durchlaufs). */
    private fun runAfterPrestige(skills: Set<String>): GameState {
        val s = state(skills).let { it.copy(run = it.run.copy(pieces = skeleton("cave_bear", 0.8))) }
        val (engine, _) = engineFor(s)
        val result = engine.prestige(s)
        assertTrue(result is PrestigeResult.Success, "Prestige erwartet, war $result")
        return (result as PrestigeResult.Success).state
    }

    /** Alle Zonen-Qualitaeten von [rolls] Knochenwuerfen (gleicher Seed) in [layer]. */
    private fun zoneRolls(engine: GameEngine, layer: String, undamaged: Boolean, rolls: Int = 500): List<Double> {
        val rng = SeededRandom(SEED)
        val l = data.layer(layer)
        return (1..rolls).flatMap { engine.prep.rollZones(l, "skull", undamaged, rng) }
    }

    // --- Abdeckung ---

    @Test
    fun `alle 20 MVP-Knoten sind abgedeckt und Expedition hat keine Knoten`() {
        val tested = setOf(
            "dig_1", "dig_2", "dig_3", "dig_4", "dig_5",
            "prep_1", "prep_2", "prep_3", "prep_4", "prep_5",
            "lab_1", "lab_2", "lab_3", "lab_4", "lab_5",
            "curator_1", "curator_2", "curator_3", "curator_4", "curator_5",
        )
        assertEquals(tested, data.skills.map { it.id }.toSet())
        assertTrue(data.skills.none { it.branch == SkillBranch.EXPEDITION })
        assertEquals(4, data.skills.count { it.capstone })
    }

    // --- Grabung ---

    @Test
    fun `dig_1 erhoeht die Fundchance um 5 Prozent`() {
        val (a, b) = withAndWithout("dig_1") { e, _, _ -> e.prep.fossilChance() }
        assertEquals(data.balance.findChance, a, EPS)
        assertEquals(data.balance.findChance * 1.05, b, EPS)
    }

    @Test
    fun `dig_2 erhoeht die Teamrate um 15 Prozent`() {
        val (a, b) = withAndWithout(
            "dig_2",
            adjust = { it.copy(run = it.run.copy(teams = mapOf(quaternary.id to GameNumber(10.0)))) },
        ) { e, _, s -> e.blocksPerSecond(s, quaternary.id).toDouble() }
        assertEquals(10 * quaternary.teamRate, a, EPS)
        assertEquals(10 * quaternary.teamRate * 1.15, b, EPS)
    }

    @Test
    fun `dig_3 startet jeden Durchlauf mit 5 Teams in Schicht 1`() {
        assertEquals(GameNumber.ZERO, runAfterPrestige(emptySet()).run.teams.getValue(quaternary.id))
        assertEquals(GameNumber(5.0), runAfterPrestige(setOf("dig_3")).run.teams.getValue(quaternary.id))
    }

    @Test
    fun `dig_4 senkt die Schicht-Freischaltkosten um 25 Prozent`() {
        val (a, b) = withAndWithout("dig_4") { _, m, _ -> Purchases(data, m).unlockCost(neogene).toDouble() }
        assertEquals(neogene.unlockCost, a, EPS)
        assertEquals(neogene.unlockCost * 0.75, b, EPS)
    }

    @Test
    fun `Capstone dig_5 Glueckliche Hand liefert mehr Fossilien`() {
        val blocks = 20_000.0
        val (a, b) = withAndWithout(
            "dig_5",
            adjust = { it.copy(run = it.run.copy(blocks = mapOf(quaternary.id to GameNumber(blocks)))) },
        ) { e, _, s ->
            val context = AdvanceContext(SeededRandom(SEED))
            e.prep.process(s, blocks, context)
            context.events.filterIsInstance<GameEvent.BlocksPrepared>().sumOf { it.fossils.toDouble() } / blocks
        }
        val find = data.balance.findChance
        assertEquals(find, a, 0.006)
        assertEquals(1.0 - (1.0 - find) * (1.0 - 0.02), b, 0.006)
        assertTrue(b > a)
    }

    // --- Praeparation ---

    @Test
    fun `prep_1 senkt den Schaden`() {
        val (a, b) = withAndWithout("prep_1") { e, _, _ -> zoneRolls(e, cretaceous.id, undamaged = false) }
        // Gleicher Seed: jede Zone ist mindestens so gut, im Mittel etwa um 0.02 * mittlerer Schadenswurf.
        a.zip(b).forEach { (x, y) -> assertTrue(y >= x - EPS) }
        assertTrue(b.average() - a.average() > 0.01)
    }

    @Test
    fun `prep_2 erhoeht das Praeparator-Tempo um 20 Prozent`() {
        val (a, b) = withAndWithout(
            "prep_2",
            adjust = { it.copy(run = it.run.copy(preparators = GameNumber(5.0))) },
        ) { e, _, _ -> e.prepRate() }
        assertTrue(a > 0.0)
        assertEquals(a * 1.2, b, EPS)
    }

    @Test
    fun `prep_3 hebt die Mindest-Grundqualitaet um 0_05`() {
        val (a, b) = withAndWithout("prep_3") { e, _, _ -> zoneRolls(e, quaternary.id, undamaged = true) }
        assertTrue(a.min() < data.balance.qualityBaseMin + 0.05)
        assertTrue(b.all { it >= data.balance.qualityBaseMin + 0.05 - EPS })
        assertTrue(b.average() > a.average())
    }

    @Test
    fun `prep_4 senkt den Schaden staerker als prep_1`() {
        val (a, b) = withAndWithout("prep_4") { e, _, _ -> zoneRolls(e, cretaceous.id, undamaged = false) }
        val (_, c) = withAndWithout("prep_1") { e, _, _ -> zoneRolls(e, cretaceous.id, undamaged = false) }
        a.zip(b).forEach { (x, y) -> assertTrue(y >= x - EPS) }
        assertTrue(b.average() - a.average() > 0.02)
        assertTrue(b.average() > c.average())
    }

    @Test
    fun `Capstone prep_5 Meisterpraeparator laesst Funde ohne Schaden`() {
        val rolls = 5_000
        val (a, b) = withAndWithout("prep_5") { e, _, s ->
            val rng = SeededRandom(SEED)
            (1..rolls).map { e.prep.rollFind(s.run, cretaceous, rng)!! }.count { it.undamaged }
        }
        assertEquals(0, a)
        assertEquals(0.03, b.toDouble() / rolls, 0.008)
    }

    // --- Labor ---

    @Test
    fun `lab_1 verkuerzt die Laborzeit um 10 Prozent`() {
        val (a, b) = withAndWithout("lab_1") { e, _, _ -> e.lab.jobDuration() }
        assertEquals(data.balance.labDuration, a, EPS)
        assertEquals(data.balance.labDuration * 0.9, b, EPS)
    }

    @Test
    fun `lab_2 erhoeht die FP um 30 Prozent`() {
        val key = PieceKey("cave_bear", "skull")
        val (a, b) = withAndWithout("lab_2") { e, _, _ -> e.lab.researchPointsFor(key).toDouble() }
        assertEquals(a * 1.3, b, EPS)
    }

    @Test
    fun `lab_3 gibt zu Beginn jedes Durchlaufs einen Laborplatz mehr`() {
        val base = runAfterPrestige(emptySet()).run.labSlots
        assertEquals(base + 1.0, runAfterPrestige(setOf("lab_3")).run.labSlots)
    }

    @Test
    fun `lab_4 verkuerzt die Laborzeit um 25 Prozent`() {
        val (a, b) = withAndWithout("lab_4") { e, _, _ -> e.lab.jobDuration() }
        assertEquals(data.balance.labDuration * 0.75, b, EPS)
        assertTrue(b < a)
    }

    @Test
    fun `Capstone lab_5 Restaurator rekonstruiert mit einer fehlenden Zone`() {
        val skull = PieceKey("cave_bear", "skull")
        val zones = data.bone("skull").zones
        val spares = mapOf(
            SpareZoneKey(skull, zones[0]) to 0.8,
            SpareZoneKey(skull, zones[1]) to 0.8,
            SpareZoneKey(skull, zones[3]) to 0.8,
        )
        val (a, b) = withAndWithout(
            "lab_5",
            adjust = { it.copy(run = it.run.copy(spareZones = spares)) },
        ) { e, _, s -> e.reconstruct(s, skull) }
        assertEquals(ReconstructionResult.Failure(ReconstructionFailure.MISSING_SPARE_ZONES), a)
        assertTrue(b is ReconstructionResult.Success, "erwartet Erfolg, war $b")
        val entry = (b as ReconstructionResult.Success).state.meta.book.getValue(skull)
        assertEquals(data.balance.reconstructionMissingZoneQuality, entry.zones[2], EPS)
    }

    // --- Kurator ---

    @Test
    fun `curator_1 erhoeht die Museumseinnahmen um 10 Prozent`() {
        val (a, b) = withAndWithout(
            "curator_1",
            adjust = { it.copy(run = it.run.copy(pieces = mapOf(PieceKey("cave_bear", "skull") to Piece(listOf(0.5))))) },
        ) { e, _, s -> e.incomePerSecond(s).toDouble() }
        assertTrue(a > 0.0)
        assertEquals(a * 1.1, b, EPS)
    }

    @Test
    fun `curator_2 senkt die Hallenkosten um 20 Prozent`() {
        val (a, b) = withAndWithout("curator_2") { e, _, s -> e.hallQuote(s, BuyMode.X1)!!.cost.toDouble() }
        assertEquals(a * 0.8, b, EPS)
    }

    @Test
    fun `curator_3 erhoeht den Skelett-Faktor um 0_5`() {
        val (a, b) = withAndWithout(
            "curator_3",
            adjust = { it.copy(run = it.run.copy(pieces = skeleton("cave_bear", 0.5))) },
        ) { e, _, s -> e.incomePerSecond(s).toDouble() }
        val mult = data.balance.skeletonMult
        assertEquals(a / mult * (mult + 0.5), b, EPS)
    }

    @Test
    fun `curator_4 erhoeht die Verkaufspreise um 60 Prozent`() {
        val key = PieceKey("cave_bear", "skull")
        val (a, b) = withAndWithout("curator_4") { _, m, _ -> FindClassifier(data, m).sellValue(key, 0.5).toDouble() }
        assertTrue(a > 0.0)
        assertEquals(a * 1.6, b, EPS)
    }

    @Test
    fun `Capstone curator_5 Sonderausstellung bringt mal 5`() {
        val bear = "cave_bear"
        val (a, b) = withAndWithout(
            "curator_5",
            adjust = { it.copy(run = it.run.copy(pieces = skeleton(bear, 0.5))) },
        ) { e, _, s ->
            val before = e.incomePerSecond(s).toDouble()
            when (val r = e.setSpecialExhibit(s, bear)) {
                is SpecialExhibitResult.Success -> before to e.incomePerSecond(r.state).toDouble()
                is SpecialExhibitResult.Failure -> before to Double.NaN
            }
        }
        assertTrue(a.second.isNaN(), "ohne Capstone keine Sonderausstellung")
        assertEquals(b.first * 5.0, b.second, EPS)
    }

    @Test
    fun `gekaufte Skills wirken ueber Quellen mit Prefix skill`() {
        val s = state(data.skills.map { it.id }.toSet())
        val (_, m) = engineFor(s)
        assertEquals(data.skills.map { "skill:${it.id}" }.toSet(), m.sourceIds.filter { it.startsWith("skill:") }.toSet())
    }

    private companion object {
        const val SEED = 1313L
        const val EPS = 1e-9
    }
}

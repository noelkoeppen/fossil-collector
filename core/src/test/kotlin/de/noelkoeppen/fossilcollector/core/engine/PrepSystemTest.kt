package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.BoneDefinition
import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.LayerDefinition
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import de.noelkoeppen.fossilcollector.core.modifier.ModifierCategory
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests fuer [PrepSystem] mit den echten Daten (Kapitel 4.2, 4.3, 4.9, 6.4: findChance 0.08,
 * Grundqualitaet 0.30..1.00, Schadenswurf 0.5..2.0, Zerbrechlichkeit 0.15/0.30/0.45,
 * rockSellFactor 0.5, missingBoneWeight 3, tapPrepAmount 0.25, Praeparator +0.2 Bloecke/s).
 */
class PrepSystemTest {

    private val data: GameData = DataRepository().load()

    private fun n(v: Double) = GameNumber(v)

    private val quaternary: LayerDefinition get() = data.layer(Q)
    private val cretaceous: LayerDefinition get() = data.layer(C)

    /** Zustand mit allen [unlocked] Schichten offen, Lager je Schicht, Praeparatoren, Skills, Werkzeugstufe. */
    private fun state(
        blocks: Map<String, Double> = emptyMap(),
        unlocked: Int = data.layers.size,
        preparators: Double = 0.0,
        toolLevel: Double = 0.0,
        skills: Set<String> = emptySet(),
        pieces: Map<PieceKey, Piece> = emptyMap(),
    ): GameState {
        val modifiers = ModifierEngine.fromGameData(data)
        val base = GameState.newGame(data.layers.first().id, RunStart.values(data, modifiers), now = 0L)
        val open = data.layers.take(unlocked).map { it.id }
        return base.copy(
            meta = base.meta.copy(skills = skills),
            run = base.run.copy(
                unlockedLayers = n(unlocked.toDouble()),
                teams = open.associateWith { GameNumber.ZERO },
                blocks = open.associateWith { GameNumber.ZERO } + blocks.mapValues { n(it.value) },
                preparators = n(preparators),
                toolLevel = n(toolLevel),
                pieces = pieces,
            ),
        )
    }

    /** Modifier-Engine, abgeglichen mit [state], plus optionale Test-Quelle. */
    private fun modifiers(state: GameState, extra: List<Modifier> = emptyList()): ModifierEngine {
        val engine = ModifierEngine.fromGameData(data)
        ModifierSources.sync(engine, state, data)
        if (extra.isNotEmpty()) engine.setSource("test:extra", extra)
        return engine
    }

    private fun prep(
        state: GameState,
        extra: List<Modifier> = emptyList(),
        handler: FindHandler = FindHandler.NONE,
        gameData: GameData = data,
    ) = PrepSystem(gameData, modifiers(state, extra), handler)

    private fun mod(stat: StatId, op: ModifierOp, value: Double) = Modifier(stat, op, value, ModifierCategory.SKILL)

    private fun blocks(state: GameState, layerId: String) = (state.run.blocks[layerId] ?: GameNumber.ZERO).toDouble()

    private fun meanQuality(system: PrepSystem, layer: LayerDefinition, seed: Long = SEED): Double {
        val rng = SeededRandom(seed)
        return (1..ROLLS).map { system.rollZones(layer, "skull", undamaged = false, rng = rng).average() }.average()
    }

    // --- Statistik ---

    @Test
    fun `10 000 Bloecke ergeben etwa 8 Prozent Fossilien`() {
        val s = state(mapOf(Q to ROLLS.toDouble()), unlocked = 1)
        val context = AdvanceContext(SeededRandom(SEED))
        val result = prep(s).process(s, ROLLS.toDouble(), context)

        val fossils = context.events.filterIsInstance<GameEvent.FossilFound>().size
        assertEquals(0.08, fossils.toDouble() / ROLLS, 0.01)
        assertEquals(0.0, blocks(result, Q), EPS)
        assertEquals(fossils.toDouble(), result.meta.stats.piecesFound.toDouble(), EPS)
        // Gestein: (Bloecke - Fossilien) * value 1 * rockSellFactor 0.5
        assertEquals((ROLLS - fossils) * 0.5, result.run.money.toDouble(), EPS)
        val prepared = context.events.filterIsInstance<GameEvent.BlocksPrepared>().single()
        assertEquals(GameEvent.BlocksPrepared(Q, n(ROLLS.toDouble()), n(fossils.toDouble()), result.run.money), prepared)
    }

    @Test
    fun `Artenverteilung folgt rarityWeight`() {
        val s = state()
        val system = prep(s)
        val rng = SeededRandom(SEED)
        val counts = HashMap<String, Int>()
        repeat(ROLLS) {
            val find = system.rollFind(s.run, quaternary, rng)!!
            counts.merge(find.pieceKey.speciesId, 1, Int::plus)
        }
        val species = data.speciesByLayer.getValue(Q)
        val total = species.sumOf { it.rarityWeight }
        for (sp in species) {
            val expected = sp.rarityWeight / total
            val actual = (counts[sp.id] ?: 0).toDouble() / ROLLS
            assertEquals(expected, actual, 0.015, "Art ${sp.id}")
        }
        assertTrue(counts.keys.all { id -> species.any { it.id == id } }, "nur Arten der Schicht")
    }

    @Test
    fun `tiefe Schicht ergibt niedrigere Durchschnittsqualitaet`() {
        val system = prep(state())
        val q = meanQuality(system, quaternary)
        val c = meanQuality(system, cretaceous)
        // Quartaer ohne Clamp: E = 0.65 - 0.15 * 1.25 = 0.4625
        assertEquals(0.4625, q, 0.01)
        assertTrue(c < q - 0.2, "Kreide $c sollte deutlich unter Quartaer $q liegen")
    }

    @Test
    fun `mehr damage_reduction ergibt hoehere Qualitaet`() {
        val withoutTools = meanQuality(prep(state()), cretaceous)
        val tools5 = meanQuality(prep(state(toolLevel = 5.0)), cretaceous)
        val tools10 = meanQuality(prep(state(toolLevel = 10.0)), cretaceous)
        assertTrue(tools5 > withoutTools + 0.05, "$tools5 vs $withoutTools")
        assertTrue(tools10 > tools5 + 0.05, "$tools10 vs $tools5")
        // Stufe 10: damage_reduction 0.30 -> Kreide wirkt wie Zerbrechlichkeit 0.15 (wie Quartaer ohne Werkzeug)
        assertEquals(0.4625, tools10, 0.01)
        // Reduktion ueber die Zerbrechlichkeit hinaus: kein Schaden, Mittel der Grundqualitaet 0.65
        val tools20 = meanQuality(prep(state(toolLevel = 20.0)), cretaceous)
        assertEquals(0.65, tools20, 0.01)
    }

    @Test
    fun `quality_base_min hebt die Untergrenze der Grundqualitaet`() {
        val system = prep(state(toolLevel = 20.0), extra = listOf(mod(StatId.QUALITY_BASE_MIN, ModifierOp.ADD, 0.5)))
        val rng = SeededRandom(SEED)
        repeat(1000) {
            system.rollZones(quaternary, "skull", undamaged = false, rng = rng).forEach { assertTrue(it >= 0.8) }
        }
    }

    // --- Pech-Schutz ---

    @Test
    fun `fehlende Knochen bekommen missingBoneWeight`() {
        val s = state(pieces = mapOf(PieceKey("cave_bear", "skull") to Piece(listOf(0.5, 0.5, 0.5, 0.5))))
        val system = prep(s)
        assertEquals(1.0, system.boneWeight(s.run, "cave_bear", "skull"), EPS)
        assertEquals(3.0, system.boneWeight(s.run, "cave_bear", "jaw"), EPS)
        assertEquals(3.0, system.boneWeight(s.run, "cave_bear_other", "skull"), EPS)
        // Anderer Schluessel (bestimmtes Alter) zaehlt nicht als vorhanden fuer `speciesId:boneId:_:_`.
        val aged = state(pieces = mapOf(PieceKey("cave_bear", "skull", "adult", null) to Piece(listOf(0.5))))
        assertEquals(3.0, system.boneWeight(aged.run, "cave_bear", "skull"), EPS)
    }

    @Test
    fun `Knochenwahl mit Pech-Schutz bevorzugt den fehlenden Knochen`() {
        val species = data.species("cave_bear")
        val missing = species.bones.last()
        val pieces = species.bones.dropLast(1).associate { PieceKey(species.id, it) to Piece(listOf(0.5)) }
        val s = state(pieces = pieces)
        val system = prep(s)
        val rng = SeededRandom(SEED)
        val hits = (1..ROLLS).count { system.rollBone(s.run, species, rng) == missing }
        // Gewicht 3 von 7 * 1 + 3 = 10
        assertEquals(0.3, hits.toDouble() / ROLLS, 0.015)
    }

    @Test
    fun `ohne Funde sind alle Knochen gleich wahrscheinlich`() {
        val species = data.species("cave_bear")
        val s = state()
        val system = prep(s)
        val rng = SeededRandom(SEED)
        val counts = (1..ROLLS).groupingBy { system.rollBone(s.run, species, rng) }.eachCount()
        for (bone in species.bones) {
            assertEquals(1.0 / species.bones.size, (counts[bone] ?: 0).toDouble() / ROLLS, 0.015, bone)
        }
    }

    @Test
    fun `Pech-Schutz fuehrt schneller zum vollstaendigen Skelett`() {
        val species = data.species("cave_bear")
        fun meanDistinct(gameData: GameData): Double {
            val system = prep(state(), gameData = gameData)
            val rng = SeededRandom(SEED)
            return (1..2000).map {
                var s = state()
                repeat(species.bones.size) {
                    val bone = system.rollBone(s.run, species, rng)
                    s = s.copy(run = s.run.copy(pieces = s.run.pieces + (PieceKey(species.id, bone) to Piece(listOf(0.5)))))
                }
                s.run.pieces.size
            }.average()
        }
        val withoutProtection = meanDistinct(data.copy(balance = data.balance.copy(missingBoneWeight = 1.0)))
        val withProtection = meanDistinct(data)
        // Ohne Schutz: 8 * (1 - (7/8)^8) = 5.25 verschiedene Knochen nach 8 Funden
        assertEquals(5.25, withoutProtection, 0.1)
        assertTrue(withProtection > withoutProtection + 0.5, "$withProtection vs $withoutProtection")
    }

    @Test
    fun `FindHandler sieht jeden Fund und sein Zustand wird uebernommen`() {
        val seen = ArrayList<Find>()
        // Handler wie AP-09 vereinfacht: jeder Fund wird Run-Fund.
        val handler = FindHandler { st, find, _ ->
            seen += find
            st.copy(run = st.run.copy(pieces = st.run.pieces + (find.pieceKey to find.toPiece())))
        }
        val s = state(mapOf(Q to 2000.0), unlocked = 1)
        val context = AdvanceContext(SeededRandom(SEED))
        val result = prep(s, handler = handler).process(s, 2000.0, context)

        val events = context.events.filterIsInstance<GameEvent.FossilFound>().map { it.find }
        assertEquals(events, seen)
        assertEquals(seen.map { it.pieceKey }.toSet(), result.run.pieces.keys)
        assertEquals(seen.size.toDouble(), result.meta.stats.piecesFound.toDouble(), EPS)
        assertTrue(seen.size > 100)
    }

    // --- Reihenfolge ---

    @Test
    fun `tiefste Schicht mit ganzen Bloecken zuerst`() {
        val s = state(mapOf(Q to 5.0, N to 5.0, C to 2.0))
        val context = AdvanceContext(SeededRandom(SEED))
        val result = prep(s).process(s, 4.0, context)
        assertEquals(0.0, blocks(result, C), EPS)
        assertEquals(3.0, blocks(result, N), EPS)
        assertEquals(5.0, blocks(result, Q), EPS)
        val prepared = context.events.filterIsInstance<GameEvent.BlocksPrepared>()
        assertEquals(listOf(C, N), prepared.map { it.layerId })
        assertEquals(listOf(2.0, 2.0), prepared.map { it.blocks.toDouble() })
        context.events.filterIsInstance<GameEvent.FossilFound>().forEach { assertTrue(it.find.layerId in setOf(C, N)) }
    }

    @Test
    fun `Schicht mit nur einem Bruchteil wird uebersprungen`() {
        val s = state(mapOf(Q to 3.0, C to 0.5))
        val result = prep(s).process(s, 1.0, AdvanceContext(SeededRandom(SEED)))
        assertEquals(0.5, blocks(result, C), EPS)
        assertEquals(2.0, blocks(result, Q), EPS)
    }

    // --- Bruchteile ---

    @Test
    fun `vier Tipps legen genau einen Block frei`() {
        var s = state(mapOf(Q to 1.0), unlocked = 1)
        val system = prep(s)
        val rng = SeededRandom(SEED)
        repeat(3) {
            val r = system.tapPrep(s, rng)
            assertTrue(r.events.isEmpty())
            s = r.state
        }
        assertEquals(1.0, blocks(s, Q), EPS)
        assertEquals(0.75, s.run.prepProgress.toDouble(), EPS)

        val last = system.tapPrep(s, rng)
        assertEquals(0.0, blocks(last.state, Q), EPS)
        assertEquals(0.0, last.state.run.prepProgress.toDouble(), EPS)
        assertEquals(1.0, last.events.filterIsInstance<GameEvent.BlocksPrepared>().single().blocks.toDouble(), EPS)
    }

    @Test
    fun `Praeparator mit 0_2 pro Sekunde legt in 5 s bei 0_1-Ticks genau einen Block frei`() {
        var s = state(mapOf(Q to 10.0), unlocked = 1, preparators = 1.0)
        val system = prep(s)
        assertEquals(0.2, system.prepRate(), EPS)
        val rng = SeededRandom(SEED)
        repeat(49) { s = system.advance(s, 0.1, AdvanceContext(rng)) }
        assertEquals(10.0, blocks(s, Q), EPS)
        s = system.advance(s, 0.1, AdvanceContext(rng))
        assertEquals(9.0, blocks(s, Q), EPS)
        assertTrue(s.run.prepProgress.toDouble() < 1e-6)
    }

    @Test
    fun `Kapazitaet ohne Bloecke verfaellt, der Bruchteil bleibt`() {
        val s = state(mapOf(Q to 1.0), unlocked = 1)
        val result = prep(s).process(s, 3.5, AdvanceContext(SeededRandom(SEED)))
        assertEquals(0.0, blocks(result, Q), EPS)
        assertEquals(0.5, result.run.prepProgress.toDouble(), EPS)

        val idle = prep(s).process(state(unlocked = 1), 7.25, AdvanceContext(SeededRandom(SEED)))
        assertEquals(0.25, idle.run.prepProgress.toDouble(), EPS)
    }

    @Test
    fun `prepProgress bleibt immer unter 1`() {
        val rng = SeededRandom(SEED)
        var s = state(mapOf(Q to 3.0), unlocked = 1)
        val system = prep(s)
        repeat(200) {
            s = system.process(s, rng.nextDouble(0.0, 2.0), AdvanceContext(rng))
            val p = s.run.prepProgress.toDouble()
            assertTrue(p >= 0.0 && p < 1.0, "prepProgress $p")
        }
    }

    @Test
    fun `ohne Praeparatoren aendert sich nichts`() {
        val s = state(mapOf(Q to 10.0), unlocked = 1)
        val context = AdvanceContext(SeededRandom(SEED))
        assertSame(s, prep(s).advance(s, 10.0, context))
        assertTrue(context.events.isEmpty())
    }

    // --- Capstones ---

    @Test
    fun `no_damage_chance laesst Funde ohne Schaden`() {
        val s = state(skills = setOf("prep_5"))
        val system = prep(s)
        val rng = SeededRandom(SEED)
        val finds = (1..ROLLS).map { system.rollFind(s.run, cretaceous, rng)!! }
        val undamaged = finds.filter { it.undamaged }
        assertEquals(0.03, undamaged.size.toDouble() / ROLLS, 0.006)
        undamaged.forEach { f -> f.zones.forEach { assertTrue(it >= data.balance.qualityBaseMin) } }
        assertTrue(undamaged.map { it.quality }.average() > finds.filterNot { it.undamaged }.map { it.quality }.average())
    }

    @Test
    fun `no_damage_chance 1 ergibt reine Grundqualitaet`() {
        val s = state()
        val system = prep(s, extra = listOf(mod(StatId.NO_DAMAGE_CHANCE, ModifierOp.ADD, 1.0)))
        val rng = SeededRandom(SEED)
        val finds = (1..1000).map { system.rollFind(s.run, cretaceous, rng)!! }
        assertTrue(finds.all { it.undamaged })
        assertEquals(0.65, finds.flatMap { it.zones }.average(), 0.02)
    }

    @Test
    fun `Fundchance kombiniert find_chance und guaranteed_fossil_chance`() {
        assertEquals(0.08, prep(state()).fossilChance(), EPS)
        // dig_1: find_chance mult +5 % -> 0.084
        assertEquals(0.084, prep(state(skills = setOf("dig_1"))).fossilChance(), EPS)
        // dig_5: 1 - (1 - 0.08) * (1 - 0.02)
        assertEquals(1.0 - 0.92 * 0.98, prep(state(skills = setOf("dig_5"))).fossilChance(), EPS)
        // Ohne normale Fundchance bleibt nur die garantierte Chance.
        val onlyGuaranteed = prep(
            state(skills = setOf("dig_5")),
            extra = listOf(mod(StatId.FIND_CHANCE, ModifierOp.MULT, -1.0)),
        )
        assertEquals(0.02, onlyGuaranteed.fossilChance(), EPS)
    }

    @Test
    fun `garantierte Fossilien wirken im Wurf`() {
        val s = state(mapOf(Q to ROLLS.toDouble()), unlocked = 1, skills = setOf("dig_5"))
        val context = AdvanceContext(SeededRandom(SEED))
        prep(s, extra = listOf(mod(StatId.FIND_CHANCE, ModifierOp.MULT, -1.0))).process(s, ROLLS.toDouble(), context)
        val fossils = context.events.filterIsInstance<GameEvent.FossilFound>().size
        assertEquals(0.02, fossils.toDouble() / ROLLS, 0.005)
    }

    @Test
    fun `ohne Fundchance nur Gestein mit Erloes value mal rockSellFactor`() {
        val s = state(mapOf(C to 100.0))
        val context = AdvanceContext(SeededRandom(SEED))
        val result = prep(s, extra = listOf(mod(StatId.FIND_CHANCE, ModifierOp.MULT, -1.0))).process(s, 100.0, context)
        assertTrue(context.events.none { it is GameEvent.FossilFound })
        assertEquals(100.0 * cretaceous.value * 0.5, result.run.money.toDouble(), EPS)
        assertEquals(0.0, result.meta.stats.piecesFound.toDouble(), EPS)
    }

    // --- Zonen und Stufen ---

    @Test
    fun `Zonenanzahl kommt aus bones json`() {
        val custom = data.copy(
            bones = data.bones.map { if (it.id == "skull") BoneDefinition("skull", listOf("a", "b", "c")) else it },
        )
        val s = state()
        val system = prep(s, gameData = custom)
        val zones = system.rollZones(custom.layer(Q), "skull", undamaged = false, rng = SeededRandom(SEED))
        assertEquals(3, zones.size)
        val find = system.rollFind(s.run, custom.layer(Q), SeededRandom(SEED))!!
        assertEquals(custom.bone(find.pieceKey.boneId).zones.size, find.zones.size)
    }

    @Test
    fun `Fund-Ereignis traegt die Qualitaetsstufe`() {
        val s = state(mapOf(C to 3000.0))
        val context = AdvanceContext(SeededRandom(SEED))
        prep(s).process(s, 3000.0, context)
        val found = context.events.filterIsInstance<GameEvent.FossilFound>()
        assertTrue(found.isNotEmpty())
        val tiers = QualityTiers.fromGameData(data)
        for (event in found) {
            assertEquals(tiers.tierOf(event.find.quality).id, event.tierId)
            assertEquals(PieceKey(event.find.pieceKey.speciesId, event.find.pieceKey.boneId), event.find.pieceKey)
            assertEquals(data.species(event.find.pieceKey.speciesId).layer, event.find.layerId)
            event.find.zones.forEach { assertTrue(it in 0.0..1.0) }
        }
    }

    // --- Unveraenderlichkeit und Determinismus ---

    @Test
    fun `Eingabezustand bleibt unveraendert`() {
        val s = state(mapOf(Q to 50.0, C to 50.0), preparators = 10.0)
        val snapshot = s.copy()
        val system = prep(s)
        val after = system.advance(s, 30.0, AdvanceContext(SeededRandom(SEED)))
        val tapped = system.tapPrep(s, SeededRandom(SEED))
        assertEquals(snapshot, s)
        assertNotSame(s, after)
        assertFalse(after == s)
        assertFalse(tapped.state == s)
    }

    @Test
    fun `gleicher Seed ergibt gleiches Ergebnis`() {
        val s = state(mapOf(Q to 500.0, N to 500.0), preparators = 50.0)
        val a = AdvanceContext(SeededRandom(SEED))
        val b = AdvanceContext(SeededRandom(SEED))
        assertEquals(prep(s).advance(s, 50.0, a), prep(s).advance(s, 50.0, b))
        assertEquals(a.events, b.events)
    }

    // --- Integration in GameEngine ---

    @Test
    fun `GameEngine graebt und praepariert im selben Tick`() {
        val modifiers = ModifierEngine.fromGameData(data)
        val engine = GameEngine(data, modifiers)
        val s = state(unlocked = 1, preparators = 5.0).let { it.copy(run = it.run.copy(teams = mapOf(Q to n(10.0)))) }
        val result = engine.advance(s, 10.0, SeededRandom(SEED))
        // 10 Teams * 0.1 * 10 s = 10 Bloecke, 5 Praeparatoren * 0.2 * 10 s = 10 Bloecke
        assertEquals(0.0, blocks(result.state, Q), EPS)
        assertEquals(1.0, engine.prepRate(), EPS)
        val kinds = result.events.map { it::class }
        assertEquals(GameEvent.BlocksDug::class, kinds.first())
        assertEquals(GameEvent.BlocksPrepared::class, kinds.last())
        assertEquals(10.0, result.events.filterIsInstance<GameEvent.BlocksPrepared>().single().blocks.toDouble(), EPS)
    }

    @Test
    fun `GameEngine tapPrep nutzt die Praeparation aus den Systemen`() {
        val modifiers = ModifierEngine.fromGameData(data)
        var calls = 0
        val handler = FindHandler { st, _, _ -> calls++; st }
        val engine = GameEngine(data, modifiers, listOf(DigSystem(data, modifiers), PrepSystem(data, modifiers, handler)))
        var s = state(mapOf(Q to 400.0), unlocked = 1)
        val rng = SeededRandom(SEED)
        var fossils = 0
        repeat(1600) {
            val r = engine.tapPrep(s, rng)
            fossils += r.events.count { it is GameEvent.FossilFound }
            s = r.state
        }
        assertEquals(0.0, blocks(s, Q), EPS)
        assertTrue(fossils > 0)
        assertEquals(fossils, calls)
    }

    private companion object {
        const val SEED = 20261105L
        const val ROLLS = 10_000
        const val EPS = 1e-9
        const val Q = "quaternary"
        const val N = "neogene"
        const val C = "cretaceous"
    }
}

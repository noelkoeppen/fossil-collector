package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.QueuedPiece
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Tests fuer den Offline-Fortschritt (Kapitel 7.1, AP-14) mit den echten Daten
 * (offlineEfficiency 0.5, offlineCapHours 4, maxDetailedOfflineFinds 2000) bzw. gezielt
 * angepassten Testdaten.
 */
class OfflineProgressTest {

    private val data: GameData = DataRepository().load()

    private fun n(v: Double) = GameNumber(v)

    private fun engine(gameData: GameData = data): GameEngine = GameEngine(gameData, ModifierEngine.fromGameData(gameData))

    private fun withBalance(
        findChance: Double = data.balance.findChance,
        maxDetailed: Double = data.balance.maxDetailedOfflineFinds,
        efficiency: Double = data.balance.offlineEfficiency,
    ): GameData = data.copy(
        balance = data.balance.copy(
            findChance = findChance,
            maxDetailedOfflineFinds = maxDetailed,
            offlineEfficiency = efficiency,
        ),
    )

    /**
     * Zustand mit allen Schichten offen, Teams, Praeparatoren, Hallen und Laborplaetzen;
     * alle Meilensteine erledigt (keine Belohnungen in exakten Vergleichen), `lastSavedAt = 0`.
     */
    private fun state(
        gameData: GameData = data,
        teams: Map<String, Double> = mapOf(Q to 30.0),
        unlocked: Int = 1,
        preparators: Double = 10.0,
        halls: Double = 1.0,
        labSlots: Double = 1.0,
        pieces: Map<PieceKey, Piece> = emptyMap(),
        labQueue: List<QueuedPiece> = emptyList(),
        milestonesDone: Boolean = true,
    ): GameState {
        val modifiers = ModifierEngine.fromGameData(gameData)
        val base = GameState.newGame(gameData.layers.first().id, RunStart.values(gameData, modifiers), now = 0L)
        val open = gameData.layers.take(unlocked).map { it.id }
        val s = base.copy(
            run = base.run.copy(
                unlockedLayers = n(unlocked.toDouble()),
                teams = open.associateWith { GameNumber.ZERO } + teams.mapValues { n(it.value) },
                blocks = open.associateWith { GameNumber.ZERO },
                preparators = n(preparators),
                halls = n(halls),
                labSlots = n(labSlots),
                pieces = pieces,
                labQueue = labQueue,
            ),
        )
        return if (milestonesDone) s.withAllMilestonesDone(gameData) else s
    }

    /** Ein Stueck je Knochen der ersten [speciesCount] Arten jeder offenen Schicht, Qualitaet [q]. */
    private fun museumPieces(unlocked: Int, speciesCount: Int, q: Double): Map<PieceKey, Piece> =
        data.layers.take(unlocked).flatMap { layer ->
            data.speciesByLayer[layer.id].orEmpty().take(speciesCount).flatMap { species ->
                species.bones.map { bone -> PieceKey(species.id, bone) to Piece(List(data.bone(bone).zones.size) { q }) }
            }
        }.toMap()

    private fun hours(h: Double): Long = (h * 3_600_000.0).toLong()

    private fun blocks(state: GameState, layerId: String): Double = (state.run.blocks[layerId] ?: GameNumber.ZERO).toDouble()

    private fun online(engine: GameEngine, start: GameState, seconds: Double, dt: Double, seed: Long): GameState {
        val rng = SeededRandom(seed)
        var s = start
        val steps = Math.round(seconds / dt)
        repeat(steps.toInt()) { s = engine.advance(s, dt, rng).state }
        return s
    }

    // --- Zeitberechnung ---

    @Test
    fun `negative Abwesenheit bringt nichts und setzt lastSavedAt`() {
        val start = state().copy(lastSavedAt = hours(2.0))
        val result = engine().applyOffline(start, now = hours(1.0), rng = SeededRandom(SEED))
        assertEquals(start.copy(lastSavedAt = hours(1.0)), result.state)
        with(result.summary) {
            assertEquals(0.0, elapsedSeconds)
            assertEquals(0.0, effectiveSeconds)
            assertEquals(GameNumber.ZERO, moneyGained)
            assertEquals(GameNumber.ZERO, totalFinds)
            assertTrue(bestFinds.isEmpty())
        }
    }

    @Test
    fun `keine Abwesenheit bringt nichts`() {
        val start = state().copy(lastSavedAt = 5_000L)
        val result = engine().applyOffline(start, now = 5_000L, rng = SeededRandom(SEED))
        assertEquals(start, result.state)
        assertEquals(0.0, result.summary.effectiveSeconds)
    }

    @Test
    fun `Obergrenze von 4 h greift`() {
        val start = state()
        val tenHours = engine().applyOffline(start, now = hours(10.0), rng = SeededRandom(SEED))
        val fourHours = engine().applyOffline(start, now = hours(4.0), rng = SeededRandom(SEED))
        with(tenHours.summary) {
            assertEquals(36_000.0, elapsedSeconds, EPS)
            assertEquals(14_400.0, creditedSeconds, EPS)
            assertEquals(7_200.0, effectiveSeconds, EPS)
        }
        // Gleicher Seed, gleiche effektive Zeit: identischer Spielstand bis auf lastSavedAt.
        assertEquals(fourHours.state.copy(lastSavedAt = 0L), tenHours.state.copy(lastSavedAt = 0L))
        assertEquals(hours(10.0), tenHours.state.lastSavedAt)
    }

    @Test
    fun `Offline nutzt advance in 1-Sekunden-Schritten mit Rest als letztem Schritt`() {
        val gameData = withBalance(efficiency = 1.0, maxDetailed = 1e9)
        val start = state(gameData)
        val offline = engine(gameData).applyOffline(start, now = 2_500L, rng = SeededRandom(SEED))

        val e = engine(gameData)
        val rng = SeededRandom(SEED)
        var s = start
        for (dt in listOf(1.0, 1.0, 0.5)) s = e.advance(s, dt, rng).state
        assertEquals(s.copy(lastSavedAt = 2_500L), offline.state)
        assertEquals(2.5, offline.summary.effectiveSeconds, EPS)
    }

    @Test
    fun `verworfener Schritt bricht die Nachberechnung ab`() {
        var calls = 0
        var errors = 0
        val nanOnThirdStep = AdvanceSystem { state, _, _ ->
            calls++
            val money = if (calls == 3) GameNumber(Double.NaN) else state.run.money + 1.0
            state.copy(run = state.run.copy(money = money))
        }
        val gameData = withBalance(efficiency = 1.0)
        val e = GameEngine(
            gameData,
            ModifierEngine.fromGameData(gameData),
            listOf(nanOnThirdStep),
            NumericErrorHandler { errors++ },
        )
        val start = state(gameData)
        val result = e.applyOffline(start, now = 100_000L, rng = SeededRandom(SEED))
        assertEquals(3, calls)
        assertEquals(1, errors)
        assertEquals(start.run.money + 2.0, result.state.run.money)
        assertEquals(100_000L, result.state.lastSavedAt)
    }

    // --- Offline 1 h entspricht Online 0,5 h ---

    @Test
    fun `Offline 1 h entspricht Online 0,5 h bei deterministischen Systemen`() {
        // Ohne Fossilien (findChance 0) sind Grabung, Praeparation (Gestein), Museum und Labor
        // deterministisch: Vergleich mit dem Online-Takt von 0.1 s.
        val gameData = withBalance(findChance = 0.0)
        val queue = List(20) { QueuedPiece(PieceKey("cave_bear", "skull"), listOf(0.5, 0.5, 0.5, 0.5)) }
        val start = state(
            gameData,
            teams = mapOf(Q to 60.0, N to 10.0),
            unlocked = 2,
            preparators = 40.0,
            pieces = museumPieces(unlocked = 2, speciesCount = 2, q = 0.6),
            labQueue = queue,
            labSlots = 2.0,
        )
        val offline = engine(gameData).applyOffline(start, now = hours(1.0), rng = SeededRandom(SEED))
        val online = online(engine(gameData), start, seconds = 1_800.0, dt = 0.1, seed = SEED)

        assertEquals(1_800.0, offline.summary.effectiveSeconds, EPS)
        val money = online.run.money.toDouble()
        assertTrue(money > 0.0)
        // Bruchteil-Praeparation: hoechstens ein Block Unterschied je Schicht.
        assertEquals(money, offline.state.run.money.toDouble(), 1.0 + money * 1e-9)
        assertEquals(blocks(online, Q), blocks(offline.state, Q), 1.0 + 1e-6)
        assertEquals(blocks(online, N), blocks(offline.state, N), 1.0 + 1e-6)
        assertEquals(online.run.researchPoints.toDouble(), offline.state.run.researchPoints.toDouble(), 1e-6)
        assertEquals(online.run.labQueue.size, offline.state.run.labQueue.size)
        assertEquals(offline.state.run.money - start.run.money, offline.summary.moneyGained)
        assertEquals(offline.state.run.researchPoints - start.run.researchPoints, offline.summary.researchPointsGained)
    }

    @Test
    fun `Offline 1 h ist mit gleichem Seed identisch zu 1800 Online-Schritten von 1 s`() {
        val gameData = withBalance(maxDetailed = 1e9)
        val start = state(gameData, teams = mapOf(Q to 60.0), preparators = 30.0)
        val offline = engine(gameData).applyOffline(start, now = hours(1.0), rng = SeededRandom(SEED))
        val online = online(engine(gameData), start, seconds = 1_800.0, dt = 1.0, seed = SEED)
        assertEquals(online.copy(lastSavedAt = hours(1.0)), offline.state)
        assertEquals(GameNumber.ZERO, offline.summary.aggregatedFinds)
        assertEquals(online.meta.stats.piecesFound - start.meta.stats.piecesFound, offline.summary.detailedFinds)
    }

    @Test
    fun `Offline 1 h entspricht ungefaehr Online 0,5 h mit Fossilien`() {
        val start = state(teams = mapOf(Q to 60.0), preparators = 30.0)
        val offline = engine().applyOffline(start, now = hours(1.0), rng = SeededRandom(SEED))
        val online = online(engine(), start, seconds = 1_800.0, dt = 0.1, seed = SEED + 1)

        // Grabung ist deterministisch; die Praeparation haengt hier vom Zufall ab, weil Buch-Boni
        // (z. B. Riesenhirsch: prep_rate) je nach gefundenen Arten frueher oder spaeter wirken.
        val onlineBlocks = blocks(online, Q)
        assertTrue(abs(blocks(offline.state, Q) - onlineBlocks) / onlineBlocks < 0.05)
        val onlineMoney = online.run.money.toDouble()
        val offlineMoney = offline.state.run.money.toDouble()
        assertTrue(abs(offlineMoney - onlineMoney) / onlineMoney < 0.10, "online $onlineMoney, offline $offlineMoney")
        val onlineFinds = online.meta.stats.piecesFound.toDouble()
        val offlineFinds = offline.summary.totalFinds.toDouble()
        assertTrue(abs(offlineFinds - onlineFinds) / onlineFinds < 0.10, "online $onlineFinds, offline $offlineFinds")
    }

    // --- Grenze fuer einzeln gewuerfelte Funde ---

    @Test
    fun `nach maxDetailedOfflineFinds werden Funde aggregiert`() {
        val gameData = withBalance(maxDetailed = 5.0)
        val start = state(gameData, teams = mapOf(Q to 60.0), preparators = 30.0)
        val result = engine(gameData).applyOffline(start, now = hours(1.0), rng = SeededRandom(SEED))
        with(result.summary) {
            assertEquals(n(5.0), detailedFinds)
            assertTrue(aggregatedFinds.toDouble() > 100.0, "aggregiert: $aggregatedFinds")
            assertEquals(result.state.meta.stats.piecesFound - start.meta.stats.piecesFound, totalFinds)
            assertTrue(bestFinds.size <= 3)
        }
        // Nur die 5 einzeln gewuerfelten Funde landen in Buch und Run-Bestand.
        assertTrue(result.state.meta.book.size <= 5)
        assertTrue(result.state.run.pieces.size <= 5)
    }

    @Test
    fun `aggregierte Funde bringen genau den erwarteten Verkaufserloes`() {
        // Jeder Block ist ein Fossil, kein Fund wird einzeln gewuerfelt: kein Gestein, keine
        // Museumsstuecke, kein Labor. Der Geldzuwachs ist also nur der aggregierte Erloes.
        val gameData = withBalance(findChance = 1.0, maxDetailed = 0.0)
        val start = state(gameData, teams = mapOf(Q to 30.0), preparators = 10.0)
        val e = engine(gameData)
        val result = e.applyOffline(start, now = hours(1.0), rng = SeededRandom(SEED))

        val count = result.summary.aggregatedFinds.toDouble()
        assertTrue(count > 0.0)
        assertEquals(GameNumber.ZERO, result.summary.detailedFinds)
        val layer = gameData.layer(Q)
        val expectedQuality = e.prep.expectedQuality(layer)
        val perFind = FindClassifier(gameData, ModifierEngine.fromGameData(gameData)).sellValue(layer, expectedQuality)
        assertEquals(perFind.toDouble() * count, result.summary.moneyGained.toDouble(), 1e-6 * count)
        assertTrue(result.state.meta.book.isEmpty())
        assertTrue(result.state.run.pieces.isEmpty())
        assertTrue(result.summary.bestFinds.isEmpty())
    }

    @Test
    fun `Erwartungswert der Qualitaet stimmt mit gewuerfelten Zonen ueberein`() {
        val e = engine()
        e.syncModifiers(state())
        val rng = SeededRandom(SEED)
        for (layer in data.layers) {
            var sum = 0.0
            val rolls = 20_000
            repeat(rolls) { sum += e.prep.rollZones(layer, "skull", undamaged = false, rng = rng).average() }
            val expected = e.prep.expectedQuality(layer)
            assertEquals(sum / rolls, expected, 0.005, "Schicht ${layer.id}")
        }
    }

    @Test
    fun `Praeparation aggregiert Funde nur mit erschoepfter Grenze`() {
        val gameData = withBalance(findChance = 1.0)
        val e = engine(gameData)
        val start = state(gameData, preparators = 0.0).let {
            it.copy(run = it.run.copy(blocks = mapOf(Q to n(10.0))))
        }
        e.syncModifiers(start)
        val limit = DetailedFindLimit(3)
        val context = AdvanceContext(SeededRandom(SEED), detailedFinds = limit)
        val after = e.prep.process(start, 10.0, context)
        val events = context.events

        assertEquals(3, events.count { it is GameEvent.FossilFound })
        val aggregated = events.filterIsInstance<GameEvent.FindsAggregated>().single()
        assertEquals(n(7.0), aggregated.count)
        assertEquals(3L, limit.detailed)
        assertEquals(7L, limit.aggregated)
        assertEquals(n(10.0), after.meta.stats.piecesFound)
        assertEquals(n(10.0), events.filterIsInstance<GameEvent.BlocksPrepared>().single().fossils)
    }

    @Test
    fun `ohne Grenze im Kontext wird nie aggregiert`() {
        val gameData = withBalance(findChance = 1.0)
        val e = engine(gameData)
        val start = state(gameData, preparators = 0.0).let { it.copy(run = it.run.copy(blocks = mapOf(Q to n(10.0)))) }
        e.syncModifiers(start)
        val context = AdvanceContext(SeededRandom(SEED))
        e.prep.process(start, 10.0, context)
        assertEquals(10, context.events.count { it is GameEvent.FossilFound })
        assertTrue(context.events.none { it is GameEvent.FindsAggregated })
    }

    // --- Zusammenfassung ---

    @Test
    fun `beste drei Funde sind die besten Buchqualitaeten`() {
        val start = state(teams = mapOf(Q to 60.0), preparators = 30.0)
        val result = engine().applyOffline(start, now = hours(1.0), rng = SeededRandom(SEED))
        val tiers = QualityTiers.fromGameData(data)
        // Buch war leer und alle Funde wurden einzeln gewuerfelt: Das Buch enthaelt den besten Fund je Schluessel.
        val expected = result.state.meta.book.entries
            .sortedWith(compareByDescending<Map.Entry<PieceKey, BookEntry>> { it.value.quality }.thenBy { it.key.toString() })
            .take(3)
            .map { OfflineFind(it.key, it.value.quality, tiers.tierOf(it.value.quality).id) }
        assertEquals(3, result.summary.bestFinds.size)
        assertEquals(expected, result.summary.bestFinds)
    }

    @Test
    fun `Zusammenfassung enthaelt Zeiten, Geld, FP, Funde und Meilensteine`() {
        val start = state(teams = mapOf(Q to 60.0), preparators = 30.0, milestonesDone = false)
        val result = engine().applyOffline(start, now = hours(2.0) + 500L, rng = SeededRandom(SEED))
        with(result.summary) {
            assertEquals(7_200.5, elapsedSeconds, EPS)
            assertEquals(7_200.5, creditedSeconds, EPS)
            assertEquals(3_600.25, effectiveSeconds, EPS)
            assertEquals(result.state.run.money - start.run.money, moneyGained)
            assertEquals(result.state.run.researchPoints - start.run.researchPoints, researchPointsGained)
            assertTrue(moneyGained.toDouble() > 0.0)
            assertTrue(researchPointsGained.toDouble() > 0.0, "Labor laeuft offline weiter")
            assertEquals(result.state.meta.stats.piecesFound - start.meta.stats.piecesFound, totalFinds)
            assertTrue("first_find" in milestonesReached, "Meilensteine: $milestonesReached")
            assertEquals(result.state.meta.milestonesDone - start.meta.milestonesDone, milestonesReached.toSet())
        }
        assertEquals(hours(2.0) + 500L, result.state.lastSavedAt)
    }

    // --- Performance ---

    @Test
    fun `Performance 4 h mit spaetem Durchlauf`() {
        // Spaeter Durchlauf: alle Schichten offen, viele Teams und Praeparatoren, volles Museum,
        // Werkzeugstufen und Skills. Effizienz 1.0, damit wirklich 14 400 Schritte laufen.
        val gameData = withBalance(efficiency = 1.0)
        val base = state(
            gameData,
            teams = mapOf(Q to 220.0, N to 120.0, C to 60.0),
            unlocked = 3,
            preparators = 150.0,
            halls = 8.0,
            labSlots = 3.0,
            pieces = museumPieces(unlocked = 3, speciesCount = 6, q = 0.7),
        )
        val start = base.copy(
            meta = base.meta.copy(
                skills = setOf("dig_1", "dig_2", "prep_1", "prep_2", "lab_1", "curator_1"),
                book = base.run.pieces.mapValues { BookEntry(it.value.quality, reconstructed = false, zones = it.value.zones) },
            ),
            run = base.run.copy(toolLevel = n(8.0)),
        )
        val e = engine(gameData)
        e.applyOffline(start, now = hours(0.1), rng = SeededRandom(SEED)) // Aufwaermen (JIT)

        val begin = System.nanoTime()
        val result = e.applyOffline(start, now = hours(4.0), rng = SeededRandom(SEED))
        val millis = (System.nanoTime() - begin) / 1_000_000.0

        // Kein harter Zeit-Assert (abhaengig von der Maschine); Messwert fuer den PR und Kapitel 7.1.
        println(
            "OfflineProgress 4 h (14 400 Schritte): ${"%.0f".format(millis)} ms, " +
                "Funde ${result.summary.detailedFinds} einzeln + ${result.summary.aggregatedFinds} aggregiert",
        )
        assertEquals(14_400.0, result.summary.effectiveSeconds, EPS)
        assertEquals(n(gameData.balance.maxDetailedOfflineFinds), result.summary.detailedFinds)
        assertTrue(result.summary.aggregatedFinds.toDouble() > 0.0)
    }

    private companion object {
        const val SEED = 1234L
        const val EPS = 1e-9
        const val Q = "quaternary"
        const val N = "neogene"
        const val C = "cretaceous"
    }
}

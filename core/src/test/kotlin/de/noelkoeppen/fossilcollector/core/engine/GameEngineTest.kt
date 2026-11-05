package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LabJob
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests fuer [GameEngine.advance] und die Grabung ([DigSystem]) mit den echten Daten
 * (Kapitel 4.1, 6.4: teamRate 0.1, Schwellen 25/50/100/200, Faktor 2, tapDigAmount 0.25).
 */
class GameEngineTest {

    private val data: GameData = DataRepository().load()
    private val rng = SeededRandom(SEED)

    private fun n(v: Double) = GameNumber(v)

    private fun engine(
        gameData: GameData = data,
        systems: ((GameData, ModifierEngine) -> List<AdvanceSystem>)? = null,
        handler: NumericErrorHandler = NumericErrorHandler.THROW,
    ): GameEngine {
        val modifiers = ModifierEngine.fromGameData(gameData)
        val list = systems?.invoke(gameData, modifiers) ?: GameEngine.defaultSystems(gameData, modifiers)
        return GameEngine(gameData, modifiers, list, handler)
    }

    /**
     * Durchlauf mit [unlocked] offenen Schichten, Teams je Schicht-ID und Skills.
     * Lagereintraege nur fuer offene Schichten (wie nach `unlockNextLayer`).
     */
    private fun state(
        teams: Map<String, Double> = emptyMap(),
        unlocked: Int = 1,
        skills: Set<String> = emptySet(),
    ): GameState {
        val modifiers = ModifierEngine.fromGameData(data)
        val base = GameState.newGame(data.layers.first().id, RunStart.values(data, modifiers), now = 0L)
        val open = data.layers.take(unlocked).map { it.id }
        return base.copy(
            meta = base.meta.copy(skills = skills),
            run = base.run.copy(
                unlockedLayers = n(unlocked.toDouble()),
                teams = open.associateWith { GameNumber.ZERO } + teams.mapValues { n(it.value) },
                blocks = open.associateWith { GameNumber.ZERO },
            ),
        )
    }

    private fun blocks(state: GameState, layerId: String): Double =
        (state.run.blocks[layerId] ?: GameNumber.ZERO).toDouble()

    // --- Produktion ---

    @Test
    fun `10 Teams im Quartaer erzeugen in 10 s 10 Bloecke`() {
        val result = engine().advance(state(mapOf(Q to 10.0)), 10.0, rng)
        assertEquals(10.0, blocks(result.state, Q), EPS)
    }

    @Test
    fun `Produktion in vielen kleinen Ticks entspricht einem grossen Schritt`() {
        val e = engine()
        var s = state(mapOf(Q to 10.0))
        repeat(100) { s = e.advance(s, 0.1, rng).state }
        assertEquals(10.0, blocks(s, Q), 1e-9)
    }

    @Test
    fun `grosses dt (Offline) wird in einem Schritt gerechnet`() {
        val result = engine().advance(state(mapOf(Q to 10.0)), 4 * 3600.0, rng)
        assertEquals(14_400.0, blocks(result.state, Q), 1e-6)
    }

    @Test
    fun `ohne Teams wird nichts erzeugt und der Zustand bleibt dieselbe Instanz`() {
        val s = state()
        val result = engine().advance(s, 5.0, rng)
        assertSame(s, result.state)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `Bloecke kommen zum vorhandenen Lager hinzu`() {
        val s = state(mapOf(Q to 10.0)).let { it.copy(run = it.run.copy(blocks = mapOf(Q to n(2.5)))) }
        assertEquals(3.5, blocks(engine().advance(s, 1.0, rng).state, Q), EPS)
    }

    @Test
    fun `fehlender Lagereintrag einer offenen Schicht zaehlt als 0`() {
        val s = state(mapOf(Q to 10.0)).let { it.copy(run = it.run.copy(blocks = emptyMap())) }
        assertEquals(1.0, blocks(engine().advance(s, 1.0, rng).state, Q), EPS)
    }

    // --- Meilenstein-Verdopplung ---

    @Test
    fun `Meilensteine verdoppeln die Produktion bei 25 50 100 200 Teams`() {
        val e = engine()
        val expected = mapOf(
            24.0 to 24 * 0.1,
            25.0 to 25 * 0.1 * 2,
            49.0 to 49 * 0.1 * 2,
            50.0 to 50 * 0.1 * 4,
            99.0 to 99 * 0.1 * 4,
            100.0 to 100 * 0.1 * 8,
            200.0 to 200 * 0.1 * 16,
        )
        for ((teams, perSec) in expected) {
            val s = state(mapOf(Q to teams))
            assertEquals(perSec, e.blocksPerSecond(s, Q).toDouble(), 1e-9, "Teams $teams")
            assertEquals(perSec * 10, blocks(e.advance(s, 10.0, rng).state, Q), 1e-9, "Teams $teams")
        }
    }

    @Test
    fun `Meilenstein-Zaehlung und Faktor kommen aus balance`() {
        val custom = data.copy(balance = data.balance.copy(teamMilestones = listOf(10.0), teamMilestoneFactor = 3.0))
        val e = engine(custom)
        assertEquals(0, e.dig.reachedMilestones(n(9.0)))
        assertEquals(1, e.dig.reachedMilestones(n(10.0)))
        assertEquals(3.0, e.dig.milestoneMultiplier(n(10.0)), EPS)
        assertEquals(10 * 0.1 * 3, e.blocksPerSecond(state(mapOf(Q to 10.0)), Q).toDouble(), EPS)
    }

    // --- Modifier ---

    @Test
    fun `Skill dig_2 erhoeht die Teamrate um 15 Prozent`() {
        val s = state(mapOf(Q to 10.0), skills = setOf("dig_1", "dig_2"))
        val result = engine().advance(s, 10.0, rng)
        assertEquals(11.5, blocks(result.state, Q), 1e-9)
    }

    @Test
    fun `dig_rate und Meilenstein wirken zusammen`() {
        val s = state(mapOf(Q to 50.0), skills = setOf("dig_1", "dig_2"))
        assertEquals(50 * 0.1 * 4 * 1.15, blocks(engine().advance(s, 1.0, rng).state, Q), 1e-9)
    }

    @Test
    fun `advance gleicht Modifier-Quellen mit dem Zustand ab`() {
        val e = engine()
        e.advance(state(mapOf(Q to 10.0), skills = setOf("dig_1", "dig_2")), 1.0, rng)
        assertEquals(1.15, e.blocksPerSecond(state(mapOf(Q to 10.0)), Q).toDouble(), 1e-9)
        e.advance(state(mapOf(Q to 10.0)), 1.0, rng)
        assertEquals(1.0, e.blocksPerSecond(state(mapOf(Q to 10.0)), Q).toDouble(), 1e-9)
    }

    // --- Mehrere und gesperrte Schichten ---

    @Test
    fun `mehrere freigeschaltete Schichten produzieren unabhaengig`() {
        val s = state(mapOf(Q to 30.0, N to 5.0, C to 1.0), unlocked = 3)
        val result = engine().advance(s, 2.0, rng)
        assertEquals(30 * 0.1 * 2 * 2.0, blocks(result.state, Q), 1e-9)
        assertEquals(5 * 0.1 * 2.0, blocks(result.state, N), 1e-9)
        assertEquals(1 * 0.1 * 2.0, blocks(result.state, C), 1e-9)
    }

    @Test
    fun `gesperrte Schichten produzieren nichts`() {
        val s = state(mapOf(Q to 10.0, N to 10.0), unlocked = 1)
        val e = engine()
        val result = e.advance(s, 10.0, rng)
        assertEquals(10.0, blocks(result.state, Q), EPS)
        assertNull(result.state.run.blocks[N])
        assertEquals(GameNumber.ZERO, e.blocksPerSecond(s, N))
        assertEquals(listOf(GameEvent.BlocksDug(Q, n(10.0))), result.events)
    }

    @Test
    fun `unbekannte Schicht hat Produktion 0`() {
        assertEquals(GameNumber.ZERO, engine().blocksPerSecond(state(mapOf(Q to 10.0)), "unknown"))
    }

    // --- Ereignisse ---

    @Test
    fun `BlocksDug je produzierender Schicht in Schichtreihenfolge`() {
        val s = state(mapOf(Q to 10.0, C to 2.0), unlocked = 3)
        val events = engine().advance(s, 1.0, rng).events
        assertEquals(2, events.size)
        val q = events[0] as GameEvent.BlocksDug
        val c = events[1] as GameEvent.BlocksDug
        assertEquals(Q, q.layerId)
        assertEquals(1.0, q.amount.toDouble(), EPS)
        assertEquals(C, c.layerId)
        assertEquals(0.2, c.amount.toDouble(), EPS)
    }

    // --- Tippen ---

    @Test
    fun `Tippen erzeugt tapDigAmount Bloecke`() {
        val e = engine()
        var s = state()
        repeat(4) { s = e.tapDig(s, Q) }
        assertEquals(4 * 0.25, blocks(s, Q), EPS)
    }

    @Test
    fun `Tippen wird nicht von dig_rate beeinflusst`() {
        val e = engine()
        val s = state(skills = setOf("dig_1", "dig_2"))
        e.syncModifiers(s)
        assertEquals(0.25, blocks(e.tapDig(s, Q), Q), EPS)
    }

    @Test
    fun `Tippen auf freigeschaltete tiefere Schicht`() {
        val s = engine().tapDig(state(unlocked = 2), N)
        assertEquals(0.25, blocks(s, N), EPS)
        assertEquals(0.0, blocks(s, Q), EPS)
    }

    @Test
    fun `Tippen auf gesperrte oder unbekannte Schicht aendert nichts`() {
        val e = engine()
        val s = state()
        assertSame(s, e.tapDig(s, N))
        assertSame(s, e.tapDig(s, "unknown"))
    }

    // --- dt und Unveraenderlichkeit ---

    @Test
    fun `dt kleiner gleich 0 aendert nichts`() {
        val e = engine()
        val s = state(mapOf(Q to 10.0))
        for (dt in listOf(0.0, -1.0, -1e9)) {
            val result = e.advance(s, dt, rng)
            assertSame(s, result.state)
            assertTrue(result.events.isEmpty())
        }
    }

    @Test
    fun `Eingabe-Zustand bleibt unveraendert`() {
        val s = state(mapOf(Q to 30.0, N to 3.0), unlocked = 2, skills = setOf("dig_1", "dig_2"))
        val snapshot = s.copy(run = s.run.copy(), meta = s.meta.copy())
        val e = engine()
        e.advance(s, 10.0, rng)
        e.tapDig(s, Q)
        assertEquals(snapshot, s)
        assertEquals(0.0, blocks(s, Q))
    }

    // --- Erweiterung: Systemreihenfolge ---

    @Test
    fun `Systeme laufen nacheinander und sehen den Zustand des Vorgaengers`() {
        val seen = mutableListOf<Double>()
        val observer = AdvanceSystem { st, _, _ -> seen += blocks(st, Q); st }
        val e = engine(systems = { d, m -> listOf(DigSystem(d, m), observer) })
        e.advance(state(mapOf(Q to 10.0)), 1.0, rng)
        assertEquals(listOf(1.0), seen)
    }

    @Test
    fun `Systeme bekommen dt und das injizierte rng`() {
        var gotDt = 0.0
        var gotValue = 0.0
        val probe = AdvanceSystem { st, dt, ctx -> gotDt = dt; gotValue = ctx.rng.nextDouble(); st }
        engine(systems = { _, _ -> listOf(probe) }).advance(state(), 0.1, SeededRandom(SEED))
        assertEquals(0.1, gotDt)
        assertEquals(SeededRandom(SEED).nextDouble(), gotValue)
    }

    // --- NaN / Infinity (Kapitel 6.6) ---

    @Test
    fun `nicht endliches dt wirft mit Standard-Handler`() {
        val e = engine()
        for (dt in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
            val ex = assertThrows<NonFiniteValueException> { e.advance(state(mapOf(Q to 1.0)), dt, rng) }
            assertEquals("dt", ex.path)
        }
    }

    @Test
    fun `NaN im Zustand wird erkannt`() {
        val s = state(mapOf(Q to 10.0)).let { it.copy(run = it.run.copy(blocks = mapOf(Q to n(Double.NaN)))) }
        val ex = assertThrows<NonFiniteValueException> { engine().advance(s, 0.1, rng) }
        assertEquals("run.blocks[quaternary]", ex.path)
    }

    @Test
    fun `NaN bei den Teams wird erkannt`() {
        val s = state(mapOf(Q to Double.NaN))
        assertThrows<NonFiniteValueException> { engine().advance(s, 0.1, rng) }
    }

    @Test
    fun `Infinity aus einem System wird erkannt`() {
        val broken = AdvanceSystem { st, _, _ -> st.copy(run = st.run.copy(money = n(Double.POSITIVE_INFINITY))) }
        val e = engine(systems = { _, _ -> listOf(broken) })
        val ex = assertThrows<NonFiniteValueException> { e.advance(state(), 0.1, rng) }
        assertEquals("run.money", ex.path)
    }

    @Test
    fun `nicht werfender Handler verwirft den Tick`() {
        val errors = mutableListOf<NonFiniteValueException>()
        val broken = AdvanceSystem { st, _, ctx ->
            ctx.emit(GameEvent.BlocksDug(Q, n(1.0)))
            st.copy(run = st.run.copy(money = n(Double.NaN)))
        }
        val e = engine(systems = { _, _ -> listOf(broken) }, handler = { errors += it })
        val s = state()
        val result = e.advance(s, 0.1, rng)
        assertSame(s, result.state)
        assertTrue(result.events.isEmpty())
        assertEquals(1, errors.size)
        assertEquals("run.money", errors.single().path)
    }

    @Test
    fun `StateValidator findet auch Double-Werte`() {
        val s = state()
        assertNull(StateValidator.findNonFinite(s))
        val bad = s.copy(
            run = s.run.copy(
                labJobs = listOf(
                    LabJob(
                        PieceKey("cave_bear", "skull"),
                        listOf(0.5, Double.NaN),
                        1.0,
                    ),
                ),
            ),
        )
        assertEquals("run.labJobs[0].zones[1]", StateValidator.findNonFinite(bad)?.path)
    }

    private companion object {
        const val SEED = 20261105L
        const val EPS = 1e-12
        const val Q = "quaternary"
        const val N = "neogene"
        const val C = "cretaceous"
    }
}

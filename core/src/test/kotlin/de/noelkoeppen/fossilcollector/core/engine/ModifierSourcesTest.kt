package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.DefinitionIds
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import de.noelkoeppen.fossilcollector.core.modifier.ModifierCategory
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Tests fuer [ModifierSources] und die Standard-Provider. */
class ModifierSourcesTest {

    private val data: GameData = DataRepository().load()

    private fun n(v: Double) = GameNumber(v)

    private fun newState(): GameState {
        val engine = ModifierEngine.fromGameData(data)
        return GameState.newGame(data.layers.first().id, RunStart.values(data, engine), now = 0L)
    }

    private fun GameState.withRun(preparators: Double = 0.0, toolLevel: Double = 0.0) =
        copy(run = run.copy(preparators = n(preparators), toolLevel = n(toolLevel)))

    private fun GameState.withSkills(vararg ids: String) = copy(meta = meta.copy(skills = ids.toSet()))

    @Test
    fun `Startzustand ohne Kaeufe und Skills setzt keine Quellen`() {
        val engine = ModifierEngine.fromGameData(data)
        ModifierSources.sync(engine, newState(), data)
        assertTrue(engine.sourceIds.isEmpty())
    }

    /** Zustand mit vollstaendigem Buch fuer [speciesId] (Qualitaet 0.6) und Praeparatoren. */
    private fun stateWithBook(speciesId: String): GameState {
        val species = data.species(speciesId)
        val book = species.bones.associate { PieceKey(species.id, it) to BookEntry(0.6, reconstructed = false) }
        val s = newState().withRun(preparators = 2.0)
        return s.copy(meta = s.meta.copy(book = book, researchedSpecies = setOf(speciesId)))
    }

    @Test
    fun `BookSources liefert bei unveraendertem Buch dieselbe Instanz`() {
        val state = stateWithBook("cave_bear")
        val first = BookSources.sources(state, data)
        assertSame(first, BookSources.sources(state.copy(run = state.run.copy(money = n(5.0))), data))
        val other = state.copy(meta = state.meta.copy(book = state.meta.book.toMap()))
        assertEquals(first, BookSources.sources(other, data))
    }

    @Test
    fun `Abgleich wird nach fremder Aenderung der Engine trotz gleichem Zustand wiederholt`() {
        val engine = ModifierEngine.fromGameData(data)
        val state = stateWithBook("cave_bear")
        ModifierSources.sync(engine, state, data)
        val expected = engine.sourceIds
        assertTrue("book:cave_bear" in expected)

        engine.clearSources()
        ModifierSources.sync(engine, state, data)
        assertEquals(expected, engine.sourceIds)

        engine.removeSource("book:cave_bear")
        ModifierSources.sync(engine, state, data)
        assertEquals(expected, engine.sourceIds)
    }

    @Test
    fun `Abgleich mit gleichem Zustand fuer eine zweite Engine setzt alle Quellen`() {
        val state = stateWithBook("cave_bear")
        val first = ModifierEngine.fromGameData(data)
        val second = ModifierEngine.fromGameData(data)
        ModifierSources.sync(first, state, data)
        ModifierSources.sync(second, state, data)
        assertEquals(first.sourceIds, second.sourceIds)
        assertEquals(first.factor(StatId.MUSEUM_INCOME), second.factor(StatId.MUSEUM_INCOME), EPS)
    }

    @Test
    fun `Praeparatoren liefern prep_rate 0_2 je Stueck`() {
        val engine = ModifierEngine.fromGameData(data)
        ModifierSources.sync(engine, newState().withRun(preparators = 3.0), data)
        assertNotNull(engine.source("upgrade:" + DefinitionIds.PREPARATOR))
        assertEquals(0.6, engine.additive(StatId.PREP_RATE), EPS)
    }

    @Test
    fun `Werkzeugstufen und Skills addieren damage_reduction`() {
        val engine = ModifierEngine.fromGameData(data)
        ModifierSources.sync(engine, newState().withRun(toolLevel = 2.0).withSkills("prep_1"), data)
        assertEquals(setOf("upgrade:tools", "skill:prep_1"), engine.sourceIds)
        // 2 * 0.03 + 0.02
        assertEquals(0.08, engine.additive(StatId.DAMAGE_REDUCTION), EPS)
    }

    @Test
    fun `Skills liefern Faktor-Stats`() {
        val engine = ModifierEngine.fromGameData(data)
        ModifierSources.sync(engine, newState().withSkills("curator_1", "curator_2", "dig_4"), data)
        assertEquals(0.8, engine.factor(StatId.HALL_COST), EPS)
        assertEquals(0.75, engine.factor(StatId.LAYER_UNLOCK_COST), EPS)
        assertEquals(1.1, engine.factor(StatId.MUSEUM_INCOME), EPS)
    }

    @Test
    fun `Abgleich entfernt weggefallene Quellen`() {
        val engine = ModifierEngine.fromGameData(data)
        ModifierSources.sync(engine, newState().withRun(preparators = 2.0, toolLevel = 4.0).withSkills("prep_1"), data)
        assertEquals(3, engine.sourceIds.size)

        // z. B. nach Prestige mit Respec
        ModifierSources.sync(engine, newState(), data)
        assertTrue(engine.sourceIds.isEmpty())
        assertEquals(0.0, engine.additive(StatId.DAMAGE_REDUCTION))
    }

    @Test
    fun `Abgleich aktualisiert veraenderte Bestaende`() {
        val engine = ModifierEngine.fromGameData(data)
        ModifierSources.sync(engine, newState().withRun(toolLevel = 1.0), data)
        assertEquals(0.03, engine.additive(StatId.DAMAGE_REDUCTION), EPS)
        ModifierSources.sync(engine, newState().withRun(toolLevel = 5.0), data)
        assertEquals(0.15, engine.additive(StatId.DAMAGE_REDUCTION), EPS)
    }

    @Test
    fun `fremde Quellen bleiben unberuehrt`() {
        val engine = ModifierEngine.fromGameData(data)
        val extra = listOf(Modifier(StatId.MUSEUM_INCOME, ModifierOp.MULT, 0.01, ModifierCategory.EVENT))
        engine.setSource("event:test", extra)
        ModifierSources.sync(engine, newState().withSkills("dig_1"), data)
        assertEquals(setOf("event:test", "skill:dig_1"), engine.sourceIds)
        assertEquals(extra, engine.source("event:test"))
    }

    @Test
    fun `unbekannte Skill-IDs werden ignoriert`() {
        val engine = ModifierEngine.fromGameData(data)
        ModifierSources.sync(engine, newState().withSkills("dig_1", "removed_skill"), data)
        assertEquals(setOf("skill:dig_1"), engine.sourceIds)
    }

    @Test
    fun `Abgleich ist idempotent`() {
        val engine = ModifierEngine.fromGameData(data)
        val state = newState().withRun(preparators = 1.0).withSkills("lab_1")
        ModifierSources.sync(engine, state, data)
        val first = engine.sourceIds.associateWith { engine.source(it) }
        ModifierSources.sync(engine, state, data)
        assertEquals(first, engine.sourceIds.associateWith { engine.source(it) })
    }

    @Test
    fun `eigener Provider erweitert den Abgleich`() {
        val extra = object : ModifierSourceProvider {
            override val prefix = "event:"
            override fun sources(state: GameState, data: GameData) =
                mapOf("event:test" to listOf(Modifier(StatId.DIG_RATE, ModifierOp.MULT, 0.5, ModifierCategory.EVENT)))
        }
        val sources = ModifierSources(ModifierSources.DEFAULT_PROVIDERS + extra)
        val engine = ModifierEngine.fromGameData(data)
        sources.sync(engine, newState(), data)
        assertEquals(1.5, engine.factor(StatId.DIG_RATE), EPS)
        assertFalse(engine.sourceIds.any { it.startsWith("skill:") })
    }

    @Test
    fun `ueberlappende Prefixe sind verboten`() {
        val a = object : ModifierSourceProvider {
            override val prefix = "upgrade:x:"
            override fun sources(state: GameState, data: GameData) = emptyMap<String, List<Modifier>>()
        }
        assertThrows<IllegalArgumentException> { ModifierSources(ModifierSources.DEFAULT_PROVIDERS + a) }
    }

    private companion object {
        const val EPS = 1e-9
    }
}

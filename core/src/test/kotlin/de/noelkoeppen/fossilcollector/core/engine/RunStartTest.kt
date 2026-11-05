package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.DefinitionIds
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.MetaState
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/** Tests fuer [RunStart] und die Zuordnung von [DefinitionIds] zu den Daten. */
class RunStartTest {

    private val data: GameData = DataRepository().load()

    private fun n(v: Double) = GameNumber(v)

    private fun engineWithSkills(vararg skills: String): ModifierEngine {
        val engine = ModifierEngine.fromGameData(data)
        val dummy = GameState.newGame(
            data.layers.first().id,
            RunStart.values(data, ModifierEngine.fromGameData(data)),
            now = 0L,
        ).copy(meta = MetaState(skills = skills.toSet()))
        ModifierSources.sync(engine, dummy, data)
        return engine
    }

    @Test
    fun `Startwerte ohne Skills`() {
        val start = RunStart.values(data, engineWithSkills())
        assertEquals(n(0.0), start.money)
        assertEquals(n(0.0), start.researchPoints)
        assertEquals(n(0.0), start.teamsInFirstLayer)
        assertEquals(n(0.0), start.preparators)
        assertEquals(n(1.0), start.halls)
        assertEquals(n(1.0), start.labSlots)
        assertEquals(n(0.0), start.toolLevel)
    }

    @Test
    fun `Start-Skills erhoehen Teams und Laborplaetze`() {
        // dig_3: start_teams +5, lab_3: start_lab_slots +1
        val start = RunStart.values(data, engineWithSkills("dig_1", "dig_2", "dig_3", "lab_1", "lab_2", "lab_3"))
        assertEquals(n(5.0), start.teamsInFirstLayer)
        assertEquals(n(2.0), start.labSlots)
        assertEquals(n(1.0), start.halls)
    }

    @Test
    fun `feste IDs existieren in den Daten`() {
        assertNotNull(data.generatorsById[DefinitionIds.PREPARATOR])
        assertNotNull(data.generatorsById[DefinitionIds.HALL])
        assertNotNull(data.generatorsById[DefinitionIds.LAB_SLOT])
        assertNotNull(data.upgradesById[DefinitionIds.TOOLS])
    }
}

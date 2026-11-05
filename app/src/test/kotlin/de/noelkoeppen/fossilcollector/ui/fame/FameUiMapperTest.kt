package de.noelkoeppen.fossilcollector.ui.fame

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.SkillBranch
import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.PrestigeResult
import de.noelkoeppen.fossilcollector.core.engine.RunStart
import de.noelkoeppen.fossilcollector.core.engine.SkillNodeStatus
import de.noelkoeppen.fossilcollector.core.engine.SkillPurchaseFailure
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests fuer [FameUiMapper] und [FameUiState.node]. */
class FameUiMapperTest {

    private val data: GameData = DataRepository().load()

    private fun state(
        fame: Double = 0.0,
        fameSpent: Double = 0.0,
        skills: Set<String> = emptySet(),
        completeSpecies: String? = null,
    ): GameState {
        val base = GameState.newGame(data.layers.first().id, RunStart.values(data, ModifierEngine.fromGameData(data)), 0L)
        val pieces = completeSpecies?.let { id ->
            data.species(id).bones.associate { PieceKey(id, it) to Piece(listOf(0.8, 0.8, 0.8, 0.8)) }
        } ?: emptyMap()
        return base.copy(
            meta = base.meta.copy(
                fame = GameNumber(fame),
                fameTotal = GameNumber(fame + fameSpent),
                fameSpent = GameNumber(fameSpent),
                skills = skills,
            ),
            run = base.run.copy(pieces = pieces),
        )
    }

    private fun engine(state: GameState): GameEngine =
        GameEngine(data, ModifierEngine.fromGameData(data)).also { it.syncModifiers(state) }

    private fun map(state: GameState, dialog: PrestigeDialogState? = null): FameUiState =
        FameUiMapper.map(engine(state), state, dialog)

    private val bear = "cave_bear"

    @Test
    fun `neues Spiel zeigt 4 Aeste, keine Prestige-Moeglichkeit und keinen Dialog`() {
        val ui = map(state())
        assertEquals(listOf(SkillBranch.DIG, SkillBranch.PREP, SkillBranch.LAB, SkillBranch.CURATOR), ui.branches.map { it.branch })
        assertTrue(ui.branches.all { it.total == 5 && it.ownedCount == 0 })
        assertEquals(GameNumber.ZERO, ui.fameGainPreview)
        assertFalse(ui.canPrestige)
        assertNull(ui.dialog)
    }

    @Test
    fun `Knoten tragen Status, Preis, Kaufbarkeit, Voraussetzungen und Wirkung aus den Daten`() {
        val s = state(fame = 2.0, skills = setOf("dig_1"))
        val ui = map(s)
        val e = engine(s)
        val dig = ui.branches.first { it.branch == SkillBranch.DIG }
        assertEquals(listOf(1, 2, 3, 4, 5), dig.nodes.map { it.depth })
        assertEquals(1, dig.ownedCount)

        val owned = ui.node("dig_1")!!
        assertEquals(SkillNodeStatus.OWNED, owned.status)
        assertNull(owned.price)
        assertEquals(data.skill("dig_1").effects, owned.effects)

        val next = ui.node("dig_2")!!
        assertEquals(SkillNodeStatus.AVAILABLE, next.status)
        assertEquals(e.skillPrice(s, "dig_2"), next.price)
        assertEquals(e.skillNode(s, "dig_2").affordable, next.affordable)

        val locked = ui.node("dig_3")!!
        assertEquals(SkillNodeStatus.LOCKED, locked.status)
        assertEquals(listOf("dig_2"), locked.missingRequirements)
        assertFalse(locked.affordable)

        assertTrue(ui.node("dig_5")!!.capstone)
        assertNull(ui.node("unbekannt"))
    }

    @Test
    fun `Ruhm-Werte und Vorschau kommen aus Spielstand und Engine`() {
        val s = state(fame = 3.0, fameSpent = 2.0, skills = setOf("dig_1"), completeSpecies = bear)
        val ui = map(s)
        assertEquals(GameNumber(3.0), ui.fame)
        assertEquals(GameNumber(5.0), ui.fameTotal)
        assertEquals(engine(s).fameGainPreview(s), ui.fameGainPreview)
        assertTrue(ui.fameGainPreview >= GameNumber.ONE, "vollstaendiges Skelett bringt Ruhm")
        assertTrue(ui.canPrestige)
    }

    @Test
    fun `Dialog Skills behalten zeigt die Prestige-Uebersicht ohne Respec`() {
        val s = state(completeSpecies = bear)
        val ui = map(s, PrestigeDialogState())
        val dialog = checkNotNull(ui.dialog)
        assertEquals(engine(s).prestigeSummary(s), dialog.summary)
        assertEquals(1, dialog.summary.skeletonCount)
        assertNull(dialog.respec)
    }

    @Test
    fun `Respec-Vorschau zeigt Erstattung, gekaufte und abgelehnte Wuensche`() {
        val s = state(fame = 0.0, fameSpent = 3.0, skills = setOf("dig_1", "dig_2"), completeSpecies = bear)
        val selection = listOf("prep_1", "lab_3", "dig_1")
        val ui = map(s, PrestigeDialogState(respec = true, selection = selection))
        val respec = checkNotNull(ui.dialog?.respec)
        val plan = engine(s).respecPreview(s, selection)

        assertEquals(selection, respec.selection)
        assertEquals(GameNumber(3.0), respec.refundedFame)
        assertEquals(ui.dialog!!.summary.fameAfter + GameNumber(3.0), respec.fameAvailable)
        assertEquals(plan.meta.fame, respec.fameLeft)
        assertEquals(plan.bought, respec.bought)
        assertTrue("prep_1" in respec.bought)
        assertEquals(listOf(RejectedSkillUi("lab_3", SkillPurchaseFailure.REQUIREMENTS_MISSING)), respec.rejected)
        assertTrue(respec.isRejected("lab_3"))
        assertFalse(respec.isRejected("prep_1"))

        // Der Baum der Vorschau zeigt die neu gekauften Skills als gekauft, die zurueckgegebenen nicht.
        val nodes = respec.branches.flatMap { it.nodes }.associateBy { it.id }
        assertEquals(SkillNodeStatus.OWNED, nodes.getValue("prep_1").status)
        assertEquals(SkillNodeStatus.OWNED, nodes.getValue("dig_1").status)
        assertTrue(nodes.getValue("dig_2").status != SkillNodeStatus.OWNED)
    }

    @Test
    fun `Prestige mit Respec-Auswahl entspricht der Vorschau`() {
        val s = state(fame = 0.0, fameSpent = 3.0, skills = setOf("dig_1", "dig_2"), completeSpecies = bear)
        val dialog = PrestigeDialogState(respec = true, selection = listOf("prep_1", "prep_2"))
        val respec = map(s, dialog).dialog!!.respec!!
        val e = engine(s)
        val result = e.prestige(s, e.respecStep(dialog.selection)) as PrestigeResult.Success
        assertEquals(respec.fameLeft, result.state.meta.fame)
        assertEquals(respec.bought.toSet(), result.state.meta.skills)
    }
}

package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.SkillBranch
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.save.SaveCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.ceil
import kotlin.math.pow

/**
 * Tests fuer [SkillTree], [GameEngine.buySkill] und den Respec beim Prestige (Kapitel 5.3, 5.4).
 *
 * Preise mit `skillCostGrowth` 1.25 und Aufrunden: Index 0 -> x1, 1 -> x1.25, 2 -> x1.5625,
 * 3 -> x1.953125, 4 -> x2.44140625.
 */
class SkillTreeTest {

    private val data: GameData = DataRepository().load()
    private val growth = data.balance.skillCostGrowth

    private fun n(v: Double) = GameNumber(v)

    private fun engine() = GameEngine(data, ModifierEngine.fromGameData(data))

    private fun state(fame: Double = 0.0, skills: Set<String> = emptySet()): GameState {
        val base = GameState.newGame(data.layers.first().id, RunStart.values(data, ModifierEngine.fromGameData(data)), 0L)
        return base.copy(meta = base.meta.copy(fame = n(fame), fameTotal = n(fame), skills = skills))
            .withAllMilestonesDone(data)
    }

    private fun bought(result: SkillPurchaseResult): SkillPurchaseResult.Success {
        assertTrue(result is SkillPurchaseResult.Success, "erwartet Erfolg, war $result")
        return result as SkillPurchaseResult.Success
    }

    private fun failure(result: SkillPurchaseResult): SkillPurchaseFailure {
        assertTrue(result is SkillPurchaseResult.Failure, "erwartet Fehlschlag, war $result")
        return (result as SkillPurchaseResult.Failure).reason
    }

    /** Kauft [ids] nacheinander; jeder Kauf muss gelingen. */
    private fun buyAll(engine: GameEngine, start: GameState, vararg ids: String): GameState =
        ids.fold(start) { s, id -> bought(engine.buySkill(s, id)).state }

    /** Erwarteter Preis nach Formel 5.4 (mit Aufrunden). */
    private fun expectedPrice(baseCost: Double, owned: Int): Double = ceil(baseCost * growth.pow(owned) - 1e-9)

    private fun skeleton(speciesId: String, q: Double): Map<PieceKey, Piece> =
        data.species(speciesId).bones.associate { PieceKey(speciesId, it) to Piece(listOf(q, q, q, q)) }

    // --- Preise und Kostenwachstum ---

    @Test
    fun `Grundpreise nach Tiefe 1 2 3 5 8`() {
        val tree = engine().skillTree
        val meta = state().meta
        for (branch in listOf("dig", "prep", "lab", "curator")) {
            val prices = (1..5).map { tree.price(meta, "${branch}_$it").toDouble() }
            assertEquals(listOf(1.0, 2.0, 3.0, 5.0, 8.0), prices, branch)
        }
    }

    @Test
    fun `globales Kostenwachstum ueber mehrere Kaeufe und Aeste`() {
        val engine = engine()
        var s = state(fame = 100.0)
        val plan = listOf("dig_1", "prep_1", "lab_1", "dig_2", "curator_1", "prep_2", "dig_3")
        val expected = listOf(1.0, 2.0, 2.0, 4.0, 3.0, 7.0, 12.0)
        plan.forEachIndexed { i, id ->
            assertEquals(expectedPrice(data.skill(id).baseCost, i), expected[i], id)
            assertEquals(n(expected[i]), engine.skillPrice(s, id), id)
            val r = bought(engine.buySkill(s, id))
            assertEquals(n(expected[i]), r.cost)
            assertEquals(id, r.skillId)
            s = r.state
        }
        val total = expected.sum()
        assertEquals(n(100.0 - total), s.meta.fame)
        assertEquals(n(total), s.meta.fameSpent)
        assertEquals(n(100.0), s.meta.fameTotal, "fameTotal aendert sich beim Kauf nicht")
        assertEquals(plan.toSet(), s.meta.skills)
        // Der naechste Kauf, egal in welchem Ast, nutzt Index 7.
        assertEquals(n(expectedPrice(2.0, 7)), engine.skillPrice(s, "lab_2"))
        assertEquals(n(expectedPrice(2.0, 7)), engine.skillPrice(s, "curator_2"))
    }

    @Test
    fun `unbekannte Skills im Spielstand zaehlen nicht zum Kostenwachstum`() {
        val tree = engine().skillTree
        val meta = state(skills = setOf("dig_1", "old_removed_skill")).meta
        assertEquals(1, tree.ownedCount(meta))
        assertEquals(n(expectedPrice(2.0, 1)), tree.price(meta, "dig_2"))
    }

    // --- Fehlgruende ---

    @Test
    fun `Voraussetzungen muessen gekauft sein`() {
        val engine = engine()
        val s = state(fame = 100.0)
        assertEquals(SkillPurchaseFailure.REQUIREMENTS_MISSING, failure(engine.buySkill(s, "dig_2")))
        assertEquals(SkillPurchaseFailure.REQUIREMENTS_MISSING, failure(engine.buySkill(s, "curator_5")))
        val node = engine.skillNode(s, "dig_2")
        assertEquals(SkillNodeStatus.LOCKED, node.status)
        assertEquals(listOf("dig_1"), node.missingRequirements)
        assertFalse(node.affordable)

        val after = buyAll(engine, s, "dig_1")
        assertEquals(SkillNodeStatus.AVAILABLE, engine.skillNode(after, "dig_2").status)
        bought(engine.buySkill(after, "dig_2"))
    }

    @Test
    fun `zu wenig Ruhm`() {
        val engine = engine()
        val none = state(fame = 0.0)
        assertEquals(SkillPurchaseFailure.NOT_ENOUGH_FAME, failure(engine.buySkill(none, "dig_1")))
        assertFalse(engine.skillNode(none, "dig_1").affordable)
        assertEquals(SkillNodeStatus.AVAILABLE, engine.skillNode(none, "dig_1").status)

        // Genau passend: 1 + ceil(1.25) = 3 Ruhm reichen fuer zwei Erste-Tiefe-Knoten.
        val s = buyAll(engine, state(fame = 3.0), "dig_1", "prep_1")
        assertEquals(n(0.0), s.meta.fame)
        assertEquals(SkillPurchaseFailure.NOT_ENOUGH_FAME, failure(engine.buySkill(s, "lab_1")))
    }

    @Test
    fun `doppelter Kauf und unbekannte Skills werden abgelehnt`() {
        val engine = engine()
        val s = buyAll(engine, state(fame = 10.0), "dig_1")
        assertEquals(SkillPurchaseFailure.ALREADY_OWNED, failure(engine.buySkill(s, "dig_1")))
        assertEquals(SkillPurchaseFailure.UNKNOWN_SKILL, failure(engine.buySkill(s, "expedition_1")))
        assertEquals(SkillPurchaseFailure.UNKNOWN_SKILL, failure(engine.buySkill(s, "nope")))
        assertEquals(SkillNodeStatus.OWNED, engine.skillNode(s, "dig_1").status)
        assertNull(engine.skillNode(s, "dig_1").price)
    }

    @Test
    fun `Fehlschlag und Erfolg veraendern den Ausgangszustand nicht`() {
        val engine = engine()
        val s = state(fame = 5.0)
        val copy = s.copy()
        engine.buySkill(s, "dig_2")
        bought(engine.buySkill(s, "dig_1"))
        assertEquals(copy, s)
    }

    // --- Abfragen fuer die UI ---

    @Test
    fun `Uebersicht pro Ast ohne Expedition`() {
        val engine = engine()
        val s = buyAll(engine, state(fame = 4.0), "dig_1", "dig_2")
        val overview = engine.skillOverview(s)
        assertEquals(listOf(SkillBranch.DIG, SkillBranch.PREP, SkillBranch.LAB, SkillBranch.CURATOR), overview.map { it.branch })
        overview.forEach { branch ->
            assertEquals(5, branch.total)
            assertEquals((1..5).toList(), branch.nodes.map { it.definition.depth })
            assertTrue(branch.nodes.last().definition.capstone)
        }
        val dig = overview.first()
        assertEquals(2, dig.ownedCount)
        assertFalse(dig.complete)
        assertEquals(
            listOf(SkillNodeStatus.OWNED, SkillNodeStatus.OWNED, SkillNodeStatus.AVAILABLE, SkillNodeStatus.LOCKED, SkillNodeStatus.LOCKED),
            dig.nodes.map { it.status },
        )
        // Verbleibender Ruhm: 4 - 1 - 3 = 0, dig_3 kostet ceil(3 * 1.5625) = 5.
        assertEquals(n(5.0), dig.nodes[2].price)
        assertFalse(dig.nodes[2].affordable)
        val prep = overview[1]
        assertEquals(SkillNodeStatus.AVAILABLE, prep.nodes[0].status)
        assertEquals(n(2.0), prep.nodes[0].price)
    }

    @Test
    fun `buySkill gleicht die Modifier-Quellen sofort ab`() {
        val engine = engine()
        val s = state(fame = 5.0)
        engine.syncModifiers(s)
        assertEquals(data.balance.findChance, engine.prep.fossilChance(), 1e-12)
        bought(engine.buySkill(s, "dig_1"))
        assertEquals(data.balance.findChance * 1.05, engine.prep.fossilChance(), 1e-12)
    }

    @Test
    fun `voller Baum kostet die Summe aller Preise`() {
        val engine = engine()
        val order = listOf("dig", "prep", "lab", "curator").flatMap { b -> (1..5).map { "${b}_$it" } }
        val expected = order.mapIndexed { i, id -> expectedPrice(data.skill(id).baseCost, i) }.sum()
        val s = buyAll(engine, state(fame = expected), *order.toTypedArray())
        assertEquals(n(0.0), s.meta.fame)
        assertEquals(n(expected), s.meta.fameSpent)
        assertTrue(engine.skillOverview(s).all { it.complete })
    }

    // --- Respec ---

    @Test
    fun `Respec erstattet den gezahlten Ruhm exakt`() {
        val engine = engine()
        val s = buyAll(engine, state(fame = 30.0), "dig_1", "lab_1", "dig_2", "lab_2", "dig_3")
        val spent = 1.0 + 2.0 + 4.0 + 4.0 + 8.0
        assertEquals(n(spent), s.meta.fameSpent)
        val respec = engine.skillTree.respec(s.meta)
        assertEquals(n(spent), respec.refundedFame)
        assertEquals(setOf("dig_1", "lab_1", "dig_2", "lab_2", "dig_3"), respec.refundedSkills)
        assertEquals(n(30.0), respec.meta.fame)
        assertEquals(n(0.0), respec.meta.fameSpent)
        assertTrue(respec.meta.skills.isEmpty())
        assertEquals(s.meta.fameTotal, respec.meta.fameTotal)
    }

    @Test
    fun `Erstattung ist unabhaengig von der Kaufreihenfolge exakt`() {
        val engine = engine()
        // Gleiche Skills, andere Reihenfolge, anderer Gesamtpreis.
        val a = buyAll(engine, state(fame = 50.0), "curator_1", "curator_2", "dig_1", "dig_2")
        val b = buyAll(engine, state(fame = 50.0), "dig_1", "curator_1", "dig_2", "curator_2")
        assertEquals(a.meta.skills, b.meta.skills)
        assertTrue(a.meta.fameSpent != b.meta.fameSpent, "${a.meta.fameSpent} vs ${b.meta.fameSpent}")
        assertEquals(n(50.0), engine.skillTree.respec(a.meta).meta.fame)
        assertEquals(n(50.0), engine.skillTree.respec(b.meta).meta.fame)
    }

    @Test
    fun `Respec mit Neukauf ueberspringt nicht kaufbare Wuensche`() {
        val engine = engine()
        val s = buyAll(engine, state(fame = 6.0), "dig_1", "dig_2") // 1 + 3 = 4, Rest 2
        val plan = engine.skillTree.planRespec(s.meta, listOf("prep_2", "prep_1", "prep_2", "prep_1", "lab_1", "lab_2"))
        // Nach Erstattung 6 Ruhm: prep_2 gesperrt, prep_1 = 1, prep_2 = ceil(2.5) = 3, prep_1 doppelt, lab_1 = 2, lab_2 = ceil(3.90625) = 4 -> zu teuer.
        assertEquals(n(4.0), plan.refundedFame)
        assertEquals(listOf("prep_1", "prep_2", "lab_1"), plan.bought)
        assertEquals(
            listOf(
                "prep_2" to SkillPurchaseFailure.REQUIREMENTS_MISSING,
                "prep_1" to SkillPurchaseFailure.ALREADY_OWNED,
                "lab_2" to SkillPurchaseFailure.NOT_ENOUGH_FAME,
            ),
            plan.rejected,
        )
        assertEquals(n(0.0), plan.meta.fame)
        assertEquals(n(6.0), plan.meta.fameSpent)
    }

    // --- Prestige mit Respec ---

    /** Spielstand mit zwei vollstaendigen Skeletten (Ruhm-Gewinn 2) und gekauften Start-Skills. */
    private fun prestigeReady(engine: GameEngine): GameState {
        val bought = buyAll(engine, state(fame = 20.0), "dig_1", "dig_2", "dig_3", "lab_1")
        return bought.copy(run = bought.run.copy(pieces = skeleton("cave_bear", 0.7) + skeleton("giant_deer", 0.7)))
    }

    private fun prestiged(result: PrestigeResult): GameState {
        assertTrue(result is PrestigeResult.Success, "erwartet Erfolg, war $result")
        return (result as PrestigeResult.Success).state
    }

    @Test
    fun `Prestige ohne Respec behaelt Skills und Start-Skills wirken`() {
        val engine = engine()
        val s = prestigeReady(engine)
        val spent = s.meta.fameSpent
        val after = prestiged(engine.prestige(s))
        assertEquals(s.meta.skills, after.meta.skills)
        assertEquals(spent, after.meta.fameSpent)
        assertEquals(s.meta.fame + 2.0, after.meta.fame)
        assertEquals(n(5.0), after.run.teams.getValue(data.layers.first().id))
    }

    @Test
    fun `Prestige mit Respec gibt alle Skills zurueck und erstattet 100 Prozent`() {
        val engine = engine()
        val s = prestigeReady(engine)
        // dig_1 1, dig_2 ceil(2.5) 3, dig_3 ceil(4.6875) 5, lab_1 ceil(1.953125) 2
        assertEquals(n(11.0), s.meta.fameSpent)
        assertEquals(n(11.0), engine.prestigeSummary(s).respecRefund)
        val after = prestiged(engine.prestige(s, engine.respecStep()))
        assertTrue(after.meta.skills.isEmpty())
        assertEquals(n(0.0), after.meta.fameSpent)
        assertEquals(n(20.0 + 2.0), after.meta.fame)
        assertEquals(n(22.0), after.meta.fameTotal)
        // dig_3 zurueckgegeben -> keine Start-Teams, Quellen ohne Skills.
        assertEquals(n(0.0), after.run.teams.getValue(data.layers.first().id))
        engine.syncModifiers(after)
        assertEquals(data.balance.findChance, engine.prep.fossilChance(), 1e-12)
    }

    @Test
    fun `Prestige mit Respec und Neuverteilung`() {
        val engine = engine()
        val s = prestigeReady(engine)
        val rebuy = listOf("lab_1", "lab_2", "lab_3", "dig_1")
        val preview = engine.respecPreview(s, rebuy)
        val after = prestiged(engine.prestige(s, engine.respecStep(rebuy)))
        assertEquals(preview.meta.skills, after.meta.skills)
        assertEquals(preview.meta.fame, after.meta.fame)
        assertEquals(rebuy, preview.bought)
        // lab_1 1, lab_2 ceil(2.5) 3, lab_3 ceil(4.6875) 5, dig_1 ceil(1.953125) 2 = 11 von 22
        assertEquals(n(11.0), after.meta.fameSpent)
        assertEquals(n(11.0), after.meta.fame)
        // Neu gekaufter Start-Skill lab_3 wirkt sofort im neuen Durchlauf, dig_3 nicht mehr.
        val fresh = prestiged(engine.prestige(prestigeReady(engine), engine.respecStep()))
        assertEquals(fresh.run.labSlots + 1.0, after.run.labSlots)
        assertEquals(n(0.0), after.run.teams.getValue(data.layers.first().id))
    }

    @Test
    fun `Respec ohne Ruhm-Gewinn ist nicht moeglich`() {
        val engine = engine()
        val s = buyAll(engine, state(fame = 5.0), "dig_1")
        val result = engine.prestige(s, engine.respecStep())
        assertEquals(PrestigeResult.Failure(PrestigeFailure.NOT_ENOUGH_FAME), result)
    }

    // --- Spielstand ---

    @Test
    fun `fameSpent wird gespeichert und fehlt in alten Spielstaenden ohne Migration`() {
        val engine = engine()
        val s = buyAll(engine, state(fame = 5.0), "dig_1", "prep_1")
        val codec = SaveCodec.DEFAULT
        val text = codec.encode(s)
        assertEquals(s, codec.decode(text))

        val root = Json.parseToJsonElement(text).jsonObject
        val meta = root.getValue("meta").jsonObject
        val legacy = JsonObject(root + ("meta" to JsonObject(meta - "fameSpent")))
        val loaded = codec.decode(legacy.toString())
        assertEquals(n(0.0), loaded.meta.fameSpent)
        assertEquals(s.meta.skills, loaded.meta.skills)
    }
}

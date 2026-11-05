package de.noelkoeppen.fossilcollector.core.modifier

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.SkillBranch
import de.noelkoeppen.fossilcollector.core.data.StatLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Tests fuer [ModifierEngine] (GAME_DESIGN.md, Kapitel 6.1, 6.2 und 6.6). */
class ModifierEngineTest {

    private fun add(stat: StatId, value: Double, category: ModifierCategory = ModifierCategory.UPGRADE) =
        Modifier(stat, ModifierOp.ADD, value, category)

    private fun mult(stat: StatId, value: Double, category: ModifierCategory) =
        Modifier(stat, ModifierOp.MULT, value, category)

    // --- Revision (AP-14) ---

    @Test
    fun `revision steigt bei jeder Aenderung der Quellen`() {
        val engine = ModifierEngine()
        assertEquals(0L, engine.revision)
        engine.setSource("a", listOf(add(StatId.DIG_RATE, 1.0)))
        assertEquals(1L, engine.revision)
        assertFalse(engine.removeSource("unbekannt"))
        assertEquals(1L, engine.revision)
        assertTrue(engine.removeSource("a"))
        assertEquals(2L, engine.revision)
        engine.clearSources()
        assertEquals(2L, engine.revision, "leeres clearSources aendert nichts")
        engine.setSource("b", emptyList())
        engine.clearSources()
        assertEquals(4L, engine.revision)
    }

    // --- Formel ---

    @Test
    fun `ohne Modifier bleibt der Basiswert`() {
        val engine = ModifierEngine()
        assertEquals(30.0, engine.value(StatId.DIG_RATE, 30.0))
        assertEquals(1.0, engine.factor(StatId.MUSEUM_INCOME))
        assertEquals(0.0, engine.additive(StatId.DAMAGE_REDUCTION))
    }

    @Test
    fun `mult innerhalb einer Kategorie wird addiert`() {
        val engine = ModifierEngine()
        engine.setSource("a", listOf(mult(StatId.MUSEUM_INCOME, 0.10, ModifierCategory.SKILL)))
        engine.setSource("b", listOf(mult(StatId.MUSEUM_INCOME, 0.20, ModifierCategory.SKILL)))
        // 1 * (1 + 0.10 + 0.20), nicht 1.1 * 1.2
        assertEquals(1.30, engine.factor(StatId.MUSEUM_INCOME), EPS)
    }

    @Test
    fun `add-Modifier werden auf den Basiswert addiert`() {
        val engine = ModifierEngine()
        engine.setSource("a", listOf(add(StatId.DAMAGE_REDUCTION, 0.03), add(StatId.DAMAGE_REDUCTION, 0.03)))
        engine.setSource("b", listOf(add(StatId.DAMAGE_REDUCTION, 0.01, ModifierCategory.BOOK)))
        assertEquals(0.07, engine.additive(StatId.DAMAGE_REDUCTION), EPS)
        assertEquals(0.57, engine.value(StatId.DAMAGE_REDUCTION, 0.5), EPS)
    }

    @Test
    fun `mult zwischen Kategorien wird multipliziert`() {
        val engine = ModifierEngine()
        engine.setSource(
            "x",
            listOf(
                mult(StatId.MUSEUM_INCOME, 0.10, ModifierCategory.SKILL),
                mult(StatId.MUSEUM_INCOME, 0.50, ModifierCategory.UPGRADE),
                mult(StatId.MUSEUM_INCOME, 0.01, ModifierCategory.BOOK),
                mult(StatId.MUSEUM_INCOME, 1.00, ModifierCategory.MILESTONE),
            ),
        )
        assertEquals(1.1 * 1.5 * 1.01 * 2.0, engine.factor(StatId.MUSEUM_INCOME), EPS)
    }

    @Test
    fun `add und mult kombiniert`() {
        val engine = ModifierEngine()
        engine.setSource(
            "x",
            listOf(
                add(StatId.SKELETON_BONUS, 0.5, ModifierCategory.SKILL),
                add(StatId.SKELETON_BONUS, 0.02, ModifierCategory.BOOK),
                mult(StatId.SKELETON_BONUS, 0.10, ModifierCategory.SKILL),
                mult(StatId.SKELETON_BONUS, 0.20, ModifierCategory.SKILL),
                mult(StatId.SKELETON_BONUS, 0.50, ModifierCategory.UPGRADE),
            ),
        )
        // (2.0 + 0.52) * (1 + 0.3) * (1 + 0.5)
        assertEquals(2.52 * 1.3 * 1.5, engine.value(StatId.SKELETON_BONUS, 2.0), EPS)
    }

    @Test
    fun `Modifier anderer Stats wirken nicht`() {
        val engine = ModifierEngine()
        engine.setSource("x", listOf(mult(StatId.DIG_RATE, 1.0, ModifierCategory.SKILL), add(StatId.PREP_RATE, 5.0)))
        assertEquals(1.0, engine.factor(StatId.MUSEUM_INCOME))
        assertEquals(listOf(add(StatId.PREP_RATE, 5.0)), engine.modifiers(StatId.PREP_RATE))
    }

    @Test
    fun `negative mult-Werte wirken als Rabatt`() {
        val engine = ModifierEngine()
        engine.setSource("a", listOf(mult(StatId.HALL_COST, -0.10, ModifierCategory.SKILL)))
        engine.setSource("b", listOf(mult(StatId.HALL_COST, -0.20, ModifierCategory.SKILL)))
        engine.setSource("c", listOf(mult(StatId.HALL_COST, -0.50, ModifierCategory.UPGRADE)))
        // (1 - 0.30) * (1 - 0.50)
        assertEquals(0.35, engine.factor(StatId.HALL_COST), EPS)
    }

    @Test
    fun `reine Berechnung entspricht der Engine`() {
        val mods = listOf(add(StatId.DIG_RATE, 1.0), mult(StatId.DIG_RATE, 0.5, ModifierCategory.SKILL))
        assertEquals(4.5, ModifierEngine.compute(StatId.DIG_RATE, 2.0, mods, null), EPS)
        assertEquals(4.0, ModifierEngine.compute(StatId.DIG_RATE, 2.0, mods, StatLimit(max = 4.0)), EPS)
    }

    // --- Clamps ---

    @Test
    fun `Clamp min begrenzt den Endwert nach unten`() {
        val engine = ModifierEngine(mapOf(StatId.LAB_SPEED to StatLimit(min = 0.1)))
        engine.setSource("a", listOf(mult(StatId.LAB_SPEED, -0.6, ModifierCategory.SKILL)))
        engine.setSource("b", listOf(mult(StatId.LAB_SPEED, -0.9, ModifierCategory.UPGRADE)))
        // 0.4 * 0.1 = 0.04 -> 0.1
        assertEquals(0.1, engine.factor(StatId.LAB_SPEED), EPS)
    }

    @Test
    fun `Clamp min greift auch bei negativem Zwischenergebnis`() {
        val engine = ModifierEngine(mapOf(StatId.LAB_SPEED to StatLimit(min = 0.1)))
        engine.setSource("a", listOf(mult(StatId.LAB_SPEED, -1.5, ModifierCategory.SKILL)))
        assertEquals(0.1, engine.factor(StatId.LAB_SPEED), EPS)
    }

    @Test
    fun `Clamp max begrenzt den Endwert nach oben`() {
        val engine = ModifierEngine(mapOf(StatId.FIND_CHANCE to StatLimit(min = 0.0, max = 1.0)))
        engine.setSource("a", listOf(mult(StatId.FIND_CHANCE, 20.0, ModifierCategory.SKILL)))
        assertEquals(1.0, engine.value(StatId.FIND_CHANCE, 0.08), EPS)
        engine.setSource("a", listOf(add(StatId.FIND_CHANCE, -1.0)))
        assertEquals(0.0, engine.value(StatId.FIND_CHANCE, 0.08), EPS)
    }

    @Test
    fun `Clamp innerhalb der Grenzen aendert nichts`() {
        val engine = ModifierEngine(mapOf(StatId.FIND_CHANCE to StatLimit(min = 0.0, max = 1.0)))
        engine.setSource("a", listOf(mult(StatId.FIND_CHANCE, 0.5, ModifierCategory.SKILL)))
        assertEquals(0.12, engine.value(StatId.FIND_CHANCE, 0.08), EPS)
    }

    // --- Quellen und Cache ---

    @Test
    fun `Cache wird bei setSource, removeSource und clearSources invalidiert`() {
        val engine = ModifierEngine()
        assertEquals(1.0, engine.factor(StatId.SELL_PRICE))

        engine.setSource("a", listOf(mult(StatId.SELL_PRICE, 0.5, ModifierCategory.SKILL)))
        assertEquals(1.5, engine.factor(StatId.SELL_PRICE), EPS)

        engine.setSource("a", listOf(mult(StatId.SELL_PRICE, 0.25, ModifierCategory.SKILL)))
        assertEquals(1.25, engine.factor(StatId.SELL_PRICE), EPS)

        engine.setSource("b", listOf(mult(StatId.SELL_PRICE, 1.0, ModifierCategory.MILESTONE)))
        assertEquals(2.5, engine.factor(StatId.SELL_PRICE), EPS)

        assertTrue(engine.removeSource("a"))
        assertEquals(2.0, engine.factor(StatId.SELL_PRICE), EPS)

        engine.clearSources()
        assertEquals(1.0, engine.factor(StatId.SELL_PRICE), EPS)
    }

    @Test
    fun `Cache unterscheidet Basiswerte`() {
        val engine = ModifierEngine()
        engine.setSource("a", listOf(mult(StatId.DIG_RATE, 1.0, ModifierCategory.SKILL)))
        assertEquals(2.0, engine.value(StatId.DIG_RATE, 1.0), EPS)
        assertEquals(6.0, engine.value(StatId.DIG_RATE, 3.0), EPS)
        assertEquals(2.0, engine.value(StatId.DIG_RATE, 1.0), EPS)
    }

    @Test
    fun `Aenderung der uebergebenen Liste wirkt nicht auf die Quelle`() {
        val engine = ModifierEngine()
        val list = mutableListOf(mult(StatId.DIG_RATE, 1.0, ModifierCategory.SKILL))
        engine.setSource("a", list)
        assertEquals(2.0, engine.factor(StatId.DIG_RATE), EPS)
        list += mult(StatId.DIG_RATE, 1.0, ModifierCategory.SKILL)
        assertEquals(2.0, engine.factor(StatId.DIG_RATE), EPS)
    }

    @Test
    fun `Quellen verwalten`() {
        val engine = ModifierEngine()
        engine.setSource("skill:lab_1", listOf(mult(StatId.LAB_SPEED, -0.1, ModifierCategory.SKILL)))
        engine.setSource("upgrade:tool", emptyList())
        assertEquals(setOf("skill:lab_1", "upgrade:tool"), engine.sourceIds)
        assertEquals(emptyList<Modifier>(), engine.source("upgrade:tool"))
        assertFalse(engine.removeSource("gibt_es_nicht"))
        assertTrue(engine.removeSource("upgrade:tool"))
        assertNull(engine.source("upgrade:tool"))
        assertEquals(setOf("skill:lab_1"), engine.sourceIds)
    }

    // --- NaN / Infinity ---

    @Test
    fun `NaN im Ergebnis wirft IllegalStateException`() {
        val engine = ModifierEngine()
        engine.setSource("a", listOf(add(StatId.DIG_RATE, Double.NaN)))
        assertThrows<IllegalStateException> { engine.value(StatId.DIG_RATE, 1.0) }
        assertThrows<IllegalStateException> { ModifierEngine().value(StatId.DIG_RATE, Double.NaN) }
    }

    @Test
    fun `Infinity im Ergebnis wirft IllegalStateException, auch mit Clamp`() {
        val engine = ModifierEngine(mapOf(StatId.FIND_CHANCE to StatLimit(min = 0.0, max = 1.0)))
        engine.setSource("a", listOf(mult(StatId.FIND_CHANCE, Double.MAX_VALUE, ModifierCategory.SKILL)))
        engine.setSource("b", listOf(mult(StatId.FIND_CHANCE, Double.MAX_VALUE, ModifierCategory.UPGRADE)))
        assertThrows<IllegalStateException> { engine.value(StatId.FIND_CHANCE, 1.0) }
        assertThrows<IllegalStateException> { ModifierEngine().value(StatId.DIG_RATE, Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `nach einem Fehler rechnet die Engine mit korrigierten Quellen weiter`() {
        val engine = ModifierEngine()
        engine.setSource("a", listOf(add(StatId.DIG_RATE, Double.NaN)))
        assertThrows<IllegalStateException> { engine.factor(StatId.DIG_RATE) }
        engine.setSource("a", listOf(add(StatId.DIG_RATE, 1.0)))
        assertEquals(2.0, engine.factor(StatId.DIG_RATE), EPS)
    }

    // --- Echte Daten ---

    @Test
    fun `alle Skills des Astes lab mit echten Daten`() {
        val data: GameData = DataRepository().load()
        val engine = ModifierEngine.fromGameData(data)
        data.skills.filter { it.branch == SkillBranch.LAB }.forEach { engine.setSource("skill:${it.id}", it.effects) }

        assertEquals(1.0 - 0.10 - 0.25, engine.factor(StatId.LAB_SPEED), EPS)
        assertEquals(
            data.balance.labDuration * 0.65,
            data.balance.labDuration * engine.factor(StatId.LAB_SPEED),
            EPS,
        )
        assertEquals(1.30, engine.factor(StatId.RESEARCH_POINTS), EPS)
        assertEquals(1.0, engine.additive(StatId.START_LAB_SLOTS), EPS)
        assertEquals(1.0, engine.additive(StatId.RECONSTRUCTION_MISSING_ZONES), EPS)
    }

    @Test
    fun `Clamps aus balance statLimits werden uebernommen`() {
        val data: GameData = DataRepository().load()
        val engine = ModifierEngine.fromGameData(data)
        val labMin = checkNotNull(data.balance.statLimits[StatId.LAB_SPEED]?.min)
        engine.setSource("test", listOf(mult(StatId.LAB_SPEED, -5.0, ModifierCategory.UPGRADE)))
        assertEquals(labMin, engine.factor(StatId.LAB_SPEED), EPS)
    }

    @Test
    fun `researchedBonus aus den Daten wirkt in der Kategorie book`() {
        val data: GameData = DataRepository().load()
        val engine = ModifierEngine.fromGameData(data)
        val bonus = data.balance.researchedBonus
        engine.setSource("book:a", listOf(bonus))
        engine.setSource("book:b", listOf(bonus))
        assertEquals(1.0 + 2 * bonus.value, engine.factor(bonus.stat), EPS)
    }

    private companion object {
        const val EPS = 1e-9
    }
}

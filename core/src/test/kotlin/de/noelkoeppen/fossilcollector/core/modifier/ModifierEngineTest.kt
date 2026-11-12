package de.noelkoeppen.fossilcollector.core.modifier

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.StatLimit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Tests fuer [ModifierEngine]. */
class ModifierEngineTest {

    private fun add(stat: StatId, value: Double, category: ModifierCategory = ModifierCategory.UPGRADE) =
        Modifier(stat, ModifierOp.ADD, value, category)

    private fun mult(stat: StatId, value: Double, category: ModifierCategory) =
        Modifier(stat, ModifierOp.MULT, value, category)

    // --- Revision ---

    @Test
    fun `revision steigt bei jeder Aenderung der Quellen`() {
        val engine = ModifierEngine()
        assertEquals(0L, engine.revision)
        engine.setSource("a", listOf(add(StatId.SELL_PRICE, 1.0)))
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
        assertEquals(30.0, engine.value(StatId.SELL_PRICE, 30.0))
        assertEquals(1.0, engine.factor(StatId.SELL_PRICE))
        assertEquals(0.0, engine.additive(StatId.DAMAGE_REDUCTION))
    }

    @Test
    fun `mult innerhalb einer Kategorie wird addiert`() {
        val engine = ModifierEngine()
        engine.setSource("a", listOf(mult(StatId.SELL_PRICE, 0.10, ModifierCategory.UPGRADE)))
        engine.setSource("b", listOf(mult(StatId.SELL_PRICE, 0.20, ModifierCategory.UPGRADE)))
        // 1 * (1 + 0.10 + 0.20), nicht 1.1 * 1.2
        assertEquals(1.30, engine.factor(StatId.SELL_PRICE), EPS)
    }

    @Test
    fun `add-Modifier werden auf den Basiswert addiert`() {
        val engine = ModifierEngine()
        engine.setSource("a", listOf(add(StatId.DAMAGE_REDUCTION, 0.03), add(StatId.DAMAGE_REDUCTION, 0.03)))
        engine.setSource("b", listOf(add(StatId.DAMAGE_REDUCTION, 0.01, ModifierCategory.EVENT)))
        assertEquals(0.07, engine.additive(StatId.DAMAGE_REDUCTION), EPS)
        assertEquals(0.57, engine.value(StatId.DAMAGE_REDUCTION, 0.5), EPS)
    }

    @Test
    fun `mult zwischen Kategorien wird multipliziert`() {
        val engine = ModifierEngine()
        engine.setSource(
            "x",
            listOf(
                mult(StatId.SELL_PRICE, 0.10, ModifierCategory.UPGRADE),
                mult(StatId.SELL_PRICE, 0.40, ModifierCategory.UPGRADE),
                mult(StatId.SELL_PRICE, 1.00, ModifierCategory.EVENT),
            ),
        )
        assertEquals(1.5 * 2.0, engine.factor(StatId.SELL_PRICE), EPS)
    }

    @Test
    fun `add und mult kombiniert`() {
        val engine = ModifierEngine()
        engine.setSource(
            "x",
            listOf(
                add(StatId.SELL_PRICE, 0.5, ModifierCategory.UPGRADE),
                add(StatId.SELL_PRICE, 0.02, ModifierCategory.EVENT),
                mult(StatId.SELL_PRICE, 0.10, ModifierCategory.UPGRADE),
                mult(StatId.SELL_PRICE, 0.20, ModifierCategory.UPGRADE),
                mult(StatId.SELL_PRICE, 0.50, ModifierCategory.EVENT),
            ),
        )
        // (2.0 + 0.52) * (1 + 0.3) * (1 + 0.5)
        assertEquals(2.52 * 1.3 * 1.5, engine.value(StatId.SELL_PRICE, 2.0), EPS)
    }

    @Test
    fun `Modifier anderer Stats wirken nicht`() {
        val engine = ModifierEngine()
        engine.setSource("x", listOf(mult(StatId.QUALITY_BASE_MIN, 1.0, ModifierCategory.UPGRADE), add(StatId.DAMAGE_REDUCTION, 5.0)))
        assertEquals(1.0, engine.factor(StatId.SELL_PRICE))
        assertEquals(listOf(add(StatId.DAMAGE_REDUCTION, 5.0)), engine.modifiers(StatId.DAMAGE_REDUCTION))
    }

    @Test
    fun `negative mult-Werte wirken als Rabatt`() {
        val engine = ModifierEngine()
        engine.setSource("a", listOf(mult(StatId.SELL_PRICE, -0.10, ModifierCategory.UPGRADE)))
        engine.setSource("b", listOf(mult(StatId.SELL_PRICE, -0.20, ModifierCategory.UPGRADE)))
        engine.setSource("c", listOf(mult(StatId.SELL_PRICE, -0.50, ModifierCategory.EVENT)))
        // (1 - 0.30) * (1 - 0.50)
        assertEquals(0.35, engine.factor(StatId.SELL_PRICE), EPS)
    }

    @Test
    fun `reine Berechnung entspricht der Engine`() {
        val mods = listOf(add(StatId.SELL_PRICE, 1.0), mult(StatId.SELL_PRICE, 0.5, ModifierCategory.UPGRADE))
        assertEquals(4.5, ModifierEngine.compute(StatId.SELL_PRICE, 2.0, mods, null), EPS)
        assertEquals(4.0, ModifierEngine.compute(StatId.SELL_PRICE, 2.0, mods, StatLimit(max = 4.0)), EPS)
    }

    // --- Clamps ---

    @Test
    fun `Clamp min begrenzt den Endwert nach unten`() {
        val engine = ModifierEngine(mapOf(StatId.SELL_PRICE to StatLimit(min = 0.1)))
        engine.setSource("a", listOf(mult(StatId.SELL_PRICE, -0.6, ModifierCategory.UPGRADE)))
        engine.setSource("b", listOf(mult(StatId.SELL_PRICE, -0.9, ModifierCategory.EVENT)))
        // 0.4 * 0.1 = 0.04 -> 0.1
        assertEquals(0.1, engine.factor(StatId.SELL_PRICE), EPS)
    }

    @Test
    fun `Clamp min greift auch bei negativem Zwischenergebnis`() {
        val engine = ModifierEngine(mapOf(StatId.SELL_PRICE to StatLimit(min = 0.1)))
        engine.setSource("a", listOf(mult(StatId.SELL_PRICE, -1.5, ModifierCategory.UPGRADE)))
        assertEquals(0.1, engine.factor(StatId.SELL_PRICE), EPS)
    }

    @Test
    fun `Clamp max begrenzt den Endwert nach oben`() {
        val engine = ModifierEngine(mapOf(StatId.QUALITY_BASE_MIN to StatLimit(min = 0.0, max = 1.0)))
        engine.setSource("a", listOf(mult(StatId.QUALITY_BASE_MIN, 20.0, ModifierCategory.UPGRADE)))
        assertEquals(1.0, engine.value(StatId.QUALITY_BASE_MIN, 0.08), EPS)
        engine.setSource("a", listOf(add(StatId.QUALITY_BASE_MIN, -1.0)))
        assertEquals(0.0, engine.value(StatId.QUALITY_BASE_MIN, 0.08), EPS)
    }

    @Test
    fun `Clamp innerhalb der Grenzen aendert nichts`() {
        val engine = ModifierEngine(mapOf(StatId.QUALITY_BASE_MIN to StatLimit(min = 0.0, max = 1.0)))
        engine.setSource("a", listOf(mult(StatId.QUALITY_BASE_MIN, 0.5, ModifierCategory.UPGRADE)))
        assertEquals(0.12, engine.value(StatId.QUALITY_BASE_MIN, 0.08), EPS)
    }

    // --- Quellen und Cache ---

    @Test
    fun `Cache wird bei setSource, removeSource und clearSources invalidiert`() {
        val engine = ModifierEngine()
        assertEquals(1.0, engine.factor(StatId.SELL_PRICE))

        engine.setSource("a", listOf(mult(StatId.SELL_PRICE, 0.5, ModifierCategory.UPGRADE)))
        assertEquals(1.5, engine.factor(StatId.SELL_PRICE), EPS)

        engine.setSource("a", listOf(mult(StatId.SELL_PRICE, 0.25, ModifierCategory.UPGRADE)))
        assertEquals(1.25, engine.factor(StatId.SELL_PRICE), EPS)

        engine.setSource("b", listOf(mult(StatId.SELL_PRICE, 1.0, ModifierCategory.EVENT)))
        assertEquals(2.5, engine.factor(StatId.SELL_PRICE), EPS)

        assertTrue(engine.removeSource("a"))
        assertEquals(2.0, engine.factor(StatId.SELL_PRICE), EPS)

        engine.clearSources()
        assertEquals(1.0, engine.factor(StatId.SELL_PRICE), EPS)
    }

    @Test
    fun `Cache unterscheidet Basiswerte`() {
        val engine = ModifierEngine()
        engine.setSource("a", listOf(mult(StatId.SELL_PRICE, 1.0, ModifierCategory.UPGRADE)))
        assertEquals(2.0, engine.value(StatId.SELL_PRICE, 1.0), EPS)
        assertEquals(6.0, engine.value(StatId.SELL_PRICE, 3.0), EPS)
        assertEquals(2.0, engine.value(StatId.SELL_PRICE, 1.0), EPS)
    }

    @Test
    fun `Aenderung der uebergebenen Liste wirkt nicht auf die Quelle`() {
        val engine = ModifierEngine()
        val list = mutableListOf(mult(StatId.SELL_PRICE, 1.0, ModifierCategory.UPGRADE))
        engine.setSource("a", list)
        assertEquals(2.0, engine.factor(StatId.SELL_PRICE), EPS)
        list += mult(StatId.SELL_PRICE, 1.0, ModifierCategory.UPGRADE)
        assertEquals(2.0, engine.factor(StatId.SELL_PRICE), EPS)
    }

    @Test
    fun `Quellen verwalten`() {
        val engine = ModifierEngine()
        engine.setSource("upgrade:brush", listOf(mult(StatId.SELL_PRICE, -0.1, ModifierCategory.UPGRADE)))
        engine.setSource("upgrade:tool", emptyList())
        assertEquals(setOf("upgrade:brush", "upgrade:tool"), engine.sourceIds)
        assertEquals(emptyList<Modifier>(), engine.source("upgrade:tool"))
        assertFalse(engine.removeSource("gibt_es_nicht"))
        assertTrue(engine.removeSource("upgrade:tool"))
        assertNull(engine.source("upgrade:tool"))
        assertEquals(setOf("upgrade:brush"), engine.sourceIds)
    }

    // --- NaN / Infinity ---

    @Test
    fun `NaN im Ergebnis wirft IllegalStateException`() {
        val engine = ModifierEngine()
        engine.setSource("a", listOf(add(StatId.SELL_PRICE, Double.NaN)))
        assertThrows<IllegalStateException> { engine.value(StatId.SELL_PRICE, 1.0) }
        assertThrows<IllegalStateException> { ModifierEngine().value(StatId.SELL_PRICE, Double.NaN) }
    }

    @Test
    fun `Infinity im Ergebnis wirft IllegalStateException, auch mit Clamp`() {
        val engine = ModifierEngine(mapOf(StatId.QUALITY_BASE_MIN to StatLimit(min = 0.0, max = 1.0)))
        engine.setSource("a", listOf(mult(StatId.QUALITY_BASE_MIN, Double.MAX_VALUE, ModifierCategory.UPGRADE)))
        engine.setSource("b", listOf(mult(StatId.QUALITY_BASE_MIN, Double.MAX_VALUE, ModifierCategory.EVENT)))
        assertThrows<IllegalStateException> { engine.value(StatId.QUALITY_BASE_MIN, 1.0) }
        assertThrows<IllegalStateException> { ModifierEngine().value(StatId.SELL_PRICE, Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `nach einem Fehler rechnet die Engine mit korrigierten Quellen weiter`() {
        val engine = ModifierEngine()
        engine.setSource("a", listOf(add(StatId.SELL_PRICE, Double.NaN)))
        assertThrows<IllegalStateException> { engine.factor(StatId.SELL_PRICE) }
        engine.setSource("a", listOf(add(StatId.SELL_PRICE, 1.0)))
        assertEquals(2.0, engine.factor(StatId.SELL_PRICE), EPS)
    }

    // --- Echte Daten ---

    @Test
    fun `Clamps aus balance statLimits werden uebernommen`() {
        val data: GameData = DataRepository().load()
        val engine = ModifierEngine.fromGameData(data)
        val limit = checkNotNull(data.balance.statLimits[StatId.QUALITY_BASE_MIN])
        engine.setSource("test", listOf(add(StatId.QUALITY_BASE_MIN, 5.0)))
        assertEquals(checkNotNull(limit.max), engine.additive(StatId.QUALITY_BASE_MIN), EPS)
        engine.setSource("test", listOf(add(StatId.QUALITY_BASE_MIN, -5.0)))
        assertEquals(checkNotNull(limit.min), engine.additive(StatId.QUALITY_BASE_MIN), EPS)
    }

    private companion object {
        const val EPS = 1e-9
    }
}

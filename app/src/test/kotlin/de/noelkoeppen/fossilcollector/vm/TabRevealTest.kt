package de.noelkoeppen.fossilcollector.vm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests fuer [TabReveal] (AP-25, Kapitel 8.2): gezeigte Tabs und Hinweistexte. */
class TabRevealTest {

    @Test
    fun `neues Spiel zeigt den Hinweis der Grabung`() {
        val update = TabReveal.update(emptySet(), setOf(GameTab.DIG))
        assertEquals(setOf(GameTab.DIG), update.revealed)
        assertEquals(listOf(GameTab.DIG), update.hints)
    }

    @Test
    fun `neuer Tab bekommt genau einmal einen Hinweis`() {
        val first = TabReveal.update(setOf(GameTab.DIG), setOf(GameTab.DIG, GameTab.MUSEUM))
        assertEquals(listOf(GameTab.MUSEUM), first.hints)
        val second = TabReveal.update(first.revealed, setOf(GameTab.DIG, GameTab.MUSEUM))
        assertTrue(second.hints.isEmpty())
        assertSame(first.revealed, second.revealed)
    }

    @Test
    fun `mehrere neue Tabs gleichzeitig in Anzeigereihenfolge`() {
        val update = TabReveal.update(setOf(GameTab.DIG), setOf(GameTab.DIG, GameTab.BOOK, GameTab.LAB, GameTab.MUSEUM))
        assertEquals(listOf(GameTab.LAB, GameTab.MUSEUM, GameTab.BOOK), update.hints)
        assertEquals(listOf(GameTab.DIG, GameTab.LAB, GameTab.MUSEUM, GameTab.BOOK), update.revealed.toList())
    }

    @Test
    fun `gezeigte Tabs verschwinden nicht, auch wenn die Bedingung nicht mehr gilt`() {
        val revealed = setOf(GameTab.DIG, GameTab.LAB)
        val update = TabReveal.update(revealed, setOf(GameTab.DIG))
        assertSame(revealed, update.revealed)
        assertTrue(update.hints.isEmpty())
    }

    @Test
    fun `alter Spielstand ohne gespeicherte Tabs uebernimmt sie still`() {
        val unlocked = setOf(GameTab.DIG, GameTab.MUSEUM, GameTab.BOOK)
        val update = TabReveal.update(emptySet(), unlocked)
        assertEquals(unlocked, update.revealed)
        assertTrue(update.hints.isEmpty())
    }

    @Test
    fun `IDs hin und zurueck, unbekannte IDs werden ignoriert`() {
        val tabs = setOf(GameTab.DIG, GameTab.FAME, GameTab.LAB)
        val ids = TabReveal.toIds(tabs)
        assertEquals(setOf("dig", "fame", "lab"), ids)
        assertEquals(listOf(GameTab.DIG, GameTab.LAB, GameTab.FAME), TabReveal.fromIds(ids).toList())
        assertEquals(setOf(GameTab.MUSEUM), TabReveal.fromIds(setOf("museum", "expedition")))
    }

    @Test
    fun `jede Tab-ID ist eindeutig und auffindbar`() {
        assertEquals(GameTab.entries.size, GameTab.entries.map { it.id }.toSet().size)
        GameTab.entries.forEach { assertEquals(it, GameTab.fromId(it.id)) }
    }
}

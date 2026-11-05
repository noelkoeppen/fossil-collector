package de.noelkoeppen.fossilcollector.ui.fame

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests fuer [PrestigeDialogState]. */
class PrestigeDialogStateTest {

    private val owned = listOf("dig_1", "dig_2", "lab_1")

    @Test
    fun `Standard ist Skills behalten ohne Auswahl`() {
        val state = PrestigeDialogState()
        assertFalse(state.respec)
        assertTrue(state.selection.isEmpty())
    }

    @Test
    fun `neu verteilen startet mit den gekauften Skills in Kaufreihenfolge`() {
        val state = PrestigeDialogState().withRespec(true, owned + "dig_1")
        assertTrue(state.respec)
        assertEquals(owned, state.selection)
    }

    @Test
    fun `zurueck auf behalten verwirft die Auswahl, gleiche Wahl aendert nichts`() {
        val respec = PrestigeDialogState().withRespec(true, owned)
        assertSame(respec, respec.withRespec(true, emptyList()))
        val keep = respec.withRespec(false, owned)
        assertFalse(keep.respec)
        assertTrue(keep.selection.isEmpty())
    }

    @Test
    fun `Tippen entfernt gewaehlte und haengt waehlbare Skills an`() {
        val state = PrestigeDialogState().withRespec(true, owned)
        assertEquals(listOf("dig_1", "lab_1"), state.toggle("dig_2", selectable = false).selection)
        assertEquals(owned + "prep_1", state.toggle("prep_1", selectable = true).selection)
        assertSame(state, state.toggle("prep_1", selectable = false))
    }

    @Test
    fun `ohne Respec haben Tippen und Leeren keine Wirkung`() {
        val keep = PrestigeDialogState()
        assertSame(keep, keep.toggle("dig_1", selectable = true))
        assertSame(keep, keep.cleared())
    }

    @Test
    fun `Leeren entfernt die ganze Auswahl`() {
        val state = PrestigeDialogState().withRespec(true, owned).cleared()
        assertTrue(state.respec)
        assertTrue(state.selection.isEmpty())
    }
}

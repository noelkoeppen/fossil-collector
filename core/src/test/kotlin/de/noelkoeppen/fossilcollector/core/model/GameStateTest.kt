package de.noelkoeppen.fossilcollector.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GameStateTest {

    @Test
    fun newGameCreatesEmptyState() {
        val state = GameState.newGame(now = 1234L)
        assertEquals(GameState.CURRENT_SAVE_VERSION, state.saveVersion)
        assertEquals(1234L, state.lastSavedAt)
        assertEquals(Settings(), state.settings)
        assertEquals(LanguageSetting.SYSTEM, state.settings.language)
        assertTrue(state.inventory.isEmpty())
    }

    @Test
    fun newGameKeepsGivenSettings() {
        val state = GameState.newGame(now = 0L, settings = Settings(LanguageSetting.EN))
        assertEquals(LanguageSetting.EN, state.settings.language)
    }
}

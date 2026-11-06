package de.noelkoeppen.fossilcollector.ui.welcome

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.OfflineFind
import de.noelkoeppen.fossilcollector.core.engine.OfflineSummary
import de.noelkoeppen.fossilcollector.core.engine.RunStart
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests fuer [WelcomeUiMapper] (AP-23). */
class WelcomeUiMapperTest {

    private val data: GameData = DataRepository().load()

    private fun summary(
        elapsed: Double = 7_200.0,
        credited: Double = elapsed,
        efficiency: Double = 0.5,
        bestFinds: List<OfflineFind> = emptyList(),
        milestones: List<String> = emptyList(),
    ) = OfflineSummary(
        elapsedSeconds = elapsed,
        creditedSeconds = credited,
        effectiveSeconds = credited * efficiency,
        moneyGained = GameNumber(1_500.0),
        researchPointsGained = GameNumber(12.0),
        detailedFinds = GameNumber(40.0),
        aggregatedFinds = GameNumber(2.0),
        bestFinds = bestFinds,
        milestonesReached = milestones,
    )

    @Test
    fun `Abwesenheit roh, Dialog ab 60 s`() {
        assertEquals(90.0, WelcomeUiMapper.elapsedSeconds(lastSavedAt = 10_000L, now = 100_000L))
        assertEquals(0.0, WelcomeUiMapper.elapsedSeconds(lastSavedAt = 100_000L, now = 10_000L))
        assertFalse(WelcomeUiMapper.showsDialog(59.9))
        assertTrue(WelcomeUiMapper.showsDialog(WelcomeUiMapper.MIN_DIALOG_SECONDS))
    }

    @Test
    fun `Zusammenfassung uebernimmt Werte, Funde und Meilensteine`() {
        val finds = listOf(
            OfflineFind(PieceKey("cave_bear", "skull"), 0.93, "pristine"),
            OfflineFind(PieceKey("giant_deer", "jaw"), 0.6, "good"),
        )
        val ui = WelcomeUiMapper.map(summary(bestFinds = finds, milestones = listOf("first_find")), 14_400.0, 0.5)
        assertEquals(7_200.0, ui.elapsedSeconds)
        assertEquals(7_200.0, ui.creditedSeconds)
        assertEquals(3_600.0, ui.effectiveSeconds)
        assertEquals(14_400.0, ui.capSeconds)
        assertEquals(50, ui.efficiencyPercent)
        assertFalse(ui.capped)
        assertEquals(1_500.0, ui.money.toDouble())
        assertEquals(12.0, ui.researchPoints.toDouble())
        assertEquals(42.0, ui.finds.toDouble())
        assertEquals(
            listOf(WelcomeFindUi("cave_bear", "skull", 0.93, "pristine"), WelcomeFindUi("giant_deer", "jaw", 0.6, "good")),
            ui.bestFinds,
        )
        assertEquals(listOf("first_find"), ui.milestoneIds)
    }

    @Test
    fun `ueber der Obergrenze ist gekappt`() {
        val ui = WelcomeUiMapper.map(summary(elapsed = 20_000.0, credited = 14_400.0), 14_400.0, 0.5)
        assertTrue(ui.capped)
        assertEquals(7_200.0, ui.effectiveSeconds)
    }

    @Test
    fun `next zeigt neue Zusammenfassung nur bei langer Abwesenheit`() {
        val open = WelcomeUiMapper.map(summary(), 14_400.0, 0.5)
        val short = summary(elapsed = 30.0)
        // Kurz: offener Dialog bleibt, Lade-Indikator verschwindet, sonst kein Dialog.
        assertSame(open, WelcomeUiMapper.next(open, short, 14_400.0, 0.5))
        assertNull(WelcomeUiMapper.next(WelcomeUiState.Calculating(30.0), short, 14_400.0, 0.5))
        assertNull(WelcomeUiMapper.next(null, short, 14_400.0, 0.5))
        // Lang: neue Zusammenfassung ersetzt Lade-Indikator bzw. alten Dialog.
        val next = WelcomeUiMapper.next(WelcomeUiState.Calculating(7_200.0), summary(elapsed = 600.0), 14_400.0, 0.5)
        assertTrue(next is WelcomeUiState.Summary)
        assertEquals(600.0, (next as WelcomeUiState.Summary).elapsedSeconds)
    }

    @Test
    fun `mit echter Engine stimmen Obergrenze, Effizienz und Funde`() {
        val modifiers = ModifierEngine.fromGameData(data)
        val engine = GameEngine(data, modifiers)
        val layer = data.layers.first().id
        val base = GameState.newGame(layer, RunStart.values(data, modifiers), now = 0L)
        val start = base.copy(
            run = base.run.copy(teams = mapOf(layer to GameNumber(20.0)), preparators = GameNumber(10.0)),
        )
        val now = 6L * 3_600_000L
        val result = engine.applyOffline(start, now, SeededRandom(7L))
        val ui = WelcomeUiMapper.map(result.summary, engine.offline.capSeconds(), engine.offline.efficiency())

        val cap = data.balance.offlineCapHours * 3_600.0
        assertEquals(6.0 * 3_600.0, ui.elapsedSeconds)
        assertEquals(cap, ui.capSeconds)
        assertEquals(cap, ui.creditedSeconds)
        assertTrue(ui.capped)
        assertEquals((data.balance.offlineEfficiency * 100).toInt(), ui.efficiencyPercent)
        assertEquals(cap * data.balance.offlineEfficiency, ui.effectiveSeconds, 1e-9)
        assertTrue(ui.finds.toDouble() > 0.0)
        assertTrue(ui.money.toDouble() > 0.0)
        assertTrue(ui.bestFinds.size in 1..3)
        assertEquals(ui.bestFinds.sortedByDescending { it.quality }, ui.bestFinds)
        assertTrue(ui.bestFinds.all { find -> data.balance.qualityTiers.any { it.id == find.tierId } })
        assertEquals(now, result.state.lastSavedAt)
    }
}

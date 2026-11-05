package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LabJob
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.QueuedPiece
import de.noelkoeppen.fossilcollector.core.model.SpareZoneKey
import de.noelkoeppen.fossilcollector.core.model.Stats
import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import de.noelkoeppen.fossilcollector.core.modifier.ModifierCategory
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.save.SaveCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests fuer [Prestige] und [GameEngine.prestige] (Kapitel 5.3). */
class PrestigeTest {

    private val data: GameData = DataRepository().load()

    private fun n(v: Double) = GameNumber(v)

    private fun newEngine(): Pair<GameEngine, ModifierEngine> {
        val modifiers = ModifierEngine.fromGameData(data)
        return GameEngine(data, modifiers) to modifiers
    }

    private fun newState(): GameState =
        GameState.newGame(data.layers.first().id, RunStart.values(data, ModifierEngine.fromGameData(data)), 0L)

    /** Alle Knochen von [speciesId] als Run-Stuecke mit Qualitaet [q]. */
    private fun runSkeleton(speciesId: String, q: Double): Map<PieceKey, Piece> =
        data.species(speciesId).bones.associate { PieceKey(speciesId, it) to Piece(listOf(q, q)) }

    private fun bookSkeleton(speciesId: String, q: Double): Map<PieceKey, BookEntry> =
        data.species(speciesId).bones.associate { PieceKey(speciesId, it) to BookEntry(q) }

    private fun GameState.withRunPieces(pieces: Map<PieceKey, Piece>): GameState = copy(run = run.copy(pieces = pieces))

    private fun success(result: PrestigeResult): PrestigeResult.Success {
        assertTrue(result is PrestigeResult.Success, "erwartet Erfolg, war $result")
        return result as PrestigeResult.Success
    }

    // --- Ruhm-Berechnung ---

    @Test
    fun `Vorschau ist 0 ohne vollstaendige Skelette`() {
        val (engine, _) = newEngine()
        val state = newState()
        assertEquals(n(0.0), engine.fameGainPreview(state))
        assertFalse(engine.canPrestige(state))
        // Unvollstaendiges Skelett zaehlt nicht.
        val partial = state.withRunPieces(runSkeleton("cave_bear", 1.0).entries.drop(1).associate { it.toPair() })
        assertEquals(n(0.0), engine.fameGainPreview(partial))
    }

    @Test
    fun `zwei Quartaer-Skelette mit q 0_7 geben floor(2 mal 1 mal 1_2) = 2`() {
        val (engine, _) = newEngine()
        val state = newState().withRunPieces(runSkeleton("cave_bear", 0.7) + runSkeleton("giant_deer", 0.7))
        assertEquals(n(2.0), engine.fameGainPreview(state))
        assertTrue(engine.canPrestige(state))
        val skeletons = engine.prestigeSystem.skeletons(state)
        assertEquals(listOf("cave_bear", "giant_deer"), skeletons.map { it.speciesId })
        assertEquals(1.2, skeletons[0].baseFame, 1e-9)
    }

    @Test
    fun `Schichtgewicht und Mittelwert der Knochen`() {
        val (engine, _) = newEngine()
        // Hipparion (Neogen, Gewicht 3): Haelfte der Knochen 0.2, Haelfte 0.6 -> avg 0.4 -> 3 * 0.9 = 2.7
        // Psittacosaurus (Kreide, Gewicht 10) mit q 0.3 -> 10 * 0.8 = 8; Summe 10.7 -> 10
        val bones = data.species("hipparion").bones
        val hipparion = bones.withIndex().associate { (i, b) ->
            PieceKey("hipparion", b) to Piece(listOf(if (i < bones.size / 2) 0.2 else 0.6))
        }
        val state = newState().withRunPieces(hipparion + runSkeleton("psittacosaurus", 0.3))
        val skeletons = engine.prestigeSystem.skeletons(state)
        assertEquals(0.4, skeletons.first { it.speciesId == "hipparion" }.avgQuality, 1e-9)
        assertEquals(n(10.0), engine.fameGainPreview(state))
    }

    @Test
    fun `fame_gain wirkt als Faktor auf die Summe vor dem Abrunden`() {
        val modifiers = ModifierEngine.fromGameData(data)
        val prestige = Prestige(data, modifiers)
        val state = newState().withRunPieces(runSkeleton("cave_bear", 0.7) + runSkeleton("giant_deer", 0.7))
        assertEquals(n(2.0), prestige.fameGainPreview(state))
        modifiers.setSource(
            "test:fame",
            listOf(Modifier(StatId.FAME_GAIN, ModifierOp.MULT, 0.5, ModifierCategory.SKILL)),
        )
        // 2.4 * 1.5 = 3.6 -> 3
        assertEquals(n(3.0), prestige.fameGainPreview(state))
        assertEquals(1.5, prestige.fameGainFactor(), 1e-9)
    }

    @Test
    fun `Buch-Bonus Tyrannosaurus erhoeht den Ruhm`() {
        val (engine, _) = newEngine()
        // Psittacosaurus q 0.45 -> 10 * 0.95 = 9.5 -> 9; mit Tyrannosaurus in "good" (Faktor 3):
        // fame_gain 1 + 0.03 * 3 = 1.09 -> 10.355 -> 10
        val base = newState().withRunPieces(runSkeleton("psittacosaurus", 0.45))
        assertEquals(n(9.0), engine.fameGainPreview(base))
        val withBook = base.copy(meta = base.meta.copy(book = bookSkeleton("tyrannosaurus", 0.6)))
        assertEquals(n(10.0), engine.fameGainPreview(withBook))
        assertEquals(1.09, engine.prestigeSummary(withBook).fameGainFactor, 1e-9)
    }

    // --- Aktion ---

    @Test
    fun `Prestige wird unter 1 Ruhm verweigert`() {
        val (engine, _) = newEngine()
        val state = newState().copy(run = newState().run.copy(money = n(1e6)))
        val result = engine.prestige(state)
        assertEquals(PrestigeResult.Failure(PrestigeFailure.NOT_ENOUGH_FAME), result)
    }

    /** Durchlauf mit allem, was zurueckgesetzt werden muss, und Metazustand, der bleiben muss. */
    private fun richState(): GameState {
        val base = newState()
        val key = PieceKey("cave_bear", "skull")
        val zone = data.bone("skull").zones.first()
        val run = base.run.copy(
            money = n(12345.0),
            researchPoints = n(77.0),
            unlockedLayers = n(3.0),
            teams = mapOf("quaternary" to n(40.0), "neogene" to n(12.0), "cretaceous" to n(2.0)),
            preparators = n(9.0),
            halls = n(4.0),
            labSlots = n(3.0),
            toolLevel = n(5.0),
            blocks = mapOf("quaternary" to n(3.0), "neogene" to n(1.5)),
            pieces = runSkeleton("cave_bear", 0.7) + runSkeleton("giant_deer", 0.7) +
                (PieceKey("hipparion", "skull") to Piece(listOf(0.4))),
            spareZones = mapOf(SpareZoneKey(key, zone) to 0.6),
            labQueue = listOf(QueuedPiece(key, listOf(0.3))),
            labJobs = listOf(LabJob(key, listOf(0.4), remaining = 12.0)),
            specialExhibit = "cave_bear",
            prepProgress = n(0.5),
        )
        val meta = base.meta.copy(
            fame = n(3.0),
            fameTotal = n(10.0),
            prestigeCount = n(0.0),
            skills = setOf("dig_1"),
            book = bookSkeleton("cave_bear", 0.8) + bookSkeleton("giant_deer", 0.5),
            researchedSpecies = setOf("cave_bear"),
            milestonesDone = data.milestones.map { it.id }.filterTo(LinkedHashSet()) { it != "first_prestige" },
            stats = Stats(piecesFound = n(500.0), pristineFound = n(2.0), researchCompleted = n(7.0), reconstructions = n(1.0)),
        )
        return base.copy(
            lastSavedAt = 99L,
            meta = meta,
            run = run,
            settings = base.settings.copy(language = LanguageSetting.EN),
        )
    }

    @Test
    fun `Reset-Tabelle - was weg muss, ist weg`() {
        val (engine, _) = newEngine()
        val after = success(engine.prestige(richState())).state
        val run = after.run
        // first_prestige gibt 100 Geld im neuen Durchlauf.
        assertEquals(n(100.0), run.money)
        assertEquals(n(0.0), run.researchPoints)
        assertEquals(n(1.0), run.unlockedLayers)
        assertEquals(mapOf("quaternary" to n(0.0)), run.teams)
        assertEquals(n(0.0), run.preparators)
        assertEquals(n(1.0), run.halls)
        assertEquals(n(1.0), run.labSlots)
        assertEquals(n(0.0), run.toolLevel)
        assertEquals(mapOf("quaternary" to n(0.0)), run.blocks)
        assertTrue(run.pieces.isEmpty())
        assertTrue(run.spareZones.isEmpty())
        assertTrue(run.labQueue.isEmpty())
        assertTrue(run.labJobs.isEmpty())
        assertNull(run.specialExhibit)
        assertEquals(n(0.0), run.prepProgress)
    }

    @Test
    fun `Reset-Tabelle - was bleiben muss, bleibt`() {
        val (engine, _) = newEngine()
        val before = richState()
        val result = success(engine.prestige(before))
        val after = result.state
        assertEquals(n(2.0), result.fameGained)
        assertEquals(n(5.0), after.meta.fame)
        assertEquals(n(12.0), after.meta.fameTotal)
        assertEquals(n(1.0), after.meta.prestigeCount)
        assertEquals(before.meta.skills, after.meta.skills)
        assertEquals(before.meta.book, after.meta.book)
        assertEquals(before.meta.researchedSpecies, after.meta.researchedSpecies)
        assertEquals(before.meta.stats, after.meta.stats)
        assertTrue(after.meta.milestonesDone.containsAll(before.meta.milestonesDone))
        assertEquals(before.settings, after.settings)
        assertEquals(before.lastSavedAt, after.lastSavedAt)
        // Sonst aendert sich am Metazustand nichts ausser Ruhm, Zaehler und neuen Meilensteinen.
        assertEquals(
            before.meta.copy(fame = n(5.0), fameTotal = n(12.0), prestigeCount = n(1.0)),
            after.meta.copy(milestonesDone = before.meta.milestonesDone),
        )
    }

    @Test
    fun `Ereignisse und Meilenstein first_prestige`() {
        val (engine, _) = newEngine()
        val result = success(engine.prestige(richState()))
        assertEquals(GameEvent.Prestiged(n(2.0)), result.events.first())
        val reached = result.events.filterIsInstance<GameEvent.MilestoneReached>().map { it.milestoneId }
        assertEquals(listOf("first_prestige"), reached)
        assertTrue("first_prestige" in result.state.meta.milestonesDone)
    }

    @Test
    fun `prestigeCount zaehlt bei jedem Prestige hoch`() {
        val (engine, _) = newEngine()
        var state = richState()
        repeat(3) { i ->
            val prepared = state.withRunPieces(runSkeleton("cave_bear", 0.7))
            state = success(engine.prestige(prepared)).state
            assertEquals(n(i + 1.0), state.meta.prestigeCount)
        }
    }

    @Test
    fun `Startwerte nach Prestige mit Skills dig_3 und lab_3`() {
        val (engine, modifiers) = newEngine()
        val skills = setOf("dig_1", "dig_2", "dig_3", "lab_1", "lab_2", "lab_3")
        val before = richState().let { it.copy(meta = it.meta.copy(skills = skills)) }
        val after = success(engine.prestige(before)).state
        assertEquals(mapOf("quaternary" to n(5.0)), after.run.teams)
        assertEquals(n(2.0), after.run.labSlots)
        assertEquals(n(1.0), after.run.halls)
        // Die Modifier-Engine passt nach dem Prestige zum neuen Zustand (keine Upgrades des alten Runs).
        assertNull(modifiers.source("upgrade:tools"))
        assertNull(modifiers.source("upgrade:preparator"))
    }

    @Test
    fun `Meta-Schritt (Respec-Hook) laeuft vor den Startwerten`() {
        val (engine, _) = newEngine()
        val before = richState().let { it.copy(meta = it.meta.copy(skills = setOf("dig_1", "dig_2", "dig_3"))) }
        var seenFame: GameNumber? = null
        val result = success(
            engine.prestige(before) { meta ->
                seenFame = meta.fame
                meta.copy(skills = emptySet())
            },
        )
        assertEquals(n(5.0), seenFame)
        assertEquals(mapOf("quaternary" to n(0.0)), result.state.run.teams)
        assertTrue(result.state.meta.skills.isEmpty())
    }

    @Test
    fun `Zusammenfassung fuer den Dialog`() {
        val (engine, _) = newEngine()
        val state = richState()
        val summary = engine.prestigeSummary(state)
        assertEquals(n(2.0), summary.fameGain)
        assertTrue(summary.canPrestige)
        assertEquals(2, summary.skeletonCount)
        assertEquals(n(5.0), summary.fameAfter)
        assertEquals(n(1.0), summary.prestigeCountAfter)
        assertEquals(n(12345.0), summary.money)
        assertEquals(n(77.0), summary.researchPoints)
        assertEquals(n(4.5), summary.blocks)
        assertEquals(n(54.0), summary.teams)
        assertEquals(n(9.0), summary.preparators)
        assertEquals(n(4.0), summary.halls)
        assertEquals(n(3.0), summary.labSlots)
        assertEquals(n(5.0), summary.toolLevel)
        assertEquals(n(2.0), summary.lockedLayers)
        assertEquals(17, summary.runPieces)
        assertEquals(1, summary.spareZones)
        assertEquals(2, summary.labItems)
        assertEquals("cave_bear", summary.specialExhibit)
        assertEquals(16, summary.bookEntries)
        assertEquals(1, summary.skills)
        assertEquals(data.milestones.size - 1, summary.milestonesDone)
        assertFalse(engine.prestigeSummary(newState()).canPrestige)
    }

    @Test
    fun `Save-Roundtrip nach Prestige`() {
        val (engine, _) = newEngine()
        val after = success(engine.prestige(richState())).state
        val codec = SaveCodec.DEFAULT
        assertEquals(after, codec.decode(codec.encode(after)))
    }

    @Test
    fun `Spiel laeuft nach Prestige normal weiter`() {
        val (engine, _) = newEngine()
        val after = success(engine.prestige(richState())).state
        val advanced = engine.advance(after, 1.0, de.noelkoeppen.fossilcollector.core.random.SeededRandom(1L))
        assertTrue(advanced.state.run.money >= after.run.money)
    }
}

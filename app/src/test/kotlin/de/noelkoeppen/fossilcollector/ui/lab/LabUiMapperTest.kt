package de.noelkoeppen.fossilcollector.ui.lab

import de.noelkoeppen.fossilcollector.core.data.Currency
import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.DefinitionIds
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.QualityTiers
import de.noelkoeppen.fossilcollector.core.engine.RunStart
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LabJob
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.QueuedPiece
import de.noelkoeppen.fossilcollector.core.model.SpareZoneKey
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests fuer [LabUiMapper] und die abgeleiteten Werte von [LabUiState]. */
class LabUiMapperTest {

    private val data: GameData = DataRepository().load()
    private val tiers = QualityTiers.fromGameData(data)

    private val skull = PieceKey("cave_bear", "skull")
    private val jaw = PieceKey("giant_deer", "jaw")

    private fun state(
        labSlots: Double? = null,
        jobs: List<LabJob> = emptyList(),
        queue: List<QueuedPiece> = emptyList(),
        spare: Map<SpareZoneKey, Double> = emptyMap(),
        pieces: Map<PieceKey, Piece> = emptyMap(),
        book: Map<PieceKey, BookEntry> = emptyMap(),
        money: Double = 0.0,
        researchPoints: Double = 0.0,
        toolLevel: Double = 0.0,
        skills: Set<String> = emptySet(),
    ): GameState {
        val base = GameState.newGame(data.layers.first().id, RunStart.values(data, ModifierEngine.fromGameData(data)), 0L)
        return base.copy(
            meta = base.meta.copy(skills = skills, book = book),
            run = base.run.copy(
                labSlots = labSlots?.let(::GameNumber) ?: base.run.labSlots,
                labJobs = jobs,
                labQueue = queue,
                spareZones = spare,
                pieces = pieces,
                money = GameNumber(money),
                researchPoints = GameNumber(researchPoints),
                toolLevel = GameNumber(toolLevel),
            ),
        )
    }

    private fun map(state: GameState): Pair<LabUiState, GameEngine> {
        val engine = GameEngine(data, ModifierEngine.fromGameData(data))
        engine.syncModifiers(state)
        return LabUiMapper.map(engine, state, data) to engine
    }

    private fun zones(q: Double) = listOf(q, q, q, q)

    private fun spares(key: PieceKey, q: Double, count: Int = 4): Map<SpareZoneKey, Double> =
        data.bone(key.boneId).zones.take(count).associate { SpareZoneKey(key, it) to q }

    @Test
    fun `neues Spiel hat freie Plaetze, leere Warteschlange und keine Rekonstruktionen`() {
        val (ui, engine) = map(state())
        val s = state()
        assertEquals(engine.lab.slots(s), ui.slotCount)
        assertTrue(ui.jobs.isEmpty())
        assertEquals(ui.slotCount, ui.freeSlots)
        assertTrue(ui.queue.isEmpty())
        assertEquals(engine.labQueueCapacity(s), ui.queueCapacity)
        assertFalse(ui.queueFull)
        assertEquals(data.balance.labDuration, ui.jobDuration, EPS)
        assertTrue(ui.reconstructions.isEmpty())
        assertEquals(0.0, ui.damageReduction, EPS)
    }

    @Test
    fun `laufende Forschung mit Fortschritt aus remaining und Dauer`() {
        val duration = data.balance.labDuration
        val job = LabJob(skull, zones(0.62), remaining = duration * 0.25)
        val (ui, engine) = map(state(labSlots = 2.0, jobs = listOf(job)))
        val jobUi = ui.jobs.single()
        assertEquals(skull.speciesId, jobUi.speciesId)
        assertEquals(skull.boneId, jobUi.boneId)
        assertEquals(tiers.tierOf(0.62).id, jobUi.tierId)
        assertEquals(0.62, jobUi.quality, EPS)
        assertEquals(0.75, jobUi.progress, EPS)
        assertEquals(duration * 0.25, jobUi.remainingSeconds, EPS)
        assertEquals(engine.lab.researchPointsFor(skull), jobUi.researchPoints)
        assertEquals(1, ui.freeSlots)
    }

    @Test
    fun `Fortschritt bleibt zwischen 0 und 1`() {
        val duration = data.balance.labDuration
        val tooLong = LabJob(skull, zones(0.5), remaining = duration * 3)
        val done = LabJob(jaw, zones(0.5), remaining = -1.0)
        val (ui, _) = map(state(labSlots = 2.0, jobs = listOf(tooLong, done)))
        assertEquals(0.0, ui.jobs[0].progress, EPS)
        assertEquals(1.0, ui.jobs[1].progress, EPS)
        assertEquals(0.0, ui.jobs[1].remainingSeconds, EPS)
    }

    @Test
    fun `Warteschlange in FIFO-Reihenfolge mit Stufe und voll-Erkennung`() {
        val s0 = state(labSlots = 1.0)
        val capacity = map(s0).second.labQueueCapacity(s0)
        val queue = List(capacity) { i -> QueuedPiece(if (i == 0) skull else jaw, zones(0.2 + 0.1 * i)) }
        val (ui, _) = map(state(labSlots = 1.0, queue = queue))
        assertEquals(capacity, ui.queue.size)
        assertEquals(skull.speciesId, ui.queue.first().speciesId)
        assertEquals(tiers.tierOf(0.2).id, ui.queue.first().tierId)
        assertEquals(jaw.boneId, ui.queue.last().boneId)
        assertTrue(ui.queueFull)
    }

    @Test
    fun `Kaufvorschauen fuer Laborplaetze in Geld und Werkzeug in FP`() {
        val labSlot = data.generator(DefinitionIds.LAB_SLOT)
        val tools = data.upgrade(DefinitionIds.TOOLS)
        val (ui, _) = map(state(money = labSlot.baseCost, researchPoints = tools.baseCost, toolLevel = 2.0))
        assertEquals(BuyMode.entries.toSet(), ui.labSlotQuotes.keys)
        assertEquals(BuyMode.entries.toSet(), ui.toolQuotes.keys)
        val slotQuote = ui.labSlotQuotes.getValue(BuyMode.X1)!!
        assertEquals(Currency.MONEY, slotQuote.currency)
        assertTrue(slotQuote.affordable)
        val toolQuote = ui.toolQuotes.getValue(BuyMode.X1)!!
        assertEquals(Currency.RESEARCH_POINTS, toolQuote.currency)
        assertFalse(toolQuote.affordable, "Stufe 3 kostet mehr als baseCost")
        assertEquals(GameNumber(2.0), ui.toolLevel)
    }

    @Test
    fun `Werkzeugstufe zeigt damage_reduction und den Zuwachs je Stufe`() {
        val perLevel = data.upgrade(DefinitionIds.TOOLS).effectsPerLevel.single().value
        val (ui, _) = map(state(toolLevel = 4.0))
        assertEquals(perLevel, ui.damageReductionPerLevel, EPS)
        assertEquals(4 * perLevel, ui.damageReduction, EPS)
    }

    @Test
    fun `rekonstruierbarer Knochen mit Verbesserung fuer Run und Buch`() {
        val (ui, _) = map(state(spare = spares(skull, 0.8)))
        val recon = ui.reconstructions.single()
        assertEquals(skull, recon.pieceKey)
        assertEquals(skull.toString(), recon.key)
        assertEquals(0.8, recon.quality, EPS)
        assertEquals(tiers.tierOf(0.8).id, recon.tierId)
        assertTrue(recon.missingZones.isEmpty())
        assertTrue(recon.improvesRun)
        assertTrue(recon.improvesBook)
        assertTrue(recon.isImprovement)
    }

    @Test
    fun `keine Verbesserung deaktiviert den Button`() {
        val s = state(
            spare = spares(skull, 0.5),
            pieces = mapOf(skull to Piece(zones(0.7))),
            book = mapOf(skull to BookEntry(0.7)),
        )
        val recon = map(s).first.reconstructions.single()
        assertFalse(recon.improvesRun)
        assertFalse(recon.improvesBook)
        assertFalse(recon.isImprovement)
    }

    @Test
    fun `nur Buch verbessert`() {
        val s = state(spare = spares(skull, 0.6), pieces = mapOf(skull to Piece(zones(0.7))))
        val recon = map(s).first.reconstructions.single()
        assertFalse(recon.improvesRun)
        assertTrue(recon.improvesBook)
        assertTrue(recon.isImprovement)
    }

    @Test
    fun `fehlende Zone mit Capstone lab_5 wird ausgewiesen`() {
        val labBranch = setOf("lab_1", "lab_2", "lab_3", "lab_4", "lab_5")
        val withoutCapstone = map(state(spare = spares(skull, 0.8, count = 3))).first
        assertTrue(withoutCapstone.reconstructions.isEmpty())

        val recon = map(state(spare = spares(skull, 0.8, count = 3), skills = labBranch)).first.reconstructions.single()
        assertEquals(listOf(data.bone(skull.boneId).zones.last()), recon.missingZones)
    }

    @Test
    fun `ohne Plaetze gibt es keine freien Plaetze`() {
        val (ui, _) = map(state(labSlots = 0.0))
        assertEquals(0, ui.slotCount)
        assertEquals(0, ui.freeSlots)
        assertEquals(0, ui.queueCapacity)
    }

    @Test
    fun `EMPTY ist konsistent`() {
        val empty = LabUiState.EMPTY
        assertEquals(0, empty.freeSlots)
        assertTrue(empty.queueFull)
        assertNull(empty.labSlotQuotes[BuyMode.X1])
    }

    private companion object {
        const val EPS = 1e-9
    }
}

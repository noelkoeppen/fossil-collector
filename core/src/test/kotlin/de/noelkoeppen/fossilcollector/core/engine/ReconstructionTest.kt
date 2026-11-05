package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.QueuedPiece
import de.noelkoeppen.fossilcollector.core.model.SpareZoneKey
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests fuer [Reconstruction] und [GameEngine.reconstruct] (Kapitel 4.5) mit den echten Daten:
 * reconstructionCap 0.89, reconstructionMissingZoneQuality 0.5, Capstone `lab_5`
 * (reconstruction_missing_zones +1), Museumsreif ab 0.9.
 */
class ReconstructionTest {

    private val data: GameData = DataRepository().load()
    private val tiers = QualityTiers.fromGameData(data)
    private val pristine = data.balance.qualityTiers.maxBy { it.minQuality }.id

    private val skull = PieceKey("cave_bear", "skull")
    private val skullZones = listOf("snout", "orbit", "skull_roof", "occiput")

    private fun spares(key: PieceKey, vararg q: Double?): Map<SpareZoneKey, Double> {
        val zones = data.bone(key.boneId).zones
        return zones.zip(q.toList()).mapNotNull { (z, v) -> v?.let { SpareZoneKey(key, z) to it } }.toMap()
    }

    private fun state(
        spare: Map<SpareZoneKey, Double> = emptyMap(),
        pieces: Map<PieceKey, Piece> = emptyMap(),
        book: Map<PieceKey, BookEntry> = emptyMap(),
        skills: Set<String> = emptySet(),
    ): GameState {
        val base = GameState.newGame(data.layers.first().id, RunStart.values(data, ModifierEngine.fromGameData(data)), 0L)
        return base.copy(
            meta = base.meta.copy(skills = skills, book = book),
            run = base.run.copy(spareZones = spare, pieces = pieces),
        )
    }

    private fun engine() = GameEngine(data, ModifierEngine.fromGameData(data))

    private fun success(result: ReconstructionResult): ReconstructionResult.Success {
        assertTrue(result is ReconstructionResult.Success, "erwartet Erfolg, war $result")
        return result as ReconstructionResult.Success
    }

    private fun piece(q: Double) = Piece(listOf(q, q, q, q))

    // --- moeglich / nicht moeglich ---

    @Test
    fun `ohne alle Ersatzteile nicht moeglich`() {
        val s = state(spare = spares(skull, 0.8, 0.8, 0.8, null))
        val e = engine()
        assertTrue(e.reconstructableBones(s).isEmpty())
        assertEquals(ReconstructionResult.Failure(ReconstructionFailure.MISSING_SPARE_ZONES), e.reconstruct(s, skull))
    }

    @Test
    fun `ohne Ersatzteile nicht moeglich, auch nicht mit Capstone`() {
        val s = state(skills = setOf("lab_5"))
        assertEquals(ReconstructionResult.Failure(ReconstructionFailure.MISSING_SPARE_ZONES), engine().reconstruct(s, skull))
    }

    @Test
    fun `unbekannter Knochen wird abgelehnt`() {
        val e = engine()
        assertEquals(
            ReconstructionResult.Failure(ReconstructionFailure.UNKNOWN_PIECE),
            e.reconstruct(state(), PieceKey("cave_bear", "tail")),
        )
        assertEquals(
            ReconstructionResult.Failure(ReconstructionFailure.UNKNOWN_PIECE),
            e.reconstruct(state(), PieceKey("unknown_species", "skull")),
        )
    }

    @Test
    fun `Rekonstruktion legt Run-Fund und Bucheintrag an`() {
        val s = state(spare = spares(skull, 0.6, 0.7, 0.8, 0.5))
        val result = success(engine().reconstruct(s, skull))
        val after = result.state
        assertEquals(0.65, result.preview.quality, EPS)
        assertEquals(listOf(0.6, 0.7, 0.8, 0.5), after.run.pieces[skull]?.zones)
        assertEquals(BookEntry(result.preview.quality, reconstructed = true, zones = listOf(0.6, 0.7, 0.8, 0.5)), after.meta.book[skull])
        assertNotSame(s.run.pieces, after.run.pieces)
        assertEquals(
            listOf(
                GameEvent.Reconstructed(skull, result.preview.quality, improvedRun = true, improvedBook = true),
                GameEvent.BookUpdated(skull, result.preview.quality, null),
                GameEvent.NewPiece(skull, result.preview.quality),
            ),
            result.events,
        )
    }

    @Test
    fun `Ersatzteile werden nicht verbraucht`() {
        val s = state(spare = spares(skull, 0.6, 0.7, 0.8, 0.5))
        assertEquals(s.run.spareZones, success(engine().reconstruct(s, skull)).state.run.spareZones)
    }

    @Test
    fun `Eingabe-Zustand bleibt unveraendert`() {
        val s = state(spare = spares(skull, 0.6, 0.7, 0.8, 0.5))
        val copy = s.copy()
        engine().reconstruct(s, skull)
        assertEquals(copy, s)
    }

    // --- Cap ---

    @Test
    fun `Qualitaet wird auf reconstructionCap begrenzt und Zonen skaliert`() {
        val s = state(spare = spares(skull, 1.0, 1.0, 0.98, 0.94))
        val result = success(engine().reconstruct(s, skull))
        val cap = data.balance.reconstructionCap
        assertEquals(cap, result.preview.quality, EPS)
        val runPiece = result.state.run.pieces.getValue(skull)
        assertEquals(cap, runPiece.quality, EPS)
        assertEquals(result.state.meta.book.getValue(skull).quality, runPiece.quality, 0.0)
        // Verhaeltnisse der Zonen bleiben erhalten
        assertEquals(1.0 / 0.94, runPiece.zones[0] / runPiece.zones[3], EPS)
        assertEquals("excellent", tiers.tierOf(result.preview.quality).id)
    }

    @Test
    fun `erneute Rekonstruktion mit denselben Ersatzteilen ist keine Verbesserung`() {
        val s = state(spare = spares(skull, 1.0, 1.0, 1.0, 1.0))
        val e = engine()
        val once = success(e.reconstruct(s, skull)).state
        assertEquals(ReconstructionResult.Failure(ReconstructionFailure.NO_IMPROVEMENT), e.reconstruct(once, skull))
        assertFalse(e.reconstructableBones(once).single().isImprovement)
    }

    // --- nur wenn besser ---

    @Test
    fun `ersetzt nichts, wenn Run-Fund und Buch besser sind`() {
        val s = state(
            spare = spares(skull, 0.8, 0.8, 0.8, 0.8),
            pieces = mapOf(skull to piece(0.85)),
            book = mapOf(skull to BookEntry(0.95)),
        )
        val e = engine()
        assertEquals(ReconstructionResult.Failure(ReconstructionFailure.NO_IMPROVEMENT), e.reconstruct(s, skull))
        val preview = e.reconstructableBones(s).single()
        assertFalse(preview.improvesRun)
        assertFalse(preview.improvesBook)
    }

    @Test
    fun `gleiche Qualitaet ist keine Verbesserung`() {
        val s = state(spare = spares(skull, 0.5, 0.5, 0.5, 0.5), pieces = mapOf(skull to piece(0.5)), book = mapOf(skull to BookEntry(0.5)))
        assertEquals(ReconstructionResult.Failure(ReconstructionFailure.NO_IMPROVEMENT), engine().reconstruct(s, skull))
    }

    @Test
    fun `ersetzt nur den Run-Fund, wenn das Buch besser ist`() {
        val book = mapOf(skull to BookEntry(0.95, reconstructed = false, zones = listOf(0.95, 0.95, 0.95, 0.95)))
        val s = state(spare = spares(skull, 0.8, 0.8, 0.8, 0.8), pieces = mapOf(skull to piece(0.4)), book = book)
        val result = success(engine().reconstruct(s, skull))
        assertEquals(0.8, result.state.run.pieces.getValue(skull).quality, EPS)
        assertSame(s.meta.book, result.state.meta.book)
        // Das ersetzte Stueck ist ein Duplikat und geht in die Warteschlange (1 Platz * 3)
        assertEquals(listOf(piece(0.4).zones), result.state.run.labQueue.map { it.zones })
        val q = result.preview.quality
        assertEquals(
            listOf(
                GameEvent.Reconstructed(skull, q, improvedRun = true, improvedBook = false),
                GameEvent.PieceImproved(skull, q, piece(0.4).quality),
                GameEvent.DuplicateQueued(skull, piece(0.4).quality),
            ),
            result.events,
        )
    }

    @Test
    fun `ersetztes Stueck wird bei voller Warteschlange verkauft`() {
        val s0 = state(spare = spares(skull, 0.8, 0.8, 0.8, 0.8), pieces = mapOf(skull to piece(0.4)))
        val full = s0.copy(
            run = s0.run.copy(labQueue = List(3) { QueuedPiece(skull, piece(0.1).zones) }),
        )
        val result = success(engine().reconstruct(full, skull))
        // Quartaer: 1 * (0.5 + 0.4) * 20 = 18
        assertEquals(18.0, result.state.run.money.toDouble(), EPS)
        assertTrue(result.events.any { it is GameEvent.DuplicateSold })
    }

    @Test
    fun `ersetzt nur das Buch, wenn der Run-Fund besser ist`() {
        val s = state(spare = spares(skull, 0.8, 0.8, 0.8, 0.8), pieces = mapOf(skull to piece(0.85)), book = mapOf(skull to BookEntry(0.3)))
        val result = success(engine().reconstruct(s, skull))
        assertSame(s.run.pieces, result.state.run.pieces)
        val entry = result.state.meta.book.getValue(skull)
        assertTrue(entry.reconstructed)
        assertEquals(0.8, entry.quality, EPS)
        assertEquals(4, entry.zones.size)
        assertTrue(result.events.any { it is GameEvent.BookUpdated && it.previousQuality == 0.3 })
    }

    @Test
    fun `Rekonstruktion kann ein Skelett vervollstaendigen`() {
        val bear = data.species("cave_bear")
        val pieces = bear.bones.filter { it != "skull" }.associate { PieceKey(bear.id, it) to piece(0.5) }
        val s = state(spare = spares(skull, 0.6, 0.6, 0.6, 0.6), pieces = pieces)
        val result = success(engine().reconstruct(s, skull))
        assertTrue(Skeletons.isCompleteInRun(result.state.run, bear))
        assertEquals(GameEvent.SkeletonCompleted("cave_bear"), result.events.last())
    }

    // --- Capstone lab_5 ---

    @Test
    fun `Capstone lab_5 erlaubt eine fehlende Zone mit 0_5`() {
        val spare = spares(skull, 1.0, 0.9, null, 0.8)
        val e = engine()
        assertTrue(e.reconstructableBones(state(spare = spare)).isEmpty())

        val s = state(spare = spare, skills = setOf("lab_5"))
        val preview = e.reconstructableBones(s).single()
        assertEquals(listOf("skull_roof"), preview.missingZones)
        assertEquals((1.0 + 0.9 + 0.5 + 0.8) / 4, preview.quality, EPS)
        assertEquals(listOf(1.0, 0.9, 0.5, 0.8), preview.zones)
        val result = success(e.reconstruct(s, skull))
        assertEquals(preview.zones, result.state.meta.book.getValue(skull).zones)
    }

    @Test
    fun `Capstone lab_5 erlaubt nicht zwei fehlende Zonen`() {
        val s = state(spare = spares(skull, 1.0, null, null, 0.8), skills = setOf("lab_5"))
        assertEquals(ReconstructionResult.Failure(ReconstructionFailure.MISSING_SPARE_ZONES), engine().reconstruct(s, skull))
    }

    // --- Kernregel: nie Museumsreif ---

    @Test
    fun `Rekonstruktion erreicht nie Museumsreif - Ersatzteile 1_0`() {
        val s = state(spare = spares(skull, 1.0, 1.0, 1.0, 1.0))
        assertNeverPristine(success(engine().reconstruct(s, skull)))
    }

    @Test
    fun `Rekonstruktion erreicht nie Museumsreif - mit Capstone`() {
        val s = state(spare = spares(skull, 1.0, 1.0, 1.0, null), skills = setOf("lab_5"))
        assertNeverPristine(success(engine().reconstruct(s, skull)))
        // auch mit allen Zonen bei Capstone
        val full = state(spare = spares(skull, 1.0, 1.0, 1.0, 1.0), skills = setOf("lab_5"))
        assertNeverPristine(success(engine().reconstruct(full, skull)))
    }

    @Test
    fun `Rekonstruktion erreicht nie Museumsreif - Zufallsstichprobe`() {
        val rng = SeededRandom(SEED)
        val e = engine()
        val keys = data.species.flatMap { sp -> sp.bones.map { PieceKey(sp.id, it) } }
        repeat(2000) {
            val key = keys[rng.nextInt(keys.size)]
            val zones = data.bone(key.boneId).zones
            val spare = zones.associate { SpareZoneKey(key, it) to (0.8 + 0.2 * rng.nextDouble()) }
                .filterKeys { rng.nextDouble() > 0.1 || it.zoneId != zones.first() }
            val s = state(spare = spare, skills = setOf("lab_5"))
            val result = e.reconstruct(s, key)
            if (result is ReconstructionResult.Success) assertNeverPristine(result, key)
        }
    }

    private fun assertNeverPristine(result: ReconstructionResult.Success, key: PieceKey = skull) {
        val runQ = result.state.run.pieces.getValue(key).quality
        val bookQ = result.state.meta.book.getValue(key).quality
        assertTrue(runQ <= data.balance.reconstructionCap + EPS)
        assertTrue(bookQ <= data.balance.reconstructionCap + EPS)
        assertNotEquals(pristine, tiers.tierOf(runQ).id)
        assertNotEquals(pristine, tiers.tierOf(bookQ).id)
        assertNotEquals(pristine, tiers.tierOf(result.preview.quality).id)
        assertTrue(result.state.meta.book.getValue(key).reconstructed)
    }

    // --- Abfrage fuer die UI ---

    @Test
    fun `reconstructableBones listet sortiert mit Qualitaet und Flags`() {
        val jaw = PieceKey("cave_bear", "jaw")
        val s = state(
            spare = spares(skull, 0.6, 0.6, 0.6, 0.6) + spares(jaw, 0.9, 0.9, 0.9, 0.9) +
                spares(PieceKey("hipparion", "ribs"), 0.5, null, 0.5, 0.5),
            pieces = mapOf(skull to piece(0.7)),
        )
        val list = engine().reconstructableBones(s)
        assertEquals(listOf(jaw, skull), list.map { it.pieceKey })
        val jawPreview = list[0]
        assertEquals(data.balance.reconstructionCap, jawPreview.quality, EPS)
        assertTrue(jawPreview.improvesRun && jawPreview.improvesBook)
        val skullPreview = list[1]
        assertEquals(0.6, skullPreview.quality, EPS)
        assertFalse(skullPreview.improvesRun)
        assertTrue(skullPreview.improvesBook)
        assertTrue(skullPreview.isImprovement)
        assertNotNull(engine().reconstruction.preview(s, skull))
        assertNull(engine().reconstruction.preview(s, PieceKey("hipparion", "ribs")))
    }

    @Test
    fun `Labor-Ersatzteile ermoeglichen Rekonstruktion ueber GameEngine`() {
        val e = engine()
        val base = state()
        val s = base.copy(
            run = base.run.copy(
                labQueue = listOf(
                    QueuedPiece(skull, listOf(0.9, 0.2, 0.9, 0.2)),
                    QueuedPiece(skull, listOf(0.2, 0.9, 0.2, 0.9)),
                ),
                labSlots = GameNumber(2.0),
            ),
        )
        val after = e.advance(s, 30.0, SeededRandom(SEED)).state
        assertEquals(skullZones.associate { SpareZoneKey(skull, it) to 0.9 }, after.run.spareZones)
        val result = success(e.reconstruct(after, skull))
        assertEquals(0.89, result.state.run.pieces.getValue(skull).quality, EPS)
        assertTrue(result.state.meta.book.getValue(skull).reconstructed)
    }

    private companion object {
        const val SEED = 20261105L
        const val EPS = 1e-9
    }
}

package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.QueuedPiece
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests fuer [FindClassifier] (Kapitel 4.4) mit den echten Daten: duplicateSellFactor 20,
 * labQueuePerSlot 3, Schichtwerte 1 / 8 / 60, Skill `curator_4` = `sell_price` +60 %.
 */
class FindClassifierTest {

    private val data: GameData = DataRepository().load()

    private fun n(v: Double) = GameNumber(v)

    private fun state(
        pieces: Map<PieceKey, Piece> = emptyMap(),
        book: Map<PieceKey, BookEntry> = emptyMap(),
        labSlots: Double = 1.0,
        queue: List<QueuedPiece> = emptyList(),
        skills: Set<String> = emptySet(),
        money: Double = 0.0,
    ): GameState {
        val modifiers = ModifierEngine.fromGameData(data)
        val base = GameState.newGame(data.layers.first().id, RunStart.values(data, modifiers), now = 0L)
        return base.copy(
            meta = base.meta.copy(book = book, skills = skills),
            run = base.run.copy(pieces = pieces, labSlots = n(labSlots), labQueue = queue, money = n(money)),
        )
    }

    private fun classifier(state: GameState): FindClassifier {
        val engine = ModifierEngine.fromGameData(data)
        ModifierSources.sync(engine, state, data)
        return FindClassifier(data, engine)
    }

    private fun find(key: PieceKey, vararg zones: Double) =
        Find(key, data.species(key.speciesId).layer, zones.toList())

    private fun classify(state: GameState, find: Find): Pair<GameState, List<GameEvent>> {
        val context = AdvanceContext(SeededRandom(SEED))
        val result = classifier(state).onFind(state, find, context)
        return result to context.events
    }

    // --- Neu, besser, schlechter ---

    @Test
    fun `neuer Fund kommt in Buch und Run-Bestand`() {
        val f = find(BEAR_SKULL, 0.4, 0.6, 0.5, 0.5)
        val (s, events) = classify(state(), f)
        assertEquals(Piece(listOf(0.4, 0.6, 0.5, 0.5)), s.run.pieces[BEAR_SKULL])
        assertEquals(BookEntry(0.5, reconstructed = false, zones = listOf(0.4, 0.6, 0.5, 0.5)), s.meta.book[BEAR_SKULL])
        assertTrue(s.run.labQueue.isEmpty())
        assertEquals(0.0, s.run.money.toDouble(), EPS)
        assertEquals(
            listOf(GameEvent.BookUpdated(BEAR_SKULL, 0.5, null), GameEvent.NewPiece(BEAR_SKULL, 0.5)),
            events,
        )
    }

    @Test
    fun `besserer Fund ersetzt Run-Fund, das alte Stueck wird Duplikat`() {
        val old = Piece(listOf(0.2, 0.2, 0.2, 0.2))
        val s0 = state(pieces = mapOf(BEAR_SKULL to old), book = mapOf(BEAR_SKULL to BookEntry(0.2)))
        val (s, events) = classify(s0, find(BEAR_SKULL, 0.8, 0.8, 0.8, 0.8))
        assertEquals(0.8, s.run.pieces.getValue(BEAR_SKULL).quality, EPS)
        assertEquals(0.8, s.meta.book.getValue(BEAR_SKULL).quality, EPS)
        assertEquals(listOf(QueuedPiece(BEAR_SKULL, old.zones)), s.run.labQueue)
        assertEquals(
            listOf(
                GameEvent.BookUpdated(BEAR_SKULL, 0.8, 0.2),
                GameEvent.PieceImproved(BEAR_SKULL, 0.8, 0.2),
                GameEvent.DuplicateQueued(BEAR_SKULL, 0.2),
            ),
            events,
        )
    }

    @Test
    fun `schlechterer Fund ist Duplikat und aendert weder Buch noch Run`() {
        val best = Piece(listOf(0.9, 0.9, 0.9, 0.9))
        val s0 = state(pieces = mapOf(BEAR_SKULL to best), book = mapOf(BEAR_SKULL to BookEntry(0.9)))
        val (s, events) = classify(s0, find(BEAR_SKULL, 0.3, 0.3, 0.3, 0.3))
        assertEquals(best, s.run.pieces[BEAR_SKULL])
        assertEquals(BookEntry(0.9), s.meta.book[BEAR_SKULL])
        assertEquals(listOf(QueuedPiece(BEAR_SKULL, listOf(0.3, 0.3, 0.3, 0.3))), s.run.labQueue)
        assertEquals(listOf(GameEvent.DuplicateQueued(BEAR_SKULL, 0.3)), events)
    }

    @Test
    fun `gleiche Qualitaet gilt nicht als besser`() {
        val best = Piece(listOf(0.5, 0.5, 0.5, 0.5))
        val s0 = state(pieces = mapOf(BEAR_SKULL to best), book = mapOf(BEAR_SKULL to BookEntry(0.5)))
        val (s, events) = classify(s0, find(BEAR_SKULL, 0.6, 0.4, 0.5, 0.5))
        assertEquals(best, s.run.pieces[BEAR_SKULL])
        assertEquals(BookEntry(0.5), s.meta.book[BEAR_SKULL])
        assertEquals(listOf(GameEvent.DuplicateQueued(BEAR_SKULL, 0.5)), events)
    }

    @Test
    fun `Buch und Run werden getrennt bewertet - Buch besser als Run`() {
        // Buch hat aus einem frueheren Durchlauf 0.95, Run-Bestand 0.3: Run wird ersetzt, Buch nicht.
        val s0 = state(pieces = mapOf(BEAR_SKULL to Piece(listOf(0.3, 0.3, 0.3, 0.3))), book = mapOf(BEAR_SKULL to BookEntry(0.95)))
        val (s, events) = classify(s0, find(BEAR_SKULL, 0.6, 0.6, 0.6, 0.6))
        assertEquals(0.6, s.run.pieces.getValue(BEAR_SKULL).quality, EPS)
        assertEquals(BookEntry(0.95), s.meta.book[BEAR_SKULL])
        assertTrue(events.none { it is GameEvent.BookUpdated })
        assertTrue(events.any { it is GameEvent.PieceImproved })
    }

    @Test
    fun `Buch und Run werden getrennt bewertet - neuer Durchlauf ohne Run-Fund`() {
        // Nach dem Prestige: Buch 0.95, Run leer -> Fund wird Run-Fund, Buch bleibt.
        val s0 = state(book = mapOf(BEAR_SKULL to BookEntry(0.95)))
        val (s, events) = classify(s0, find(BEAR_SKULL, 0.4, 0.4, 0.4, 0.4))
        assertEquals(0.4, s.run.pieces.getValue(BEAR_SKULL).quality, EPS)
        assertEquals(0.95, s.meta.book.getValue(BEAR_SKULL).quality, EPS)
        assertEquals(listOf(GameEvent.NewPiece(BEAR_SKULL, 0.4)), events)
    }

    @Test
    fun `Buch wird verbessert, auch wenn der Run-Fund besser ist`() {
        val s0 = state(pieces = mapOf(BEAR_SKULL to Piece(listOf(0.9, 0.9, 0.9, 0.9))), book = mapOf(BEAR_SKULL to BookEntry(0.1)))
        val (s, events) = classify(s0, find(BEAR_SKULL, 0.5, 0.5, 0.5, 0.5))
        assertEquals(0.5, s.meta.book.getValue(BEAR_SKULL).quality, EPS)
        assertEquals(listOf(0.5, 0.5, 0.5, 0.5), s.meta.book.getValue(BEAR_SKULL).zones)
        assertEquals(0.9, s.run.pieces.getValue(BEAR_SKULL).quality, EPS)
        assertEquals(
            listOf(GameEvent.BookUpdated(BEAR_SKULL, 0.5, 0.1), GameEvent.DuplicateQueued(BEAR_SKULL, 0.5)),
            events,
        )
    }

    @Test
    fun `Buch ersetzt rekonstruierten Eintrag und setzt das Flag zurueck`() {
        val s0 = state(book = mapOf(BEAR_SKULL to BookEntry(0.6, reconstructed = true)))
        val (s, _) = classify(s0, find(BEAR_SKULL, 0.7, 0.7, 0.7, 0.7))
        assertFalse(s.meta.book.getValue(BEAR_SKULL).reconstructed)
    }

    // --- Laborwarteschlange und Verkauf ---

    @Test
    fun `Duplikate fuellen die Warteschlange bis labSlots mal labQueuePerSlot, danach Verkauf`() {
        val best = Piece(listOf(1.0, 1.0, 1.0, 1.0))
        var s = state(pieces = mapOf(BEAR_SKULL to best), labSlots = 2.0)
        val classifier = classifier(s)
        assertEquals(6.0, classifier.labQueueMax(s.run), EPS)
        val context = AdvanceContext(SeededRandom(SEED))
        repeat(8) { s = classifier.onFind(s, find(BEAR_SKULL, 0.5, 0.5, 0.5, 0.5), context) }
        assertEquals(6, s.run.labQueue.size)
        assertEquals(6, context.events.count { it is GameEvent.DuplicateQueued })
        val sold = context.events.filterIsInstance<GameEvent.DuplicateSold>()
        assertEquals(2, sold.size)
        // Quartaer: value 1 * (0.5 + 0.5) * 20 = 20 je Stueck
        assertEquals(20.0, sold.first().money.toDouble(), EPS)
        assertEquals(40.0, s.run.money.toDouble(), EPS)
    }

    @Test
    fun `volle Warteschlange - Verkaufspreis nach Formel 4_4`() {
        val queue = List(3) { QueuedPiece(TRIC_SKULL, listOf(0.0, 0.0, 0.0, 0.0)) }
        val s0 = state(
            pieces = mapOf(TRIC_SKULL to Piece(listOf(0.9, 0.9, 0.9, 0.9))),
            book = mapOf(TRIC_SKULL to BookEntry(0.9)),
            queue = queue,
            money = 5.0,
        )
        val (s, events) = classify(s0, find(TRIC_SKULL, 0.2, 0.4, 0.2, 0.4))
        // Kreide: value 60 * (0.5 + 0.3) * 20 = 960
        val expected = 60.0 * 0.8 * 20.0
        assertEquals(queue, s.run.labQueue)
        assertEquals(5.0 + expected, s.run.money.toDouble(), EPS)
        val sold = events.single() as GameEvent.DuplicateSold
        assertEquals(TRIC_SKULL, sold.pieceKey)
        assertEquals(0.3, sold.quality, EPS)
        assertEquals(expected, sold.money.toDouble(), EPS)
    }

    @Test
    fun `ersetztes Stueck wird bei voller Warteschlange zu seinem Wert verkauft`() {
        val queue = List(3) { QueuedPiece(BEAR_SKULL, listOf(0.0, 0.0, 0.0, 0.0)) }
        val s0 = state(pieces = mapOf(HIPP_JAW to Piece(listOf(0.1, 0.1, 0.1, 0.1))), queue = queue)
        val (s, events) = classify(s0, find(HIPP_JAW, 0.9, 0.9, 0.9, 0.9))
        assertEquals(0.9, s.run.pieces.getValue(HIPP_JAW).quality, EPS)
        // Neogen: value 8 * (0.5 + 0.1) * 20 = 96
        assertEquals(96.0, (events.last() as GameEvent.DuplicateSold).money.toDouble(), EPS)
        assertEquals(96.0, s.run.money.toDouble(), EPS)
    }

    @Test
    fun `Skill curator_4 erhoeht den Verkaufspreis um 60 Prozent`() {
        val queue = List(3) { QueuedPiece(BEAR_SKULL, listOf(0.0, 0.0, 0.0, 0.0)) }
        val skills = setOf("curator_1", "curator_2", "curator_3", "curator_4")
        val s0 = state(pieces = mapOf(BEAR_SKULL to Piece(listOf(1.0, 1.0, 1.0, 1.0))), queue = queue, skills = skills)
        val (s, _) = classify(s0, find(BEAR_SKULL, 0.5, 0.5, 0.5, 0.5))
        // 1 * (0.5 + 0.5) * 20 * 1.6 = 32
        assertEquals(32.0, s.run.money.toDouble(), EPS)
        assertEquals(32.0, classifier(s0).sellValue(BEAR_SKULL, 0.5).toDouble(), EPS)
    }

    @Test
    fun `ohne Laborplatz wird jedes Duplikat verkauft`() {
        val s0 = state(
            pieces = mapOf(BEAR_SKULL to Piece(listOf(1.0, 1.0, 1.0, 1.0))),
            book = mapOf(BEAR_SKULL to BookEntry(1.0)),
            labSlots = 0.0,
        )
        val (s, events) = classify(s0, find(BEAR_SKULL, 0.5, 0.5, 0.5, 0.5))
        assertTrue(s.run.labQueue.isEmpty())
        assertTrue(events.single() is GameEvent.DuplicateSold)
    }

    // --- Skelett ---

    @Test
    fun `letzter fehlender Knochen meldet SkeletonCompleted genau einmal`() {
        val bear = data.species("cave_bear")
        val pieces = bear.bones.drop(1).associate { PieceKey(bear.id, it) to Piece(listOf(0.5, 0.5, 0.5, 0.5)) }
        val missing = PieceKey(bear.id, bear.bones.first())
        val (s, events) = classify(state(pieces = pieces), find(missing, 0.5, 0.5, 0.5, 0.5))
        assertTrue(Skeletons.isCompleteInRun(s.run, bear))
        assertEquals(GameEvent.SkeletonCompleted(bear.id), events.last())
        assertTrue(events.indexOf(GameEvent.NewPiece(missing, 0.5)) < events.lastIndex)

        // Verbesserung eines Knochens meldet kein weiteres SkeletonCompleted
        val (_, again) = classify(s, find(missing, 0.9, 0.9, 0.9, 0.9))
        assertTrue(again.none { it is GameEvent.SkeletonCompleted })
    }

    @Test
    fun `Eingabezustand bleibt unveraendert`() {
        val s0 = state(pieces = mapOf(BEAR_SKULL to Piece(listOf(0.2, 0.2, 0.2, 0.2))))
        val copy = s0.copy()
        classify(s0, find(BEAR_SKULL, 0.8, 0.8, 0.8, 0.8))
        assertEquals(copy, s0)
        assertNull(s0.meta.book[BEAR_SKULL])
    }

    private companion object {
        const val SEED = 20261105L
        const val EPS = 1e-9
        val BEAR_SKULL = PieceKey("cave_bear", "skull")
        val TRIC_SKULL = PieceKey("triceratops", "skull")
        val HIPP_JAW = PieceKey("hipparion", "jaw")
    }
}

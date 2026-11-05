package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import de.noelkoeppen.fossilcollector.core.modifier.ModifierCategory
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests fuer [MuseumSystem] und [Skeletons] (Kapitel 4.6, 4.7) mit den echten Daten:
 * slotsPerHall 8, skeletonMult 2.0, Schichtwerte 1 / 8 / 60, Skills `curator_1` (museum_income
 * +10 %), `curator_3` (skeleton_bonus +0.5), `curator_5` (special_exhibit_mult 5).
 */
class MuseumSystemTest {

    private val data: GameData = DataRepository().load()

    private fun n(v: Double) = GameNumber(v)

    private val bear get() = data.species("cave_bear")

    private fun state(
        pieces: Map<PieceKey, Piece> = emptyMap(),
        halls: Double = 1.0,
        skills: Set<String> = emptySet(),
        specialExhibit: String? = null,
    ): GameState {
        val modifiers = ModifierEngine.fromGameData(data)
        val base = GameState.newGame(data.layers.first().id, RunStart.values(data, modifiers), now = 0L)
        return base.copy(
            meta = base.meta.copy(skills = skills),
            run = base.run.copy(pieces = pieces, halls = n(halls), specialExhibit = specialExhibit),
        )
    }

    private fun modifiers(state: GameState, extra: List<Modifier> = emptyList()): ModifierEngine {
        val engine = ModifierEngine.fromGameData(data)
        ModifierSources.sync(engine, state, data)
        if (extra.isNotEmpty()) engine.setSource("test:extra", extra)
        return engine
    }

    private fun museum(state: GameState, extra: List<Modifier> = emptyList()) = MuseumSystem(data, modifiers(state, extra))

    private fun piece(q: Double) = Piece(listOf(q, q, q, q))

    /** Vollstaendiges Hoehlenbaer-Skelett mit Qualitaet [q]. */
    private fun bearSkeleton(q: Double = 0.5): Map<PieceKey, Piece> =
        bear.bones.associate { PieceKey(bear.id, it) to piece(q) }

    /** Quartaer-Schluessel ohne vollstaendiges Skelett (je Art fehlt der erste Knochen). */
    private fun quaternaryKeys(): List<PieceKey> =
        data.species.filter { it.layer == "quaternary" }.flatMap { sp -> sp.bones.drop(1).map { PieceKey(sp.id, it) } }

    private fun income(state: GameState, extra: List<Modifier> = emptyList()) =
        museum(state, extra).incomePerSecond(state).toDouble()

    // --- Einkommen ---

    @Test
    fun `ohne Stuecke kein Einkommen und keine Aenderung`() {
        val s = state()
        assertEquals(0.0, income(s), EPS)
        assertSame(s, museum(s).advance(s, 1.0, AdvanceContext(SeededRandom(SEED))))
    }

    @Test
    fun `Einkommen je Stueck ist layer value mal 0_5 plus q`() {
        val s = state(
            pieces = mapOf(
                PieceKey("cave_bear", "skull") to piece(0.5), // 1 * 1.0 = 1
                PieceKey("hipparion", "jaw") to Piece(listOf(0.2, 0.4, 0.6, 0.8)), // 8 * 1.0 = 8
                PieceKey("triceratops", "ribs") to piece(0.9), // 60 * 1.4 = 84
            ),
        )
        assertEquals(93.0, income(s), EPS)
    }

    @Test
    fun `advance schreibt Einkommen mal dt gut`() {
        val s = state(pieces = mapOf(PieceKey("triceratops", "ribs") to piece(0.9)))
        val after = museum(s).advance(s, 0.1, AdvanceContext(SeededRandom(SEED)))
        assertEquals(8.4, after.run.money.toDouble(), EPS)
    }

    @Test
    fun `museum_income wirkt auf die Summe (curator_1)`() {
        val s = state(pieces = mapOf(PieceKey("cave_bear", "skull") to piece(0.5)), skills = setOf("curator_1"))
        assertEquals(1.1, income(s), EPS)
    }

    // --- Ausstellungsplaetze ---

    @Test
    fun `Plaetze begrenzen auf die wertvollsten Stuecke`() {
        // 9 Quartaer-Stuecke mit Qualitaet 0.1 .. 0.9, eine Halle = 8 Plaetze -> das schlechteste fehlt
        val pieces = quaternaryKeys()
            .take(9)
            .mapIndexed { i, key -> key to piece((i + 1) / 10.0) }
            .toMap()
        val s = state(pieces = pieces)
        val m = museum(s)
        assertEquals(8.0, m.slots(s.run), EPS)
        val exhibited = m.exhibitedPieces(s)
        assertEquals(8, exhibited.size)
        val worst = pieces.minByOrNull { it.value.quality }!!.key
        assertFalse(exhibited.any { it.pieceKey == worst })
        // wertvollstes zuerst
        assertEquals(exhibited.sortedByDescending { it.incomePerSecond.toDouble() }, exhibited)
        // Summe (0.2 .. 0.9) + 8 * 0.5
        val expected = (2..9).sumOf { it / 10.0 } + 8 * 0.5
        assertEquals(expected, income(s), EPS)
    }

    @Test
    fun `hoeherer Schichtwert schlaegt hoehere Qualitaet`() {
        val pieces = quaternaryKeys()
            .take(8).associateWith { piece(1.0) } +
            (PieceKey("hipparion", "skull") to piece(0.0))
        val s = state(pieces = pieces)
        val exhibited = museum(s).exhibitedPieces(s)
        assertEquals(PieceKey("hipparion", "skull"), exhibited.first().pieceKey)
        // 8 * 0.5 (Hipparion) + 7 * 1.5
        assertEquals(4.0 + 7 * 1.5, income(s), EPS)
    }

    @Test
    fun `weitere Halle gibt 8 weitere Plaetze`() {
        val pieces = quaternaryKeys()
            .take(20).associateWith { piece(0.5) }
        assertEquals(8.0, income(state(pieces = pieces, halls = 1.0)), EPS)
        assertEquals(16.0, income(state(pieces = pieces, halls = 2.0)), EPS)
        assertEquals(20.0, income(state(pieces = pieces, halls = 3.0)), EPS)
        assertEquals(0.0, income(state(pieces = pieces, halls = 0.0)), EPS)
    }

    // --- Skelett-Bonus ---

    @Test
    fun `vollstaendiges Skelett verdoppelt das Einkommen der Art`() {
        val complete = state(pieces = bearSkeleton())
        assertEquals(8 * 1.0 * 2.0, income(complete), EPS)
        // ein Knochen fehlt: kein Bonus
        val incomplete = state(pieces = bearSkeleton().minus(PieceKey(bear.id, bear.bones.first())))
        assertEquals(7 * 1.0, income(incomplete), EPS)
        assertTrue(museum(complete).exhibitedPieces(complete).all { it.skeletonComplete })
    }

    @Test
    fun `Skelett-Bonus beeinflusst die Auswahl der Ausstellung`() {
        // 8 Baer-Knochen (q 0.5, mit Bonus je 2.0) gegen ein Riesenhirsch-Stueck q 1.0 (1.5)
        val s = state(pieces = bearSkeleton() + (PieceKey("giant_deer", "skull") to piece(1.0)))
        val exhibited = museum(s).exhibitedPieces(s)
        assertTrue(exhibited.all { it.pieceKey.speciesId == bear.id })
        assertEquals(16.0, income(s), EPS)
    }

    @Test
    fun `Skill curator_3 erhoeht den Skelett-Faktor um 0_5`() {
        val s = state(pieces = bearSkeleton(), skills = setOf("curator_1", "curator_2", "curator_3"))
        // 8 * 1.0 * (2.0 + 0.5) * 1.1
        assertEquals(22.0, income(s), EPS)
        assertEquals(2.5, museum(s).skeletonFactor(), EPS)
    }

    @Test
    fun `skeleton_bonus aus beliebiger Quelle ist additiv auf den Faktor`() {
        val s = state(pieces = bearSkeleton())
        val extra = listOf(Modifier(StatId.SKELETON_BONUS, ModifierOp.ADD, 0.02, ModifierCategory.BOOK))
        assertEquals(8 * 2.02, income(s, extra), EPS)
    }

    @Test
    fun `Skelett-Erkennung ignoriert Alter und Geschlecht`() {
        val pieces = bear.bones.mapIndexed { i, bone ->
            val key = if (i % 2 == 0) PieceKey(bear.id, bone, "adult", "female") else PieceKey(bear.id, bone)
            key to piece(0.5)
        }.toMap()
        val s = state(pieces = pieces)
        assertTrue(Skeletons.isCompleteInRun(s.run, bear))
        assertEquals(setOf(bear.id), Skeletons.completeSpeciesInRun(s.run, data))
        assertEquals(16.0, income(s), EPS)
    }

    @Test
    fun `Skelett im Buch fuer Completion`() {
        val book = bear.bones.associate { PieceKey(bear.id, it) to BookEntry(0.3) }
        assertTrue(Skeletons.isCompleteInBook(book, bear))
        assertEquals(setOf(bear.id), Skeletons.completeSpeciesInBook(book, data))
        val partial = book - PieceKey(bear.id, "jaw")
        assertFalse(Skeletons.isCompleteInBook(partial, bear))
        assertEquals(listOf("jaw"), Skeletons.missingBones(partial.keys, bear))
        assertTrue(Skeletons.completeSpeciesInBook(partial, data).isEmpty())
    }

    // --- Sonderausstellung ---

    private val allCurator = setOf("curator_1", "curator_2", "curator_3", "curator_4", "curator_5")

    @Test
    fun `Sonderausstellung multipliziert das gewaehlte vollstaendige Skelett`() {
        val s = state(pieces = bearSkeleton(), skills = allCurator, specialExhibit = bear.id)
        val m = museum(s)
        assertTrue(m.isSpecialExhibitActive(s))
        // 8 * 1.0 * 2.5 * 5 * 1.1
        assertEquals(110.0, m.incomePerSecond(s).toDouble(), EPS)
        assertTrue(m.exhibitedPieces(s).all { it.specialExhibit })
    }

    @Test
    fun `Sonderausstellung wirkt nur auf die gewaehlte Art`() {
        val deer = data.species("giant_deer")
        val deerSkeleton = deer.bones.associate { PieceKey(deer.id, it) to piece(0.5) }
        val s = state(pieces = bearSkeleton() + deerSkeleton, halls = 2.0, skills = allCurator, specialExhibit = bear.id)
        // Baer: 8 * 2.5 * 5, Hirsch: 8 * 2.5, alles * 1.1
        assertEquals((100.0 + 20.0) * 1.1, income(s), EPS)
    }

    @Test
    fun `Sonderausstellung ohne Capstone oder mit unvollstaendigem Skelett wirkt nicht`() {
        val withoutCapstone = state(pieces = bearSkeleton(), specialExhibit = bear.id)
        assertFalse(museum(withoutCapstone).isSpecialExhibitActive(withoutCapstone))
        assertEquals(16.0, income(withoutCapstone), EPS)

        val incomplete = state(
            pieces = bearSkeleton() - PieceKey(bear.id, "jaw"),
            skills = allCurator,
            specialExhibit = bear.id,
        )
        assertFalse(museum(incomplete).isSpecialExhibitActive(incomplete))
        assertEquals(7 * 1.1, income(incomplete), EPS)

        val unknown = state(pieces = bearSkeleton(), skills = allCurator, specialExhibit = "unknown_species")
        assertFalse(museum(unknown).isSpecialExhibitActive(unknown))
        assertEquals(22.0, income(unknown), EPS)
    }

    // --- Cache ---

    @Test
    fun `Cache erkennt geaenderte Stuecke, Hallen, Auswahl und Modifier`() {
        val s1 = state(pieces = bearSkeleton(), skills = allCurator)
        val engine = modifiers(s1)
        val m = MuseumSystem(data, engine)
        assertEquals(22.0, m.incomePerSecond(s1).toDouble(), EPS)
        assertEquals(22.0, m.incomePerSecond(s1).toDouble(), EPS)

        val s2 = s1.copy(run = s1.run.copy(specialExhibit = bear.id))
        assertEquals(110.0, m.incomePerSecond(s2).toDouble(), EPS)

        val s3 = s2.copy(run = s2.run.copy(pieces = s2.run.pieces - PieceKey(bear.id, "jaw")))
        assertEquals(7 * 1.1, m.incomePerSecond(s3).toDouble(), EPS)

        val s4 = s1.copy(run = s1.run.copy(halls = n(0.0)))
        assertEquals(0.0, m.incomePerSecond(s4).toDouble(), EPS)

        // Skill-Respec: Modifier aendern sich, gleicher Zustand liefert neuen Wert
        val s5 = s1.copy(meta = s1.meta.copy(skills = emptySet()))
        ModifierSources.sync(engine, s5, data)
        assertEquals(16.0, m.incomePerSecond(s5).toDouble(), EPS)
    }

    // --- Integration ---

    @Test
    fun `GameEngine - Funde werden eingeordnet und bringen Museumseinnahmen`() {
        val modifiers = ModifierEngine.fromGameData(data)
        val engine = GameEngine(data, modifiers)
        val base = state()
        var s = base.copy(run = base.run.copy(teams = mapOf("quaternary" to n(10.0)), preparators = n(5.0)))
        val rng = SeededRandom(SEED)
        var rockMoney = 0.0
        var soldMoney = 0.0
        var museumMoney = 0.0
        var milestoneMoney = 0.0
        var newPieces = 0
        repeat(6000) {
            val incomeBefore = engine.incomePerSecond(s).toDouble()
            val result = engine.advance(s, 0.1, rng)
            rockMoney += result.events.filterIsInstance<GameEvent.BlocksPrepared>().sumOf { it.rockMoney.toDouble() }
            soldMoney += result.events.filterIsInstance<GameEvent.DuplicateSold>().sumOf { it.money.toDouble() }
            newPieces += result.events.count { it is GameEvent.NewPiece }
            milestoneMoney += result.events.filterIsInstance<GameEvent.MilestoneReached>().sumOf { it.money.toDouble() }
            s = result.state
            // Museum laeuft nach der Praeparation und sieht den neuen Bestand dieses Ticks
            museumMoney += engine.incomePerSecond(s).toDouble() * 0.1
            assertTrue(engine.incomePerSecond(s).toDouble() >= incomeBefore - EPS)
        }
        // 600 s * 1 Block/s * 8 % = etwa 48 Fossilien
        assertTrue(s.run.pieces.isNotEmpty())
        assertEquals(newPieces, s.run.pieces.size)
        assertEquals(s.run.pieces.keys, s.meta.book.keys)
        assertTrue(museumMoney > 0.0)
        assertTrue(milestoneMoney > 0.0) // AP-11: mindestens first_find und teams_10
        assertEquals(rockMoney + soldMoney + museumMoney + milestoneMoney, s.run.money.toDouble(), 1e-6)
        assertEquals(engine.museum.incomePerSecond(s), engine.incomePerSecond(s))
        assertTrue(engine.exhibitedPieces(s).size <= 8)
    }

    @Test
    fun `GameEngine - gleicher Seed ergibt gleiches Ergebnis`() {
        fun run(): GameState {
            val engine = GameEngine(data, ModifierEngine.fromGameData(data))
            val base = state()
            var s = base.copy(run = base.run.copy(teams = mapOf("quaternary" to n(20.0)), preparators = n(10.0)))
            val rng = SeededRandom(SEED)
            repeat(300) { s = engine.advance(s, 1.0, rng).state }
            return s
        }
        assertEquals(run(), run())
    }

    private companion object {
        const val SEED = 20261105L
        const val EPS = 1e-9
    }
}

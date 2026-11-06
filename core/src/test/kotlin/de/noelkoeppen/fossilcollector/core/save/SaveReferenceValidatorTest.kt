package de.noelkoeppen.fossilcollector.core.save

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.RunStart
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Tests fuer [SaveReferenceValidator] (AP-24, Import). */
class SaveReferenceValidatorTest {

    private val data: GameData = DataRepository().load()
    private val first = data.layers.first().id
    private val skull = PieceKey("cave_bear", "skull")
    private val unknownSpecies = PieceKey("unicorn", "skull")

    private val base: GameState =
        GameState.newGame(first, RunStart.values(data, ModifierEngine.fromGameData(data)), 0L)

    /** Ein voller, gueltiger Stand mit allen Referenzarten. */
    private val full: GameState = base.copy(
        meta = base.meta.copy(
            skills = setOf(data.skills.first().id),
            researchedSpecies = setOf("cave_bear"),
            milestonesDone = setOf(data.milestones.first().id),
            book = mapOf(skull to BookEntry(0.8), PieceKey("cave_bear", "jaw", "adult", "female") to BookEntry(0.5)),
        ),
        run = base.run.copy(
            unlockedLayers = GameNumber(data.layers.size.toDouble()),
            teams = data.layers.associate { it.id to GameNumber.ONE },
            blocks = data.layers.associate { it.id to GameNumber.ZERO },
            pieces = mapOf(skull to Piece(listOf(0.5, 0.5, 0.5, 0.5))),
            spareZones = mapOf(SpareZoneKey(skull, data.bone("skull").zones.first()) to 0.4),
            labQueue = listOf(QueuedPiece(skull, listOf(0.1, 0.1, 0.1, 0.1))),
            labJobs = listOf(LabJob(skull, listOf(0.1, 0.1, 0.1, 0.1), 3.0)),
            specialExhibit = "cave_bear",
        ),
    )

    @Test
    fun `neues Spiel und voller Stand sind gueltig`() {
        assertNull(SaveReferenceValidator.findUnknownReference(base, data))
        assertNull(SaveReferenceValidator.findUnknownReference(full, data))
    }

    @Test
    fun `unbekannte Meta-Referenzen werden gemeldet`() {
        assertEquals("meta.skills: nope", check(full.copy(meta = full.meta.copy(skills = setOf("nope")))))
        assertEquals(
            "meta.researchedSpecies: unicorn",
            check(full.copy(meta = full.meta.copy(researchedSpecies = setOf("unicorn")))),
        )
        assertEquals(
            "meta.milestonesDone: nope",
            check(full.copy(meta = full.meta.copy(milestonesDone = setOf("nope")))),
        )
        assertEquals(
            "meta.book: unicorn:skull:_:_",
            check(full.copy(meta = full.meta.copy(book = mapOf(unknownSpecies to BookEntry(0.5))))),
        )
    }

    @Test
    fun `unbekannter Knochen wird gemeldet`() {
        val unknownBone = PieceKey("cave_bear", "tail_fin")
        assertEquals(
            "run.pieces: $unknownBone",
            check(full.copy(run = full.run.copy(pieces = mapOf(unknownBone to Piece(listOf(0.5)))))),
        )
    }

    @Test
    fun `unbekannte Run-Referenzen werden gemeldet`() {
        assertEquals("run.teams: mars", check(full.copy(run = full.run.copy(teams = mapOf("mars" to GameNumber.ONE)))))
        assertEquals("run.blocks: mars", check(full.copy(run = full.run.copy(blocks = mapOf("mars" to GameNumber.ONE)))))
        assertEquals(
            "run.spareZones: cave_bear:skull:_:_:wing",
            check(full.copy(run = full.run.copy(spareZones = mapOf(SpareZoneKey(skull, "wing") to 0.4)))),
        )
        assertEquals(
            "run.labQueue: unicorn:skull:_:_",
            check(full.copy(run = full.run.copy(labQueue = listOf(QueuedPiece(unknownSpecies, listOf(0.1)))))),
        )
        assertEquals(
            "run.labJobs: unicorn:skull:_:_",
            check(full.copy(run = full.run.copy(labJobs = listOf(LabJob(unknownSpecies, listOf(0.1), 1.0))))),
        )
        assertEquals("run.specialExhibit: unicorn", check(full.copy(run = full.run.copy(specialExhibit = "unicorn"))))
    }

    @Test
    fun `Anzahl offener Schichten muss zu den Daten passen`() {
        val tooMany = (data.layers.size + 1).toDouble()
        assertEquals(
            "run.unlockedLayers: $tooMany",
            check(full.copy(run = full.run.copy(unlockedLayers = GameNumber(tooMany)))),
        )
        assertEquals("run.unlockedLayers: 0.0", check(full.copy(run = full.run.copy(unlockedLayers = GameNumber.ZERO))))
    }

    private fun check(state: GameState): String? = SaveReferenceValidator.findUnknownReference(state, data)
}

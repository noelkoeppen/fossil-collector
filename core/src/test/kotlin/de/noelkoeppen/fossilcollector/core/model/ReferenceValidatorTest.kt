package de.noelkoeppen.fossilcollector.core.model

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.save.SaveCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReferenceValidatorTest {

    private val data: GameData = DataRepository().load()

    private fun piece(bone: String, species: String = "cave_bear") = Piece(species, bone, 0.5)

    private fun fixture(path: String): String =
        checkNotNull(javaClass.getResource("/save/$path")) { "Fixture fehlt: $path" }.readText()

    @Test
    fun validStateHasNoErrors() {
        val state = SaveCodec.DEFAULT.decode(fixture("v3_full.json"))
        assertEquals(emptyList<String>(), ReferenceValidator.validate(state, data))
        assertEquals(emptyList<String>(), ReferenceValidator.validate(GameState.newGame(0L), data))
    }

    @Test
    fun unknownSpeciesAndBonesAreReported() {
        val state = SaveCodec.DEFAULT.decode(fixture("errors/unknown_reference.json"))
        val errors = ReferenceValidator.validate(state, data)
        assertEquals(2, errors.size, errors.toString())
        assertTrue(errors[0].startsWith("inventory[1]") && "wing_l" in errors[0], errors.toString())
        assertTrue(errors[1].startsWith("inventory[2]") && "dodo" in errors[1], errors.toString())
    }

    @Test
    fun connectedFindIsValid() {
        val find = Find("cave_bear", listOf(piece("femur_l"), piece("tibia_l"), piece("hindpaw_l"), piece("hip_l")))
        assertEquals(emptyList<String>(), ReferenceValidator.validateFind(find, data))
        assertEquals(emptyList<String>(), ReferenceValidator.validateFind(Find("cave_bear", listOf(piece("sternum"))), data))
    }

    @Test
    fun disconnectedFindIsReported() {
        val find = Find("cave_bear", listOf(piece("femur_l"), piece("hindpaw_l")))
        val errors = ReferenceValidator.validateFind(find, data)
        assertEquals(1, errors.size, errors.toString())
        assertTrue("nicht zusammen" in errors.single())
    }

    @Test
    fun findWithUnknownBoneReportsReferenceError() {
        val find = Find("cave_bear", listOf(piece("femur_l"), piece("wing_l")))
        assertEquals(listOf("unbekannter Knochen 'wing_l' fuer Art 'cave_bear'"), ReferenceValidator.validateFind(find, data))
        val other = Find("dodo", listOf(piece("femur_l", species = "dodo")))
        assertEquals(listOf("unbekannte Art 'dodo'"), ReferenceValidator.validateFind(other, data))
    }
}

package de.noelkoeppen.fossilcollector.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PieceFindTest {

    @Test
    fun pieceAcceptsWholeQualityScale() {
        listOf(Piece.MIN_QUALITY, 0.5, Piece.MAX_QUALITY).forEach { Piece("cave_bear", "femur_l", it) }
    }

    @Test
    fun pieceRejectsQualityOutsideScaleAndNaN() {
        for (quality in listOf(-0.001, 1.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertThrows<IllegalArgumentException>("Qualitaet $quality") { Piece("cave_bear", "femur_l", quality) }
        }
    }

    @Test
    fun ageAndSexAreOptional() {
        val piece = Piece("cave_bear", "femur_l", 0.5)
        assertNull(piece.age)
        assertNull(piece.sex)
        assertEquals("adult", piece.copy(age = "adult").age)
    }

    @Test
    fun findHoldsPiecesOfOneSpecies() {
        val find = Find("cave_bear", listOf(Piece("cave_bear", "femur_l", 0.5), Piece("cave_bear", "tibia_l", 0.4)))
        assertEquals(listOf("femur_l", "tibia_l"), find.boneIds)
    }

    @Test
    fun findRejectsEmptyMixedOrDuplicatePieces() {
        assertThrows<IllegalArgumentException> { Find("cave_bear", emptyList()) }
        assertThrows<IllegalArgumentException> {
            Find("cave_bear", listOf(Piece("cave_bear", "femur_l", 0.5), Piece("giant_deer", "tibia_l", 0.5)))
        }
        assertThrows<IllegalArgumentException> {
            Find("cave_bear", listOf(Piece("cave_bear", "femur_l", 0.5), Piece("cave_bear", "femur_l", 0.6)))
        }
    }
}

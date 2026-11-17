package de.noelkoeppen.fossilcollector.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Modell der Nahansicht (AP2-08): Struktur, Fortschritt, kompakte Kodierung, Einbindung in die Flaeche. */
class BonePreparationTest {

    private fun prep(
        depths: IntArray = intArrayOf(2, 0, 5, 0, 1, 3),
        hard: BooleanArray = booleanArrayOf(false, true, true, false, false, false),
        bone: BooleanArray = booleanArrayOf(true, true, true, false, false, false),
        boneId: String = "femur_l",
    ) = BonePreparation(0, boneId, -1, -1, 4, 3, 2, depths, hard, bone)

    @Test
    fun progressCountsExposedBoneCellsOnly() {
        val p = prep()
        assertEquals(3, p.boneCellCount)
        assertEquals(1, p.exposedBoneCellCount)
        assertEquals(1.0 / 3, p.progress, 1e-12)
    }

    @Test
    fun hardFlagOnlyWithResidue() {
        val p = prep()
        assertFalse(p.isHard(1), "Zelle ohne Restschicht ist nicht hart")
        assertTrue(p.isHard(2))
    }

    @Test
    fun structureIsChecked() {
        assertThrows<IllegalArgumentException> { prep(depths = intArrayOf(1, 2, 3)) }
        assertThrows<IllegalArgumentException> { prep(depths = intArrayOf(2, 0, 200, 0, 1, 3)) }
        assertThrows<IllegalArgumentException> { prep(bone = BooleanArray(6)) }
        assertThrows<IllegalArgumentException> { BonePreparation(0, "femur_l", 0, 0, 0, 3, 2, IntArray(6), BooleanArray(6), BooleanArray(6) { true }) }
    }

    @Test
    fun residueAndBitsRoundTrip() {
        val depths = IntArray(37) { it % (BonePreparation.MAX_DEPTH + 1) }
        val hard = BooleanArray(37) { it % 3 == 0 }
        val (d, h) = BonePreparation.decodeResidue(BonePreparation.encodeResidue(depths, hard))
        assertTrue(depths.contentEquals(d))
        assertTrue(hard.contentEquals(h))
        val bits = BooleanArray(37) { it % 5 == 1 }
        assertTrue(bits.contentEquals(BonePreparation.decodeBits(BonePreparation.encodeBits(bits), 37)))
    }

    @Test
    fun siteAcceptsOnlyKnownBonesOncePerBone() {
        val find = Find("cave_bear", listOf(Piece("cave_bear", "femur_l", 0.5)))
        val placed = PlacedFind(find, mapOf("femur_l" to listOf(0)))
        val site = ExcavationSite("quaternary", 2, 2, IntArray(4), listOf(placed), listOf(prep()))
        assertEquals(prep(), site.preparation(BoneRef(0, "femur_l")))
        assertFalse(site.isPrepared(BoneRef(0, "femur_l")))
        assertThrows<IllegalArgumentException> { ExcavationSite("quaternary", 2, 2, IntArray(4), listOf(placed), listOf(prep(boneId = "tibia_l"))) }
        assertThrows<IllegalArgumentException> { ExcavationSite("quaternary", 2, 2, IntArray(4), listOf(placed), listOf(prep(), prep())) }
        // Aenderungen der Tiefen behalten die Nahansichten.
        assertEquals(site.preparations, site.with(IntArray(4) { 1 }, site.finds).preparations)
    }
}

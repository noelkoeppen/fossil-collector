package de.noelkoeppen.fossilcollector.core.model

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Struktur des Bergens und Pruefung in der Flaeche (AP2-09). */
class BoneRecoveryTest {

    private val find = Find("cave_bear", listOf(Piece("cave_bear", "femur_l", 0.5), Piece("cave_bear", "tibia_l", 0.6)))
    private val placed = PlacedFind(find, mapOf("femur_l" to listOf(0), "tibia_l" to listOf(1)))
    private val femur = BoneRef(0, "femur_l")

    /** Nahansicht 4 x 1 des Oberschenkels, Knochen in den Zellen 0..2. */
    private fun prep(finished: Boolean = true) =
        BonePreparation(0, "femur_l", 0, 0, 4, 4, 1, IntArray(4), BooleanArray(4), booleanArrayOf(true, true, true, false), finished)

    private fun site(vararg recoveries: BoneRecovery, finished: Boolean = true) =
        ExcavationSite("quaternary", 2, 1, IntArray(2), listOf(placed), listOf(prep(finished)), recoveries.toList())

    @Test
    fun countsAndSecuring() {
        val rec = BoneRecovery(0, "femur_l", listOf(0, 2), listOf(false, false))
        assertEquals(femur, rec.ref)
        assertEquals(2, rec.unsecuredCount)
        val secured = rec.withSecured(1)
        assertEquals(listOf(false, true), secured.secured)
        assertEquals(1, secured.securedCount)
        assertEquals(secured, secured.withSecured(1))
    }

    @Test
    fun structureIsChecked() {
        assertThrows<IllegalArgumentException> { BoneRecovery(-1, "femur_l", emptyList(), emptyList()) }
        assertThrows<IllegalArgumentException> { BoneRecovery(0, "femur_l", listOf(0), emptyList()) }
        assertThrows<IllegalArgumentException> { BoneRecovery(0, "femur_l", listOf(1, 1), listOf(false, false)) }
        assertThrows<IllegalArgumentException> { BoneRecovery(0, "femur_l", listOf(-1), listOf(false)) }
    }

    @Test
    fun siteChecksRecoveriesAgainstPreparation() {
        val ok = site(BoneRecovery(0, "femur_l", listOf(0, 2), listOf(true, false)))
        assertFalse(ok.isRecovered(femur))
        assertEquals(listOf(femur, BoneRef(0, "tibia_l")), ok.unrecoveredBones)
        // Stelle nicht auf dem Knochen bzw. ausserhalb des Rasters.
        assertThrows<IllegalArgumentException> { site(BoneRecovery(0, "femur_l", listOf(3), listOf(false))) }
        assertThrows<IllegalArgumentException> { site(BoneRecovery(0, "femur_l", listOf(4), listOf(false))) }
        // Ohne fertige Nahansicht bzw. ohne Nahansicht.
        assertThrows<IllegalArgumentException> { site(BoneRecovery(0, "femur_l", listOf(0), listOf(false)), finished = false) }
        assertThrows<IllegalArgumentException> { site(BoneRecovery(0, "tibia_l", emptyList(), emptyList())) }
        // Doppelt.
        assertThrows<IllegalArgumentException> {
            site(BoneRecovery(0, "femur_l", emptyList(), emptyList()), BoneRecovery(0, "femur_l", listOf(0), listOf(false)))
        }
    }

    @Test
    fun recoveredBonesAreMarkedAndNoLongerActive() {
        val s = site(BoneRecovery(0, "femur_l", listOf(0), listOf(true), recovered = true))
        assertTrue(s.isRecovered(femur))
        assertEquals(listOf(femur), s.recoveredBones)
        assertEquals(null, s.activeBoneAt(0))
        assertEquals(BoneRef(0, "tibia_l"), s.activeBoneAt(1))
        assertEquals(femur, s.boneAt(0), "die Zellen gehoeren weiter zum Fund")
        assertFalse(s.allRecovered)
        assertTrue(ExcavationSite("quaternary", 1, 1, IntArray(1), emptyList()).allRecovered, "leere Flaeche")
    }

    @Test
    fun recoveryRoundTripsAsJson() {
        val rec = BoneRecovery(0, "femur_l", listOf(0, 2), listOf(true, false), recovered = false)
        val text = Json.encodeToString(BoneRecovery.serializer(), rec)
        assertEquals(rec, Json.decodeFromString(BoneRecovery.serializer(), text))
        val s = site(rec)
        val siteText = Json.encodeToString(ExcavationSite.serializer(), s)
        assertEquals(s, Json.decodeFromString(ExcavationSite.serializer(), siteText))
    }
}

package de.noelkoeppen.fossilcollector.core.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Nachbarschaftsgraph einer Art: Nachbarn und Zusammenhang. */
class SpeciesGraphTest {

    /** Kette a - b - c und separat d - e. */
    private val toy = SpeciesDefinition(
        id = "toy",
        layer = "quaternary",
        rarityWeight = 1.0,
        bones = listOf("a", "b", "c", "d", "e"),
        neighbors = listOf(listOf("a", "b"), listOf("c", "b"), listOf("d", "e")),
    )

    @Test
    fun neighborsAreSymmetric() {
        assertEquals(setOf("b"), toy.neighborsOf("a"))
        assertEquals(setOf("a", "c"), toy.neighborsOf("b"))
        assertEquals(setOf("b"), toy.neighborsOf("c"))
        assertEquals(emptySet<String>(), toy.neighborsOf("x"))
    }

    @Test
    fun connectivityOnlyUsesGivenBones() {
        assertTrue(toy.isConnected(listOf("a")))
        assertTrue(toy.isConnected(listOf("a", "b", "c")))
        assertTrue(toy.isConnected(setOf("d", "e")))
        // a und c haengen nur ueber b zusammen.
        assertFalse(toy.isConnected(listOf("a", "c")))
        assertFalse(toy.isConnected(toy.bones))
        assertFalse(toy.isConnected(emptyList()))
        assertFalse(toy.isConnected(listOf("a", "x")))
    }

    @Test
    fun caveBearGraphIsConnectedAndAnatomicallyCorrected() {
        val bear = DataRepository().load().species("cave_bear")
        assertTrue(bear.isConnected(bear.bones))
        // Korrekturen aus art/models3d/cave_bear/neighbors_overrides.json
        assertFalse("rib_5_r" in bear.neighborsOf("rib_5_l"))
        assertEquals((1..9).flatMap { listOf("rib_${it}_l", "rib_${it}_r") }.toSet(), bear.neighborsOf("sternum"))
        assertTrue("hip_l" in bear.neighborsOf("sacrum"))
        assertFalse("hip_l" in bear.neighborsOf("lumbar_6"))
        assertFalse("fibula_l" in bear.neighborsOf("femur_l"))
        assertFalse("sphenoid" in bear.neighborsOf("mandible_l"))
        // Abgeleitete Kanten
        assertTrue("cervical_1" in bear.neighborsOf("occipital"))
        assertTrue("thoracic_5" in bear.neighborsOf("rib_5_l"))
        assertTrue("humerus_l" in bear.neighborsOf("scapula_l"))
    }
}

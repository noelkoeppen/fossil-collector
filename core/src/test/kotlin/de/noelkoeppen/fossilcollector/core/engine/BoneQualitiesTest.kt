package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.model.Piece
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Qualitaet je Knochen fuer die 3D-Ansicht (AP2-10). */
class BoneQualitiesTest {

    private val data = DataRepository().load()
    private val tiers = QualityTiers.fromGameData(data)

    @Test
    fun `bester Fund je Knochen der Art`() {
        val pieces = listOf(
            Piece("cave_bear", "femur_l", 0.4),
            Piece("cave_bear", "femur_l", 0.8),
            Piece("cave_bear", "femur_l", 0.6),
            Piece("cave_bear", "cervical_1", 0.3),
            Piece("other", "femur_r", 0.9),
        )
        assertEquals(mapOf("femur_l" to 0.8, "cervical_1" to 0.3), BoneQualities.bestByBone(pieces, "cave_bear"))
        assertEquals(mapOf("femur_r" to 0.9), BoneQualities.bestByBone(pieces, "other"))
        assertEquals(emptyMap<String, Double>(), BoneQualities.bestByBone(emptyList(), "cave_bear"))
    }

    @Test
    fun `Beispielqualitaeten gehen der Reihe nach durch alle Stufen`() {
        val bones = data.species.first().bones
        val samples = BoneQualities.tierSamples(bones, tiers)
        assertEquals(bones, samples.keys.toList())
        bones.forEachIndexed { i, id ->
            assertEquals(i % tiers.tiers.size, tiers.indexOf(samples.getValue(id)), id)
        }
        // Jede Stufe kommt vor, wenn es mindestens so viele Knochen wie Stufen gibt.
        assertTrue(bones.size >= tiers.tiers.size)
        assertEquals(tiers.tiers.indices.toSet(), samples.values.map(tiers::indexOf).toSet())
    }
}

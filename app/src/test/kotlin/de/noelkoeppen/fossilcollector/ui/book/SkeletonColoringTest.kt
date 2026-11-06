package de.noelkoeppen.fossilcollector.ui.book

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.ui.theme.Bone
import de.noelkoeppen.fossilcollector.ui.theme.QualityColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Farbzuordnung der Skelett-Grafik (AP-21): Zone, Knochenstufe, fehlend. */
class SkeletonColoringTest {

    private val colors = QualityColors.fromTiers(DataRepository().load().balance.qualityTiers)

    private fun fill(tier: String) = ZoneStyle.Fill(checkNotNull(colors.forTier(tier)))

    @Test
    fun missingBoneIsDashedForEveryZone() {
        val bone = SkeletonBone(tierIndex = null, zoneQualities = listOf(0.9, 0.9, 0.9, 0.9))
        (0 until 4).forEach { assertEquals(ZoneStyle.Missing, SkeletonColoring.zoneStyle(bone, it, colors)) }
    }

    @Test
    fun knownZoneUsesColorOfItsQuality() {
        val bone = SkeletonBone(tierIndex = 1, zoneQualities = listOf(0.95, 0.5, 0.0, 0.89))
        assertEquals(fill("pristine"), SkeletonColoring.zoneStyle(bone, 0, colors))
        assertEquals(fill("good"), SkeletonColoring.zoneStyle(bone, 1, colors))
        assertEquals(fill("fragment"), SkeletonColoring.zoneStyle(bone, 2, colors))
        assertEquals(fill("excellent"), SkeletonColoring.zoneStyle(bone, 3, colors))
    }

    @Test
    fun unknownZoneFallsBackToBoneTier() {
        val bone = SkeletonBone(tierIndex = 2, zoneQualities = listOf(null, 0.95))
        assertEquals(fill("good"), SkeletonColoring.zoneStyle(bone, 0, colors))
        // Mehr Zonen in der Grafik als Qualitaeten: Stufe des Knochens.
        assertEquals(fill("good"), SkeletonColoring.zoneStyle(bone, 3, colors))
        assertEquals(fill("excellent"), SkeletonColoring.zoneStyle(SkeletonBone(3, null), 0, colors))
    }

    @Test
    fun withoutColorsBoneColorIsUsed() {
        val bone = SkeletonBone(tierIndex = 4, zoneQualities = listOf(0.95))
        assertEquals(ZoneStyle.Fill(Bone), SkeletonColoring.zoneStyle(bone, 0, QualityColors.EMPTY))
        assertEquals(ZoneStyle.Fill(Bone), SkeletonColoring.zoneStyle(SkeletonBone(9, null), 0, colors))
    }
}

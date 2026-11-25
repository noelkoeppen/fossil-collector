package de.noelkoeppen.fossilcollector.ui.model3d

import androidx.compose.ui.graphics.Color
import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.ui.theme.QualityColors
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** Einfaerbung nach Qualitaetsstufe (AP2-10): Stufe aus core, Farbe aus balance.json. */
class BoneTintTest {

    private val data = DataRepository().load()
    private val colors = QualityColors.fromTiers(data.balance.qualityTiers)
    private val base = floatArrayOf(0.82f, 0.76f, 0.62f)

    private fun rgb(look: BoneLook) = floatArrayOf(look.red, look.green, look.blue)

    @Test
    fun tintFormulaMatchesReferenceShader() {
        // mix(base, tier * (0.35 + 1.1 * lum), 0.62) mit lum = dot(base, (0.3, 0.6, 0.1)).
        val tier = Color(0xFF4A90D9)
        val lum = 0.3f * base[0] + 0.6f * base[1] + 0.1f * base[2]
        val expected = FloatArray(3) { i ->
            val t = floatArrayOf(tier.red, tier.green, tier.blue)[i] * (0.35f + 1.1f * lum)
            base[i] * (1 - 0.62f) + t * 0.62f
        }
        assertArrayEquals(expected, BoneTint.tinted(base, tier), 1e-5f)
    }

    @Test
    fun everyTierGetsItsOwnColorFromBalance() {
        val tiers = data.balance.qualityTiers
        val bones = tiers.map { "bone_${it.id}" }
        val qualities = bones.zip(tiers).associate { (bone, tier) -> bone to tier.minQuality }
        val looks = BoneTint.looks(bones, qualities, colors, MissingBoneStyle.HIDDEN) { base }
        bones.zip(tiers).forEach { (bone, tier) ->
            val tierColor = QualityColors.parseHexColor(tier.color)
            assertArrayEquals(BoneTint.tinted(base, tierColor), rgb(looks.getValue(bone)), 1e-6f, tier.id)
        }
        assertEquals(tiers.size, looks.values.map { rgb(it).toList() }.toSet().size, "Stufen muessen unterscheidbar sein")
    }

    @Test
    fun qualityBoundaryUsesCoreTiers() {
        // 0.69 ist noch "good", 0.7 schon "excellent" (Grenzen aus balance.json, Zuordnung QualityTiers).
        val looks = BoneTint.looks(listOf("a", "b"), mapOf("a" to 0.69, "b" to 0.7), colors, MissingBoneStyle.HIDDEN) { base }
        assertArrayEquals(BoneTint.tinted(base, colors.forTier("good")!!), rgb(looks.getValue("a")), 1e-6f)
        assertArrayEquals(BoneTint.tinted(base, colors.forTier("excellent")!!), rgb(looks.getValue("b")), 1e-6f)
    }

    @Test
    fun missingBonesHiddenOrNeutral() {
        val hidden = BoneTint.looks(listOf("x"), emptyMap(), colors, MissingBoneStyle.HIDDEN) { base }
        assertFalse(hidden.getValue("x").visible)
        val neutral = BoneTint.looks(listOf("x"), emptyMap(), colors, MissingBoneStyle.NEUTRAL) { base }
        assertEquals(BoneLook(true, base[0], base[1], base[2]), neutral.getValue("x"))
    }

    @Test
    fun withoutColorOrModelColorFallsBack() {
        // Ohne Qualitaetsfarben (Laden) bleibt die Knochenfarbe; ohne Modellfarbe die Ersatzfarbe.
        val looks = BoneTint.looks(listOf("x"), mapOf("x" to 0.95), QualityColors.EMPTY, MissingBoneStyle.HIDDEN) { null }
        assertArrayEquals(BoneTint.FALLBACK_BONE_COLOR, rgb(looks.getValue("x")))
        val tinted = BoneTint.looks(listOf("x"), mapOf("x" to 0.95), colors, MissingBoneStyle.HIDDEN) { null }
        assertNotEquals(BoneTint.FALLBACK_BONE_COLOR.toList(), rgb(tinted.getValue("x")).toList())
    }
}

package de.noelkoeppen.fossilcollector.ui.model3d

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import de.noelkoeppen.fossilcollector.ui.theme.QualityColors

/** Darstellung von Knochen ohne Zustand (nicht gefunden bzw. nicht zugeordnet). */
enum class MissingBoneStyle {
    /** Nicht zeichnen. */
    HIDDEN,

    /** In der Knochenfarbe des Modells ohne Qualitaetsfarbe zeichnen. */
    NEUTRAL,
}

/**
 * Wie ein Knochen gezeichnet wird: sichtbar ja/nein und Grundfarbe (linear 0..1, vor der Beleuchtung).
 */
@Immutable
data class BoneLook(val visible: Boolean, val red: Float, val green: Float, val blue: Float) {
    companion object {
        val HIDDEN = BoneLook(false, 0f, 0f, 0f)
    }
}

/**
 * Einfaerbung nach Qualitaetsstufe (AP2-10). Die Stufe einer Qualitaet bestimmt [QualityColors]
 * ueber `QualityTiers` aus `core`, die Farbe kommt aus `balance.json` (`qualityTiers[].color`).
 * Welcher Knochen welche Qualitaet hat, entscheidet der Aufrufer (Spielzustand aus `core`).
 */
object BoneTint {

    /**
     * Anteil der Stufenfarbe an der Grundfarbe. Wert und Formel wie im Referenz-Shader
     * `art/models3d/cave_bear/skeleton.html` (`mix(alb, zc * (0.35 + 1.1 * lum), 0.62)`): Die
     * Stufe ist klar erkennbar, die Helligkeit des Knochens bleibt erhalten, er wirkt nicht wie
     * Plastik.
     */
    const val STRENGTH = 0.62f
    private const val TIER_FLOOR = 0.35f
    private const val TIER_LUMA = 1.1f
    private val LUMA = floatArrayOf(0.3f, 0.6f, 0.1f)

    /**
     * Ersatzfarbe, wenn die `.glb` keine Knochenfarbe hat; gleicher Wert wie `DEFAULTS["color"]`
     * in `tools/models3d/bone_images.py` (Darstellung, kein Spielwert).
     */
    val FALLBACK_BONE_COLOR = floatArrayOf(0.82f, 0.76f, 0.62f)

    /** Knochenfarbe [base] (`r, g, b`) mit der Stufenfarbe [tier] gemischt. */
    fun tinted(base: FloatArray, tier: Color): FloatArray {
        val luma = base[0] * LUMA[0] + base[1] * LUMA[1] + base[2] * LUMA[2]
        val scale = TIER_FLOOR + TIER_LUMA * luma
        val tierRgb = floatArrayOf(tier.red, tier.green, tier.blue)
        return FloatArray(3) { i -> base[i] + (tierRgb[i] * scale - base[i]) * STRENGTH }
    }

    /**
     * Aussehen je Knochen.
     *
     * @param boneIds Knochen der Ansicht
     * @param qualities Qualitaet (0..1) je Knochen mit Zustand; Knochen ohne Eintrag gelten als fehlend
     * @param colors Qualitaetsfarben; ohne Farbe fuer eine Stufe (z. B. waehrend des Ladens) bleibt
     *   der Knochen in seiner Grundfarbe
     * @param missing Darstellung fehlender Knochen
     * @param baseColor Knochenfarbe des Modells je Knochen oder `null`
     */
    fun looks(
        boneIds: Collection<String>,
        qualities: Map<String, Double>,
        colors: QualityColors,
        missing: MissingBoneStyle,
        baseColor: (String) -> FloatArray?,
    ): Map<String, BoneLook> = boneIds.associateWith { boneId ->
        val base = baseColor(boneId) ?: FALLBACK_BONE_COLOR
        val quality = qualities[boneId]
        when {
            quality == null && missing == MissingBoneStyle.HIDDEN -> BoneLook.HIDDEN
            quality == null -> look(base)
            else -> look(colors.forQuality(quality)?.let { tinted(base, it) } ?: base)
        }
    }

    private fun look(rgb: FloatArray) = BoneLook(true, rgb[0], rgb[1], rgb[2])
}

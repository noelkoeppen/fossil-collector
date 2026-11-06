package de.noelkoeppen.fossilcollector.ui.book

import androidx.compose.ui.graphics.Color
import de.noelkoeppen.fossilcollector.ui.theme.Bone
import de.noelkoeppen.fossilcollector.ui.theme.QualityColors

/** Darstellung einer Zone in der Skelett-Grafik. */
internal sealed interface ZoneStyle {
    /** Zone gefuellt in [color]. */
    data class Fill(val color: Color) : ZoneStyle

    /** Knochen fehlt: nur grau gestrichelte Kontur. */
    data object Missing : ZoneStyle
}

/**
 * Farbzuordnung der Skelett-Grafik (Kapitel 8.3, 8.5), als reine Funktion testbar.
 *
 * - Knochen fehlt (`tierIndex == null`): [ZoneStyle.Missing].
 * - Zonenqualitaet bekannt: Farbe der Stufe dieser Qualitaet (`QualityColors.forQuality`).
 * - sonst: Farbe der Knochenstufe (`QualityColors.forTierIndex`).
 * - ohne Qualitaetsfarben (Laden, Vorschau ohne Daten): Knochenfarbe [Bone].
 */
internal object SkeletonColoring {

    fun zoneStyle(bone: SkeletonBone, zoneIndex: Int, colors: QualityColors): ZoneStyle {
        val tierIndex = bone.tierIndex ?: return ZoneStyle.Missing
        val zoneColor = bone.zoneQualities?.getOrNull(zoneIndex)?.let { colors.forQuality(it) }
        return ZoneStyle.Fill(zoneColor ?: colors.forTierIndex(tierIndex) ?: Bone)
    }
}

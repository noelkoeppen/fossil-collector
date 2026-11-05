package de.noelkoeppen.fossilcollector.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import de.noelkoeppen.fossilcollector.core.data.QualityTierDefinition

/**
 * Qualitaetsfarben (Kapitel 8.4) fuer die UI. Quelle sind die Stufen aus `balance.json`
 * (`qualityTiers[].color`), damit Farben und Grenzen nur an einer Stelle stehen.
 *
 * Abfrage per Stufen-ID ([forTier]) oder per Qualitaet 0..1 ([forQuality]).
 */
@Immutable
class QualityColors private constructor(
    private val tiers: List<Tier>,
) {
    private class Tier(val id: String, val minQuality: Double, val color: Color)

    /** Farbe der Stufe [tierId] oder `null`, wenn unbekannt. */
    fun forTier(tierId: String): Color? = tiers.firstOrNull { it.id == tierId }?.color

    /** Farbe der hoechsten Stufe, deren Untergrenze <= [quality] ist; `null` ohne Stufen. */
    fun forQuality(quality: Double): Color? =
        tiers.lastOrNull { quality >= it.minQuality }?.color ?: tiers.firstOrNull()?.color

    /** Stufen-IDs in aufsteigender Reihenfolge. */
    val tierIds: List<String> get() = tiers.map { it.id }

    companion object {
        /** Ohne Daten (z. B. waehrend des Ladens oder in Vorschauen). */
        val EMPTY: QualityColors = QualityColors(emptyList())

        /** Baut die Farben aus den Stufen der Spieldaten; ungueltige Farbangaben werden grau. */
        fun fromTiers(tiers: List<QualityTierDefinition>): QualityColors =
            QualityColors(
                tiers.sortedBy { it.minQuality }.map { Tier(it.id, it.minQuality, parseHexColor(it.color)) },
            )

        /** `#RRGGBB` bzw. `#AARRGGBB` als [Color]; bei ungueltiger Angabe [Color.Gray]. */
        fun parseHexColor(hex: String): Color {
            val digits = hex.removePrefix("#")
            val value = digits.toLongOrNull(radix = 16) ?: return Color.Gray
            return when (digits.length) {
                6 -> Color(0xFF000000L or value)
                8 -> Color(value)
                else -> Color.Gray
            }
        }
    }
}

/** Qualitaetsfarben der aktuellen Spieldaten. */
val LocalQualityColors = staticCompositionLocalOf { QualityColors.EMPTY }

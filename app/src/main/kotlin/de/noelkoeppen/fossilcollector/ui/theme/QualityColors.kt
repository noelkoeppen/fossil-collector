package de.noelkoeppen.fossilcollector.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import de.noelkoeppen.fossilcollector.core.data.QualityTierDefinition
import de.noelkoeppen.fossilcollector.core.engine.QualityTiers

/**
 * Qualitaetsfarben (Kapitel 8.4) fuer die UI. Quelle sind die Stufen aus `balance.json`
 * (`qualityTiers[].color`), damit Farben und Grenzen nur an einer Stelle stehen.
 *
 * Abfrage per Stufen-ID ([forTier]) oder per Qualitaet 0..1 ([forQuality]); die Zuordnung
 * Qualitaet -> Stufe macht [QualityTiers] aus `core`.
 */
@Immutable
class QualityColors private constructor(
    private val tiers: QualityTiers?,
    private val colors: Map<String, Color>,
) {
    /** Farbe der Stufe [tierId] oder `null`, wenn unbekannt. */
    fun forTier(tierId: String): Color? = colors[tierId]

    /** Farbe der Stufe von [quality]; `null` ohne Stufen. */
    fun forQuality(quality: Double): Color? = tiers?.let { colors[it.tierOf(quality).id] }

    /**
     * Farbe der Stufe mit dem Index [index] (Reihenfolge von [QualityTiers.tiers], passend zu
     * `QualityTiers.indexOf` und `balance.bookTierFactor`); `null`, wenn der Index unbekannt ist.
     */
    fun forTierIndex(index: Int): Color? = tiers?.tiers?.getOrNull(index)?.let { colors[it.id] }

    companion object {
        /** Ohne Daten (z. B. waehrend des Ladens oder in Vorschauen). */
        val EMPTY: QualityColors = QualityColors(null, emptyMap())

        /** Baut die Farben aus den Stufen der Spieldaten; ungueltige Farbangaben werden grau. */
        fun fromTiers(tiers: List<QualityTierDefinition>): QualityColors =
            if (tiers.isEmpty()) {
                EMPTY
            } else {
                QualityColors(QualityTiers(tiers), tiers.associate { it.id to parseHexColor(it.color) })
            }

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

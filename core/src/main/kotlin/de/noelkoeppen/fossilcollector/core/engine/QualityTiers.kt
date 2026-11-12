package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.QualityTierDefinition

/**
 * Zuordnung Qualitaet -> Qualitaetsstufe (Grenzen aus `balance.qualityTiers`).
 *
 * Eine Stufe gilt ab ihrer `minQuality` (inklusive) bis ausschliesslich zur Untergrenze der
 * naechsten Stufe. Qualitaeten unter der ersten Grenze (oder `NaN`) gehoeren zur niedrigsten Stufe,
 * alles ab der letzten Grenze zur hoechsten.
 *
 * @param tiers Stufen; werden nach `minQuality` sortiert
 */
class QualityTiers(tiers: List<QualityTierDefinition>) {

    /** Stufen aufsteigend nach Untergrenze. */
    val tiers: List<QualityTierDefinition> = tiers.sortedBy { it.minQuality }

    init {
        require(this.tiers.isNotEmpty()) { "Mindestens eine Qualitaetsstufe noetig" }
    }

    /**
     * Index der Stufe von [quality] in [tiers] (0 = niedrigste).
     */
    fun indexOf(quality: Double): Int {
        var index = 0
        for (i in tiers.indices) if (quality >= tiers[i].minQuality) index = i
        return index
    }

    /** Stufe von [quality]. */
    fun tierOf(quality: Double): QualityTierDefinition = tiers[indexOf(quality)]

    companion object {
        /** Stufen aus `balance.qualityTiers`. */
        fun fromGameData(data: GameData): QualityTiers = QualityTiers(data.balance.qualityTiers)
    }
}

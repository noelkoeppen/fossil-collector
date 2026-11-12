package de.noelkoeppen.fossilcollector.core.modifier

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stat-IDs des Modifier-Systems. In JSON werden sie in snake_case geschrieben, siehe [serialName].
 *
 * Nach AP2-02 bleiben nur die Stats, die nicht an entfernte Idle-Systeme gebunden sind. Die
 * Stats der neuen Upgrades (Toleranzen, Schaden, Tempo, Hinweise) legt AP2-14 fest.
 * Clamps aus `balance.statLimits` gelten fuer den Endwert eines Stats.
 *
 * [isFraction] (nur Anzeige): Der Stat ist ein Anteil im Bereich 0..1. Die UI zeigt auch additive
 * Boni darauf als Prozent (`+0.02` -> `+2 %`) statt als Zahl.
 */
@Serializable
enum class StatId(val serialName: String, val isFraction: Boolean = false) {
    @SerialName("damage_reduction") DAMAGE_REDUCTION("damage_reduction", isFraction = true),
    @SerialName("quality_base_min") QUALITY_BASE_MIN("quality_base_min", isFraction = true),
    @SerialName("sell_price") SELL_PRICE("sell_price");

    companion object {
        /** Sucht eine Stat-ID ueber ihren JSON-Namen, `null` wenn unbekannt. */
        fun fromSerialName(name: String): StatId? = entries.firstOrNull { it.serialName == name }
    }
}

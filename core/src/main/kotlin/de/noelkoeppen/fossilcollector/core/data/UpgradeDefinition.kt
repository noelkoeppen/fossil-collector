package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import kotlinx.serialization.Serializable

/** Ein stufenweise kaufbares Upgrade aus `upgrades.json`, z. B. Werkzeugstufen (Kapitel 6.4). */
@Serializable
data class UpgradeDefinition(
    val id: String,
    val currency: Currency,
    val baseCost: Double,
    val growth: Double,
    /** Hoechste Stufe, `null` = unbegrenzt. */
    val maxLevel: Int? = null,
    /** Modifier, die pro gekaufter Stufe einmal wirken. */
    val effectsPerLevel: List<Modifier>,
)

package de.noelkoeppen.fossilcollector.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Waehrung, mit der ein Generator oder Upgrade bezahlt wird (Kapitel 3). */
@Serializable
enum class Currency {
    @SerialName("money") MONEY,
    @SerialName("research_points") RESEARCH_POINTS,
}

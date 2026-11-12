package de.noelkoeppen.fossilcollector.core.data

import kotlinx.serialization.Serializable

/** Eine Art aus `species.json`. */
@Serializable
data class SpeciesDefinition(
    val id: String,
    /** ID der Schicht, in der die Art gefunden wird. */
    val layer: String,
    /** Gewicht bei der Artenwahl innerhalb der Schicht. */
    val rarityWeight: Double,
    /** Knochen-IDs aus `bones.json`, die das Skelett dieser Art bilden. */
    val bones: List<String>,
)

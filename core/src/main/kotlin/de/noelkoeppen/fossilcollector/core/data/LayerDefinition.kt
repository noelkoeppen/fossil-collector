package de.noelkoeppen.fossilcollector.core.data

import kotlinx.serialization.Serializable

/** Eine Erdschicht aus `layers.json`. */
@Serializable
data class LayerDefinition(
    val id: String,
    /** Reihenfolge von oben (jung, 0) nach unten (alt). */
    val order: Int,
    /** Farbe der Schicht als `#RRGGBB`. */
    val color: String,
    /** Gesteinstextur der Schicht; fehlt sie, gilt [LayerTexture.PLAIN]. */
    val texture: LayerTexture = LayerTexture.PLAIN,
)

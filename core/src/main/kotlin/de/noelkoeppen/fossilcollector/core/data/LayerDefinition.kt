package de.noelkoeppen.fossilcollector.core.data

import kotlinx.serialization.Serializable

/** Eine Erdschicht aus `layers.json` (Kapitel 4.1, 6.4, 11). */
@Serializable
data class LayerDefinition(
    val id: String,
    /** Reihenfolge von oben (jung, 0) nach unten (alt). */
    val order: Int,
    /** Freischaltkosten in Geld; die erste Schicht kostet 0. */
    val unlockCost: Double,
    /** Grundpreis eines Grabungsteams in dieser Schicht. */
    val teamBaseCost: Double,
    /** Kostenwachstum der Teams pro gekauftem Team. */
    val teamGrowth: Double,
    /** Bloecke pro Sekunde und Team. */
    val teamRate: Double,
    /** Grundwert eines Stuecks dieser Schicht ($/s im Museum). */
    val value: Double,
    /** Zerbrechlichkeit, Grundlage des Praeparationsschadens. */
    val fragility: Double,
    /** Gewicht der Schicht bei der Ruhm-Berechnung. */
    val fameWeight: Double,
    /** Farbe des Schichtbands als `#RRGGBB`. */
    val color: String,
    /** Gesteinstextur des Schichtbands (Kapitel 8.5, AP-26); fehlt sie, gilt [LayerTexture.PLAIN]. */
    val texture: LayerTexture = LayerTexture.PLAIN,
)

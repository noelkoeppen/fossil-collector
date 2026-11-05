package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import kotlinx.serialization.Serializable

/**
 * Ein mit Geld kaufbarer Generator aus `generators.json` (Kapitel 6.3, 6.4): Praeparator,
 * Museumshalle, Laborplatz. Grabungsteams stehen pro Schicht in `layers.json`.
 */
@Serializable
data class GeneratorDefinition(
    val id: String,
    val baseCost: Double,
    val growth: Double,
    val currency: Currency,
    /** Anzahl, die zu Beginn jedes Durchlaufs gratis vorhanden ist. */
    val free: Int = 0,
    /** Modifier pro besessener Einheit. */
    val effects: List<Modifier> = emptyList(),
)

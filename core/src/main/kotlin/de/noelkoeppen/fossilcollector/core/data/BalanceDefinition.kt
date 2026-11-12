package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.StatId
import kotlinx.serialization.Serializable

/**
 * Globale Balancing-Konstanten aus `balance.json`.
 *
 * Nach AP2-02 nur noch die Qualitaetsstufen und die Grenzen des Modifier-Systems; die Werte der
 * neuen Systeme (Fundsystem, Grabung, Schaden) kommen mit den jeweiligen Arbeitspaketen dazu.
 */
@Serializable
data class BalanceDefinition(
    /** Qualitaetsstufen aufsteigend nach Untergrenze. */
    val qualityTiers: List<QualityTierDefinition>,
    /** Clamps pro Stat, angewendet auf den Endwert des Stats. */
    val statLimits: Map<StatId, StatLimit> = emptyMap(),
)

/** Eine Qualitaetsstufe: gilt ab [minQuality] (inklusive) bis zur naechsten Stufe. */
@Serializable
data class QualityTierDefinition(
    val id: String,
    val minQuality: Double,
    /** Farbe der Stufe als `#RRGGBB`. */
    val color: String,
)

/** Unter- und Obergrenze eines Stats; `null` = keine Grenze. */
@Serializable
data class StatLimit(
    val min: Double? = null,
    val max: Double? = null,
)

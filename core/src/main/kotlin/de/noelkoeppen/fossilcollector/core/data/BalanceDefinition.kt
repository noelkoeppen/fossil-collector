package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.StatId
import kotlinx.serialization.Serializable

/**
 * Globale Balancing-Konstanten aus `balance.json`.
 *
 * Qualitaetsstufen, Grenzen des Modifier-Systems und das Fundsystem (AP2-04); die Werte der
 * weiteren Systeme (Grabung, Schaden) kommen mit den jeweiligen Arbeitspaketen dazu.
 */
@Serializable
data class BalanceDefinition(
    /** Qualitaetsstufen aufsteigend nach Untergrenze. */
    val qualityTiers: List<QualityTierDefinition>,
    /** Clamps pro Stat, angewendet auf den Endwert des Stats. */
    val statLimits: Map<StatId, StatLimit> = emptyMap(),
    /** Parameter fuer das Wuerfeln eines Fundes (Kapitel 4). */
    val finds: FindBalance,
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

/**
 * Parameter des Fundsystems (Kapitel 4), verwendet von `FindRoller`.
 *
 * Die Grundqualitaet eines Fundes ist gleichverteilt in `[baseQualityMin, baseQualityMax)`; die
 * Untergrenze laeuft dabei ueber den Stat `quality_base_min`. Jeder Knochen weicht gleichverteilt um
 * hoechstens [boneQualityDeviation] davon ab und wird danach auf die Qualitaetsskala 0..1 begrenzt.
 */
@Serializable
data class FindBalance(
    /** Gewichtete Tabelle der Fundgroessen (Anzahl zusammenhaengender Knochen). */
    val sizeWeights: List<FindSizeWeight>,
    /** Basiswert der Untergrenze der Grundqualitaet (Basis des Stats `quality_base_min`). */
    val baseQualityMin: Double,
    /** Obergrenze der Grundqualitaet. */
    val baseQualityMax: Double,
    /** Maximale Abweichung der Qualitaet eines Knochens von der Grundqualitaet (nach oben und unten). */
    val boneQualityDeviation: Double,
)

/** Ein Eintrag der Fundgroessen-Tabelle: [size] Knochen mit relativem Gewicht [weight]. */
@Serializable
data class FindSizeWeight(
    val size: Int,
    val weight: Double,
)

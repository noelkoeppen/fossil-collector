package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.StatId
import kotlinx.serialization.Serializable

/**
 * Globale Balancing-Konstanten aus `balance.json`.
 *
 * Qualitaetsstufen, Grenzen des Modifier-Systems, das Fundsystem (AP2-04) und die Grabungsflaeche
 * (AP2-05); die Werte der weiteren Systeme kommen mit den jeweiligen Arbeitspaketen dazu.
 */
@Serializable
data class BalanceDefinition(
    /** Qualitaetsstufen aufsteigend nach Untergrenze. */
    val qualityTiers: List<QualityTierDefinition>,
    /** Clamps pro Stat, angewendet auf den Endwert des Stats. */
    val statLimits: Map<StatId, StatLimit> = emptyMap(),
    /** Parameter fuer das Wuerfeln eines Fundes (Kapitel 4). */
    val finds: FindBalance,
    /** Parameter der Grabungsflaeche (Kapitel 3). */
    val excavation: ExcavationBalance,
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

/**
 * Parameter der Grabungsflaeche (Kapitel 3, AP2-05), verwendet von `ExcavationGenerator` und
 * `Excavator`. Startwerte vorlaeufig bis zum Playtest.
 *
 * Die Flaeche ist ein internes Raster aus [width] x [height] Zellen. Jede Zelle hat eine Erdtiefe in
 * Erdeinheiten, gleichverteilt in `[depthMin, depthMax]`. Liegt ueber einer Knochenzelle hoechstens
 * [hintDepth] Erde, zeigt sie einen Hinweis.
 */
@Serializable
data class ExcavationBalance(
    val width: Int,
    val height: Int,
    val depthMin: Int,
    val depthMax: Int,
    /** Hinweis, sobald ueber einer Knochenzelle hoechstens so viel Erde liegt. */
    val hintDepth: Int,
    /** Gewichtete Tabelle der Anzahl Funde pro Ausgrabung. */
    val findCountWeights: List<FindCountWeight>,
    /** Mindestabstand (Chebyshev, in Zellen) zwischen Zellen verschiedener Funde; 0 = nur keine Ueberlappung. */
    val findSpacing: Int,
    /** Versuche, einen Fund zu platzieren; passt er danach nicht, wird er weggelassen. */
    val placementAttempts: Int,
    /** Platzhalter-Umriss jedes Knochens bis zu den Masken aus AP2-06. */
    val placeholderFootprint: FootprintSize,
)

/** Ein Eintrag der Tabelle der Fundanzahl: [count] Funde mit relativem Gewicht [weight]. */
@Serializable
data class FindCountWeight(
    val count: Int,
    val weight: Double,
)

/** Rechteck in Zellen. */
@Serializable
data class FootprintSize(
    val width: Int,
    val height: Int,
)

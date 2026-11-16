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
    /** Parameter des feinen Freilegens (Kapitel 5.1, AP2-08). */
    val preparation: PreparationBalance,
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
 * [hintDepth] Erde, zeigt sie einen Hinweis. Die Umrisse der Knochen kommen aus den Knochenmasken
 * ([cellsPerUnit], AP2-07).
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
    /**
     * Zellen pro Modelleinheit (AP2-07): Massstab der Knochenumrisse in der Flaeche. Die Umrisse in
     * `data/footprints/<speciesId>.json` rechnet `tools/models3d/bone_footprints.py` mit diesem Wert
     * aus den Knochenmasken; der `DataValidator` prueft, dass sie dazu passen.
     */
    val cellsPerUnit: Int,
)

/** Ein Eintrag der Tabelle der Fundanzahl: [count] Funde mit relativem Gewicht [weight]. */
@Serializable
data class FindCountWeight(
    val count: Int,
    val weight: Double,
)

/**
 * Parameter des feinen Freilegens (Kapitel 5.1, AP2-08), verwendet von `PreparationGenerator` und
 * `Preparer`. Startwerte vorlaeufig bis zum Playtest.
 *
 * Die Nahansicht eines Knochens ist ein feines Raster: jede Zelle der Flaeche in [subdivision] x
 * [subdivision] feine Zellen geteilt, ueber dem Rechteck des Knochens plus [margin] Zellen Rand. Jede
 * feine Zelle traegt eine duenne Restschicht: lose Erde mit Dicke gleichverteilt in
 * `[looseDepthMin, looseDepthMax]`, an harten Stellen stattdessen harte Erde mit Dicke in
 * `[hardDepthMin, hardDepthMax]`. Harte Stellen sind Kreise mit Radius in
 * `[hardSpotRadiusMin, hardSpotRadiusMax]` feinen Zellen um zufaellige Knochenzellen; ihre Anzahl
 * ist im Mittel [hardSpotsPerCell] je Zelle, die der Knochen in der Flaeche belegt. Fertig
 * freigelegt ist der Knochen, sobald mindestens [finishedShare] seiner feinen Zellen frei liegen.
 */
@Serializable
data class PreparationBalance(
    /** Feine Zellen je Zelle und Achse; `bone_footprints.py` liest den Wert von hier. */
    val subdivision: Int,
    /** Rand um das Rechteck des Knochens in Zellen der Flaeche. */
    val margin: Int,
    val looseDepthMin: Int,
    val looseDepthMax: Int,
    val hardDepthMin: Int,
    val hardDepthMax: Int,
    /** Mittlere Anzahl harter Stellen je Zelle, die der Knochen in der Flaeche belegt. */
    val hardSpotsPerCell: Double,
    /** Kleinster Radius einer harten Stelle in feinen Zellen. */
    val hardSpotRadiusMin: Double,
    /** Groesster Radius einer harten Stelle in feinen Zellen. */
    val hardSpotRadiusMax: Double,
    /** Anteil freiliegender Knochenzellen, ab dem der Knochen fertig freigelegt ist (0 < x <= 1). */
    val finishedShare: Double,
)

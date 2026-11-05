package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import kotlinx.serialization.Serializable

/** Globale Balancing-Konstanten aus `balance.json` (Kapitel 6.4, 11). */
@Serializable
data class BalanceDefinition(
    /** Grundchance, dass ein Block ein Fossil enthaelt (Stat `find_chance`). */
    val findChance: Double,
    /** Untergrenze der Grundqualitaet einer Zone (Stat `quality_base_min`). */
    val qualityBaseMin: Double,
    /** Obergrenze der Grundqualitaet einer Zone. */
    val qualityBaseMax: Double,
    /** Untergrenze des Schadenswurfs (`damage = ... * uniform(min, max)`). */
    val damageRollMin: Double,
    /** Obergrenze des Schadenswurfs. */
    val damageRollMax: Double,
    /** Gestein bringt `layer.value * rockSellFactor` Geld. */
    val rockSellFactor: Double,
    /** Faktor fuer den Sofortverkauf von Duplikaten. */
    val duplicateSellFactor: Double,
    /** Dauer einer Laborforschung in Sekunden (Stat `lab_speed` als Faktor). */
    val labDuration: Double,
    /** Plaetze in der Laborwarteschlange pro Laborplatz. */
    val labQueuePerSlot: Double,
    /** Hoechste Qualitaet einer Rekonstruktion. */
    val reconstructionCap: Double,
    /** Qualitaet, mit der fehlende Zonen beim Capstone Restaurator ergaenzt werden. */
    val reconstructionMissingZoneQuality: Double,
    /** Faktor fuer vollstaendige Skelette im Museum (Stat `skeleton_bonus` addiert). */
    val skeletonMult: Double,
    /** Ausstellungsplaetze pro Museumshalle. */
    val slotsPerHall: Double,
    /** Gewicht fehlender Knochen bei der Knochenwahl (Pech-Schutz). */
    val missingBoneWeight: Double,
    /** Bloecke pro Tipp auf eine Schicht. */
    val tapDigAmount: Double,
    /** Verarbeitete Bloecke pro Tipp auf Freilegen. */
    val tapPrepAmount: Double,
    /** Kostenwachstum im Skilltree pro besessenem Knoten. */
    val skillCostGrowth: Double,
    /** Anteil der Offline-Zeit, der simuliert wird. */
    val offlineEfficiency: Double,
    /** Hoechstens angerechnete Offline-Zeit in Stunden. */
    val offlineCapHours: Double,
    /** Grenze fuer einzeln gewuerfelte Offline-Funde. */
    val maxDetailedOfflineFinds: Double,
    /** Teamanzahlen, ab denen die Produktion einer Schicht mit [teamMilestoneFactor] multipliziert wird. */
    val teamMilestones: List<Double>,
    /** Faktor je erreichter Team-Schwelle (Standard 2). */
    val teamMilestoneFactor: Double,
    /** Qualitaetsstufen aufsteigend nach Untergrenze. */
    val qualityTiers: List<QualityTierDefinition>,
    /** Faktor des Buch-Bonus je niedrigster Qualitaetsstufe, gleiche Reihenfolge wie [qualityTiers]. */
    val bookTierFactor: List<Double>,
    /** Pauschaler Bonus fuer Completion-Stufe 1 (erforscht), pro Art. */
    val researchedBonus: Modifier,
    /** Clamps pro Stat, angewendet auf den Endwert des Stats. */
    val statLimits: Map<StatId, StatLimit> = emptyMap(),
)

/** Eine Qualitaetsstufe: gilt ab [minQuality] (inklusive) bis zur naechsten Stufe. */
@Serializable
data class QualityTierDefinition(
    val id: String,
    val minQuality: Double,
    /** Farbe der Stufe als `#RRGGBB` (Kapitel 8.4). */
    val color: String,
)

/** Unter- und Obergrenze eines Stats; `null` = keine Grenze. */
@Serializable
data class StatLimit(
    val min: Double? = null,
    val max: Double? = null,
)

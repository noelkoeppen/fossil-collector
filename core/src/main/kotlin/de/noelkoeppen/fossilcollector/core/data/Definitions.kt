package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.ModifierCategory
import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Definitions-Klassen fuer die statischen Spieldaten (GAME_DESIGN.md, Kapitel 11).
 * Sie beschreiben nur Daten, keine Logik. Texte stehen nicht hier, sondern als Schluessel
 * in locale/<sprache>.json (siehe [LocaleKeys]).
 *
 * Zahlen sind hier bewusst Double bzw. Int: Es sind feste Konfigurationswerte, keine
 * wachsenden Spielwerte. GameNumber (AP-03) betrifft den Spielzustand.
 */

/** Ein Modifier-Eintrag in den Daten (Kapitel 6.2). */
@Serializable
data class EffectDef(
    val stat: StatId,
    val op: ModifierOp,
    val value: Double,
    val category: ModifierCategory,
)

/** Eintrag in layers.json. Grabungsteams sind pro Schicht definiert. */
@Serializable
data class LayerDef(
    val id: String,
    val order: Int,
    val unlockCost: Double,
    val teamBaseCost: Double,
    val teamGrowth: Double,
    val teamRate: Double,
    val value: Double,
    val fragility: Double,
    val fameWeight: Double,
    val color: String,
)

@Serializable
enum class BodyPlan {
    @SerialName("quadruped") QUADRUPED,
    @SerialName("biped") BIPED,
}

/**
 * Buch-Bonus einer Art (Kapitel 4.7). Die Kategorie ist immer `book` und steht deshalb
 * nicht in den Daten.
 */
@Serializable
data class BookBonusDef(
    val stat: StatId,
    val op: ModifierOp,
    val value: Double,
)

/** Eintrag in species.json. */
@Serializable
data class SpeciesDef(
    val id: String,
    val layer: String,
    val rarityWeight: Double,
    val bodyPlan: BodyPlan,
    val bones: List<String>,
    val art: String,
    val bookBonus: BookBonusDef,
)

/** Eintrag in bones.json. Die Anzahl der Zonen ist pro Knochen frei (Kapitel 4.3). */
@Serializable
data class BoneDef(
    val id: String,
    val zones: List<String>,
)

@Serializable
enum class Currency {
    @SerialName("money") MONEY,
    @SerialName("research_points") RESEARCH_POINTS,
}

/** Eintrag in generators.json (Praeparator, Halle, Laborplatz). */
@Serializable
data class GeneratorDef(
    val id: String,
    val baseCost: Double,
    val growth: Double,
    val currency: Currency,
    /** Anzahl, die jeder Durchlauf gratis mitbringt. */
    val free: Int = 0,
    val effects: List<EffectDef> = emptyList(),
)

/** Eintrag in upgrades.json (z. B. Werkzeugstufen). */
@Serializable
data class UpgradeDef(
    val id: String,
    val currency: Currency,
    val baseCost: Double,
    val growth: Double,
    /** `null` = unbegrenzt. */
    val maxLevel: Int? = null,
    val effectsPerLevel: List<EffectDef>,
)

@Serializable
enum class SkillBranch {
    @SerialName("dig") DIG,
    @SerialName("prep") PREP,
    @SerialName("lab") LAB,
    @SerialName("curator") CURATOR,

    /** [Später] Ast Expedition. Wird im MVP nicht befuellt. */
    @SerialName("expedition") EXPEDITION,
}

/** Knoten in skilltree.json (Kapitel 5.4). */
@Serializable
data class SkillNodeDef(
    val id: String,
    val branch: SkillBranch,
    val depth: Int,
    val baseCost: Double,
    val requires: List<String> = emptyList(),
    val capstone: Boolean = false,
    val effects: List<EffectDef>,
)

@Serializable
enum class MilestoneConditionType {
    @SerialName("pieces_found") PIECES_FOUND,
    @SerialName("skeletons_completed") SKELETONS_COMPLETED,
    @SerialName("pristine_found") PRISTINE_FOUND,
    @SerialName("teams_total") TEAMS_TOTAL,
    @SerialName("layers_unlocked") LAYERS_UNLOCKED,
    @SerialName("reconstructions") RECONSTRUCTIONS,
    @SerialName("prestige_count") PRESTIGE_COUNT,
}

@Serializable
data class MilestoneConditionDef(
    val type: MilestoneConditionType,
    val value: Double,
)

@Serializable
data class MilestoneRewardDef(
    val money: Double = 0.0,
    val researchPoints: Double = 0.0,
)

/** Eintrag in milestones.json (Kapitel 5.2). */
@Serializable
data class MilestoneDef(
    val id: String,
    val condition: MilestoneConditionDef,
    val reward: MilestoneRewardDef,
)

@Serializable
enum class QualityTierId {
    FRAGMENT,
    DAMAGED,
    GOOD,
    EXCELLENT,
    PRISTINE,
}

/** Qualitaetsstufe: gilt ab [min] (inklusive) bis zur naechsten Stufe (Kapitel 4.2, 8.4). */
@Serializable
data class QualityTierDef(
    val id: QualityTierId,
    val min: Double,
    val color: String,
)

/** Unter- bzw. Obergrenze fuer den Endwert eines Stats (Kapitel 6.2). */
@Serializable
data class StatLimitDef(
    val min: Double? = null,
    val max: Double? = null,
)

/** Inhalt von balance.json: alle globalen Konstanten (Kapitel 6.4). */
@Serializable
data class BalanceDef(
    val findChance: Double,
    val qualityBaseMin: Double,
    val qualityBaseMax: Double,
    /** Grenzen des Zufallsfaktors fuer Praeparationsschaden, `uniform(min, max)` (Kapitel 4.2). */
    val damageRollMin: Double,
    val damageRollMax: Double,
    /** Summand in `(offset + q)` bei Einkommen, Verkaufswert und Ruhm (Kapitel 4.4, 4.6, 5.3). */
    val qualityValueOffset: Double,
    val rockSellFactor: Double,
    val duplicateSellFactor: Double,
    val labDuration: Double,
    val labQueuePerSlot: Int,
    val reconstructionCap: Double,
    /** Qualitaet, mit der fehlende Zonen beim Capstone *Restaurator* ergaenzt werden. */
    val reconstructionFillQuality: Double,
    val skeletonMult: Double,
    val slotsPerHall: Int,
    val missingBoneWeight: Double,
    val tapDigAmount: Double,
    val tapPrepAmount: Double,
    val skillCostGrowth: Double,
    val offlineEfficiency: Double,
    val offlineCapHours: Double,
    val maxDetailedOfflineFinds: Int,
    val teamMilestones: List<Int>,
    /** Faktor pro erreichter Team-Schwelle (Kapitel 4.1, Standard 2). */
    val teamMilestoneMultiplier: Double,
    /** Pauschaler Bonus fuer Completion-Stufe 1 je Art (Kapitel 4.7). */
    val researchedBonus: EffectDef,
    val qualityTiers: List<QualityTierDef>,
    /** Faktor je Qualitaetsstufe, gleiche Reihenfolge wie [qualityTiers]. */
    val bookTierFactor: List<Double>,
    val statLimits: Map<StatId, StatLimitDef> = emptyMap(),
)

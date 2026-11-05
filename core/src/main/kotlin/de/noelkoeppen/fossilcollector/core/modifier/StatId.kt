package de.noelkoeppen.fossilcollector.core.modifier

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Alle Stats, auf die Modifier wirken koennen (GAME_DESIGN.md, Kapitel 6.2).
 * Der JSON-Name ist der Bezeichner aus dem Design-Dokument. Unbekannte Stat-IDs in den
 * Datendateien fuehren deshalb schon beim Laden zu einem Fehler.
 *
 * Die Berechnung (ModifierEngine) folgt in AP-04.
 */
@Serializable
enum class StatId {
    @SerialName("dig_rate") DIG_RATE,
    @SerialName("prep_rate") PREP_RATE,
    @SerialName("find_chance") FIND_CHANCE,
    @SerialName("damage_reduction") DAMAGE_REDUCTION,
    @SerialName("quality_base_min") QUALITY_BASE_MIN,
    @SerialName("museum_income") MUSEUM_INCOME,
    @SerialName("skeleton_bonus") SKELETON_BONUS,
    @SerialName("sell_price") SELL_PRICE,
    @SerialName("lab_speed") LAB_SPEED,
    @SerialName("research_points") RESEARCH_POINTS,
    @SerialName("fame_gain") FAME_GAIN,
    @SerialName("layer_unlock_cost") LAYER_UNLOCK_COST,
    @SerialName("hall_cost") HALL_COST,
    @SerialName("start_teams") START_TEAMS,
    @SerialName("start_lab_slots") START_LAB_SLOTS,
    @SerialName("guaranteed_fossil_chance") GUARANTEED_FOSSIL_CHANCE,
    @SerialName("no_damage_chance") NO_DAMAGE_CHANCE,
    @SerialName("reconstruction_missing_zones") RECONSTRUCTION_MISSING_ZONES,
    @SerialName("special_exhibit_mult") SPECIAL_EXHIBIT_MULT,
    @SerialName("offline_efficiency") OFFLINE_EFFICIENCY,
    @SerialName("offline_cap_hours") OFFLINE_CAP_HOURS,
}

/** `add` wirkt auf den Basiswert, `mult` prozentual (Kapitel 6.2). */
@Serializable
enum class ModifierOp {
    @SerialName("add") ADD,
    @SerialName("mult") MULT,
}

/** Innerhalb einer Kategorie wird addiert, zwischen Kategorien multipliziert (Kapitel 6.1). */
@Serializable
enum class ModifierCategory {
    @SerialName("upgrade") UPGRADE,
    @SerialName("skill") SKILL,
    @SerialName("book") BOOK,
    @SerialName("milestone") MILESTONE,

    /** [Später] Ereignisse. Existiert schon, damit das Datenformat es nicht verbaut. */
    @SerialName("event") EVENT,
}

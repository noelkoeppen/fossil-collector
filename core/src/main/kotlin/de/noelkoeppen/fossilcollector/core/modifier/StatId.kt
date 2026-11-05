package de.noelkoeppen.fossilcollector.core.modifier

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Alle Stat-IDs des MVP (GAME_DESIGN.md, Kapitel 6.2). In JSON werden sie in snake_case
 * geschrieben, siehe [serialName].
 *
 * Faktor-Stats haben die Basis 1.0 und wirken als Faktor auf einen Wert aus den Daten:
 * `museum_income`, `sell_price`, `research_points`, `fame_gain`, `layer_unlock_cost`,
 * `hall_cost` und `lab_speed`. `lab_speed` ist der Faktor auf `balance.labDuration`;
 * negative `mult`-Werte verkuerzen also die Laborzeit. Clamps aus `balance.statLimits`
 * gelten fuer den Endwert eines Stats.
 */
@Serializable
enum class StatId(val serialName: String) {
    @SerialName("dig_rate") DIG_RATE("dig_rate"),
    @SerialName("prep_rate") PREP_RATE("prep_rate"),
    @SerialName("find_chance") FIND_CHANCE("find_chance"),
    @SerialName("damage_reduction") DAMAGE_REDUCTION("damage_reduction"),
    @SerialName("quality_base_min") QUALITY_BASE_MIN("quality_base_min"),
    @SerialName("museum_income") MUSEUM_INCOME("museum_income"),
    @SerialName("skeleton_bonus") SKELETON_BONUS("skeleton_bonus"),
    @SerialName("sell_price") SELL_PRICE("sell_price"),
    @SerialName("lab_speed") LAB_SPEED("lab_speed"),
    @SerialName("research_points") RESEARCH_POINTS("research_points"),
    @SerialName("fame_gain") FAME_GAIN("fame_gain"),
    @SerialName("layer_unlock_cost") LAYER_UNLOCK_COST("layer_unlock_cost"),
    @SerialName("hall_cost") HALL_COST("hall_cost"),
    @SerialName("start_teams") START_TEAMS("start_teams"),
    @SerialName("start_lab_slots") START_LAB_SLOTS("start_lab_slots"),
    @SerialName("guaranteed_fossil_chance") GUARANTEED_FOSSIL_CHANCE("guaranteed_fossil_chance"),
    @SerialName("no_damage_chance") NO_DAMAGE_CHANCE("no_damage_chance"),
    @SerialName("reconstruction_missing_zones") RECONSTRUCTION_MISSING_ZONES("reconstruction_missing_zones"),
    @SerialName("special_exhibit_mult") SPECIAL_EXHIBIT_MULT("special_exhibit_mult"),
    @SerialName("offline_efficiency") OFFLINE_EFFICIENCY("offline_efficiency"),
    @SerialName("offline_cap_hours") OFFLINE_CAP_HOURS("offline_cap_hours");

    companion object {
        /** Sucht eine Stat-ID ueber ihren JSON-Namen, `null` wenn unbekannt. */
        fun fromSerialName(name: String): StatId? = entries.firstOrNull { it.serialName == name }
    }
}

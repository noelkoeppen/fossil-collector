package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ein Meilenstein aus `milestones.json` (Kapitel 5.2). */
@Serializable
data class MilestoneDefinition(
    val id: String,
    val condition: MilestoneCondition,
    val reward: MilestoneReward,
)

/** Bedingung: Der Zaehler [type] erreicht mindestens [value]. */
@Serializable
data class MilestoneCondition(
    val type: MilestoneConditionType,
    val value: Double,
)

/** Zaehler, auf die sich Meilensteine beziehen. */
@Serializable
enum class MilestoneConditionType {
    /** Gefundene Fossilien insgesamt (Statistik, permanent). */
    @SerialName("pieces_found") PIECES_FOUND,
    /** Vollstaendige Skelette (Completion-Stufe 2) im Sammelbuch. */
    @SerialName("skeletons_completed") SKELETONS_COMPLETED,
    /** Funde in Qualitaetsstufe Museumsreif. */
    @SerialName("pristine_found") PRISTINE_FOUND,
    /** Grabungsteams ueber alle Schichten im aktuellen Durchlauf. */
    @SerialName("teams_total") TEAMS_TOTAL,
    /** Freigeschaltete Schichten im aktuellen Durchlauf. */
    @SerialName("layers_unlocked") LAYERS_UNLOCKED,
    /** Abgeschlossene Forschungen im Labor. */
    @SerialName("research_completed") RESEARCH_COMPLETED,
    /** Durchgefuehrte Rekonstruktionen. */
    @SerialName("reconstructions") RECONSTRUCTIONS,
    /** Durchgefuehrte Prestiges. */
    @SerialName("prestiges") PRESTIGES,
}

/** Belohnung eines Meilensteins. Optionale Modifier laufen in der Kategorie `milestone`. */
@Serializable
data class MilestoneReward(
    val money: Double = 0.0,
    val researchPoints: Double = 0.0,
    val effects: List<Modifier> = emptyList(),
)

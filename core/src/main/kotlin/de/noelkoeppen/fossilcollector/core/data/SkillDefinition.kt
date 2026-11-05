package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Ein Knoten aus `skilltree.json` (Kapitel 5.4). */
@Serializable
data class SkillDefinition(
    val id: String,
    val branch: SkillBranch,
    /** Tiefe im Ast, 1 bis 5. */
    val depth: Int,
    /** Grundkosten in Ruhm vor `balance.skillCostGrowth`. */
    val baseCost: Double,
    /** IDs der Knoten, die vorher gekauft sein muessen. */
    val requires: List<String> = emptyList(),
    val capstone: Boolean = false,
    val effects: List<Modifier>,
)

/** Aeste des Skilltrees. `EXPEDITION` ist [Später] und hat im MVP keine Knoten. */
@Serializable
enum class SkillBranch(val serialName: String) {
    @SerialName("dig") DIG("dig"),
    @SerialName("prep") PREP("prep"),
    @SerialName("lab") LAB("lab"),
    @SerialName("curator") CURATOR("curator"),
    @SerialName("expedition") EXPEDITION("expedition"),
}

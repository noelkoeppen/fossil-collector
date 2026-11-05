package de.noelkoeppen.fossilcollector.core.modifier

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Datengetriebener Bonus in Definitionsform (GAME_DESIGN.md, Kapitel 6.2), z. B.
 * `{ "stat": "museum_income", "op": "mult", "value": 0.10, "category": "skill" }`.
 *
 * Die Verrechnung uebernimmt [ModifierEngine].
 */
@Serializable
data class Modifier(
    val stat: StatId,
    val op: ModifierOp,
    val value: Double,
    val category: ModifierCategory,
)

/** Rechenart eines Modifiers: `add` addiert auf den Basiswert, `mult` wirkt prozentual. */
@Serializable
enum class ModifierOp {
    @SerialName("add") ADD,
    @SerialName("mult") MULT,
}

/**
 * Kategorie eines Modifiers. Innerhalb einer Kategorie wird addiert, zwischen den Kategorien
 * multipliziert (Kapitel 6.1). `EVENT` ist [Später] und hat im MVP keine Quellen.
 */
@Serializable
enum class ModifierCategory {
    @SerialName("upgrade") UPGRADE,
    @SerialName("skill") SKILL,
    @SerialName("book") BOOK,
    @SerialName("milestone") MILESTONE,
    @SerialName("event") EVENT,
}

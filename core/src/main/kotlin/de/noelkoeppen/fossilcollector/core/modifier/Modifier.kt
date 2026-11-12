package de.noelkoeppen.fossilcollector.core.modifier

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Datengetriebener Bonus in Definitionsform, z. B.
 * `{ "stat": "sell_price", "op": "mult", "value": 0.10, "category": "upgrade" }`.
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
 * multipliziert. `EVENT` ist [Später] (Ereignisse an Funden) und hat im MVP keine Quellen.
 */
@Serializable
enum class ModifierCategory {
    @SerialName("upgrade") UPGRADE,
    @SerialName("event") EVENT,
}

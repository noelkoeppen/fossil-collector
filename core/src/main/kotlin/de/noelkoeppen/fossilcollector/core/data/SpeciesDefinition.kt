package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Eine Art aus `species.json` (Kapitel 4.7, 6.4, 11). */
@Serializable
data class SpeciesDefinition(
    val id: String,
    /** ID der Schicht, in der die Art gefunden wird. */
    val layer: String,
    /** Gewicht bei der Artenwahl innerhalb der Schicht. */
    val rarityWeight: Double,
    val bodyPlan: BodyPlan,
    /** Knochen-IDs aus `bones.json`, die das Skelett dieser Art bilden. */
    val bones: List<String>,
    /** Dateiname der Skelett-Grafik (entsteht in AP-21/AP-27). */
    val art: String,
    val bookBonus: BookBonusDefinition,
)

/**
 * Koerperbau, bestimmt die Platzhalter-Grafik `skeleton_<serialName>.json` (Kapitel 8.5).
 *
 * @property serialName Name im JSON
 */
@Serializable
enum class BodyPlan(val serialName: String) {
    @SerialName("biped") BIPED("biped"),
    @SerialName("quadruped") QUADRUPED("quadruped"),
}

/**
 * Buch-Bonus einer Art (Kapitel 4.7). Die Kategorie ist immer `book`; der wirksame Wert ist
 * `value * balance.bookTierFactor[minTier]`.
 */
@Serializable
data class BookBonusDefinition(
    val stat: StatId,
    val op: ModifierOp,
    /** Basiswert vor dem Stufenfaktor. */
    val value: Double,
)

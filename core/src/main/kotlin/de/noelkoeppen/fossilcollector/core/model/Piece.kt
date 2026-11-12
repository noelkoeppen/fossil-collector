package de.noelkoeppen.fossilcollector.core.model

import kotlinx.serialization.Serializable

/**
 * Ein einzelner Knochen einer Art mit genau einer Qualitaet (Kapitel 4).
 *
 * Referenzen auf die Spieldaten ([speciesId], [boneId]) werden nicht hier, sondern mit
 * [ReferenceValidator] gegen `GameData` geprueft, weil das Modell die Daten nicht kennt.
 *
 * @property speciesId ID der Art aus `species.json`
 * @property boneId ID des Knochens innerhalb der Art (`species.json: bones`)
 * @property quality Qualitaet von 0 (schlechteste) bis 1 (beste); die Stufe ergibt sich aus
 *   `balance.json: qualityTiers`
 * @property age Altersstufe [Spaeter], bis dahin immer `null`
 * @property sex Geschlecht [Spaeter], bis dahin immer `null`
 */
@Serializable
data class Piece(
    val speciesId: String,
    val boneId: String,
    val quality: Double,
    val age: String? = null,
    val sex: String? = null,
) {
    init {
        require(quality in MIN_QUALITY..MAX_QUALITY) { "Qualitaet $quality liegt nicht in $MIN_QUALITY..$MAX_QUALITY" }
    }

    companion object {
        /** Untergrenze der Qualitaetsskala (Definition der Skala, kein Balancing-Wert). */
        const val MIN_QUALITY: Double = 0.0

        /** Obergrenze der Qualitaetsskala (Definition der Skala, kein Balancing-Wert). */
        const val MAX_QUALITY: Double = 1.0
    }
}

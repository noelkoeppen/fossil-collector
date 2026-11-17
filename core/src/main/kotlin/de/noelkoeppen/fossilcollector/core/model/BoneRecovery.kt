package de.noelkoeppen.fossilcollector.core.model

import kotlinx.serialization.Serializable

/**
 * Bergen eines Knochens (Kapitel 5.2, AP2-09): bruechige Stellen und ob sie mit Gips gesichert sind.
 *
 * Die Stellen liegen auf Knochenzellen der Nahansicht des Knochens ([BonePreparation], gleicher
 * Zellindex `y * width + x` im feinen Raster); die Nahansicht muss fertig freigelegt sein. Das
 * prueft [ExcavationSite] beim Erzeugen. [recovered] wird gesetzt, sobald der Knochen
 * herausgehoben ist; ab dann liegt er im Inventar (`GameState.inventory`) und nicht mehr in der
 * Flaeche. Der Fund selbst bleibt unveraendert in der Flaeche stehen (sonst wuerden die Knochen
 * eines Fundes im Nachbarschaftsgraph auseinanderfallen); geborgene Knochen werden nur markiert.
 *
 * Unveraenderlich; Aktionen (`Recoverer`) erzeugen einen neuen Zustand.
 *
 * @property findIndex Fund in `ExcavationSite.finds`
 * @property boneId Knochen des Fundes
 * @property spots bruechige Stellen als feine Zellen der Nahansicht, ohne Duplikate
 * @property secured je Stelle in [spots], ob sie gesichert ist
 * @property recovered Knochen ist geborgen (im Inventar)
 */
@Serializable
data class BoneRecovery(
    val findIndex: Int,
    val boneId: String,
    val spots: List<Int>,
    val secured: List<Boolean>,
    val recovered: Boolean = false,
) {
    init {
        require(findIndex >= 0) { "findIndex muss >= 0 sein" }
        require(secured.size == spots.size) { "secured (${secured.size}) passt nicht zu ${spots.size} Stellen" }
        require(spots.all { it >= 0 }) { "Stellen muessen >= 0 sein" }
        require(spots.toSet().size == spots.size) { "Stelle doppelt" }
    }

    /** Knochen dieses Bergens. */
    val ref: BoneRef get() = BoneRef(findIndex, boneId)

    /** Anzahl gesicherter Stellen. */
    val securedCount: Int get() = secured.count { it }

    /** Anzahl ungesicherter Stellen. */
    val unsecuredCount: Int get() = spots.size - securedCount

    /** Neuer Zustand, in dem Stelle [index] (Index in [spots]) gesichert ist. */
    fun withSecured(index: Int): BoneRecovery = copy(secured = secured.mapIndexed { i, s -> s || i == index })
}

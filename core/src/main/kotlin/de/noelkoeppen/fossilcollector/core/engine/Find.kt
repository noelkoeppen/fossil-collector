package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey

/**
 * Ein frisch freigelegtes Fossil (Ergebnis der Praeparation, Kapitel 4.2).
 *
 * Ein Fund ist **transient**: Er wird nicht gespeichert, sondern von der Praeparation erzeugt und
 * sofort an den [FindHandler] uebergeben (AP-09: Buch, Museum, Labor oder Verkauf, Kapitel 4.4)
 * sowie als [GameEvent.FossilFound] gemeldet.
 *
 * @property pieceKey Schluessel `speciesId:boneId:age:sex`; im MVP sind `age`/`sex` immer `null`
 * @property layerId Schicht, aus deren Block der Fund stammt (Grundlage fuer `layer.value`)
 * @property zones Qualitaet je Zone (0.0 bis 1.0) in der Reihenfolge der Zonen aus `bones.json`
 * @property undamaged `true`, wenn der Fund durch `no_damage_chance` keinen Schaden genommen hat
 */
data class Find(
    val pieceKey: PieceKey,
    val layerId: String,
    val zones: List<Double>,
    val undamaged: Boolean = false,
) {
    /** Qualitaet des Stuecks = Mittelwert der Zonen (Kapitel 4.2, Schritt 5). */
    val quality: Double get() = toPiece().quality

    /** Das Stueck in der Form, in der es im Run-Bestand bzw. Labor gespeichert wird. */
    fun toPiece(): Piece = Piece(zones)
}

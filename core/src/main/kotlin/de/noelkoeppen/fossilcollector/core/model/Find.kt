package de.noelkoeppen.fossilcollector.core.model

import kotlinx.serialization.Serializable

/**
 * Ein Fund: zusammenhaengende Knochen einer Art, die beieinander in der Grabungsflaeche liegen
 * (Kapitel 4). Gewuerfelt wird ein Fund in AP2-04, platziert und gespeichert in AP2-05.
 *
 * Geprueft wird hier nur, was ohne Spieldaten geht (nicht leer, eine Art, keine doppelten Knochen).
 * Ob die Knochen im Nachbarschaftsgraph der Art zusammenhaengen, prueft [ReferenceValidator.validateFind].
 *
 * @property speciesId Art aller Knochen des Fundes
 * @property pieces die Knochen des Fundes, jeder mit eigener Qualitaet
 */
@Serializable
data class Find(
    val speciesId: String,
    val pieces: List<Piece>,
) {
    init {
        require(pieces.isNotEmpty()) { "Fund ohne Knochen" }
        require(pieces.all { it.speciesId == speciesId }) { "Fund enthaelt Knochen einer anderen Art als $speciesId" }
        require(pieces.map { it.boneId }.toSet().size == pieces.size) { "Fund enthaelt einen Knochen doppelt" }
    }

    /** IDs der Knochen des Fundes. */
    val boneIds: List<String> get() = pieces.map { it.boneId }
}

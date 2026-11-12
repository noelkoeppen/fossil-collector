package de.noelkoeppen.fossilcollector.core.model

import de.noelkoeppen.fossilcollector.core.data.GameData

/**
 * Prueft Spielstand-Inhalte gegen die Spieldaten: Alle IDs muessen existieren, Funde muessen im
 * Nachbarschaftsgraph der Art zusammenhaengen. Wird beim Import eines Spielstands verwendet.
 * Eine leere Liste bedeutet: alles gueltig.
 */
object ReferenceValidator {

    /** Alle Referenzfehler eines Spielstands. */
    fun validate(state: GameState, data: GameData): List<String> =
        state.inventory.flatMapIndexed { i, piece -> validatePiece(piece, data).map { "inventory[$i]: $it" } }

    /** Art und Knochen eines [Piece] muessen in den Daten existieren. */
    fun validatePiece(piece: Piece, data: GameData): List<String> {
        val species = data.speciesById[piece.speciesId] ?: return listOf("unbekannte Art '${piece.speciesId}'")
        return if (piece.boneId in species.bones) {
            emptyList()
        } else {
            listOf("unbekannter Knochen '${piece.boneId}' fuer Art '${piece.speciesId}'")
        }
    }

    /** Referenzen aller Knochen und Zusammenhang des Fundes im Nachbarschaftsgraph. */
    fun validateFind(find: Find, data: GameData): List<String> {
        val pieceErrors = find.pieces.flatMap { validatePiece(it, data) }
        if (pieceErrors.isNotEmpty()) return pieceErrors
        val species = data.species(find.speciesId)
        return if (species.isConnected(find.boneIds)) {
            emptyList()
        } else {
            listOf("Knochen ${find.boneIds} haengen im Graph von '${find.speciesId}' nicht zusammen")
        }
    }
}

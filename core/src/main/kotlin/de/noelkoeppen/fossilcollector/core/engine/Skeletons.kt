package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.SpeciesDefinition
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.RunState

/**
 * Skelett-Erkennung (Kapitel 4.6 und 4.7, Stufe 2).
 *
 * Eine Art ist **vollstaendig**, wenn es fuer jeden Knochen aus `species.bones` mindestens einen
 * Eintrag mit diesem `speciesId:boneId` gibt. Alter und Geschlecht spielen keine Rolle (Stufe 2);
 * konsistente Skelette (Stufe 3) sind [Spaeter].
 *
 * - **Durchlauf** ([isCompleteInRun], [completeSpeciesInRun]): Grundlage fuer den Skelett-Faktor im
 *   Museum (AP-09).
 * - **Sammelbuch** ([isCompleteInBook], [completeSpeciesInBook]): Grundlage fuer Completion-Stufe 2
 *   und Buch-Boni (AP-11).
 *
 * Reine Funktionen, Aufwand linear in der Zahl der Eintraege.
 */
object Skeletons {

    /** `true`, wenn [run] fuer alle Knochen von [species] einen Fund enthaelt. */
    fun isCompleteInRun(run: RunState, species: SpeciesDefinition): Boolean =
        isComplete(run.pieces.keys, species)

    /** IDs aller Arten, deren Skelett im aktuellen Durchlauf vollstaendig ist. */
    fun completeSpeciesInRun(run: RunState, data: GameData): Set<String> =
        completeSpecies(run.pieces.keys, data)

    /** `true`, wenn [book] fuer alle Knochen von [species] einen Eintrag enthaelt. */
    fun isCompleteInBook(book: Map<PieceKey, BookEntry>, species: SpeciesDefinition): Boolean =
        isComplete(book.keys, species)

    /** IDs aller Arten, deren Skelett im Sammelbuch vollstaendig ist (Completion-Stufe 2). */
    fun completeSpeciesInBook(book: Map<PieceKey, BookEntry>, data: GameData): Set<String> =
        completeSpecies(book.keys, data)

    /** Knochen-IDs von [species], fuer die es in [keys] noch keinen Eintrag gibt (Reihenfolge aus den Daten). */
    fun missingBones(keys: Collection<PieceKey>, species: SpeciesDefinition): List<String> {
        val present = presentBones(keys, species.id)
        return species.bones.filter { it !in present }
    }

    /** `true`, wenn [keys] fuer alle Knochen von [species] einen Eintrag enthalten. */
    fun isComplete(keys: Collection<PieceKey>, species: SpeciesDefinition): Boolean {
        if (species.bones.isEmpty()) return false
        // Schneller Weg fuer den MVP (age/sex = null), sonst Suche ueber alle Schluessel.
        if (keys is Set<PieceKey> && species.bones.all { PieceKey(species.id, it) in keys }) return true
        return missingBones(keys, species).isEmpty()
    }

    /** IDs aller Arten aus [data], deren Knochen in [keys] vollstaendig vorhanden sind. */
    fun completeSpecies(keys: Collection<PieceKey>, data: GameData): Set<String> {
        val bySpecies = HashMap<String, MutableSet<String>>()
        for (key in keys) bySpecies.getOrPut(key.speciesId) { HashSet() }.add(key.boneId)
        return data.species
            .filter { species ->
                val present = bySpecies[species.id]
                present != null && species.bones.isNotEmpty() && present.containsAll(species.bones)
            }
            .mapTo(LinkedHashSet()) { it.id }
    }

    private fun presentBones(keys: Collection<PieceKey>, speciesId: String): Set<String> =
        keys.asSequence().filter { it.speciesId == speciesId }.mapTo(HashSet()) { it.boneId }
}

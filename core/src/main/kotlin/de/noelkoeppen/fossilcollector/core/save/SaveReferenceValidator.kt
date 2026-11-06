package de.noelkoeppen.fossilcollector.core.save

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.PieceKey

/**
 * Prueft, ob ein Spielstand nur IDs enthaelt, die es in den Spieldaten gibt (AP-24, Kapitel 7.2).
 *
 * [SaveCodec] prueft nur die Struktur. Ein importierter Text (Kapitel 7.2, Export/Import) kann aber
 * IDs enthalten, die die Engine nicht kennt (von Hand bearbeitet, aus einem Entwicklungsstand).
 * Die Engine schlaegt solche IDs ueber `GameData.layer(...)` usw. nach und wuerde dann bei jedem
 * Tick abstuerzen; weil ein Import sofort gespeichert wird, waere das Spiel danach unbrauchbar.
 * Der Import lehnt solche Staende deshalb vorher ab.
 *
 * Geprueft werden Schicht-IDs (`run.teams`, `run.blocks`), Arten und Knochen aller [PieceKey]s
 * (Knochen muss zur Art gehoeren), Zonen-IDs der Ersatzteile, Skills, erforschte Arten,
 * Meilensteine, die Sonderausstellung sowie `run.unlockedLayers` (1 bis Anzahl Schichten).
 * `age`/`sex` sind Felder fuer spaeter (Kapitel 4.8) ohne Datendefinition und werden nicht geprueft.
 * Nicht endliche Zahlen fallen schon beim Dekodieren auf (JSON ohne `NaN`/`Infinity`).
 */
object SaveReferenceValidator {

    /**
     * Beschreibung der ersten unbekannten Referenz in [state] (z. B. `meta.skills: foo`) oder
     * `null`, wenn alle Referenzen zu [data] passen.
     */
    fun findUnknownReference(state: GameState, data: GameData): String? {
        val meta = state.meta
        val run = state.run

        meta.skills.firstOrNull { it !in data.skillsById }?.let { return "meta.skills: $it" }
        meta.researchedSpecies.firstOrNull { it !in data.speciesById }?.let { return "meta.researchedSpecies: $it" }
        meta.milestonesDone.firstOrNull { it !in data.milestonesById }?.let { return "meta.milestonesDone: $it" }
        meta.book.keys.firstOrNull { !isKnownPiece(it, data) }?.let { return "meta.book: $it" }

        val unlocked = run.unlockedLayers.toDouble()
        if (unlocked < 1.0 || unlocked > data.layers.size) return "run.unlockedLayers: $unlocked"
        run.teams.keys.firstOrNull { it !in data.layersById }?.let { return "run.teams: $it" }
        run.blocks.keys.firstOrNull { it !in data.layersById }?.let { return "run.blocks: $it" }
        run.pieces.keys.firstOrNull { !isKnownPiece(it, data) }?.let { return "run.pieces: $it" }
        run.spareZones.keys.firstOrNull { key ->
            !isKnownPiece(key.piece, data) || key.zoneId !in data.bone(key.piece.boneId).zones
        }?.let { return "run.spareZones: $it" }
        run.labQueue.firstOrNull { !isKnownPiece(it.piece, data) }?.let { return "run.labQueue: ${it.piece}" }
        run.labJobs.firstOrNull { !isKnownPiece(it.piece, data) }?.let { return "run.labJobs: ${it.piece}" }
        run.specialExhibit?.let { if (it !in data.speciesById) return "run.specialExhibit: $it" }
        return null
    }

    private fun isKnownPiece(key: PieceKey, data: GameData): Boolean {
        val species = data.speciesById[key.speciesId] ?: return false
        return key.boneId in data.bonesById && key.boneId in species.bones
    }
}

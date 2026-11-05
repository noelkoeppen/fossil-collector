package de.noelkoeppen.fossilcollector.ui.lab

import de.noelkoeppen.fossilcollector.core.data.DefinitionIds
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.QualityTiers
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId

/**
 * Baut den [LabUiState] aus Engine und Spielstand (ausserhalb der Composables, testbar). Alle Werte
 * stammen aus `core` (`GameEngine.lab`, `labQueueCapacity`, `labSlotQuote`, `toolLevelQuote`,
 * `damageReduction`, `reconstructableBones`); hier wird nichts berechnet, was Spiellogik ist.
 *
 * Fortschrittsbalken: `lab.progress(job)` leitet den Fortschritt direkt aus `remaining / duration`
 * ab. Die UI zeigt ihn ohne Animation, damit der Balken beim Wechsel auf das naechste Stueck nicht
 * zurueckschwingt oder flackert.
 *
 * Nutzt den Stand der Modifier-Quellen nach dem letzten `advance` bzw. `syncModifiers`
 * (`reconstructableBones` gleicht selbst ab).
 */
object LabUiMapper {

    /** Obergrenze fuer die Zahl der angezeigten Plaetze (Schutz vor absurden Bestaenden). */
    const val MAX_UI_SLOTS: Int = 1_000

    fun map(engine: GameEngine, state: GameState, data: GameData): LabUiState {
        val tiers = QualityTiers.fromGameData(data)
        val run = state.run

        val jobs = run.labJobs.map { job ->
            val quality = Piece(job.zones).quality
            LabJobUi(
                speciesId = job.piece.speciesId,
                boneId = job.piece.boneId,
                tierId = tiers.tierOf(quality).id,
                quality = quality,
                progress = engine.lab.progress(job),
                remainingSeconds = job.remaining.coerceAtLeast(0.0),
                researchPoints = engine.lab.researchPointsFor(job.piece),
            )
        }

        val queue = run.labQueue.map { queued ->
            val quality = Piece(queued.zones).quality
            QueuedPieceUi(
                speciesId = queued.piece.speciesId,
                boneId = queued.piece.boneId,
                tierId = tiers.tierOf(quality).id,
                quality = quality,
            )
        }

        val reconstructions = engine.reconstructableBones(state).map { preview ->
            ReconstructionUi(
                pieceKey = preview.pieceKey,
                key = preview.pieceKey.toString(),
                speciesId = preview.pieceKey.speciesId,
                boneId = preview.pieceKey.boneId,
                tierId = tiers.tierOf(preview.quality).id,
                quality = preview.quality,
                missingZones = preview.missingZones,
                improvesRun = preview.improvesRun,
                improvesBook = preview.improvesBook,
            )
        }

        return LabUiState(
            slotCount = engine.lab.slots(state).coerceAtMost(MAX_UI_SLOTS),
            jobs = jobs,
            jobDuration = engine.lab.jobDuration(),
            queue = queue,
            queueCapacity = engine.labQueueCapacity(state),
            labSlots = run.labSlots,
            labSlotQuotes = BuyMode.entries.associateWith { engine.labSlotQuote(state, it) },
            toolLevel = run.toolLevel,
            damageReduction = engine.damageReduction(),
            damageReductionPerLevel = damageReductionPerLevel(data),
            toolQuotes = BuyMode.entries.associateWith { engine.toolLevelQuote(state, it) },
            reconstructions = reconstructions,
        )
    }

    /** Summe der additiven `damage_reduction`-Effekte einer Werkzeugstufe aus `upgrades.json`. */
    private fun damageReductionPerLevel(data: GameData): Double =
        data.upgrades.firstOrNull { it.id == DefinitionIds.TOOLS }
            ?.effectsPerLevel
            ?.filter { it.stat == StatId.DAMAGE_REDUCTION && it.op == ModifierOp.ADD }
            ?.sumOf { it.value }
            ?: 0.0
}

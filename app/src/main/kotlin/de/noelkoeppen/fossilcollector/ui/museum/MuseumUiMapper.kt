package de.noelkoeppen.fossilcollector.ui.museum

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.QualityTiers
import de.noelkoeppen.fossilcollector.core.engine.Skeletons
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.model.GameState
import kotlin.math.floor
import kotlin.math.min

/**
 * Baut den [MuseumUiState] aus Engine und Spielstand (ausserhalb der Composables, testbar).
 * Alle Werte stammen aus `core` (`GameEngine.incomePerSecond`, `exhibitedPieces`,
 * `museum.slots`, `hallQuote`, [Skeletons]); hier wird nichts berechnet, was Spiellogik ist.
 *
 * Nutzt den Stand der Modifier-Quellen nach dem letzten `advance` bzw. `syncModifiers`.
 */
object MuseumUiMapper {

    /** Obergrenze fuer die Zahl der Plaetze in der UI (Schutz vor absurden Hallenzahlen). */
    private const val MAX_UI_SLOTS: Double = 100_000.0

    fun map(engine: GameEngine, state: GameState, data: GameData): MuseumUiState {
        val tiers = QualityTiers.fromGameData(data)
        val slotsPerHall = floor(data.balance.slotsPerHall).toInt().coerceAtLeast(0)
        val slotCount = min(engine.museum.slots(state.run), MAX_UI_SLOTS).toInt()

        val exhibits = engine.exhibitedPieces(state).map { exhibited ->
            val quality = exhibited.piece.quality
            val tier = tiers.tierOf(quality)
            ExhibitUi(
                key = exhibited.pieceKey.toString(),
                speciesId = exhibited.pieceKey.speciesId,
                boneId = exhibited.pieceKey.boneId,
                tierId = tier.id,
                quality = quality,
                incomePerSecond = exhibited.incomePerSecond,
                skeletonComplete = exhibited.skeletonComplete,
                specialExhibit = exhibited.specialExhibit,
            )
        }

        val completeIds = Skeletons.completeSpeciesInRun(state.run, data)
        val completeSkeletons = data.species.map { it.id }.filter { it in completeIds }

        val specialFactor = engine.museum.specialExhibitFactor()
        val selected = state.run.specialExhibit
        val specialExhibit = if (specialFactor > 0.0) {
            SpecialExhibitUi(
                factor = specialFactor,
                selectedSpeciesId = selected,
                active = engine.museum.isSpecialExhibitActive(state),
                options = completeSkeletons,
            )
        } else {
            null
        }

        return MuseumUiState(
            incomePerSecond = engine.incomePerSecond(state),
            halls = state.run.halls,
            slotsPerHall = slotsPerHall,
            slotCount = slotCount,
            exhibits = exhibits,
            hallQuotes = BuyMode.entries.associateWith { engine.hallQuote(state, it) },
            skeletonFactor = engine.museum.skeletonFactor(),
            completeSkeletons = completeSkeletons,
            specialExhibit = specialExhibit,
        )
    }
}

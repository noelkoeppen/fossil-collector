package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseTarget
import de.noelkoeppen.fossilcollector.core.engine.purchase.Purchases
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Leitet den [DigUiState] aus dem Spielstand ab (AP-17). Nutzt ausschliesslich Abfragen aus `core`
 * ([GameEngine], [Purchases]); hier steht keine Spiellogik, nur die Zusammenstellung fuer die Anzeige.
 *
 * Die Modifier-Quellen der Engine muessen zum Zustand passen (nach `advance` bzw. `syncModifiers`).
 */
object DigStateMapper {

    fun map(data: GameData, engine: GameEngine, purchases: Purchases, state: GameState): DigUiState {
        val run = state.run
        val nextLocked = purchases.nextLockedLayer(state)
        val layers = data.layers.map { layer ->
            if (purchases.isLayerUnlocked(state, layer.id)) {
                DigLayerUi.Unlocked(
                    layerId = layer.id,
                    color = layer.color,
                    blocks = run.blocks[layer.id] ?: GameNumber.ZERO,
                    blocksPerSecond = engine.blocksPerSecond(state, layer.id),
                    teams = run.teams[layer.id] ?: GameNumber.ZERO,
                    teamQuotes = quotes { purchases.quote(state, PurchaseTarget.Teams(layer.id), it) },
                )
            } else {
                val cost = purchases.unlockCost(layer)
                val isNext = layer.id == nextLocked?.id
                DigLayerUi.Locked(
                    layerId = layer.id,
                    color = layer.color,
                    unlockCost = cost,
                    savingsProgress = savingsProgress(run.money, cost),
                    isNext = isNext,
                    canUnlock = isNext && run.money >= cost,
                )
            }
        }
        val available = engine.dig.unlockedLayers(run)
            .fold(GameNumber.ZERO) { sum, layer -> sum + (run.blocks[layer.id] ?: GameNumber.ZERO).floor() }
        val prep = PrepUi(
            preparators = run.preparators,
            ratePerSecond = GameNumber(engine.prepRate()),
            availableBlocks = available,
            progress = run.prepProgress.toDouble().coerceIn(0.0, 1.0),
            canTap = available >= GameNumber.ONE,
            preparatorQuotes = quotes { purchases.quote(state, PurchaseTarget.Preparators, it) },
        )
        return DigUiState(layers, prep)
    }

    /** Spar-Fortschritt `money / cost`, begrenzt auf 0..1; kostenlose Schichten gelten als erreicht. */
    fun savingsProgress(money: GameNumber, cost: GameNumber): Double {
        if (cost <= GameNumber.ZERO) return 1.0
        val ratio = (money / cost).toDouble()
        return if (ratio.isNaN()) 0.0 else ratio.coerceIn(0.0, 1.0)
    }

    private inline fun <T> quotes(quote: (BuyMode) -> T): Map<BuyMode, T> =
        BuyMode.entries.associateWith { quote(it) }
}

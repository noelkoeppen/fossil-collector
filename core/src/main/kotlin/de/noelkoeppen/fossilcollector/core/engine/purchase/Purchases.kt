package de.noelkoeppen.fossilcollector.core.engine.purchase

import de.noelkoeppen.fossilcollector.core.data.Currency
import de.noelkoeppen.fossilcollector.core.data.DefinitionIds
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.LayerDefinition
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.RunState
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Kaufaktionen fuer Geld bzw. FP (GAME_DESIGN.md, Kapitel 4.1, 4.5, 4.6, 6.3, 6.4).
 *
 * Alle Aktionen sind rein: Sie veraendern weder den uebergebenen [GameState] noch die
 * [ModifierEngine], sondern geben ein [PurchaseResult] mit einem neuen Zustand zurueck.
 *
 * **Preisindex:** Grundlage der Kostenkurve ist die Zahl der *bezahlten* Einheiten
 * `n = max(0, Bestand - free)`. `free` stammt aus `generators.json` (Hallen, Laborplaetze), daher
 * kostet z. B. die erste kaufbare Halle bei 1 Gratis-Halle `baseCost`. Einheiten aus Start-Boni
 * (`start_teams`, `start_lab_slots`) zaehlen wie gekaufte Einheiten (Begruendung in Kapitel 6.3,
 * Praezisierungen AP-06).
 *
 * **Rabatte:** `hall_cost` wirkt als Faktor auf den Grundpreis der Hallen,
 * `layer_unlock_cost` als Faktor auf `unlockCost` der Schichten (Faktor-Stats, Basis 1.0).
 * Sie werden bei jedem Aufruf aus [modifiers] gelesen; der Aufrufer muss die Quellen vorher mit
 * [de.noelkoeppen.fossilcollector.core.engine.ModifierSources.sync] abgleichen.
 *
 * **Nach einem Kauf** aendern sich ggf. Modifier-Quellen (Praeparatoren, Werkzeugstufen). Der
 * Aufrufer ruft danach `ModifierSources.sync` mit dem neuen Zustand auf.
 */
class Purchases(
    private val data: GameData,
    private val modifiers: ModifierEngine,
) {

    // --- Aktionen mit Kaufmodus ---

    /** Kauft Grabungsteams fuer die freigeschaltete Schicht [layerId]. */
    fun buyTeams(state: GameState, layerId: String, mode: BuyMode): PurchaseResult =
        buy(state, PurchaseTarget.Teams(layerId), mode)

    /** Kauft Praeparatoren. */
    fun buyPreparators(state: GameState, mode: BuyMode): PurchaseResult =
        buy(state, PurchaseTarget.Preparators, mode)

    /** Kauft Museumshallen (Rabatt `hall_cost`). */
    fun buyHalls(state: GameState, mode: BuyMode): PurchaseResult =
        buy(state, PurchaseTarget.Halls, mode)

    /** Kauft Laborplaetze. */
    fun buyLabSlots(state: GameState, mode: BuyMode): PurchaseResult =
        buy(state, PurchaseTarget.LabSlots, mode)

    /** Kauft Werkzeugstufen mit FP (beachtet `maxLevel`). */
    fun buyToolLevel(state: GameState, mode: BuyMode): PurchaseResult =
        buy(state, PurchaseTarget.ToolLevel, mode)

    /** Allgemeiner Kauf fuer [target] im Modus [mode]. */
    fun buy(state: GameState, target: PurchaseTarget, mode: BuyMode): PurchaseResult {
        val offer = when (val o = offer(state, target)) {
            is OfferOrFailure.Fail -> return PurchaseResult.Failure(o.reason)
            is OfferOrFailure.Ok -> o.offer
        }
        val funds = funds(state.run, offer.currency)
        val quantity: GameNumber
        val cost: GameNumber
        val fixed = mode.fixedQuantity
        if (fixed != null) {
            quantity = GameNumber(fixed.toDouble())
            if (offer.cap != null && offer.cap < quantity) {
                return PurchaseResult.Failure(PurchaseFailure.MAX_LEVEL_REACHED)
            }
            cost = offer.curve.cost(offer.paid, quantity)
            if (cost > funds) return PurchaseResult.Failure(PurchaseFailure.INSUFFICIENT_FUNDS)
        } else {
            quantity = offer.curve.maxAffordable(offer.paid, funds, offer.cap)
            if (quantity <= GameNumber.ZERO) return PurchaseResult.Failure(PurchaseFailure.INSUFFICIENT_FUNDS)
            cost = offer.curve.cost(offer.paid, quantity)
        }
        val run = pay(state.run, offer.currency, cost)
        val newRun = addStock(run, target, quantity)
        return PurchaseResult.Success(state.copy(run = newRun), quantity, cost, offer.currency)
    }

    /**
     * Vorschau fuer die UI (Menge, Kosten, bezahlbar). `null`, wenn [target] gerade nicht kaufbar ist
     * (unbekannte oder gesperrte Schicht, Maximalstufe erreicht bzw. Modus ueberschreitet sie).
     */
    fun quote(state: GameState, target: PurchaseTarget, mode: BuyMode): PurchaseQuote? {
        val offer = (offer(state, target) as? OfferOrFailure.Ok)?.offer ?: return null
        val funds = funds(state.run, offer.currency)
        val fixed = mode.fixedQuantity
        if (fixed != null) {
            val quantity = GameNumber(fixed.toDouble())
            if (offer.cap != null && offer.cap < quantity) return null
            val cost = offer.curve.cost(offer.paid, quantity)
            return PurchaseQuote(quantity, cost, offer.currency, cost <= funds)
        }
        val quantity = offer.curve.maxAffordable(offer.paid, funds, offer.cap)
        return if (quantity > GameNumber.ZERO) {
            PurchaseQuote(quantity, offer.curve.cost(offer.paid, quantity), offer.currency, true)
        } else {
            PurchaseQuote(GameNumber.ZERO, offer.curve.price(offer.paid), offer.currency, false)
        }
    }

    /** Kostenkurve fuer [target] inkl. Rabatten, bzw. `null`, wenn [target] unbekannt/gesperrt ist. */
    fun costCurve(state: GameState, target: PurchaseTarget): CostCurve? =
        (offer(state, target) as? OfferOrFailure.Ok)?.offer?.curve

    // --- Schicht-Freischaltung ---

    /** Die naechste noch gesperrte Schicht in Reihenfolge oder `null`, wenn alle offen sind. */
    fun nextLockedLayer(state: GameState): LayerDefinition? =
        data.layers.getOrNull(unlockedCount(state.run))

    /** Freischaltpreis von [layer] inkl. Rabatt `layer_unlock_cost`. */
    fun unlockCost(layer: LayerDefinition): GameNumber =
        GameNumber(layer.unlockCost * modifiers.factor(StatId.LAYER_UNLOCK_COST))

    /** `true`, wenn die Schicht [layerId] freigeschaltet ist. Unbekannte IDs ergeben `false`. */
    fun isLayerUnlocked(state: GameState, layerId: String): Boolean {
        val index = data.layers.indexOfFirst { it.id == layerId }
        return index >= 0 && index < unlockedCount(state.run)
    }

    /**
     * Schaltet die naechste Schicht in Reihenfolge frei (einmaliger Preis `unlockCost`,
     * Rabatt `layer_unlock_cost`). Legt fuer die Schicht Team- und Lagereintraege mit 0 an.
     */
    fun unlockNextLayer(state: GameState): PurchaseResult {
        val layer = nextLockedLayer(state)
            ?: return PurchaseResult.Failure(PurchaseFailure.ALL_LAYERS_UNLOCKED)
        val cost = unlockCost(layer)
        if (cost > state.run.money) return PurchaseResult.Failure(PurchaseFailure.INSUFFICIENT_FUNDS)
        val run = state.run
        val newRun = run.copy(
            money = run.money - cost,
            unlockedLayers = run.unlockedLayers + GameNumber.ONE,
            teams = run.teams + (layer.id to (run.teams[layer.id] ?: GameNumber.ZERO)),
            blocks = run.blocks + (layer.id to (run.blocks[layer.id] ?: GameNumber.ZERO)),
        )
        return PurchaseResult.Success(state.copy(run = newRun), GameNumber.ONE, cost, Currency.MONEY)
    }

    /**
     * Schaltet die Schicht [layerId] frei. Nur die naechste Schicht in Reihenfolge ist erlaubt,
     * sonst [PurchaseFailure.LAYER_NOT_NEXT] bzw. [PurchaseFailure.LAYER_ALREADY_UNLOCKED].
     */
    fun unlockLayer(state: GameState, layerId: String): PurchaseResult {
        val index = data.layers.indexOfFirst { it.id == layerId }
        val unlocked = unlockedCount(state.run)
        return when {
            index < 0 -> PurchaseResult.Failure(PurchaseFailure.UNKNOWN_LAYER)
            index < unlocked -> PurchaseResult.Failure(PurchaseFailure.LAYER_ALREADY_UNLOCKED)
            index > unlocked -> PurchaseResult.Failure(PurchaseFailure.LAYER_NOT_NEXT)
            else -> unlockNextLayer(state)
        }
    }

    // --- Intern ---

    /** Kaufangebot: Kurve inkl. Rabatt, bezahlte Einheiten, Waehrung, verbleibender Deckel. */
    private data class Offer(
        val curve: CostCurve,
        val paid: GameNumber,
        val currency: Currency,
        val cap: GameNumber?,
    )

    private sealed interface OfferOrFailure {
        data class Ok(val offer: Offer) : OfferOrFailure
        data class Fail(val reason: PurchaseFailure) : OfferOrFailure
    }

    private fun offer(state: GameState, target: PurchaseTarget): OfferOrFailure {
        val run = state.run
        return when (target) {
            is PurchaseTarget.Teams -> {
                val index = data.layers.indexOfFirst { it.id == target.layerId }
                when {
                    index < 0 -> OfferOrFailure.Fail(PurchaseFailure.UNKNOWN_LAYER)
                    index >= unlockedCount(run) -> OfferOrFailure.Fail(PurchaseFailure.LAYER_LOCKED)
                    else -> {
                        val layer = data.layers[index]
                        val owned = run.teams[layer.id] ?: GameNumber.ZERO
                        OfferOrFailure.Ok(
                            Offer(CostCurve(layer.teamBaseCost, layer.teamGrowth), owned, Currency.MONEY, null),
                        )
                    }
                }
            }
            PurchaseTarget.Preparators -> generatorOffer(DefinitionIds.PREPARATOR, run.preparators, 1.0)
            PurchaseTarget.Halls ->
                generatorOffer(DefinitionIds.HALL, run.halls, modifiers.factor(StatId.HALL_COST))
            PurchaseTarget.LabSlots -> generatorOffer(DefinitionIds.LAB_SLOT, run.labSlots, 1.0)
            PurchaseTarget.ToolLevel -> {
                val upgrade = data.upgrade(DefinitionIds.TOOLS)
                val level = run.toolLevel
                val cap = upgrade.maxLevel?.let {
                    GameNumber.max(GameNumber.ZERO, GameNumber(it.toDouble()) - level)
                }
                if (cap != null && cap <= GameNumber.ZERO) {
                    OfferOrFailure.Fail(PurchaseFailure.MAX_LEVEL_REACHED)
                } else {
                    OfferOrFailure.Ok(Offer(CostCurve(upgrade.baseCost, upgrade.growth), level, upgrade.currency, cap))
                }
            }
        }
    }

    private fun generatorOffer(id: String, owned: GameNumber, costFactor: Double): OfferOrFailure {
        val generator = data.generator(id)
        val paid = GameNumber.max(GameNumber.ZERO, owned - generator.free.toDouble())
        val curve = CostCurve(generator.baseCost * costFactor, generator.growth)
        return OfferOrFailure.Ok(Offer(curve, paid, generator.currency, null))
    }

    private fun addStock(run: RunState, target: PurchaseTarget, quantity: GameNumber): RunState =
        when (target) {
            is PurchaseTarget.Teams ->
                run.copy(teams = run.teams + (target.layerId to ((run.teams[target.layerId] ?: GameNumber.ZERO) + quantity)))
            PurchaseTarget.Preparators -> run.copy(preparators = run.preparators + quantity)
            PurchaseTarget.Halls -> run.copy(halls = run.halls + quantity)
            PurchaseTarget.LabSlots -> run.copy(labSlots = run.labSlots + quantity)
            PurchaseTarget.ToolLevel -> run.copy(toolLevel = run.toolLevel + quantity)
        }

    private fun funds(run: RunState, currency: Currency): GameNumber = when (currency) {
        Currency.MONEY -> run.money
        Currency.RESEARCH_POINTS -> run.researchPoints
    }

    private fun pay(run: RunState, currency: Currency, cost: GameNumber): RunState = when (currency) {
        Currency.MONEY -> run.copy(money = run.money - cost)
        Currency.RESEARCH_POINTS -> run.copy(researchPoints = run.researchPoints - cost)
    }

    private fun unlockedCount(run: RunState): Int =
        run.unlockedLayers.toDouble().toInt().coerceIn(0, data.layers.size)
}

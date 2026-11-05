package de.noelkoeppen.fossilcollector.core.sim

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.AdvanceResult
import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.GameEvent
import de.noelkoeppen.fossilcollector.core.engine.ReconstructionResult
import de.noelkoeppen.fossilcollector.core.engine.RunStart
import de.noelkoeppen.fossilcollector.core.engine.Skeletons
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseResult
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseTarget
import de.noelkoeppen.fossilcollector.core.engine.purchase.Purchases
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.random.SeededRandom

/**
 * Strategie-Parameter der gierigen Kauf-KI (AP-15). Das sind **keine Spielwerte**, sondern das
 * Verhalten eines simulierten Spielers. Die Standardwerte entsprechen der Referenz
 * `tools/sim/fossil_sim.py`, damit Kotlin-Engine und Python-Simulation vergleichbar bleiben.
 *
 * @property tickSeconds Schrittweite fuer [GameEngine.advance]. 1 s wie die Referenz (und wie der
 *   Offline-Fortschritt, Kapitel 7.1); `advance` ist zeitschritt-tolerant, 1 s statt 0,1 s spart
 *   Faktor 10 Laufzeit, die KI kauft hoechstens einmal je Sekunde nach.
 * @property activeSeconds so lange tippt der simulierte Spieler (Referenz: `ACTIVE_SECONDS = 600`)
 * @property digTapsPerSecond Tipps je Sekunde auf die tiefste offene Schicht
 *   (2 x `tapDigAmount` 0,25 = 0,5 Bloecke/s = Referenz `MANUAL_DIG`)
 * @property prepTapsPerSecond Tipps je Sekunde auf "Freilegen" (2 x 0,25 = Referenz `MANUAL_PREP`)
 * @property prepCapacityFactor Praeparatoren gelten als Kaufoption, solange
 *   `prepRate < Grabung * Faktor` (Referenz 1.1)
 * @property prepPriceWeight Gewichtung des Praeparatorpreises beim Vergleich der Optionen
 *   (Referenz 0.7, bezahlt wird der volle Preis)
 * @property hallFillThreshold Hallen gelten als Kaufoption ab `Run-Funde > Plaetze * Schwelle`
 *   (Referenz 0.8)
 * @property duplicatesPerLabSlot Laborplaetze gelten als Kaufoption ab `Duplikate > Wert * Plaetze`
 *   (Referenz 20)
 * @property reconstruct `true`: nach jedem abgeschlossenen Laborjob alle verbessernden
 *   Rekonstruktionen ausfuehren (Referenz rekonstruiert automatisch)
 */
data class GreedyBotConfig(
    val tickSeconds: Double = 1.0,
    val activeSeconds: Double = 600.0,
    val digTapsPerSecond: Double = 2.0,
    val prepTapsPerSecond: Double = 2.0,
    val prepCapacityFactor: Double = 1.1,
    val prepPriceWeight: Double = 0.7,
    val hallFillThreshold: Double = 0.8,
    val duplicatesPerLabSlot: Double = 20.0,
    val reconstruct: Boolean = true,
)

/** Momentaufnahme fuer den Verlauf im Testlog. */
data class BotSnapshot(
    val seconds: Double,
    val money: GameNumber,
    val museumIncome: GameNumber,
    /** Durchschnittlicher Geldzufluss je Sekunde seit der letzten Aufnahme (Museum, Gestein, Verkauf, Meilensteine). */
    val earnedPerSecond: GameNumber,
    val pieces: Int,
    val skeletons: Int,
    val famePreview: GameNumber,
    val teams: Map<String, GameNumber>,
    val preparators: GameNumber,
    val halls: GameNumber,
    val labSlots: GameNumber,
    val toolLevel: GameNumber,
    val duplicates: Int,
) {
    /** Eine Zeile fuer das Testlog. */
    fun format(): String = buildString {
        append("t=%3d min".format((seconds / 60.0).toInt()))
        append("  Geld=%10.0f".format(money.toDouble()))
        append("  Museum=%7.1f/s".format(museumIncome.toDouble()))
        append("  Zufluss=%7.1f/s".format(earnedPerSecond.toDouble()))
        append("  Funde=%3d".format(pieces))
        append("  Skelette=%2d".format(skeletons))
        append("  Ruhm-Vorschau=%3.0f".format(famePreview.toDouble()))
        append("  Teams=").append(teams.values.map { it.toDouble().toInt() })
        append(" Prep=%d".format(preparators.toDouble().toInt()))
        append(" Hallen=%d".format(halls.toDouble().toInt()))
        append(" Labs=%d".format(labSlots.toDouble().toInt()))
        append(" Werkzeug=%d".format(toolLevel.toDouble().toInt()))
        append(" Dups=%d".format(duplicates))
    }
}

/**
 * Gierige Kauf-KI fuer Pacing-Simulationen (Kapitel 5.1, 6.7, AP-15).
 *
 * Nutzt ausschliesslich oeffentliche Engine-Aktionen: [GameEngine.tapDig], [GameEngine.tapPrep],
 * [GameEngine.advance] mit festem Seed, [Purchases] (Teams, Praeparatoren, Schicht-Freischaltung),
 * [GameEngine.buyHalls], [GameEngine.buyLabSlots], [GameEngine.buyToolLevel] und
 * [GameEngine.reconstruct]. Keine Abkuerzungen am Spielstand vorbei.
 *
 * Ablauf je Sekunde (wie `Run.tick` der Referenz):
 * 1. in der aktiven Phase tippen (Grabung auf die tiefste offene Schicht, dann Freilegen),
 * 2. `advance(state, tickSeconds, rng)`,
 * 3. nach abgeschlossenen Laborjobs verbessernde Rekonstruktionen,
 * 4. Werkzeugstufen mit allen FP (Max-Kauf),
 * 5. Kaufschleife: billigste "sinnvolle" Option kaufen, bis die billigste nicht mehr bezahlbar ist
 *    (dann wird gespart, wie `Run.buy` der Referenz).
 *
 * Liegt im Testquellbaum (Paket `core.sim`), nicht in `core/src/main`: Die KI ist ein
 * Pruefwerkzeug und gehoert nicht in die App. AP-28 bindet das Python-Werkzeug an die JSON-Daten an
 * und braucht die Kotlin-KI nicht.
 */
class GreedyBot(
    private val data: GameData,
    seed: Long,
    private val config: GreedyBotConfig = GreedyBotConfig(),
) {
    private val modifiers: ModifierEngine = ModifierEngine.fromGameData(data)
    private val engine: GameEngine = GameEngine(data, modifiers)
    private val purchases: Purchases = Purchases(data, modifiers)
    private val rng = SeededRandom(seed)

    /** Aktueller Spielstand. */
    var state: GameState = GameState.newGame(data.layers.first().id, RunStart.values(data, modifiers), now = 0L)
        private set

    /** Simulierte Zeit in Sekunden. */
    var seconds: Double = 0.0
        private set

    /** Zahl der Duplikate (in die Warteschlange oder verkauft), Kaufkriterium fuer Laborplaetze. */
    var duplicates: Int = 0
        private set

    /** Zeitpunkt (s), zu dem eine Schicht freigeschaltet wurde, je Schicht-ID. */
    val layerUnlockedAt: MutableMap<String, Double> = LinkedHashMap()

    /** Zeitpunkt (s) des ersten vollstaendigen Skeletts im Durchlauf oder `null`. */
    var firstSkeletonAt: Double? = null
        private set

    private var digTapBudget: Double = 0.0
    private var prepTapBudget: Double = 0.0
    private var earned: GameNumber = GameNumber.ZERO
    private var earnedAtSnapshot: GameNumber = GameNumber.ZERO
    private var secondsAtSnapshot: Double = 0.0

    private val active: Boolean get() = seconds < config.activeSeconds

    /** Simuliert bis [untilSeconds] und ruft [onSnapshot] alle [snapshotEvery] Sekunden auf. */
    fun run(untilSeconds: Double, snapshotEvery: Double, onSnapshot: (BotSnapshot) -> Unit) {
        var nextSnapshot = snapshotEvery
        while (seconds < untilSeconds - EPSILON) {
            step()
            if (seconds >= nextSnapshot - EPSILON) {
                onSnapshot(snapshot())
                nextSnapshot += snapshotEvery
            }
        }
    }

    /** Eine Sekunde (bzw. [GreedyBotConfig.tickSeconds]) spielen. */
    fun step() {
        if (active) tap()
        val result = engine.advance(state, config.tickSeconds, rng)
        seconds += config.tickSeconds
        apply(result)
        if (config.reconstruct && result.events.any { it is GameEvent.LabJobCompleted }) reconstructAll()
        buyTools()
        buyLoop()
        val skeletons = Skeletons.completeSpeciesInRun(state.run, data)
        if (firstSkeletonAt == null && skeletons.isNotEmpty()) firstSkeletonAt = seconds
    }

    /** Momentaufnahme; setzt den Messzeitraum fuer [BotSnapshot.earnedPerSecond] zurueck. */
    fun snapshot(): BotSnapshot {
        engine.syncModifiers(state)
        val window = seconds - secondsAtSnapshot
        val rate = if (window > 0.0) (earned - earnedAtSnapshot) / window else GameNumber.ZERO
        earnedAtSnapshot = earned
        secondsAtSnapshot = seconds
        val run = state.run
        return BotSnapshot(
            seconds = seconds,
            money = run.money,
            museumIncome = engine.incomePerSecond(state),
            earnedPerSecond = rate,
            pieces = run.pieces.size,
            skeletons = Skeletons.completeSpeciesInRun(run, data).size,
            famePreview = engine.fameGainPreview(state),
            teams = run.teams,
            preparators = run.preparators,
            halls = run.halls,
            labSlots = run.labSlots,
            toolLevel = run.toolLevel,
            duplicates = duplicates,
        )
    }

    /** Ruhm bei Neustart jetzt ([GameEngine.fameGainPreview]). */
    fun fameGainPreview(): GameNumber = engine.fameGainPreview(state)

    // --- Spieleraktionen ---

    /**
     * Tippt so oft, wie es der Tipprate bis zum Ende dieses Schritts entspricht (Bruchteile werden
     * uebertragen, damit die Rate auch bei `tickSeconds` < 1 stimmt).
     */
    private fun tap() {
        val deepest = data.layers[unlockedCount() - 1].id
        digTapBudget += config.digTapsPerSecond * config.tickSeconds
        prepTapBudget += config.prepTapsPerSecond * config.tickSeconds
        while (digTapBudget >= 1.0 - EPSILON) {
            state = engine.tapDig(state, deepest)
            digTapBudget -= 1.0
        }
        while (prepTapBudget >= 1.0 - EPSILON) {
            apply(engine.tapPrep(state, rng))
            prepTapBudget -= 1.0
        }
    }

    private fun reconstructAll() {
        for (preview in engine.reconstructableBones(state)) {
            if (!preview.isImprovement) continue
            val result = engine.reconstruct(state, preview.pieceKey)
            if (result is ReconstructionResult.Success) {
                countDuplicates(result.events)
                state = result.state
            }
        }
    }

    private fun buyTools() {
        val result = engine.buyToolLevel(state, BuyMode.MAX)
        if (result is PurchaseResult.Success) state = result.state
    }

    /** Kaufentscheidung wie `Run.buy` der Referenz: billigste sinnvolle Option, sonst sparen. */
    private fun buyLoop() {
        while (true) {
            engine.syncModifiers(state)
            val option = cheapestOption() ?: return
            if (option.price > state.run.money) return
            val result = option.buy(state)
            if (result !is PurchaseResult.Success) return
            val unlockedBefore = unlockedCount()
            state = result.state
            recordUnlocks(unlockedBefore)
        }
    }

    private class Option(
        /** Vergleichswert (ggf. gewichtet). */
        val weight: GameNumber,
        /** Tatsaechlicher Preis. */
        val price: GameNumber,
        val buy: (GameState) -> PurchaseResult,
    )

    private fun cheapestOption(): Option? {
        val run = state.run
        val options = ArrayList<Option>()
        val open = data.layers.take(unlockedCount())
        for (layer in open) {
            val price = purchases.quote(state, PurchaseTarget.Teams(layer.id), BuyMode.X1)?.cost ?: continue
            options += Option(price, price) { purchases.buyTeams(it, layer.id, BuyMode.X1) }
        }
        purchases.nextLockedLayer(state)?.let { next ->
            val price = purchases.unlockCost(next)
            options += Option(price, price) { purchases.unlockNextLayer(it) }
        }
        var produced = open.sumOf { engine.blocksPerSecond(state, it.id).toDouble() }
        if (active) produced += config.digTapsPerSecond * data.balance.tapDigAmount
        if (engine.prepRate() < produced * config.prepCapacityFactor) {
            purchases.quote(state, PurchaseTarget.Preparators, BuyMode.X1)?.cost?.let { price ->
                options += Option(price * config.prepPriceWeight, price) { purchases.buyPreparators(it, BuyMode.X1) }
            }
        }
        val slots = run.halls.floor().toDouble() * data.balance.slotsPerHall
        if (run.pieces.size > slots * config.hallFillThreshold) {
            engine.hallQuote(state, BuyMode.X1)?.cost?.let { price ->
                options += Option(price, price) { engine.buyHalls(it, BuyMode.X1) }
            }
        }
        if (duplicates > config.duplicatesPerLabSlot * run.labSlots.toDouble()) {
            engine.labSlotQuote(state, BuyMode.X1)?.cost?.let { price ->
                options += Option(price, price) { engine.buyLabSlots(it, BuyMode.X1) }
            }
        }
        return options.minByOrNull { it.weight }
    }

    // --- Hilfen ---

    private fun apply(result: AdvanceResult) {
        val before = state.run.money
        val after = result.state.run.money
        if (after > before) earned += after - before
        countDuplicates(result.events)
        state = result.state
    }

    private fun countDuplicates(events: List<GameEvent>) {
        duplicates += events.count { it is GameEvent.DuplicateQueued || it is GameEvent.DuplicateSold }
    }

    private fun recordUnlocks(unlockedBefore: Int) {
        for (index in unlockedBefore until unlockedCount()) layerUnlockedAt.putIfAbsent(data.layers[index].id, seconds)
    }

    private fun unlockedCount(): Int = state.run.unlockedLayers.toDouble().toInt().coerceIn(1, data.layers.size)

    private companion object {
        /** Toleranz fuer Gleitkomma-Zeitvergleiche, kein Spielwert. */
        const val EPSILON: Double = 1e-9
    }
}

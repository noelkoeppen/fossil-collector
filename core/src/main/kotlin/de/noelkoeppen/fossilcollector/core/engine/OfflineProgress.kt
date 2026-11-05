package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.random.GameRandom
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Offline-Fortschritt (GAME_DESIGN.md, Kapitel 7.1, Kapitel 0 Regel 5).
 *
 * Ablauf von [apply]:
 * 1. `elapsed = (now - lastSavedAt) / 1000` Sekunden, negative Werte -> 0.
 * 2. `effective = min(elapsed, offline_cap_hours * 3600) * offline_efficiency`. Beide Stats laufen
 *    ueber die Modifier (Basis `balance.offlineCapHours` bzw. `balance.offlineEfficiency`, Clamps aus
 *    `balance.statLimits`); die Quellen werden vorher mit dem Spielstand abgeglichen.
 * 3. [GameEngine.advance] in Schritten von 1 s, ein kleinerer Rest als letzter Schritt. Es gibt
 *    **keinen** zweiten Simulationspfad: Es laufen dieselben Systeme wie online.
 * 4. Unterschiede zum Online-Tick stecken nur im [AdvanceContext]: Ereignisse werden nicht gesammelt,
 *    sondern direkt zusammengefasst, und nach `balance.maxDetailedOfflineFinds` einzeln gewuerfelten
 *    Fossilien rechnet die Praeparation weitere Funde ueber den Erwartungswert in Verkaufserloes um
 *    ([DetailedFindLimit], [PrepSystem.expectedQuality], [FindClassifier.onAggregatedFinds]).
 * 5. `lastSavedAt = now`, Ergebnis mit [OfflineSummary] fuer den Willkommen-Dialog (AP-23).
 *
 * Keine Absicherung gegen das Verstellen der Uhr ueber die Obergrenze hinaus (Design, Kapitel 7.1).
 *
 * Wird ein Schritt verworfen (nicht endlicher Wert mit nicht werfendem [NumericErrorHandler]), bricht
 * die Nachberechnung ab; der Zustand bis zum letzten gueltigen Schritt bleibt erhalten.
 *
 * Performance (gemessen in `OfflineProgressTest`, JVM): 4 h effektive Zeit (14 400 Schritte) mit
 * einem spaeten Durchlauf-Zustand (rund 68 000 Funde) dauern dort etwa 0,7 bis 0,9 s.
 */
class OfflineProgress(
    private val data: GameData,
    private val modifiers: ModifierEngine,
    private val engine: GameEngine,
) {
    /** Hoechstens angerechnete Abwesenheit in Sekunden (Stat `offline_cap_hours`, nie negativ). */
    fun capSeconds(): Double = max(0.0, modifiers.value(StatId.OFFLINE_CAP_HOURS, data.balance.offlineCapHours)) *
        SECONDS_PER_HOUR

    /** Anteil der angerechneten Zeit, der simuliert wird (Stat `offline_efficiency`, nie negativ). */
    fun efficiency(): Double = max(0.0, modifiers.value(StatId.OFFLINE_EFFICIENCY, data.balance.offlineEfficiency))

    /**
     * Rechnet die Abwesenheit seit `state.lastSavedAt` bis [now] nach (siehe Klassenbeschreibung).
     *
     * @param now aktueller Zeitpunkt in Millisekunden seit 1970 (UTC)
     * @param topFindCount Anzahl der besten Funde in [OfflineSummary.bestFinds] (Willkommen-Dialog: 3)
     */
    fun apply(state: GameState, now: Long, rng: GameRandom, topFindCount: Int = DEFAULT_TOP_FINDS): OfflineResult {
        engine.syncModifiers(state)
        val elapsed = max(0.0, (now - state.lastSavedAt).toDouble() / MILLIS_PER_SECOND)
        val credited = min(elapsed, capSeconds())
        val effective = credited * efficiency()

        val collector = SummaryCollector(topFindCount)
        val limit = DetailedFindLimit(PrepSystem.wholeCount(data.balance.maxDetailedOfflineFinds))
        val context = AdvanceContext(rng, collector::onEvent, limit)

        var current = state
        val fullSteps = floor(effective / STEP_SECONDS).toLong()
        val rest = effective - fullSteps * STEP_SECONDS
        var rejected = false
        for (i in 0 until fullSteps) {
            val step = engine.advance(current, STEP_SECONDS, context)
            if (step.rejected) {
                rejected = true
                break
            }
            current = step.state
        }
        if (!rejected && rest > 0.0) current = engine.advance(current, rest, context).state

        val result = current.copy(lastSavedAt = now)
        val summary = OfflineSummary(
            elapsedSeconds = elapsed,
            creditedSeconds = credited,
            effectiveSeconds = effective,
            moneyGained = result.run.money - state.run.money,
            researchPointsGained = result.run.researchPoints - state.run.researchPoints,
            detailedFinds = GameNumber(limit.detailed.toDouble()),
            aggregatedFinds = GameNumber(limit.aggregated.toDouble()),
            bestFinds = collector.bestFinds(),
            milestonesReached = collector.milestones.toList(),
        )
        return OfflineResult(result, summary)
    }

    /** Fasst die Ereignisse der Offline-Schritte zusammen, ohne sie zu speichern. */
    private class SummaryCollector(private val topCount: Int) {
        /** Bester Fund je Schluessel (Qualitaet und Stufe). */
        private val best = HashMap<PieceKey, OfflineFind>()
        val milestones = LinkedHashSet<String>()

        fun onEvent(event: GameEvent) {
            when (event) {
                is GameEvent.FossilFound -> {
                    val find = event.find
                    val quality = find.quality
                    val previous = best[find.pieceKey]
                    if (previous == null || quality > previous.quality) {
                        best[find.pieceKey] = OfflineFind(find.pieceKey, quality, event.tierId)
                    }
                }
                is GameEvent.MilestoneReached -> milestones += event.milestoneId
                else -> Unit
            }
        }

        fun bestFinds(): List<OfflineFind> = best.values
            .sortedWith(compareByDescending<OfflineFind> { it.quality }.thenBy { it.pieceKey.toString() })
            .take(max(0, topCount))
    }

    companion object {
        /** Schrittweite der Nachberechnung in Sekunden (Kapitel 7.1: 1-Sekunden-Schritte). */
        const val STEP_SECONDS: Double = 1.0

        /** Anzahl der besten Funde fuer den Willkommen-Dialog (Kapitel 7.1: "die besten 3"). */
        const val DEFAULT_TOP_FINDS: Int = 3

        private const val MILLIS_PER_SECOND: Double = 1000.0
        private const val SECONDS_PER_HOUR: Double = 3600.0
    }
}

/**
 * Ergebnis von [GameEngine.applyOffline].
 *
 * @property state neuer Spielstand mit `lastSavedAt = now`
 * @property summary Zusammenfassung fuer den Willkommen-Dialog
 */
data class OfflineResult(
    val state: GameState,
    val summary: OfflineSummary,
)

/**
 * Zusammenfassung des Offline-Fortschritts (Kapitel 7.1, Willkommen-Dialog in AP-23).
 *
 * @property elapsedSeconds rohe Abwesenheit in Sekunden (`now - lastSavedAt`, nie negativ)
 * @property creditedSeconds davon angerechnet: `min(elapsed, offline_cap_hours)`
 * @property effectiveSeconds simulierte Zeit: `creditedSeconds * offline_efficiency`
 * @property moneyGained Geldzuwachs (Gestein, Museum, Verkauf, aggregierte Funde, Meilensteine)
 * @property researchPointsGained FP-Zuwachs (Labor, Meilensteine)
 * @property detailedFinds einzeln gewuerfelte Fossilien
 * @property aggregatedFinds ueber den Erwartungswert umgerechnete Fossilien
 * @property bestFinds die besten neuen Funde (hoechstens einer je Schluessel), beste zuerst; nur aus
 *   einzeln gewuerfelten Funden
 * @property milestonesReached waehrend der Abwesenheit erreichte Meilensteine (IDs, in Reihenfolge)
 */
data class OfflineSummary(
    val elapsedSeconds: Double,
    val creditedSeconds: Double,
    val effectiveSeconds: Double,
    val moneyGained: GameNumber,
    val researchPointsGained: GameNumber,
    val detailedFinds: GameNumber,
    val aggregatedFinds: GameNumber,
    val bestFinds: List<OfflineFind>,
    val milestonesReached: List<String>,
) {
    /** Alle Funde: einzeln gewuerfelt plus aggregiert. */
    val totalFinds: GameNumber get() = detailedFinds + aggregatedFinds
}

/**
 * Ein Fund fuer die Liste "beste neue Funde".
 *
 * @property pieceKey Schluessel des Stuecks
 * @property quality Qualitaet 0..1
 * @property tierId Qualitaetsstufe (`balance.qualityTiers`, fuer die Qualitaetsfarbe)
 */
data class OfflineFind(
    val pieceKey: PieceKey,
    val quality: Double,
    val tierId: String,
)

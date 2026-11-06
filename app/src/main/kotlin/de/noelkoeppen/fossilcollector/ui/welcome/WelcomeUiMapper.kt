package de.noelkoeppen.fossilcollector.ui.welcome

import de.noelkoeppen.fossilcollector.core.engine.OfflineSummary
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Baut den Zustand des Willkommen-zurueck-Dialogs (AP-23) ausserhalb der Composables und enthaelt
 * die Regel, ab wann der Dialog erscheint.
 *
 * Kurze Abwesenheiten (unter [MIN_DIALOG_SECONDS]) werden trotzdem angerechnet, nur ohne Dialog
 * und ohne Lade-Indikator.
 */
object WelcomeUiMapper {

    /**
     * Mindestabwesenheit in Sekunden fuer den Dialog. Reine UI-Konstante (kein Balancing-Wert):
     * Ein kurzer Wechsel in eine andere App soll nicht jedes Mal einen Dialog ausloesen.
     */
    const val MIN_DIALOG_SECONDS: Double = 60.0

    private const val MILLIS_PER_SECOND: Double = 1000.0
    private const val PERCENT: Double = 100.0

    /** Rohe Abwesenheit in Sekunden wie in `OfflineProgress.apply` (negativ -> 0). */
    fun elapsedSeconds(lastSavedAt: Long, now: Long): Double = max(0.0, (now - lastSavedAt).toDouble() / MILLIS_PER_SECOND)

    /** `true`, wenn eine Abwesenheit von [elapsedSeconds] Sekunden den Dialog zeigt. */
    fun showsDialog(elapsedSeconds: Double): Boolean = elapsedSeconds >= MIN_DIALOG_SECONDS

    /**
     * Dialog-Zustand aus [summary].
     *
     * @param capSeconds Obergrenze der angerechneten Abwesenheit (`GameEngine.offline.capSeconds()`)
     * @param efficiency Offline-Effizienz (`GameEngine.offline.efficiency()`, z. B. 0.5)
     */
    fun map(summary: OfflineSummary, capSeconds: Double, efficiency: Double): WelcomeUiState.Summary =
        WelcomeUiState.Summary(
            elapsedSeconds = summary.elapsedSeconds,
            creditedSeconds = summary.creditedSeconds,
            effectiveSeconds = summary.effectiveSeconds,
            capSeconds = capSeconds,
            efficiencyPercent = if (efficiency.isFinite()) (efficiency * PERCENT).roundToInt() else 0,
            capped = summary.elapsedSeconds > summary.creditedSeconds,
            money = summary.moneyGained,
            researchPoints = summary.researchPointsGained,
            finds = summary.totalFinds,
            bestFinds = summary.bestFinds.map { WelcomeFindUi(it.pieceKey.speciesId, it.pieceKey.boneId, it.quality, it.tierId) },
            milestoneIds = summary.milestonesReached,
        )

    /**
     * Neuer Dialog-Zustand nach einer Nachberechnung: bei langer Abwesenheit die neue
     * Zusammenfassung, sonst bleibt ein noch offener Dialog stehen (ein Lade-Indikator verschwindet).
     */
    fun next(current: WelcomeUiState?, summary: OfflineSummary, capSeconds: Double, efficiency: Double): WelcomeUiState? =
        when {
            showsDialog(summary.elapsedSeconds) -> map(summary, capSeconds, efficiency)
            current is WelcomeUiState.Calculating -> null
            else -> current
        }
}

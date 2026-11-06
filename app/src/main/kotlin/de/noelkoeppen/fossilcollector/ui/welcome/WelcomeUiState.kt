package de.noelkoeppen.fossilcollector.ui.welcome

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Zustand des Willkommen-zurueck-Dialogs (AP-23, Kapitel 7.1), Feld `welcome` in
 * `GameUiState.Ready`. `null` dort bedeutet: kein Dialog.
 */
@Immutable
sealed interface WelcomeUiState {

    /**
     * Der Offline-Fortschritt wird gerade berechnet (Lade-Indikator, nicht schliessbar).
     *
     * @property elapsedSeconds rohe Abwesenheit in Sekunden (`now - lastSavedAt`)
     */
    data class Calculating(val elapsedSeconds: Double) : WelcomeUiState

    /**
     * Ergebnis der Nachberechnung (aus `OfflineSummary`, [WelcomeUiMapper]).
     *
     * @property elapsedSeconds rohe Abwesenheit in Sekunden
     * @property creditedSeconds davon angerechnet (nach der Obergrenze)
     * @property effectiveSeconds simulierte Zeit (`creditedSeconds * efficiency`)
     * @property capSeconds Obergrenze der angerechneten Abwesenheit in Sekunden
     * @property efficiencyPercent Offline-Effizienz in ganzen Prozent (Start 50)
     * @property capped `true`, wenn die Abwesenheit ueber der Obergrenze lag
     * @property money Geldzuwachs (inkl. Meilenstein-Belohnungen)
     * @property researchPoints FP-Zuwachs
     * @property finds Anzahl aller Funde (einzeln gewuerfelt plus aggregiert)
     * @property bestFinds die besten neuen Funde, beste zuerst (hoechstens 3)
     * @property milestoneIds erreichte Meilensteine (IDs, Namen ueber den `TextProvider`)
     */
    @Immutable
    data class Summary(
        val elapsedSeconds: Double,
        val creditedSeconds: Double,
        val effectiveSeconds: Double,
        val capSeconds: Double,
        val efficiencyPercent: Int,
        val capped: Boolean,
        val money: GameNumber,
        val researchPoints: GameNumber,
        val finds: GameNumber,
        val bestFinds: List<WelcomeFindUi>,
        val milestoneIds: List<String>,
    ) : WelcomeUiState
}

/**
 * Ein Fund in der Liste "beste neue Funde".
 *
 * @property speciesId Art (Name ueber `TextProvider.speciesName`)
 * @property boneId Knochen (Name ueber `TextProvider.boneName`)
 * @property quality Qualitaet 0..1
 * @property tierId Qualitaetsstufe (Farbe ueber `QualityColors.forTier`, Name ueber `qualityTierName`)
 */
@Immutable
data class WelcomeFindUi(
    val speciesId: String,
    val boneId: String,
    val quality: Double,
    val tierId: String,
)

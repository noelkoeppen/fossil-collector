package de.noelkoeppen.fossilcollector.ui.lab

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseQuote
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Labor-Ausschnitt des UI-Zustands (AP-19, Kapitel 4.5 und 8.3). Wird von [LabUiMapper] ausserhalb
 * der Composables aus Engine und Spielstand gebaut; enthaelt Rohwerte und IDs. Namen loest die UI
 * ueber den gemeinsamen `TextProvider` auf (`rememberTextProvider`).
 *
 * @property slotCount Forschungsplaetze (`GameEngine.lab.slots`), fuer die Anzeige gedeckelt
 * @property jobs laufende Forschungen in der Reihenfolge von `run.labJobs`
 * @property jobDuration Dauer einer neu startenden Forschung in Sekunden (`lab.jobDuration`)
 * @property queue wartende Duplikate in FIFO-Reihenfolge (`run.labQueue`)
 * @property queueCapacity hoechstens moegliche Laenge der Warteschlange (`GameEngine.labQueueCapacity`)
 * @property labSlots Bestand Laborplaetze (`run.labSlots`)
 * @property labSlotQuotes Kaufvorschau Laborplaetze je Kaufmodus (`null` = Modus nicht moeglich)
 * @property toolLevel aktuelle Werkzeugstufe (`run.toolLevel`)
 * @property damageReduction aktueller Wert von `damage_reduction` (alle Quellen)
 * @property damageReductionPerLevel Zuwachs von `damage_reduction` je Werkzeugstufe (`upgrades.json`)
 * @property toolQuotes Kaufvorschau Werkzeugstufen je Kaufmodus (`null` = Maximalstufe erreicht)
 * @property reconstructions rekonstruierbare Knochen (`GameEngine.reconstructableBones`)
 */
@Immutable
data class LabUiState(
    val slotCount: Int,
    val jobs: List<LabJobUi>,
    val jobDuration: Double,
    val queue: List<QueuedPieceUi>,
    val queueCapacity: Int,
    val labSlots: GameNumber,
    val labSlotQuotes: Map<BuyMode, PurchaseQuote?>,
    val toolLevel: GameNumber,
    val damageReduction: Double,
    val damageReductionPerLevel: Double,
    val toolQuotes: Map<BuyMode, PurchaseQuote?>,
    val reconstructions: List<ReconstructionUi>,
) {
    /** Freie Forschungsplaetze (nie negativ). */
    val freeSlots: Int get() = (slotCount - jobs.size).coerceAtLeast(0)

    /** `true`, wenn die Warteschlange keine weiteren Duplikate aufnimmt (sie werden verkauft). */
    val queueFull: Boolean get() = queue.size >= queueCapacity

    companion object {
        /** Leerer Zustand (z. B. fuer Vorschauen anderer Screens). */
        val EMPTY = LabUiState(
            slotCount = 0,
            jobs = emptyList(),
            jobDuration = 0.0,
            queue = emptyList(),
            queueCapacity = 0,
            labSlots = GameNumber.ZERO,
            labSlotQuotes = emptyMap(),
            toolLevel = GameNumber.ZERO,
            damageReduction = 0.0,
            damageReductionPerLevel = 0.0,
            toolQuotes = emptyMap(),
            reconstructions = emptyList(),
        )
    }
}

/**
 * Eine laufende Forschung.
 *
 * @property speciesId Art-ID (Name ueber `TextProvider.speciesName`)
 * @property boneId Knochen-ID (Name ueber `TextProvider.boneName`)
 * @property tierId Qualitaetsstufe des erforschten Stuecks (Farbe ueber `LocalQualityColors`)
 * @property quality Qualitaet 0..1 des erforschten Stuecks
 * @property progress Fortschritt 0..1 aus `remaining / duration` (`lab.progress`), ohne Animation
 * @property remainingSeconds verbleibende Sekunden
 * @property researchPoints FP bei Abschluss (`lab.researchPointsFor`)
 */
@Immutable
data class LabJobUi(
    val speciesId: String,
    val boneId: String,
    val tierId: String,
    val quality: Double,
    val progress: Double,
    val remainingSeconds: Double,
    val researchPoints: GameNumber,
)

/** Ein Duplikat in der Warteschlange: Art, Knochen und Qualitaetsstufe. */
@Immutable
data class QueuedPieceUi(
    val speciesId: String,
    val boneId: String,
    val tierId: String,
    val quality: Double,
)

/**
 * Ein rekonstruierbarer Knochen.
 *
 * @property pieceKey Schluessel fuer die Aktion `GameEngine.reconstruct`
 * @property key Text-Form von [pieceKey] (stabiler Schluessel fuer Listen)
 * @property tierId Qualitaetsstufe der erwarteten Qualitaet
 * @property quality erwartete Qualitaet 0..1
 * @property missingZones Zonen-IDs ohne Ersatzteil (Name ueber `TextProvider.zoneName`)
 * @property improvesRun Verbesserung fuer den Run-Fund (oder es gibt keinen)
 * @property improvesBook Verbesserung fuer den Bucheintrag (oder es gibt keinen)
 */
@Immutable
data class ReconstructionUi(
    val pieceKey: PieceKey,
    val key: String,
    val speciesId: String,
    val boneId: String,
    val tierId: String,
    val quality: Double,
    val missingZones: List<String>,
    val improvesRun: Boolean,
    val improvesBook: Boolean,
) {
    /** Button "Rekonstruieren" aktiv. */
    val isImprovement: Boolean get() = improvesRun || improvesBook
}

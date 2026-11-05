package de.noelkoeppen.fossilcollector.ui.museum

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseQuote
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Museums-Ausschnitt des UI-Zustands (AP-18, Kapitel 4.6 und 8.3). Wird von [MuseumUiMapper]
 * ausserhalb der Composables aus Engine und Spielstand gebaut; enthaelt Rohwerte und IDs. Namen
 * loest die UI ueber den gemeinsamen `TextProvider` auf (`rememberTextProvider`).
 *
 * @property incomePerSecond Museumseinnahmen pro Sekunde (`GameEngine.incomePerSecond`)
 * @property halls Anzahl der Hallen
 * @property slotsPerHall Plaetze je Halle (`balance.slotsPerHall`)
 * @property slotCount Ausstellungsplaetze insgesamt (`MuseumSystem.slots`)
 * @property exhibits ausgestellte Stuecke, wertvollstes zuerst
 * @property hallQuotes Kaufvorschau fuer Hallen je Kaufmodus (`null` = Modus nicht moeglich)
 * @property skeletonFactor aktueller Skelett-Faktor (z. B. 2.0)
 * @property completeSkeletons Art-IDs der im Durchlauf vollstaendigen Skelette in Datenreihenfolge
 * @property specialExhibit Sonderausstellung oder `null`, wenn der Capstone fehlt (ausgeblendet)
 */
@Immutable
data class MuseumUiState(
    val incomePerSecond: GameNumber,
    val halls: GameNumber,
    val slotsPerHall: Int,
    val slotCount: Int,
    val exhibits: List<ExhibitUi>,
    val hallQuotes: Map<BuyMode, PurchaseQuote?>,
    val skeletonFactor: Double,
    val completeSkeletons: List<String>,
    val specialExhibit: SpecialExhibitUi?,
) {
    /** Anzahl der als Raster angezeigten Hallen (ganze Hallen mit Plaetzen). */
    val hallCount: Int get() = if (slotsPerHall <= 0) 0 else slotCount / slotsPerHall

    /** Freie Plaetze. */
    val emptySlots: Int get() = (slotCount - exhibits.size).coerceAtLeast(0)

    /**
     * Plaetze der Halle [hallIndex] (0-basiert) in Anzeigereihenfolge; `null` steht fuer einen
     * leeren Platz. Die wertvollsten Stuecke fuellen die erste Halle.
     */
    fun hallSlots(hallIndex: Int): List<ExhibitUi?> {
        if (hallIndex !in 0 until hallCount) return emptyList()
        val start = hallIndex * slotsPerHall
        return List(slotsPerHall) { exhibits.getOrNull(start + it) }
    }

    companion object {
        /** Leerer Zustand (z. B. fuer Vorschauen anderer Screens). */
        val EMPTY = MuseumUiState(
            incomePerSecond = GameNumber.ZERO,
            halls = GameNumber.ZERO,
            slotsPerHall = 0,
            slotCount = 0,
            exhibits = emptyList(),
            hallQuotes = emptyMap(),
            skeletonFactor = 1.0,
            completeSkeletons = emptyList(),
            specialExhibit = null,
        )
    }
}

/**
 * Ein ausgestelltes Stueck als kleine Karte.
 *
 * @property key Text-Form des `PieceKey` (stabiler Schluessel fuer Listen)
 * @property speciesId Art-ID (Name ueber `TextProvider.speciesName`)
 * @property boneId Knochen-ID (Name ueber `TextProvider.boneName`)
 * @property tierId ID der Qualitaetsstufe (Farbe ueber `LocalQualityColors`)
 * @property quality Qualitaet 0..1
 * @property incomePerSecond Einnahmen pro Sekunde inkl. `museum_income`
 */
@Immutable
data class ExhibitUi(
    val key: String,
    val speciesId: String,
    val boneId: String,
    val tierId: String,
    val quality: Double,
    val incomePerSecond: GameNumber,
    val skeletonComplete: Boolean,
    val specialExhibit: Boolean,
)

/**
 * Sonderausstellung (Capstone `curator_5`), nur vorhanden, wenn `special_exhibit_mult` > 0.
 *
 * @property factor Faktor der Sonderausstellung (z. B. 5)
 * @property selectedSpeciesId gewaehlte Art (`run.specialExhibit`) oder `null`
 * @property active `true`, wenn die Auswahl gerade wirkt
 * @property options Art-IDs der waehlbaren Arten (vollstaendige Skelette)
 */
@Immutable
data class SpecialExhibitUi(
    val factor: Double,
    val selectedSpeciesId: String?,
    val active: Boolean,
    val options: List<String>,
)

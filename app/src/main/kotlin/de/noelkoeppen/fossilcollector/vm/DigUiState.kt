package de.noelkoeppen.fossilcollector.vm

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseQuote
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Ausschnitt des UI-Zustands fuer den Screen Grabung (Kapitel 8.3, AP-17). Alle Werte sind
 * bereits abgeleitet ([DigStateMapper]); die Composables rechnen nichts, sondern formatieren nur.
 *
 * @property layers alle Schichten von oben (jung) nach unten (alt)
 * @property prep Bereich Praeparation
 */
@Immutable
data class DigUiState(
    val layers: List<DigLayerUi>,
    val prep: PrepUi,
) {
    companion object {
        /** Ohne Daten (Vorschauen, Standardwert in [GameUiState.Ready]). */
        val EMPTY: DigUiState = DigUiState(emptyList(), PrepUi.EMPTY)
    }
}

/** Eine Schicht im Querschnitt. */
@Immutable
sealed interface DigLayerUi {
    /** Schicht-ID (Name ueber den `TextProvider`). */
    val layerId: String

    /** Farbe des Schichtbands als `#RRGGBB` aus `layers.json`. */
    val color: String

    /**
     * Freigeschaltete Schicht.
     *
     * @property blocks Bloecke im Lager (auch Bruchteile)
     * @property blocksPerSecond Produktion der Teams ([de.noelkoeppen.fossilcollector.core.engine.GameEngine.blocksPerSecond])
     * @property teams Grabungsteams in dieser Schicht
     * @property teamQuotes Vorschau des Teamkaufs je Kaufmodus (`null`: nicht kaufbar)
     */
    @Immutable
    data class Unlocked(
        override val layerId: String,
        override val color: String,
        val blocks: GameNumber,
        val blocksPerSecond: GameNumber,
        val teams: GameNumber,
        val teamQuotes: Map<BuyMode, PurchaseQuote?>,
    ) : DigLayerUi

    /**
     * Gesperrte Schicht.
     *
     * @property unlockCost Freischaltpreis inkl. Rabatt `layer_unlock_cost`
     * @property savingsProgress Spar-Fortschritt `Geld / unlockCost`, begrenzt auf 0..1
     * @property isNext `true` fuer die naechste Schicht in Reihenfolge (nur sie kann freigeschaltet werden)
     * @property canUnlock `true`, wenn sie die naechste ist und das Geld reicht
     */
    @Immutable
    data class Locked(
        override val layerId: String,
        override val color: String,
        val unlockCost: GameNumber,
        val savingsProgress: Double,
        val isNext: Boolean,
        val canUnlock: Boolean,
    ) : DigLayerUi
}

/**
 * Bereich Praeparation.
 *
 * @property preparators Anzahl Praeparatoren
 * @property ratePerSecond Verarbeitungsrate in Bloecken pro Sekunde (`prep_rate`)
 * @property availableBlocks ganze Bloecke, die ueber alle offenen Schichten zum Freilegen bereitliegen
 * @property progress angefangener Block der Praeparation (`run.prepProgress`, 0..1)
 * @property canTap `true`, wenn Tippen auf "Freilegen" einen Block bearbeiten kann (mindestens ein ganzer Block)
 * @property preparatorQuotes Vorschau des Praeparatorkaufs je Kaufmodus
 */
@Immutable
data class PrepUi(
    val preparators: GameNumber,
    val ratePerSecond: GameNumber,
    val availableBlocks: GameNumber,
    val progress: Double,
    val canTap: Boolean,
    val preparatorQuotes: Map<BuyMode, PurchaseQuote?>,
) {
    companion object {
        val EMPTY: PrepUi = PrepUi(
            preparators = GameNumber.ZERO,
            ratePerSecond = GameNumber.ZERO,
            availableBlocks = GameNumber.ZERO,
            progress = 0.0,
            canTap = false,
            preparatorQuotes = emptyMap(),
        )
    }
}

/**
 * Spieleraktionen des Screens Grabung. [GameViewModel] setzt sie ueber `perform` um;
 * Vorschauen nutzen [NONE].
 */
interface DigActions {
    /** Tippen auf eine freigeschaltete Schicht (graben). */
    fun tapDig(layerId: String)

    /** Tippen auf "Freilegen". */
    fun tapPrep()

    /** Teams fuer [layerId] im Modus [mode] kaufen. */
    fun buyTeams(layerId: String, mode: BuyMode)

    /** Praeparatoren im Modus [mode] kaufen. */
    fun buyPreparators(mode: BuyMode)

    /** Die Schicht [layerId] freischalten (nur die naechste Schicht ist erlaubt). */
    fun unlockLayer(layerId: String)

    /** Keine Wirkung (Vorschauen). */
    object NONE : DigActions {
        override fun tapDig(layerId: String) = Unit
        override fun tapPrep() = Unit
        override fun buyTeams(layerId: String, mode: BuyMode) = Unit
        override fun buyPreparators(mode: BuyMode) = Unit
        override fun unlockLayer(layerId: String) = Unit
    }
}

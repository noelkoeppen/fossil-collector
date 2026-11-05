package de.noelkoeppen.fossilcollector.core.engine.purchase

import de.noelkoeppen.fossilcollector.core.data.Currency
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Kaufmodus der UI (Kapitel 6.3).
 *
 * - [X1]: genau 1 Einheit.
 * - [X10]: genau 10 Einheiten oder nichts (kein Teilkauf, wenn das Geld nur fuer weniger reicht).
 * - [MAX]: so viele wie bezahlbar (mindestens 1, sonst Fehlschlag).
 */
enum class BuyMode(
    /** Feste Menge des Modus oder `null` fuer [MAX]. */
    val fixedQuantity: Int?,
) {
    X1(1),
    X10(10),
    MAX(null),
}

/** Was gekauft werden kann (ausser der Schicht-Freischaltung, die keinen Modus hat). */
sealed interface PurchaseTarget {
    /** Grabungsteams einer freigeschalteten Schicht. */
    data class Teams(val layerId: String) : PurchaseTarget

    /** Praeparatoren. */
    data object Preparators : PurchaseTarget

    /** Museumshallen. */
    data object Halls : PurchaseTarget

    /** Laborplaetze. */
    data object LabSlots : PurchaseTarget

    /** Werkzeugstufen (bezahlt mit FP). */
    data object ToolLevel : PurchaseTarget
}

/**
 * Vorschau eines Kaufs fuer die UI, unabhaengig davon, ob er bezahlbar ist.
 *
 * @property quantity Menge, die der Modus kaufen wuerde. Bei [BuyMode.MAX] die bezahlbare Menge
 *   (0, wenn nicht einmal 1 Einheit bezahlbar ist); bei festen Modi die feste Menge, ggf. gedeckelt
 *   durch eine Maximalstufe.
 * @property cost Gesamtkosten fuer [quantity]; bei [BuyMode.MAX] mit Menge 0 der Preis der naechsten
 *   Einheit (als Sparziel)
 * @property currency Waehrung des Kaufs
 * @property affordable `true`, wenn der Kauf mit dem aktuellen Guthaben ausgefuehrt wuerde
 */
data class PurchaseQuote(
    val quantity: GameNumber,
    val cost: GameNumber,
    val currency: Currency,
    val affordable: Boolean,
)

/** Ergebnis einer Kaufaktion. Der urspruengliche Zustand wird nie veraendert. */
sealed interface PurchaseResult {
    /**
     * Kauf ausgefuehrt.
     *
     * @property state neuer Spielstand
     * @property quantity gekaufte Menge (bei der Schicht-Freischaltung 1)
     * @property cost bezahlter Betrag in [currency]
     */
    data class Success(
        val state: GameState,
        val quantity: GameNumber,
        val cost: GameNumber,
        val currency: Currency,
    ) : PurchaseResult

    /** Kauf abgelehnt; der Spielstand bleibt unveraendert. */
    data class Failure(val reason: PurchaseFailure) : PurchaseResult
}

/** Gruende fuer einen abgelehnten Kauf. */
enum class PurchaseFailure {
    /** Zu wenig Geld bzw. FP. */
    INSUFFICIENT_FUNDS,

    /** Die Schicht ist unbekannt. */
    UNKNOWN_LAYER,

    /** Die Schicht ist noch nicht freigeschaltet (Teams kaufen). */
    LAYER_LOCKED,

    /** Die Schicht ist bereits freigeschaltet. */
    LAYER_ALREADY_UNLOCKED,

    /** Die Schicht ist nicht die naechste in der Reihenfolge. */
    LAYER_NOT_NEXT,

    /** Alle Schichten sind bereits freigeschaltet. */
    ALL_LAYERS_UNLOCKED,

    /** Die Maximalstufe ist erreicht bzw. der Modus wuerde sie ueberschreiten. */
    MAX_LEVEL_REACHED,
}

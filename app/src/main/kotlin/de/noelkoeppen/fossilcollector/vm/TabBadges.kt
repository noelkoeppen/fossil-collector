package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.engine.GameEvent
import de.noelkoeppen.fossilcollector.core.engine.purchase.BuyMode
import de.noelkoeppen.fossilcollector.core.engine.purchase.PurchaseQuote
import de.noelkoeppen.fossilcollector.ui.lab.LabUiState
import de.noelkoeppen.fossilcollector.ui.museum.MuseumUiState

/** Grund eines Badges an einem Tab (Kapitel 8.1, AP-25). */
enum class TabBadge {
    /** Labor: eine Forschung ist seit dem letzten Besuch fertig geworden. */
    RESEARCH_DONE,

    /** Sammelbuch: neuer oder besserer Eintrag seit dem letzten Besuch. */
    NEW_BOOK_ENTRY,

    /** Grabung, Museum, Labor: ein Kauf des Tabs ist bezahlbar. */
    AFFORDABLE,

    /** Ruhm: Prestige ist moeglich (Vorschau >= 1). */
    PRESTIGE,
}

/**
 * Ereignisse seit dem letzten Besuch eines Tabs ("Forschung fertig", "neuer Bucheintrag").
 *
 * Zustand im `GameViewModel`, **nicht** im Spielstand (Entscheidung AP-25): Er ist ein reiner
 * Hinweis auf Ereignisse der laufenden Sitzung. Nach einem Neustart fasst der
 * Willkommen-zurueck-Dialog die Abwesenheit zusammen, und die Badges "bezahlbar" bzw. "Prestige"
 * leiten sich ohnehin aus dem Spielstand ab. Ein Speicherfeld nur fuer diese beiden Flags waere
 * Formatballast ohne Spielwirkung.
 *
 * @property labCompleted Forschung abgeschlossen, waehrend das Labor nicht offen war
 * @property bookUpdated Bucheintrag neu bzw. verbessert, waehrend das Sammelbuch nicht offen war
 */
data class UnseenUpdates(
    val labCompleted: Boolean = false,
    val bookUpdated: Boolean = false,
) {
    /**
     * Zustand nach [events]. [activeTab] ist der gerade angezeigte Tab (`null`: keiner, z. B.
     * Einstellungen offen); Ereignisse seines eigenen Tabs gelten als gesehen.
     */
    fun afterEvents(events: List<GameEvent>, activeTab: GameTab?): UnseenUpdates {
        if (events.isEmpty()) return this
        val lab = labCompleted ||
            (activeTab != GameTab.LAB && events.any { it is GameEvent.LabJobCompleted })
        val book = bookUpdated ||
            (activeTab != GameTab.BOOK && events.any { it is GameEvent.BookUpdated })
        return if (lab == labCompleted && book == bookUpdated) this else UnseenUpdates(lab, book)
    }

    /** Zustand nach dem Oeffnen von [tab]: dessen Ereignisse sind gesehen. */
    fun visited(tab: GameTab?): UnseenUpdates = when (tab) {
        GameTab.LAB -> if (labCompleted) copy(labCompleted = false) else this
        GameTab.BOOK -> if (bookUpdated) copy(bookUpdated = false) else this
        else -> this
    }

    companion object {
        val NONE = UnseenUpdates()
    }
}

/**
 * Badges an den Tabs (Kapitel 8.1, AP-25). Reine Funktionen auf bereits abgeleiteten UI-Zustaenden.
 *
 * "Bezahlbares Upgrade" ist bewusst eng gefasst, damit das Badge etwas bedeutet: nur **x1-Kaeufe**
 * der Haupt-Generatoren je Tab (Grabung: Teams einer offenen Schicht, Praeparatoren oder die
 * naechste Schicht; Museum: Halle; Labor: Laborplatz oder Werkzeugstufe).
 */
object TabBadges {

    /** Tabs, in denen ein x1-Kauf bezahlbar ist. */
    fun affordableTabs(dig: DigUiState, museum: MuseumUiState, lab: LabUiState): Set<GameTab> = buildSet {
        val digAffordable = dig.prep.preparatorQuotes.affordableX1() ||
            dig.layers.any { layer ->
                when (layer) {
                    is DigLayerUi.Unlocked -> layer.teamQuotes.affordableX1()
                    is DigLayerUi.Locked -> layer.canUnlock
                }
            }
        if (digAffordable) add(GameTab.DIG)
        if (museum.hallQuotes.affordableX1()) add(GameTab.MUSEUM)
        if (lab.labSlotQuotes.affordableX1() || lab.toolQuotes.affordableX1()) add(GameTab.LAB)
    }

    /**
     * Badge je Tab. Nur sichtbare Tabs ([visibleTabs]) bekommen eines, der gerade angezeigte
     * ([activeTab]) nie. Labor: "Forschung fertig" vor "bezahlbar".
     *
     * @param affordable Ergebnis von [affordableTabs]
     * @param canPrestige Prestige-Vorschau >= 1
     */
    fun badges(
        visibleTabs: Collection<GameTab>,
        activeTab: GameTab?,
        unseen: UnseenUpdates,
        affordable: Set<GameTab>,
        canPrestige: Boolean,
    ): Map<GameTab, TabBadge> {
        val result = LinkedHashMap<GameTab, TabBadge>()
        for (tab in visibleTabs) {
            if (tab == activeTab) continue
            val badge = when (tab) {
                GameTab.DIG, GameTab.MUSEUM -> TabBadge.AFFORDABLE.takeIf { tab in affordable }
                GameTab.LAB -> when {
                    unseen.labCompleted -> TabBadge.RESEARCH_DONE
                    tab in affordable -> TabBadge.AFFORDABLE
                    else -> null
                }
                GameTab.BOOK -> TabBadge.NEW_BOOK_ENTRY.takeIf { unseen.bookUpdated }
                GameTab.FAME -> TabBadge.PRESTIGE.takeIf { canPrestige }
            }
            if (badge != null) result[tab] = badge
        }
        return result
    }

    private fun Map<BuyMode, PurchaseQuote?>.affordableX1(): Boolean = this[BuyMode.X1]?.affordable == true
}

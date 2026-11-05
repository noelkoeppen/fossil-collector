package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Schrittweise Einfuehrung der Tabs (GAME_DESIGN.md, Kapitel 8.2).
 *
 * | Tab | Sichtbar ab | Praedikat |
 * |---|---|---|
 * | Grabung | Start | immer |
 * | Museum | erstes Fossil | [hasFirstFossil] |
 * | Sammelbuch | 5 verschiedene Funde | [hasDistinctFindsForBook] |
 * | Labor | erstes Duplikat | [hasFirstDuplicate] |
 * | Ruhm | Prestige-Vorschau >= 1 oder nach dem ersten Prestige | [hasFameAccess] |
 *
 * Einige Bedingungen gibt es in `core` noch nicht (Duplikat-Zaehler). Diese Praedikate sind als
 * **Platzhalter** gekennzeichnet und werden von den jeweiligen APs auf die echten Engine-Abfragen
 * umgestellt. Die Prestige-Vorschau braucht Spieldaten und Modifier und wird deshalb als Funktion
 * [canPrestige] uebergeben (in der App `GameEngine::canPrestige`, AP-12). Die Regeln selbst bleiben
 * hier gebuendelt, damit die UI nur [visibleTabs] bzw. [revealTabs] kennt.
 *
 * Die Regeln sind zustandslos. Dass ein einmal gezeigter Tab sichtbar bleibt (z. B. Labor nach
 * dem Prestige), stellt [revealTabs] sicher: Der Aufrufer fuehrt die bereits gezeigten Tabs mit.
 */
object TabUnlockRules {

    /**
     * Anzahl verschiedener Funde (Eintraege im Sammelbuch) fuer den Tab Sammelbuch (Kapitel 8.2).
     * Bedienvorgabe der UI-Einfuehrung, kein Balancing-Wert.
     */
    const val BOOK_TAB_DISTINCT_FINDS: Int = 5

    /**
     * Tabs, deren Bedingung im Zustand [state] erfuellt ist, in Anzeigereihenfolge.
     *
     * @param canPrestige Prestige-Vorschau >= 1 (in der App `GameEngine::canPrestige`)
     */
    fun visibleTabs(state: GameState, canPrestige: (GameState) -> Boolean): Set<GameTab> =
        GameTab.entries.filterTo(LinkedHashSet()) { isUnlocked(it, state, canPrestige) }

    /**
     * Bisher gezeigte Tabs [revealed] plus die jetzt freigeschalteten. Tabs verschwinden nie wieder,
     * auch wenn eine Bedingung (z. B. Duplikat in der Laborwarteschlange) nach dem Prestige nicht
     * mehr gilt.
     */
    fun revealTabs(revealed: Set<GameTab>, state: GameState, canPrestige: (GameState) -> Boolean): Set<GameTab> {
        val unlocked = visibleTabs(state, canPrestige)
        if (revealed.containsAll(unlocked)) return revealed
        return GameTab.entries.filterTo(LinkedHashSet()) { it in revealed || it in unlocked }
    }

    fun isUnlocked(tab: GameTab, state: GameState, canPrestige: (GameState) -> Boolean): Boolean = when (tab) {
        GameTab.DIG -> true
        GameTab.MUSEUM -> hasFirstFossil(state)
        GameTab.BOOK -> hasDistinctFindsForBook(state)
        GameTab.LAB -> hasFirstDuplicate(state)
        GameTab.FAME -> hasFameAccess(state, canPrestige)
    }

    /** Erstes Fossil gefunden: Statistik `piecesFound` > 0 oder ein Eintrag im Sammelbuch. */
    fun hasFirstFossil(state: GameState): Boolean =
        state.meta.stats.piecesFound > GameNumber.ZERO || state.meta.book.isNotEmpty()

    /** Mindestens [BOOK_TAB_DISTINCT_FINDS] verschiedene Funde ([PieceKey]s im Sammelbuch). */
    fun hasDistinctFindsForBook(state: GameState): Boolean =
        state.meta.book.size >= BOOK_TAB_DISTINCT_FINDS

    /**
     * **Platzhalter bis AP-09/AP-10:** Es gibt noch keinen Duplikat-Zaehler. Ersatzweise gilt ein
     * Duplikat als gefunden, sobald das Labor etwas enthaelt oder hervorgebracht hat
     * (Warteschlange, laufende Forschung, Ersatzteile oder erforschte Art). Laut Kapitel 4.4 geht
     * das erste Duplikat immer in die (anfangs leere) Laborwarteschlange.
     */
    fun hasFirstDuplicate(state: GameState): Boolean =
        state.run.labQueue.isNotEmpty() ||
            state.run.labJobs.isNotEmpty() ||
            state.run.spareZones.isNotEmpty() ||
            state.meta.researchedSpecies.isNotEmpty()

    /**
     * Ruhm-Tab: mindestens ein Prestige bzw. bereits Ruhm verdient oder Prestige-Vorschau >= 1
     * ([canPrestige], nur ausgewertet, wenn die einfachen Bedingungen nicht greifen).
     */
    fun hasFameAccess(state: GameState, canPrestige: (GameState) -> Boolean): Boolean =
        state.meta.prestigeCount > GameNumber.ZERO ||
            state.meta.fameTotal > GameNumber.ZERO ||
            canPrestige(state)
}

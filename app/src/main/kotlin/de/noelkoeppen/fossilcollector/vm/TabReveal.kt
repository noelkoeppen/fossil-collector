package de.noelkoeppen.fossilcollector.vm

/**
 * Gezeigte Tabs und Hinweistexte beim ersten Erscheinen eines Tabs (Kapitel 8.2, AP-25).
 *
 * Welche Tabs schon gezeigt wurden, steht dauerhaft im Spielstand (`meta.revealedTabs` als
 * [GameTab.id]). So bleiben Tabs nach einem Neustart sichtbar und jeder Hinweis erscheint nur
 * einmal je Spielstand. Der Metazustand bleibt beim Prestige erhalten; "Spielstand loeschen"
 * beginnt mit einem neuen Metazustand und zeigt die Hinweise also erneut.
 *
 * **Alte Spielstaende** (vor AP-25) haben eine leere Menge. Damit erfahrene Spieler dann nicht
 * alle Hinweise auf einmal bekommen, gibt es beim ersten Abgleich aus einer leeren Menge nur dann
 * einen Hinweis, wenn genau ein Tab erscheint (neues Spiel: nur die Grabung). Sonst werden die
 * Tabs still uebernommen.
 *
 * Reine Funktionen; das `GameViewModel` fuehrt die Menge mit und schreibt sie in den Spielstand.
 */
object TabReveal {

    /**
     * Ergebnis eines Abgleichs.
     *
     * @property revealed gezeigte Tabs nach dem Abgleich, in Anzeigereihenfolge
     * @property hints Tabs, deren Hinweistext jetzt erscheinen soll, in Anzeigereihenfolge
     */
    data class Update(val revealed: Set<GameTab>, val hints: List<GameTab>)

    /**
     * Gleicht die bisher gezeigten Tabs [revealed] mit den jetzt freigeschalteten [unlocked]
     * ([TabUnlockRules.visibleTabs]) ab. Tabs verschwinden nie wieder. Ohne neuen Tab ist
     * [Update.revealed] dieselbe Instanz wie [revealed].
     */
    fun update(revealed: Set<GameTab>, unlocked: Set<GameTab>): Update {
        val newTabs = GameTab.entries.filter { it in unlocked && it !in revealed }
        if (newTabs.isEmpty()) return Update(revealed, emptyList())
        val all = GameTab.entries.filterTo(LinkedHashSet()) { it in revealed || it in unlocked }
        val hints = if (revealed.isEmpty() && newTabs.size > 1) emptyList() else newTabs
        return Update(all, hints)
    }

    /** Gespeicherte IDs als Tabs in Anzeigereihenfolge; unbekannte IDs werden ignoriert. */
    fun fromIds(ids: Set<String>): Set<GameTab> =
        GameTab.entries.filterTo(LinkedHashSet()) { it.id in ids }

    /** Tabs als IDs fuer den Spielstand. */
    fun toIds(tabs: Set<GameTab>): Set<String> = tabs.mapTo(LinkedHashSet()) { it.id }
}

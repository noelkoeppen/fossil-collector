package de.noelkoeppen.fossilcollector.ui.fame

import androidx.compose.runtime.Immutable

/**
 * Zustand des offenen Prestige-Dialogs (liegt im `GameViewModel`, damit er Drehungen uebersteht
 * und die Vorschau auf dem Spiel-Thread berechnet werden kann). Geschlossen = kein Objekt.
 *
 * @property respec `false` = "Skills behalten" (`PrestigeMetaStep.NONE`), `true` = "Skills neu
 *   verteilen" (`GameEngine.respecStep(selection)`)
 * @property selection Skills fuer den neuen Durchlauf in Kaufreihenfolge (nur bei [respec])
 */
@Immutable
data class PrestigeDialogState(
    val respec: Boolean = false,
    val selection: List<String> = emptyList(),
) {
    /**
     * Wechselt zwischen "behalten" und "neu verteilen". Beim Wechsel auf "neu verteilen" startet
     * die Auswahl mit den bisher gekauften Skills [ownedSkills] in Kaufreihenfolge (so kostet der
     * Neukauf genau die Erstattung und der Spieler muss nur aendern, was er tauschen will).
     */
    fun withRespec(enabled: Boolean, ownedSkills: List<String>): PrestigeDialogState = when {
        enabled == respec -> this
        enabled -> copy(respec = true, selection = ownedSkills.distinct())
        else -> copy(respec = false, selection = emptyList())
    }

    /**
     * Tippen auf [skillId] in der Respec-Auswahl: steht er schon in der Auswahl, wird er entfernt
     * (davon abhaengige Wuensche bleiben stehen und erscheinen als abgelehnt); sonst wird er
     * angehaengt, wenn [selectable] (verfuegbar und bezahlbar in der aktuellen Vorschau).
     */
    fun toggle(skillId: String, selectable: Boolean): PrestigeDialogState = when {
        !respec -> this
        skillId in selection -> copy(selection = selection - skillId)
        selectable -> copy(selection = selection + skillId)
        else -> this
    }

    /** Leert die Auswahl (alle Skills zurueckgeben, nichts neu kaufen). */
    fun cleared(): PrestigeDialogState = if (respec) copy(selection = emptyList()) else this
}

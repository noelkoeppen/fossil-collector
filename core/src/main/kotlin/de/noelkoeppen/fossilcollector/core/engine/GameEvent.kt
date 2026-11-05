package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Ereignis aus [GameEngine.advance] (Kapitel 10.3). Die UI nutzt Ereignisse fuer Popups,
 * Animationen und Badges; der Spielzustand selbst steckt immer vollstaendig im [AdvanceResult].
 *
 * **Erweiterung:** Jedes System, das neue Ereignisse braucht (Fund in AP-08, Forschung fertig in
 * AP-10, Meilenstein in AP-11 ...), ergaenzt hier seine eigene Implementierung. Ereignisse werden
 * erst angelegt, wenn ein System sie erzeugt.
 */
sealed interface GameEvent {

    /**
     * Grabungsteams haben in diesem `advance`-Aufruf Bloecke in einer Schicht erzeugt.
     * Hoechstens ein Ereignis je Schicht und Aufruf, nur wenn [amount] > 0.
     *
     * @property layerId Schicht-ID
     * @property amount erzeugte Bloecke in diesem Aufruf
     */
    data class BlocksDug(val layerId: String, val amount: GameNumber) : GameEvent
}

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

    /**
     * Die Praeparation hat ganze Bloecke einer Schicht freigelegt (Kapitel 4.2). Hoechstens ein
     * Ereignis je Schicht und Aufruf (bzw. Tipp), nur wenn [blocks] > 0. Wird nach den
     * [FossilFound]-Ereignissen dieser Schicht gemeldet.
     *
     * @property layerId Schicht-ID
     * @property blocks freigelegte Bloecke (Gestein und Fossilien zusammen)
     * @property fossils davon Fossilien
     * @property rockMoney Geld aus dem Gestein, bereits gutgeschrieben
     */
    data class BlocksPrepared(
        val layerId: String,
        val blocks: GameNumber,
        val fossils: GameNumber,
        val rockMoney: GameNumber,
    ) : GameEvent

    /**
     * Ein Fossil wurde freigelegt (Kapitel 4.2). Ein Ereignis je Fund, in der Reihenfolge der
     * Wuerfe. Das Einordnen (Buch, Museum, Labor, Verkauf) erfolgt im [FindHandler] (AP-09), der
     * eigene Ereignisse melden kann.
     *
     * @property find der Fund mit Zonen-Qualitaeten
     * @property tierId ID der Qualitaetsstufe des Stuecks (`balance.qualityTiers`)
     */
    data class FossilFound(val find: Find, val tierId: String) : GameEvent
}

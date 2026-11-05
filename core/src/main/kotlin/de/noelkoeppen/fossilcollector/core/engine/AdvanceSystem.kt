package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.random.GameRandom

/**
 * Ein Teilschritt von [GameEngine.advance] (z. B. Grabung, spaeter Praeparation, Labor, Museum).
 *
 * Ein System ist rein bezogen auf den Spielstand: Es veraendert den uebergebenen [GameState] nicht,
 * sondern gibt einen neuen zurueck (oder dieselbe Instanz, wenn sich nichts aendert). Zufall nur
 * ueber [AdvanceContext.rng], Ereignisse ueber [AdvanceContext.emit]. Spielwerte und Modifier
 * bekommt ein System ueber seinen Konstruktor (`GameData`, `ModifierEngine`).
 *
 * Die Systeme laufen in fester Reihenfolge nacheinander mit demselben `dt`; jedes sieht den
 * Zustand, den das vorige System erzeugt hat (siehe [GameEngine.defaultSystems]).
 */
fun interface AdvanceSystem {
    /**
     * Schreitet den Zustand um [dt] Sekunden fort. [GameEngine] ruft Systeme nur mit endlichem
     * `dt > 0` auf.
     */
    fun advance(state: GameState, dt: Double, context: AdvanceContext): GameState
}

/**
 * Kontext eines einzelnen `advance`-Aufrufs, gemeinsam fuer alle Systeme.
 *
 * @property rng injizierte Zufallsquelle (Kapitel 0, Regel 7)
 */
class AdvanceContext(val rng: GameRandom) {
    private val collected = ArrayList<GameEvent>()

    /** Meldet ein Ereignis; die Reihenfolge der Meldungen bleibt im [AdvanceResult] erhalten. */
    fun emit(event: GameEvent) {
        collected += event
    }

    /** Bisher gemeldete Ereignisse (Kopie). */
    val events: List<GameEvent>
        get() = collected.toList()
}

/**
 * Ergebnis von [GameEngine.advance].
 *
 * @property state neuer Spielstand (bei `dt <= 0` oder verworfenem Tick die Eingabe-Instanz)
 * @property events Ereignisse dieses Aufrufs in der Reihenfolge ihres Auftretens
 */
data class AdvanceResult(
    val state: GameState,
    val events: List<GameEvent>,
)

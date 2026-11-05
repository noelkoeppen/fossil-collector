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
 * Standard (Online-Tick): Ereignisse werden gesammelt und stehen danach in [events]. Der
 * Offline-Fortschritt ([OfflineProgress], AP-14) nutzt dieselben Systeme, setzt aber zwei Optionen:
 * - [eventSink]: Ereignisse werden nicht gesammelt, sondern sofort an den Empfaenger gereicht (der sie
 *   zusammenfasst). So entstehen bei vielen tausend Schritten keine grossen Ereignislisten.
 * - [detailedFinds]: Grenze fuer einzeln gewuerfelte Fossilien (Kapitel 7.1,
 *   `maxDetailedOfflineFinds`). Ist sie erschoepft, rechnet die Praeparation weitere Funde ueber den
 *   Erwartungswert um, siehe [PrepSystem] und [FindHandler.onAggregatedFinds].
 *
 * @property rng injizierte Zufallsquelle (Kapitel 0, Regel 7)
 * @property eventSink Empfaenger fuer Ereignisse; `null` = sammeln (Standard)
 * @property detailedFinds Grenze fuer einzeln gewuerfelte Funde; `null` = keine Grenze (Standard)
 */
class AdvanceContext(
    val rng: GameRandom,
    private val eventSink: ((GameEvent) -> Unit)? = null,
    val detailedFinds: DetailedFindLimit? = null,
) {
    private val collected = ArrayList<GameEvent>()

    /** Meldet ein Ereignis; die Reihenfolge der Meldungen bleibt im [AdvanceResult] erhalten. */
    fun emit(event: GameEvent) {
        if (eventSink != null) eventSink.invoke(event) else collected += event
    }

    /** Bisher gesammelte Ereignisse (Kopie); leer, wenn ein [eventSink] gesetzt ist. */
    val events: List<GameEvent>
        get() = collected.toList()
}

/**
 * Grenze fuer einzeln gewuerfelte Fossilien ueber mehrere `advance`-Aufrufe hinweg (Offline-Fortschritt,
 * Kapitel 7.1). Die Praeparation fragt je Fossil [tryTakeDetailed]; solange das Kontingent reicht, wird
 * der Fund normal gewuerfelt und eingeordnet, danach zaehlt er als aggregierter Fund ([aggregated]).
 *
 * Veraenderlich und nur fuer einen Offline-Lauf gedacht (nicht thread-safe, wie die Engine).
 *
 * @param max hoechstens einzeln gewuerfelte Funde (nicht negativ)
 */
class DetailedFindLimit(val max: Long) {
    init {
        require(max >= 0L) { "max darf nicht negativ sein: $max" }
    }

    /** Bisher einzeln gewuerfelte Funde. */
    var detailed: Long = 0L
        private set

    /** Bisher ueber den Erwartungswert umgerechnete Funde. */
    var aggregated: Long = 0L
        private set

    /** `true` und zaehlt hoch, wenn noch ein einzeln gewuerfelter Fund erlaubt ist. */
    fun tryTakeDetailed(): Boolean {
        if (detailed >= max) return false
        detailed++
        return true
    }

    /** Verbucht [count] aggregierte Funde. */
    fun addAggregated(count: Long) {
        aggregated += count
    }
}

/**
 * Ergebnis von [GameEngine.advance].
 *
 * @property state neuer Spielstand (bei `dt <= 0` oder verworfenem Tick die Eingabe-Instanz)
 * @property events Ereignisse dieses Aufrufs in der Reihenfolge ihres Auftretens
 * @property rejected `true`, wenn der Aufruf wegen eines nicht endlichen Werts verworfen wurde
 *   ([NumericErrorHandler] wirft nicht); [state] ist dann die Eingabe-Instanz
 */
data class AdvanceResult(
    val state: GameState,
    val events: List<GameEvent>,
    val rejected: Boolean = false,
)

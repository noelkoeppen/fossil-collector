package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Geglaettete Rate einer Groesse pro Sekunde, z. B. Geld fuer die Anzeige "$/s" der oberen Leiste
 * (Kapitel 8.1).
 *
 * Erfasst wird nur die Aenderung durch `advance()` (Zeit), nicht durch Spieleraktionen wie Kaeufe.
 * Die Rate ist die Summe der Aenderungen geteilt durch die Summe der `dt` ueber die letzten
 * [windowSeconds] Sekunden. Damit stimmt die Anzeige automatisch, sobald weitere Systeme
 * (Gesteinserloes AP-08, Museum AP-09) Geld erzeugen, ohne dass die UI Formeln kennt.
 *
 * Reine Anzeige-Hilfe ohne Spiellogik; nicht thread-safe.
 *
 * @param windowSeconds Laenge des Glaettungsfensters in Sekunden (Anzeige, kein Spielwert)
 */
class RateTracker(private val windowSeconds: Double = DEFAULT_WINDOW_SECONDS) {

    init {
        require(windowSeconds > 0.0 && windowSeconds.isFinite()) { "windowSeconds muss positiv sein" }
    }

    private val samples = ArrayDeque<Sample>()

    /** Summe der `dt` im Fenster; wird bei jedem Entfernen neu berechnet, damit sich keine Rundungsfehler ansammeln. */
    private var timeSum = 0.0

    /** Nimmt eine Aenderung [delta] ueber [dt] Sekunden auf; `dt <= 0` und nicht endliche Werte werden ignoriert. */
    fun record(delta: GameNumber, dt: Double) {
        val d = delta.toDouble()
        if (dt <= 0.0 || !dt.isFinite() || !d.isFinite()) return
        samples.addLast(Sample(d, dt))
        timeSum += dt
        var removed = false
        while (samples.size > 1 && timeSum - samples.first().dt >= windowSeconds - TIME_EPSILON) {
            timeSum -= samples.removeFirst().dt
            removed = true
        }
        if (removed) timeSum = samples.sumOf { it.dt }
    }

    /** Aktuelle Rate pro Sekunde; 0 ohne Messwerte. */
    fun perSecond(): GameNumber =
        if (timeSum <= 0.0) GameNumber.ZERO else GameNumber(samples.sumOf { it.delta } / timeSum)

    /** Verwirft alle Messwerte (z. B. nach Laden oder Prestige). */
    fun reset() {
        samples.clear()
        timeSum = 0.0
    }

    private class Sample(val delta: Double, val dt: Double)

    companion object {
        /** Standardfenster: 5 Sekunden. */
        const val DEFAULT_WINDOW_SECONDS: Double = 5.0

        /** Toleranz gegen Rundungsfehler bei Summen wie 10 * 0.1. */
        private const val TIME_EPSILON: Double = 1e-9
    }
}

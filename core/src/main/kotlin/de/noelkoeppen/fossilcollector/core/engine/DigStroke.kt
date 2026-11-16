package de.noelkoeppen.fossilcollector.core.engine

import kotlin.math.sqrt

/** Ansatzpunkt einer Werkzeug-Anwendung in Zellkoordinaten (Zelle (x, y) reicht von x bis x + 1). */
data class DigPoint(val x: Double, val y: Double)

/**
 * Legt fest, wie oft eine Wischbewegung die Aktion „Werkzeug wirkt auf Bereich“ ausloest (AP2-07,
 * Kapitel 3): einmal beim Aufsetzen des Fingers ([start]) und danach jedes Mal, wenn der Finger
 * entlang seines Weges weitere [spacing] Zellen zurueckgelegt hat ([moveTo]). Die Ansatzpunkte liegen
 * gleichmaessig auf der Strecke zwischen den gemeldeten Fingerpositionen, unabhaengig davon, wie oft
 * und in welchen Abstaenden das Geraet Bewegungen meldet. Schnelles Wischen traegt damit nicht mehr
 * Erde ab als langsames ueber dieselbe Strecke, und ohne Bewegung passiert nichts (keine Zeit-Simulation).
 *
 * [spacing] kommt aus `tools.json: strokeSpacing` des gewaehlten Werkzeugs. Reine Rechnung ohne
 * Zufall; der Zustand ist nur die letzte Position und die seit der letzten Anwendung zurueckgelegte Strecke.
 */
class DigStroke(private val spacing: Double) {
    init {
        require(spacing > 0.0 && spacing.isFinite()) { "spacing muss > 0 sein: $spacing" }
    }

    private var lastX = 0.0
    private var lastY = 0.0
    private var travelled = 0.0
    private var active = false

    /** `true` zwischen [start] und [end]. */
    val isActive: Boolean get() = active

    /** Finger setzt auf: genau eine Anwendung an dieser Stelle. */
    fun start(x: Double, y: Double): List<DigPoint> {
        requireFinite(x, y)
        lastX = x
        lastY = y
        travelled = 0.0
        active = true
        return listOf(DigPoint(x, y))
    }

    /**
     * Finger bewegt sich nach ([x], [y]): Ansatzpunkte fuer alle vollen [spacing]-Abschnitte seit der
     * letzten Anwendung, in Wischrichtung. Vor [start] oder nach [end] leer.
     */
    fun moveTo(x: Double, y: Double): List<DigPoint> {
        requireFinite(x, y)
        if (!active) return emptyList()
        val dx = x - lastX
        val dy = y - lastY
        val length = sqrt(dx * dx + dy * dy)
        if (length == 0.0) return emptyList()
        val points = mutableListOf<DigPoint>()
        // Abstand auf dieser Strecke bis zur naechsten Anwendung.
        var next = spacing - travelled
        while (next <= length) {
            val t = next / length
            points += DigPoint(lastX + dx * t, lastY + dy * t)
            next += spacing
        }
        travelled = length - (next - spacing)
        lastX = x
        lastY = y
        return points
    }

    /** Finger hebt ab oder die Geste wird abgebrochen (z. B. zweiter Finger zum Verschieben). */
    fun end() {
        active = false
        travelled = 0.0
    }

    private fun requireFinite(x: Double, y: Double) {
        require(x.isFinite() && y.isFinite()) { "Position muss endlich sein: ($x, $y)" }
    }
}

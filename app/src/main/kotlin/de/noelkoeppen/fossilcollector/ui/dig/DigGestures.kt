package de.noelkoeppen.fossilcollector.ui.dig

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

/**
 * Rueckgaben der Wisch-Bedienung in Rasterkoordinaten (Zelle (x, y) reicht von x bis x + 1).
 *
 * @property onStart Wischbewegung beginnt (eine Anwendung am Ansatzpunkt)
 * @property onMove Finger bewegt sich weiter
 * @property onEnd Finger hebt ab oder die Wischbewegung wird abgebrochen
 * @property onLongPress langes Druecken ohne Bewegung an dieser Stelle; `true`, wenn es etwas
 *   ausgeloest hat (dann wird mit dieser Beruehrung nicht gegraben). `null`: kein langes Druecken.
 * @property onSample Rohbewegung des grabenden Fingers mit Zeitstempel (Bergen, AP2-09): beim
 *   Aufsetzen ([DigSample.down]) und bei jeder Bewegung, solange nicht mit zwei Fingern verschoben
 *   wird, jeweils vor [onStart]/[onMove]. `null`: keine Samples.
 */
class DigGestureCallbacks(
    val onStart: (Double, Double) -> Unit,
    val onMove: (Double, Double) -> Unit,
    val onEnd: () -> Unit,
    val onLongPress: ((Double, Double) -> Boolean)? = null,
    val onSample: ((DigSample) -> Unit)? = null,
)

/**
 * Ein Sample der Fingerbewegung: Zeit des Systems seit dem Start in ms (monoton, Zeitstempel des
 * Eingabeereignisses) und Position in Bildschirmpixeln.
 *
 * @property down erstes Sample einer Beruehrung (Finger setzt auf)
 */
data class DigSample(val uptimeMillis: Long, val x: Float, val y: Float, val down: Boolean)

/**
 * Wisch-Bedienung des Grabens (AP2-07), gemeinsam fuer grobes Buddeln und feines Freilegen
 * (AP2-08, Kapitel 5.1: „gleiche Wisch-Bedienung, nur feiner“):
 *
 * - Ein Finger graebt. Die Anwendung startet erst, wenn sich der Finger um die Beruehrungs-Toleranz
 *   des Systems bewegt hat, oder beim Abheben ohne Bewegung (Tippen = eine Anwendung). Setzt vorher
 *   ein zweiter Finger auf, wird nicht gegraben; kommt er waehrend des Grabens dazu, endet die
 *   Wischbewegung.
 * - Zwei Finger verschieben und zoomen [viewport].
 * - Langes Druecken ohne Bewegung (Zeit des Systems) ruft [DigGestureCallbacks.onLongPress] auf
 *   (Bediengeste, keine Spielzeit); loest es etwas aus, wird mit dieser Beruehrung nicht gegraben.
 *
 * Wie oft aus einer Wischbewegung Anwendungen werden, entscheidet `DigStroke` in `core`.
 *
 * @param key Neustart der Gestenerkennung, wenn sich das Raster aendert
 * @param callbacks aktuelle Rueckgaben (ueber `rememberUpdatedState`)
 */
fun Modifier.digGestures(
    key: Any,
    viewport: MutableState<DigViewport?>,
    callbacks: State<DigGestureCallbacks>,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val longPressMillis = viewConfiguration.longPressTimeoutMillis
        var digging = false
        var transforming = false
        var longPressed = false
        var longPressChecked = callbacks.value.onLongPress == null
        var lastUptime = down.uptimeMillis
        callbacks.value.onSample?.invoke(DigSample(down.uptimeMillis, down.position.x, down.position.y, down = true))
        fun cell(position: Offset): Pair<Double, Double>? = viewport.value?.screenToCell(position.x, position.y)
        while (true) {
            val waitForLongPress = !longPressChecked && !digging && !transforming
            val event: PointerEvent? = if (waitForLongPress) {
                val remaining = longPressMillis - (lastUptime - down.uptimeMillis)
                if (remaining <= 0) null else withTimeoutOrNull(remaining) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                // Finger liegt still, Zeit fuer langes Druecken erreicht.
                longPressChecked = true
                val onLongPress = callbacks.value.onLongPress
                longPressed = onLongPress != null && cell(down.position)?.let { (x, y) -> onLongPress(x, y) } == true
                continue
            }
            lastUptime = event.changes.maxOf { it.uptimeMillis }
            val pressed = event.changes.count { it.pressed }
            if (longPressed) {
                event.changes.forEach { it.consume() }
            } else if (pressed >= 2) {
                if (digging) {
                    callbacks.value.onEnd()
                    digging = false
                }
                transforming = true
                val current = viewport.value
                if (current != null) {
                    val centroid = event.calculateCentroid(useCurrent = true)
                    val pan = event.calculatePan()
                    viewport.value = current.transformed(centroid.x, centroid.y, pan.x, pan.y, event.calculateZoom())
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (!transforming) {
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change != null) {
                    if (change.pressed && change.positionChanged()) {
                        callbacks.value.onSample?.invoke(DigSample(change.uptimeMillis, change.position.x, change.position.y, down = false))
                    }
                    if (!digging && change.pressed && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        cell(down.position)?.let { (x, y) -> callbacks.value.onStart(x, y) }
                        digging = true
                        longPressChecked = true
                    }
                    if (digging && change.pressed && change.positionChanged()) {
                        cell(change.position)?.let { (x, y) -> callbacks.value.onMove(x, y) }
                        change.consume()
                    }
                    if (!change.pressed && !digging) {
                        // Tippen ohne Bewegung: eine Anwendung.
                        cell(down.position)?.let { (x, y) -> callbacks.value.onStart(x, y) }
                        digging = true
                    }
                }
            }
            if (event.changes.none { it.pressed }) break
        }
        if (digging) callbacks.value.onEnd()
    }
}

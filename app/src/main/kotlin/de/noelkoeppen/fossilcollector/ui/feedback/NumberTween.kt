package de.noelkoeppen.fossilcollector.ui.feedback

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Weiche Zahlen-Animation der oberen Leiste (AP-25, Kapitel 8.1): Geld, FP und Ruhm laufen dem
 * Zielwert in [DURATION_MILLIS] linear hinterher, statt 10-mal pro Sekunde zu springen.
 *
 * Regeln (reine Funktionen, getestet):
 * - Steigt der Wert maessig, wird animiert. Weil jeder neue Zielwert vom gerade angezeigten Wert
 *   aus startet und dieser zwischen alt und neu liegt, laeuft die Anzeige bei steigendem Wert nur
 *   vorwaerts (kein Flackern, kein Zurueckspringen).
 * - Sinkt der Wert (Kauf), steigt er um mehr als den Faktor [SNAP_FACTOR] (Meilenstein-Belohnung,
 *   Offline-Fortschritt, Prestige) oder ist ein Wert nicht endlich bzw. der Startwert nicht positiv,
 *   springt die Anzeige sofort.
 *
 * Die Werte sind Bedienvorgaben der Darstellung, keine Balancing-Werte.
 */
object NumberTween {

    /** Dauer einer Animation in Millisekunden (gut 2 Ticks bei 10 Ticks/s). */
    const val DURATION_MILLIS: Int = 250

    /** Ab diesem Verhaeltnis neu/alt springt die Anzeige sofort. */
    const val SNAP_FACTOR: Double = 2.0

    /** `true`, wenn die Anzeige von [shown] sofort auf [target] springen soll. */
    fun shouldSnap(shown: Double, target: Double): Boolean =
        !shown.isFinite() || !target.isFinite() || shown <= 0.0 || target < shown || target > shown * SNAP_FACTOR

    /** Wert zwischen [from] und [to] bei Fortschritt [t] (auf 0..1 begrenzt). */
    fun interpolate(from: Double, to: Double, t: Float): Double {
        val clamped = t.coerceIn(0f, 1f).toDouble()
        if (clamped >= 1.0) return to
        return from + (to - from) * clamped
    }
}

/** Animierte Anzeige von [target] nach den Regeln aus [NumberTween]. */
@Composable
fun animatedGameNumber(target: GameNumber): GameNumber {
    val value = target.toDouble()
    var from by remember { mutableDoubleStateOf(value) }
    var to by remember { mutableDoubleStateOf(value) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(value) {
        val shown = NumberTween.interpolate(from, to, progress.value)
        if (NumberTween.shouldSnap(shown, value)) {
            from = value
            to = value
            progress.snapTo(1f)
        } else {
            from = shown
            to = value
            progress.snapTo(0f)
            progress.animateTo(1f, tween(NumberTween.DURATION_MILLIS, easing = LinearEasing))
        }
    }
    return GameNumber(NumberTween.interpolate(from, to, progress.value))
}

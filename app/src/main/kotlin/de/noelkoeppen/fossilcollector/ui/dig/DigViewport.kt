package de.noelkoeppen.fossilcollector.ui.dig

import androidx.compose.runtime.Immutable
import kotlin.math.min

/**
 * Ausschnitt der Grabungsflaeche auf dem Bildschirm (AP2-07): Verschieben und Zoomen mit zwei
 * Fingern, Umrechnung von Bildschirmpixeln in Zellkoordinaten fuer das Graben mit einem Finger.
 *
 * Bei [zoom] 1 passt die ganze Flaeche in den Ausschnitt ([baseCellPx] Pixel je Zelle). [panX] und
 * [panY] sind die Bildschirmposition der linken oberen Ecke der Flaeche. Reine Rechnung, ohne Android.
 *
 * @param viewWidth Breite des Ausschnitts in Pixeln
 * @param viewHeight Hoehe des Ausschnitts in Pixeln
 * @param cols Breite der Flaeche in Zellen
 * @param rows Hoehe der Flaeche in Zellen
 */
@Immutable
data class DigViewport(
    val viewWidth: Float,
    val viewHeight: Float,
    val cols: Int,
    val rows: Int,
    val zoom: Float = MIN_ZOOM,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    /** Pixel je Zelle bei Zoom 1 (ganze Flaeche sichtbar). */
    val baseCellPx: Float = if (cols > 0 && rows > 0) min(viewWidth / cols, viewHeight / rows) else 0f

    /** Pixel je Zelle beim aktuellen Zoom. */
    val cellPx: Float get() = baseCellPx * zoom

    /** Breite der Flaeche auf dem Bildschirm. */
    val siteWidthPx: Float get() = cols * cellPx

    /** Hoehe der Flaeche auf dem Bildschirm. */
    val siteHeightPx: Float get() = rows * cellPx

    /** Bildschirmpunkt in Zellkoordinaten (Zelle (x, y) reicht von x bis x + 1). */
    fun screenToCell(x: Float, y: Float): Pair<Double, Double> =
        ((x - panX) / cellPx).toDouble() to ((y - panY) / cellPx).toDouble()

    /** Zellkoordinaten in Bildschirmpixeln. */
    fun cellToScreen(cx: Double, cy: Double): Pair<Float, Float> =
        (panX + cx.toFloat() * cellPx) to (panY + cy.toFloat() * cellPx)

    /**
     * Zwei-Finger-Geste: um [zoomFactor] zoomen (Punkt unter [centroidX]/[centroidY] bleibt stehen),
     * dann um [dx]/[dy] verschieben. Zoom bleibt in [MIN_ZOOM]..[MAX_ZOOM], die Flaeche im Bild.
     */
    fun transformed(centroidX: Float, centroidY: Float, dx: Float, dy: Float, zoomFactor: Float): DigViewport {
        if (baseCellPx <= 0f) return this
        val newZoom = (zoom * zoomFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val scale = newZoom / zoom
        val x = centroidX - (centroidX - panX) * scale + dx
        val y = centroidY - (centroidY - panY) * scale + dy
        return copy(zoom = newZoom, panX = x, panY = y).clamped()
    }

    /** Neue Ausschnittsgroesse (z. B. Drehen): Zoom behalten, Lage begrenzen. */
    fun resized(width: Float, height: Float): DigViewport = copy(viewWidth = width, viewHeight = height).clamped()

    /**
     * Haelt die Flaeche im Bild: Ist sie in einer Richtung kleiner als der Ausschnitt, steht sie dort
     * mittig; sonst darf kein Rand ins Bild ruecken.
     */
    fun clamped(): DigViewport {
        val w = siteWidthPx
        val h = siteHeightPx
        val x = if (w <= viewWidth) (viewWidth - w) / 2f else panX.coerceIn(viewWidth - w, 0f)
        val y = if (h <= viewHeight) (viewHeight - h) / 2f else panY.coerceIn(viewHeight - h, 0f)
        return if (x == panX && y == panY) this else copy(panX = x, panY = y)
    }

    companion object {
        /**
         * Zoom-Grenzen (UI-Konstanten, keine Spielwerte): 1 zeigt die ganze Flaeche, damit man nie
         * etwas verliert; 4 macht eine Zelle auf einem Telefon (Flaeche 32 Zellen breit, rund 11 dp je
         * Zelle bei Zoom 1) etwa 45 dp gross, genug fuer genaues Arbeiten mit der Kelle.
         */
        const val MIN_ZOOM: Float = 1f
        const val MAX_ZOOM: Float = 4f

        /** Ganze Flaeche, mittig. */
        fun fit(viewWidth: Float, viewHeight: Float, cols: Int, rows: Int): DigViewport =
            DigViewport(viewWidth, viewHeight, cols, rows).clamped()
    }
}

package de.noelkoeppen.fossilcollector.ui.recovery

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import de.noelkoeppen.fossilcollector.ui.dig.BoneBitmapCache
import de.noelkoeppen.fossilcollector.ui.dig.DigGestureCallbacks
import de.noelkoeppen.fossilcollector.ui.dig.DigViewport
import de.noelkoeppen.fossilcollector.ui.dig.LayerTexturePalette
import de.noelkoeppen.fossilcollector.ui.dig.digGestures
import de.noelkoeppen.fossilcollector.ui.dig.renderTexture
import de.noelkoeppen.fossilcollector.ui.prep.PrepLayout
import de.noelkoeppen.fossilcollector.vm.RecoveryUiState
import kotlin.math.roundToInt

/** Aktionen des Bergens; [NONE] fuer Vorschauen. */
data class RecoveryActions(
    /** Finger setzt auf bzw. Tippen, feine Zellkoordinaten. */
    val onTouchStart: (Double, Double) -> Unit,
    /** Sample: Zeitstempel in ms, Position in dp, erstes Sample der Beruehrung. */
    val onSample: (Long, Double, Double, Boolean) -> Unit,
    /** Finger zieht. */
    val onDrag: () -> Unit,
    /** Finger hebt ab. */
    val onTouchEnd: () -> Unit,
    /** Zurueck (vor dem Herausheben zur Nahansicht, danach zur Flaeche). */
    val onClose: () -> Unit,
) {
    companion object {
        val NONE = RecoveryActions({ _, _ -> }, { _, _, _, _ -> }, {}, {}, {})
    }
}

/**
 * Bergen (AP2-09, Kapitel 5.2 und 8): Boden der Grube und der fertig freigelegte Knochen im Raster
 * der Nahansicht, darauf die bruechigen Stellen (leuchtend, gesichert als Gipsfleck). Bedienung
 * ueber die gemeinsame Wisch-Bedienung ([digGestures]): Tippen sichert eine Stelle, Ziehen mit
 * einem Finger hebt den Knochen (das Bild folgt dem Finger), zwei Finger verschieben und zoomen.
 * Die Bewegung geht als Samples mit Zeitstempel der Eingabe und Position in dp an die Logik.
 *
 * Leistung: alles in einer Zeichenfunktion ohne Element pro Stelle; Ziehen, Leuchten und
 * Markierungen zeichnen nur neu, ohne Recomposition. Textur und Knochenbild entstehen einmal.
 */
@Composable
fun RecoveryField(
    recovery: RecoveryUiState,
    actions: RecoveryActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cache = remember(context) { BoneBitmapCache(context.assets) }
    val cols = recovery.preparation.width
    val rows = recovery.preparation.height
    val image by produceState<ImageBitmap?>(null, recovery.speciesId, recovery.boneId) { value = cache.load(recovery.speciesId, recovery.boneId) }
    val palette = remember(recovery.layerColor) { LayerTexturePalette.forBand(LayerTexturePalette.parseHex(recovery.layerColor)) }
    val current by rememberUpdatedState(recovery)
    val layerTexture = recovery.layerTexture
    val layerId = recovery.layerId
    val viewport = remember(recovery.ref, cols, rows) { mutableStateOf<DigViewport?>(null) }
    val drag = remember(recovery.ref) { DragState() }
    val density = LocalDensity.current.density
    val jerkFlash = remember { Animatable(0f) }
    val secureFlash = remember { Animatable(0f) }
    LaunchedEffect(recovery.feedback.sequence) {
        if (recovery.feedback.newJerk) {
            jerkFlash.snapTo(1f)
            jerkFlash.animateTo(0f, tween(FLASH_MILLIS))
        }
        if (recovery.feedback.securedSpot != null) {
            secureFlash.snapTo(1f)
            secureFlash.animateTo(0f, tween(FLASH_MILLIS))
        }
    }
    val pulse by rememberInfiniteTransition(label = "fragile").animateFloat(
        initialValue = PULSE_MIN,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_MILLIS), RepeatMode.Reverse),
        label = "fragilePulse",
    )
    val callbacks = rememberUpdatedState(
        DigGestureCallbacks(
            onStart = actions.onTouchStart,
            onMove = { _, _ ->
                drag.moving = true
                drag.offset.value = drag.last - drag.start
                actions.onDrag()
            },
            onEnd = {
                drag.moving = false
                drag.offset.value = Offset.Zero
                actions.onTouchEnd()
            },
            onSample = { s ->
                val position = Offset(s.x, s.y)
                if (s.down) drag.start = position
                drag.last = position
                if (drag.moving) drag.offset.value = position - drag.start
                actions.onSample(s.uptimeMillis, (s.x / density).toDouble(), (s.y / density).toDouble(), s.down)
            },
        ),
    )

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size ->
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                viewport.value = viewport.value?.resized(w, h) ?: DigViewport.fit(w, h, cols, rows)
            }
            .digGestures(Triple(recovery.ref, cols, rows), viewport, callbacks)
            .drawWithCache {
                val fit = DigViewport.fit(size.width, size.height, cols, rows)
                val base = IntSize((cols * fit.baseCellPx).roundToInt().coerceAtLeast(1), (rows * fit.baseCellPx).roundToInt().coerceAtLeast(1))
                val texture = renderTexture(base, palette, layerTexture, layerId, this)
                onDrawBehind {
                    val vp = viewport.value ?: fit
                    val state = current
                    drawRect(SURROUND)
                    withTransform({
                        translate(vp.panX, vp.panY)
                        scale(vp.zoom, vp.zoom, pivot = Offset.Zero)
                    }) {
                        val offset = drag.offset.value / vp.zoom
                        val needsPulse = state.outcome == null && state.secured.any { !it }
                        drawRecovery(
                            state, base, fit.baseCellPx, texture, image, offset,
                            pulse = if (needsPulse) pulse else 1f,
                            jerk = jerkFlash.value,
                            secured = secureFlash.value,
                        )
                    }
                }
            },
    )
}

/** Bedienzustand des Ziehens (nur UI): Startpunkt, letzter Punkt und Versatz des Knochenbilds in Pixeln. */
private class DragState {
    var start: Offset = Offset.Zero
    var last: Offset = Offset.Zero
    var moving: Boolean = false
    val offset = mutableStateOf(Offset.Zero)
}

/** Zeichnet Boden, Abdruck, Knochen mit bruechigen Stellen und Markierungen in Pixeln bei Zoom 1. */
private fun DrawScope.drawRecovery(
    rec: RecoveryUiState,
    base: IntSize,
    cellPx: Float,
    texture: ImageBitmap,
    image: ImageBitmap?,
    offset: Offset,
    pulse: Float,
    jerk: Float,
    secured: Float,
) {
    drawImage(texture)
    drawRect(FLOOR_SHADE, size = Size(base.width.toFloat(), base.height.toFloat()))
    if (image == null) return
    val rect = PrepLayout.imageRect(rec.preparation, rec.cells, rec.siteWidth, rec.footprint, rec.pxPerCell, image.width, image.height)
    val dstOffset = IntOffset((rect.left * cellPx).roundToInt(), (rect.top * cellPx).roundToInt())
    val dstSize = IntSize((rect.width * cellPx).roundToInt().coerceAtLeast(1), (rect.height * cellPx).roundToInt().coerceAtLeast(1))
    val lifted = rec.outcome != null
    val moving = offset != Offset.Zero
    if (lifted || moving) {
        // Abdruck im Boden, wo der Knochen lag.
        drawImage(image, dstOffset = dstOffset, dstSize = dstSize, alpha = IMPRINT_ALPHA, colorFilter = IMPRINT_FILTER, filterQuality = FilterQuality.Low)
    }
    if (lifted) return
    translate(offset.x, offset.y) {
        if (moving) {
            val shadow = IntOffset(dstOffset.x + (cellPx * SHADOW_CELLS).roundToInt(), dstOffset.y + (cellPx * SHADOW_CELLS).roundToInt())
            drawImage(image, dstOffset = shadow, dstSize = dstSize, alpha = SHADOW_ALPHA, colorFilter = IMPRINT_FILTER, filterQuality = FilterQuality.Low)
        }
        val tint = if (jerk > 0f) ColorFilter.tint(JERK_FLASH.copy(alpha = jerk * JERK_FLASH.alpha), BlendMode.SrcAtop) else null
        drawImage(image, dstOffset = dstOffset, dstSize = dstSize, colorFilter = tint, filterQuality = FilterQuality.Medium)
        drawSpots(rec, cellPx, pulse, secured)
    }
}

/** Bruechige Stellen: ungesichert als pulsierendes Leuchten, gesichert als Gipsfleck. */
private fun DrawScope.drawSpots(rec: RecoveryUiState, cellPx: Float, pulse: Float, securedFlash: Float) {
    val width = rec.preparation.width
    val glowRadius = cellPx * (rec.secureRadius * GLOW_SHARE).toFloat()
    rec.spots.forEachIndexed { i, cell ->
        val center = Offset((cell % width + HALF) * cellPx, (cell / width + HALF) * cellPx)
        if (rec.secured.getOrElse(i) { false }) {
            val r = cellPx * PLASTER_CELLS
            drawCircle(PLASTER, radius = r, center = center)
            drawCircle(PLASTER_EDGE, radius = r, center = center, style = Stroke(width = cellPx * PLASTER_EDGE_CELLS))
            if (i == rec.feedback.securedSpot && securedFlash > 0f) {
                drawCircle(
                    PLASTER.copy(alpha = securedFlash),
                    radius = r * (1f + (1f - securedFlash) * RING_GROWTH),
                    center = center,
                    style = Stroke(width = cellPx * PLASTER_EDGE_CELLS),
                )
            }
        } else {
            drawCircle(
                brush = Brush.radialGradient(listOf(FRAGILE_GLOW.copy(alpha = pulse * FRAGILE_GLOW.alpha), Color.Transparent), center, glowRadius),
                radius = glowRadius,
                center = center,
            )
            drawCircle(FRAGILE_CORE.copy(alpha = pulse), radius = cellPx * CORE_CELLS, center = center)
        }
    }
}

// Darstellung (keine Spielwerte)
private const val FLASH_MILLIS = 450
private const val PULSE_MILLIS = 700
private const val PULSE_MIN = 0.45f
private const val HALF = 0.5f
private const val GLOW_SHARE = 0.8
private const val CORE_CELLS = 0.55f
private const val PLASTER_CELLS = 1.2f
private const val PLASTER_EDGE_CELLS = 0.2f
private const val RING_GROWTH = 1.5f
private const val SHADOW_CELLS = 0.8f
private const val SHADOW_ALPHA = 0.45f
private const val IMPRINT_ALPHA = 0.35f
private val SURROUND = Color(0xFF2B231C)
private val FLOOR_SHADE = Color(0x73000000)
private val IMPRINT_FILTER = ColorFilter.tint(Color(0xFF120C08), BlendMode.SrcIn)
private val JERK_FLASH = Color(0xB3E53935)
private val FRAGILE_GLOW = Color(0xE6FFB300)
private val FRAGILE_CORE = Color(0xFFFFE082)
private val PLASTER = Color(0xFFF4F1EA)
private val PLASTER_EDGE = Color(0xFF9E9A90)

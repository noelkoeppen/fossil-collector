package de.noelkoeppen.fossilcollector.ui.prep

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import de.noelkoeppen.fossilcollector.core.model.BonePreparation
import de.noelkoeppen.fossilcollector.ui.dig.BoneBitmapCache
import de.noelkoeppen.fossilcollector.ui.dig.DigGestureCallbacks
import de.noelkoeppen.fossilcollector.ui.dig.DigViewport
import de.noelkoeppen.fossilcollector.ui.dig.LayerTexturePalette
import de.noelkoeppen.fossilcollector.ui.dig.digGestures
import de.noelkoeppen.fossilcollector.ui.dig.renderTexture
import de.noelkoeppen.fossilcollector.vm.PrepUiState
import kotlin.math.roundToInt

/**
 * Nahansicht beim feinen Freilegen (AP2-08, Kapitel 5.1 und 8): Boden der Grube, darauf das
 * Knochenbild, darueber die Restschicht ([ResidueRaster]: helle lose Erde, dunkle harte Stellen).
 * Gleiche Bedienung wie beim groben Buddeln ([digGestures]): ein Finger arbeitet, zwei verschieben
 * und zoomen; die Positionen gehen als feine Zellkoordinaten an [onStart]/[onMove].
 *
 * Leistung wie bei der Flaeche: alles in einer Zeichenfunktion, die Restschicht ist ein kleines
 * Bitmap (8 Pixel je feiner Zelle), von dem nach einer Aktion nur der geaenderte Ausschnitt neu
 * gerechnet wird; Verschieben, Zoomen und die Treffer-Markierung zeichnen nur neu.
 */
@Composable
fun PrepField(
    prep: PrepUiState,
    onStart: (Double, Double) -> Unit,
    onMove: (Double, Double) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cache = remember(context) { BoneBitmapCache(context.assets) }
    val cols = prep.preparation.width
    val rows = prep.preparation.height
    val image by produceState<ImageBitmap?>(null, prep.speciesId, prep.boneId) { value = cache.load(prep.speciesId, prep.boneId) }
    val residue = remember(prep.ref, cols, rows, prep.maxDepth) { ResidueBitmap(cols, rows, prep.maxDepth) }
    val palette = remember(prep.layerColor) { LayerTexturePalette.forBand(LayerTexturePalette.parseHex(prep.layerColor)) }
    val current by rememberUpdatedState(prep)
    val layerTexture = prep.layerTexture
    val layerId = prep.layerId
    val viewport = remember(prep.ref, cols, rows) { mutableStateOf<DigViewport?>(null) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(prep.feedback.sequence) {
        if (prep.feedback.damaged) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(FLASH_MILLIS))
        }
    }
    val callbacks = rememberUpdatedState(DigGestureCallbacks(onStart = onStart, onMove = onMove, onEnd = onEnd))

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size ->
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                viewport.value = viewport.value?.resized(w, h) ?: DigViewport.fit(w, h, cols, rows)
            }
            .digGestures(Triple(prep.ref, cols, rows), viewport, callbacks)
            .drawWithCache {
                val fit = DigViewport.fit(size.width, size.height, cols, rows)
                val base = IntSize((cols * fit.baseCellPx).roundToInt().coerceAtLeast(1), (rows * fit.baseCellPx).roundToInt().coerceAtLeast(1))
                val texture = renderTexture(base, palette, layerTexture, layerId, this)
                onDrawBehind {
                    val vp = viewport.value ?: fit
                    val state = current
                    residue.sync(state.preparation)
                    drawRect(SURROUND)
                    withTransform({
                        translate(vp.panX, vp.panY)
                        scale(vp.zoom, vp.zoom, pivot = Offset.Zero)
                    }) {
                        drawCloseUp(state, base, fit.baseCellPx, texture, residue, image, flash.value)
                    }
                }
            },
    )
}

/** Zeichnet Boden, Knochen, Restschicht und Treffer-Markierung in Pixeln bei Zoom 1. */
private fun DrawScope.drawCloseUp(
    prep: PrepUiState,
    base: IntSize,
    cellPx: Float,
    texture: ImageBitmap,
    residue: ResidueBitmap,
    image: ImageBitmap?,
    flash: Float,
) {
    drawImage(texture)
    drawRect(FLOOR_SHADE, size = Size(base.width.toFloat(), base.height.toFloat()))
    if (image != null) {
        val rect = PrepLayout.imageRect(prep.preparation, prep.cells, prep.siteWidth, prep.footprint, prep.pxPerCell, image.width, image.height)
        drawImage(
            image = image,
            dstOffset = IntOffset((rect.left * cellPx).roundToInt(), (rect.top * cellPx).roundToInt()),
            dstSize = IntSize((rect.width * cellPx).roundToInt().coerceAtLeast(1), (rect.height * cellPx).roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Medium,
        )
    }
    drawImage(residue.image, srcSize = residue.size, dstSize = base, filterQuality = FilterQuality.Low)
    if (flash > 0f) {
        val width = prep.preparation.width
        for (cell in prep.feedback.hitCells) {
            val center = Offset((cell % width + HALF) * cellPx, (cell / width + HALF) * cellPx)
            val radius = cellPx * FLASH_RADIUS_CELLS
            drawCircle(
                brush = Brush.radialGradient(listOf(DAMAGE_FLASH.copy(alpha = flash * DAMAGE_FLASH.alpha), Color.Transparent), center, radius),
                radius = radius,
                center = center,
            )
        }
    }
}

/** Restschicht als Android-Bitmap aus [ResidueRaster]; [sync] kopiert nur den geaenderten Ausschnitt. */
private class ResidueBitmap(cols: Int, rows: Int, maxDepth: Int) {
    private val raster = ResidueRaster(cols, rows, maxDepth)
    private val bitmap: Bitmap = Bitmap.createBitmap(raster.width, raster.height, Bitmap.Config.ARGB_8888)
    val image: ImageBitmap = bitmap.asImageBitmap()
    val size = IntSize(raster.width, raster.height)
    private var last: BonePreparation? = null

    fun sync(preparation: BonePreparation) {
        if (preparation === last) return
        last = preparation
        val rect = raster.update(preparation.depthsCopy(), preparation.hardCopy()) ?: return
        val offset = rect.top * raster.width + rect.left
        bitmap.setPixels(raster.pixels, offset, raster.width, rect.left, rect.top, rect.width, rect.height)
    }
}

// Darstellung (keine Spielwerte)
private const val FLASH_MILLIS = 450
private const val FLASH_RADIUS_CELLS = 2.2f
private const val HALF = 0.5f
private val SURROUND = Color(0xFF2B231C)
private val FLOOR_SHADE = Color(0x73000000)
private val DAMAGE_FLASH = Color(0xCCE53935)

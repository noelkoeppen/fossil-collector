package de.noelkoeppen.fossilcollector.ui.dig

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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import de.noelkoeppen.fossilcollector.core.data.LayerTexture
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite
import de.noelkoeppen.fossilcollector.vm.DigUiState
import kotlin.math.roundToInt

/**
 * Grabungsflaeche des groben Buddelns (AP2-07, Kapitel 3 und 8): Boden mit Knochenbildern, darueber
 * die Erde als Maske ([EarthRaster]) ueber der Gesteinstextur der Schicht, ohne sichtbares Raster.
 *
 * Bedienung ([digGestures]): Ein Finger graebt. Die Anwendung startet erst, wenn sich der Finger um
 * die Beruehrungs-Toleranz bewegt hat oder wieder abhebt (Tippen = eine Anwendung); setzt vorher ein
 * zweiter Finger auf, wird nicht gegraben. Zwei Finger verschieben und zoomen ([DigViewport]). Die
 * Positionen gehen als Zellkoordinaten an [onDigStart]/[onDigMove]; wie oft daraus Anwendungen
 * werden, entscheidet `DigStroke` in `core`. Langes Druecken auf einen entdeckten Knochen oeffnet
 * seine Nahansicht ([onOpenBone], feines Freilegen, AP2-08).
 *
 * Leistung: Kein Element pro Zelle. Alles wird in einer Zeichenfunktion gezeichnet; Verschieben,
 * Zoomen und die Schadensmarkierung zeichnen nur neu, ohne Recomposition. Die Erde ist ein kleines
 * Bitmap (8 Pixel je Zelle), von dem nach einer Aktion nur der geaenderte Ausschnitt neu gerechnet
 * wird; Textur und Knochenbilder werden einmal erzeugt bzw. geladen.
 */
@Composable
fun DigField(
    dig: DigUiState,
    onDigStart: (Double, Double) -> Unit,
    onDigMove: (Double, Double) -> Unit,
    onDigEnd: () -> Unit,
    onOpenBone: (BoneRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cache = remember(context) { BoneBitmapCache(context.assets) }
    val cols = dig.site.width
    val rows = dig.site.height
    val boneKeys = remember(dig.site.finds) { dig.bones.map { it.speciesId to it.boneId }.distinct() }
    val images by produceState(emptyMap<Pair<String, String>, ImageBitmap>(), boneKeys) {
        value = boneKeys.mapNotNull { key -> cache.load(key.first, key.second)?.let { key to it } }.toMap()
    }
    val earth = remember(cols, rows, dig.depthMin, dig.hintDepth) { EarthBitmaps(cols, rows, dig.depthMin, dig.hintDepth) }
    val palette = remember(dig.layerColor) { LayerTexturePalette.forBand(LayerTexturePalette.parseHex(dig.layerColor)) }
    val currentDig by rememberUpdatedState(dig)
    // Nicht ueber currentDig im Cache-Block lesen, sonst entstuende die Textur bei jeder Aktion neu.
    val layerTexture = dig.layerTexture
    val layerId = dig.site.layerId
    val viewport = remember(cols, rows) { mutableStateOf<DigViewport?>(null) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(dig.feedback.sequence) {
        if (dig.feedback.damaged.isNotEmpty()) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(FLASH_MILLIS))
        }
    }
    val callbacks = rememberUpdatedState(
        DigGestureCallbacks(
            onStart = onDigStart,
            onMove = onDigMove,
            onEnd = onDigEnd,
            onLongPress = { x, y -> openBoneAt(currentDig, x, y, onOpenBone) },
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
            .digGestures(cols to rows, viewport, callbacks)
            .drawWithCache {
                val fit = DigViewport.fit(size.width, size.height, cols, rows)
                val base = IntSize((cols * fit.baseCellPx).roundToInt().coerceAtLeast(1), (rows * fit.baseCellPx).roundToInt().coerceAtLeast(1))
                val texture = renderTexture(base, palette, layerTexture, layerId, this)
                onDrawBehind {
                    val vp = viewport.value ?: fit
                    val state = currentDig
                    earth.sync(state)
                    drawRect(SURROUND)
                    withTransform({
                        translate(vp.panX, vp.panY)
                        scale(vp.zoom, vp.zoom, pivot = Offset.Zero)
                    }) {
                        drawSite(state, base, fit.baseCellPx, texture, earth, images, flash.value)
                    }
                }
            },
    )
}

/** Langes Druecken an ([x], [y]): oeffnet die Nahansicht, wenn dort ein entdeckter Knochen liegt. */
private fun openBoneAt(dig: DigUiState, x: Double, y: Double, onOpenBone: (BoneRef) -> Unit): Boolean {
    val ref = DigUiState.discoveredBoneAt(dig, x, y) ?: return false
    onOpenBone(ref)
    return true
}

/** Zeichnet Boden, Knochen, Erde und Schadensmarkierung in Flaechenpixeln bei Zoom 1. */
private fun DrawScope.drawSite(
    dig: DigUiState,
    base: IntSize,
    cellPx: Float,
    texture: ImageBitmap,
    earth: EarthBitmaps,
    images: Map<Pair<String, String>, ImageBitmap>,
    flash: Float,
) {
    // Boden der Grube: dieselbe Textur, abgedunkelt.
    drawImage(texture)
    drawRect(FLOOR_SHADE, size = Size(base.width.toFloat(), base.height.toFloat()))
    for (bone in dig.bones) {
        if (bone.recovered) continue
        val image = images[bone.speciesId to bone.boneId] ?: continue
        val rect = BoneImageLayout.imageRect(bone.cells, dig.site.width, bone.footprint, bone.pxPerCell, image.width, image.height)
        drawImage(
            image = image,
            dstOffset = IntOffset((rect.left * cellPx).roundToInt(), (rect.top * cellPx).roundToInt()),
            dstSize = IntSize((rect.width * cellPx).roundToInt().coerceAtLeast(1), (rect.height * cellPx).roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Medium,
        )
    }
    // Erde: Textur mit Tiefen-Abstufung und Hinweisen, ausgestanzt mit der Erdmaske.
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(0f, 0f, base.width.toFloat(), base.height.toFloat()), Paint())
    drawImage(texture)
    drawImage(earth.shade, srcSize = earth.size, dstSize = base, filterQuality = FilterQuality.Low)
    drawImage(earth.mask, srcSize = earth.size, dstSize = base, blendMode = BlendMode.DstIn, filterQuality = FilterQuality.Low)
    canvas.restore()
    if (flash > 0f) {
        for (ref in dig.feedback.damaged) {
            val bone = dig.bone(ref) ?: continue
            for (cell in bone.cells) {
                val center = Offset((cell % dig.site.width + HALF) * cellPx, (cell / dig.site.width + HALF) * cellPx)
                val radius = cellPx * FLASH_RADIUS_CELLS
                drawCircle(
                    brush = Brush.radialGradient(listOf(DAMAGE_FLASH.copy(alpha = flash * DAMAGE_FLASH.alpha), Color.Transparent), center, radius),
                    radius = radius,
                    center = center,
                )
            }
        }
    }
}

/** Gesteinstextur der Schicht als Bitmap in Flaechengroesse (einmal je Groesse). */
internal fun renderTexture(size: IntSize, palette: LayerTexturePalette, texture: LayerTexture, layerId: String, density: Density): ImageBitmap {
    val image = ImageBitmap(size.width, size.height)
    val pattern = LayerTexturePattern.generate(
        texture,
        LayerTexturePattern.seedFor(layerId),
        size.width.toFloat(),
        size.height.toFloat(),
        density.density,
    )
    val paths = TexturePaths.build(pattern)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(image), Size(size.width.toFloat(), size.height.toFloat())) {
        drawTexture(paths, palette)
    }
    return image
}

/**
 * Erdmaske und Ueberzug als Android-Bitmaps, gefuellt aus [EarthRaster]. [sync] kopiert nach einer
 * Aenderung der Flaeche nur den geaenderten Ausschnitt.
 */
private class EarthBitmaps(cols: Int, rows: Int, depthMin: Int, hintDepth: Int) {
    private val raster = EarthRaster(cols, rows, depthMin, hintDepth)
    private val maskBitmap: Bitmap = Bitmap.createBitmap(raster.width, raster.height, Bitmap.Config.ARGB_8888)
    private val shadeBitmap: Bitmap = Bitmap.createBitmap(raster.width, raster.height, Bitmap.Config.ARGB_8888)
    val mask: ImageBitmap = maskBitmap.asImageBitmap()
    val shade: ImageBitmap = shadeBitmap.asImageBitmap()
    val size = IntSize(raster.width, raster.height)
    private var lastSite: ExcavationSite? = null
    private var lastHinted: BooleanArray? = null

    fun sync(dig: DigUiState) {
        if (dig.site === lastSite && dig.hinted === lastHinted) return
        lastSite = dig.site
        lastHinted = dig.hinted
        val rect = raster.update(dig.site.depthsCopy(), dig.hinted) ?: return
        val offset = rect.top * raster.width + rect.left
        maskBitmap.setPixels(raster.mask, offset, raster.width, rect.left, rect.top, rect.width, rect.height)
        shadeBitmap.setPixels(raster.shade, offset, raster.width, rect.left, rect.top, rect.width, rect.height)
    }
}

// Darstellung (keine Spielwerte)
private const val FLASH_MILLIS = 450
private const val FLASH_RADIUS_CELLS = 1.1f
private const val HALF = 0.5f
private val SURROUND = Color(0xFF2B231C)
private val FLOOR_SHADE = Color(0x73000000)
private val DAMAGE_FLASH = Color(0xCCE53935)

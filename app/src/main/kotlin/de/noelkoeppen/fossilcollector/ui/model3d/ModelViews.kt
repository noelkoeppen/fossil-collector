package de.noelkoeppen.fossilcollector.ui.model3d

import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.noelkoeppen.fossilcollector.ui.theme.LocalQualityColors

/**
 * Ganzes Skelett einer Art in 3D, drehbar (ein Finger) und zoombar (zwei Finger), jeder Knochen
 * nach seiner Qualitaetsstufe eingefaerbt (AP2-10, Kapitel 8).
 *
 * Wiederverwendbar fuer Zuordnen (AP2-11), Sammelbuch (AP2-12) und Museum (AP2-13): Welcher
 * Knochen welche Qualitaet hat, liefert der Aufrufer aus dem Spielzustand (`core`); das Element
 * zeichnet nur.
 *
 * @param boneIds alle Knochen der Art (Reihenfolge aus `species.json`)
 * @param boneQualities Qualitaet 0..1 je Knochen mit Zustand; die uebrigen gelten als fehlend
 * @param missingBones Darstellung fehlender Knochen
 * @param description Text fuer Bedienungshilfen (z. B. Name der Art)
 */
@Composable
fun SkeletonView(
    speciesId: String,
    boneIds: List<String>,
    boneQualities: Map<String, Double>,
    modifier: Modifier = Modifier,
    missingBones: MissingBoneStyle = MissingBoneStyle.NEUTRAL,
    description: String? = null,
) {
    ModelView(speciesId, boneIds, boneQualities, missingBones, description, modifier)
}

/**
 * Ein einzelner Knochen in 3D, drehbar und zoombar, eingefaerbt nach Qualitaetsstufe.
 *
 * @param quality Qualitaet 0..1 oder `null` (dann in der Knochenfarbe ohne Stufenfarbe)
 */
@Composable
fun BoneView(
    speciesId: String,
    boneId: String,
    quality: Double?,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val ids = remember(boneId) { listOf(boneId) }
    val qualities = remember(boneId, quality) { quality?.let { mapOf(boneId to it) } ?: emptyMap() }
    ModelView(speciesId, ids, qualities, MissingBoneStyle.NEUTRAL, description, modifier)
}

@Composable
private fun ModelView(
    speciesId: String,
    boneIds: List<String>,
    boneQualities: Map<String, Double>,
    missingBones: MissingBoneStyle,
    description: String?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val cache = remember(context) { ModelMeshCache.get(context) }
    val meshes by produceState<Map<String, GlbMesh>?>(null, speciesId, boneIds) {
        value = cache.loadAll(speciesId, boneIds)
    }
    // Neue Kamera, wenn sich die Ansicht aendert (andere Art bzw. anderer Knochen).
    var camera by remember(speciesId, boneIds) { mutableStateOf(OrbitCamera()) }
    val colors = LocalQualityColors.current
    val background = MaterialTheme.colorScheme.surfaceContainerLowest

    Box(modifier = modifier.semantics { description?.let { contentDescription = it } }) {
        val loaded = meshes
        if (loaded == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            val frame = remember(loaded) { ModelFrame.of(loaded.values) }
            val looks = remember(loaded, boneQualities, colors, missingBones) {
                BoneTint.looks(loaded.keys, boneQualities, colors, missingBones) { loaded[it]?.baseColor }
            }
            ModelSurface(RenderScene(loaded, looks, frame, camera, background.rgb()), Modifier.fillMaxSize())
            Box(
                Modifier.matchParentSize().pointerInput(speciesId, boneIds) {
                    detectOrbitGestures(
                        onRotate = { dx, dy -> camera = camera.rotatedBy(dx, dy) },
                        onZoom = { factor -> camera = camera.zoomedBy(factor) },
                    )
                },
            )
        }
    }
}

/** GL-Flaeche mit [ModelRenderer]; zeichnet nur bei Aenderungen neu (kein Dauer-Rendern). */
@Composable
private fun ModelSurface(scene: RenderScene, modifier: Modifier) {
    val renderer = remember { ModelRenderer() }
    val holder = remember { arrayOfNulls<GLSurfaceView>(1) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder[0]?.onResume()
                Lifecycle.Event.ON_PAUSE -> holder[0]?.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(GLES_VERSION)
                setEGLConfigChooser(ModelRenderer.ConfigChooser())
                preserveEGLContextOnPause = true
                renderer.update(scene)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                holder[0] = this
            }
        },
        update = { view ->
            renderer.update(scene)
            view.requestRender()
        },
        onRelease = { holder[0] = null },
    )
}

/**
 * Gesten der 3D-Ansicht: ein Finger dreht (Bewegung in dp), zwei oder mehr Finger zoomen
 * (Faktor der Fingerspreizung). Wechselt die Fingerzahl, geht es ohne Sprung weiter, weil jede
 * Aenderung aus den Bewegungen seit dem letzten Ereignis kommt.
 */
internal suspend fun PointerInputScope.detectOrbitGestures(
    onRotate: (dxDp: Float, dyDp: Float) -> Unit,
    onZoom: (factor: Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.size >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) onZoom(zoom)
            } else if (pressed.size == 1) {
                val delta = pressed[0].positionChange()
                if (delta != Offset.Zero) onRotate(delta.x.toDp().value, delta.y.toDp().value)
            }
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

private fun Color.rgb() = floatArrayOf(red, green, blue)

private const val GLES_VERSION = 3

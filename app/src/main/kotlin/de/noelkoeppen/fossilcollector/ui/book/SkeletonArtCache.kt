package de.noelkoeppen.fossilcollector.ui.book

import android.util.Log
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser
import de.noelkoeppen.fossilcollector.core.data.BodyPlan
import de.noelkoeppen.fossilcollector.core.data.DataValidationException
import de.noelkoeppen.fossilcollector.core.data.SkeletonArt
import de.noelkoeppen.fossilcollector.core.data.SkeletonArtRepository
import java.util.concurrent.ConcurrentHashMap

/**
 * Zeichenfertige Skelett-Grafik: Pfade einmal mit [PathParser] geparst.
 *
 * @property viewBox Koordinatensystem der Pfade (aus dem SVG)
 * @property bones Knochen in Zeichenreihenfolge
 */
internal class RenderedSkeleton(val viewBox: Rect, val bones: List<RenderedBone>) {
    val aspectRatio: Float get() = viewBox.width / viewBox.height
}

/** Ein Knochen mit seinen Zonen-Pfaden; Index i entspricht der i-ten Zone aus `bones.json`. */
internal class RenderedBone(val id: String, val zones: List<Path>)

/**
 * Prozessweiter Cache der Skelett-Grafiken (AP-21). Laden und Parsen passiert je Datei genau
 * einmal; Arten ohne eigene Grafik teilen sich den Platzhalter ihres Koerperbaus
 * (Fallback-Regel in [SkeletonArtRepository]). Die Pfade werden nach dem Erzeugen nur gelesen.
 */
internal object SkeletonArtCache {

    private val repository = SkeletonArtRepository()

    /** Dateiname -> geparste Grafik. */
    private val byFile = ConcurrentHashMap<String, RenderedSkeleton>()

    /** Art und Koerperbau -> aufgeloeste Datei (leer = keine Grafik vorhanden). */
    private val resolved = ConcurrentHashMap<String, String>()

    private fun key(speciesId: String, bodyPlan: BodyPlan) = "$speciesId/${bodyPlan.serialName}"

    /** Bereits geladene Grafik, ohne Dateizugriff; `null`, wenn noch nicht geladen oder nicht vorhanden. */
    fun peek(speciesId: String, bodyPlan: BodyPlan): RenderedSkeleton? =
        resolved[key(speciesId, bodyPlan)]?.let { byFile[it] }

    /** Laedt die Grafik (blockierend, beim ersten Mal mit Dateizugriff); `null`, wenn keine existiert. */
    fun get(speciesId: String, bodyPlan: BodyPlan): RenderedSkeleton? {
        val file = resolved.getOrPut(key(speciesId, bodyPlan)) {
            repository.resolveFile(speciesId, bodyPlan).orEmpty()
        }
        if (file.isEmpty()) return null
        byFile[file]?.let { return it }
        val art = try {
            repository.load(file)
        } catch (e: DataValidationException) {
            Log.e(TAG, "Skelett-Grafik $file ungueltig", e)
            null
        } ?: return null
        return byFile.getOrPut(file) { render(art) }
    }

    private fun render(art: SkeletonArt): RenderedSkeleton {
        val box = art.viewBox
        return RenderedSkeleton(
            viewBox = Rect(
                left = box.x.toFloat(),
                top = box.y.toFloat(),
                right = (box.x + box.width).toFloat(),
                bottom = (box.y + box.height).toFloat(),
            ),
            bones = art.bones.map { bone ->
                RenderedBone(bone.id, bone.zones.map { PathParser().parsePathString(it.d).toPath() })
            },
        )
    }

    private const val TAG = "SkeletonArtCache"
}

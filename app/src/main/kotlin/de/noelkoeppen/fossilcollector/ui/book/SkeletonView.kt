package de.noelkoeppen.fossilcollector.ui.book

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.noelkoeppen.fossilcollector.core.data.BodyPlan
import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.ui.theme.FossilCollectorTheme
import de.noelkoeppen.fossilcollector.ui.theme.LocalQualityColors
import de.noelkoeppen.fossilcollector.ui.theme.QualityColors
import de.noelkoeppen.fossilcollector.ui.theme.Rock500
import de.noelkoeppen.fossilcollector.ui.theme.Rock900
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Zustand eines Knochens fuer die Skelett-Grafik (Schnittstelle zwischen Sammelbuch und Renderer).
 *
 * @property tierIndex Qualitaetsstufe des besten Bucheintrags (Index in `balance.qualityTiers`,
 *   Farbe ueber `QualityColors.forTierIndex`); `null` = Knochen fehlt (grau gestrichelt)
 * @property zoneQualities Qualitaet je Zone in der Reihenfolge aus `bones.json` (Einfaerbung pro Zone);
 *   `null`, wenn unbekannt; einzelne Eintraege `null` = Zone unbekannt
 */
@Immutable
data class SkeletonBone(
    val tierIndex: Int?,
    val zoneQualities: List<Double?>?,
)

/**
 * Skelett-Grafik einer Art (Kapitel 8.3 und 8.5), Renderer aus AP-21.
 *
 * Quelle ist `data/skeletons/skeleton_<speciesId>.json` bzw. ohne eigene Grafik der Platzhalter
 * `skeleton_<bodyPlan>.json` (erzeugt von `tools/svg2json.py`, geladen und geparst ueber
 * [SkeletonArtCache]). Gezeichnet wird in einer [Canvas], skaliert auf die viewBox (zentriert,
 * Seitenverhaeltnis bleibt). Einfaerbung je Zone nach [SkeletonColoring]: Zonenqualitaet, sonst
 * Knochenstufe; fehlende Knochen nur als grau gestrichelte Kontur. Knochen der Grafik, die nicht in
 * [bones] stehen, werden nicht gezeichnet.
 *
 * @param speciesId Art (Auswahl der Grafik)
 * @param bodyPlan Koerperbau (Platzhalter ohne eigene Grafik)
 * @param bones boneId -> Zustand, in der Reihenfolge aus `species.json`
 * @param contentDescription Beschreibung fuer Bedienungshilfen
 */
@Composable
fun SkeletonView(
    speciesId: String,
    bodyPlan: BodyPlan,
    bones: Map<String, SkeletonBone>,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val colors = LocalQualityColors.current
    val inspection = LocalInspectionMode.current
    var skeleton by remember(speciesId, bodyPlan) {
        // Vorschau: synchron laden, weil dort keine Effekte laufen.
        mutableStateOf(
            if (inspection) SkeletonArtCache.get(speciesId, bodyPlan) else SkeletonArtCache.peek(speciesId, bodyPlan),
        )
    }
    if (skeleton == null) {
        LaunchedEffect(speciesId, bodyPlan) {
            skeleton = withContext(Dispatchers.IO) { SkeletonArtCache.get(speciesId, bodyPlan) }
        }
    }
    val art = skeleton
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(art?.aspectRatio ?: fallbackAspectRatio(bodyPlan))
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        if (art != null) drawSkeleton(art, bones, colors)
    }
}

/** Seitenverhaeltnis bis zum Laden, passend zu den Platzhaltern (vermeidet Springen des Layouts). */
private fun fallbackAspectRatio(bodyPlan: BodyPlan): Float = when (bodyPlan) {
    BodyPlan.QUADRUPED -> 2f
    BodyPlan.BIPED -> 1.5f
}

private fun DrawScope.drawSkeleton(art: RenderedSkeleton, bones: Map<String, SkeletonBone>, colors: QualityColors) {
    val box = art.viewBox
    val scale = min(size.width / box.width, size.height / box.height)
    if (scale <= 0f) return
    val dx = (size.width - box.width * scale) / 2f - box.left * scale
    val dy = (size.height - box.height * scale) / 2f - box.top * scale
    // Linienbreiten in Pfadkoordinaten, damit sie auf dem Bildschirm konstant in dp sind.
    val missingStroke = Stroke(
        width = STROKE_DP.dp.toPx() / scale,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_DP.dp.toPx() / scale, GAP_DP.dp.toPx() / scale)),
    )
    withTransform({
        translate(dx, dy)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        for (bone in art.bones) {
            val state = bones[bone.id] ?: continue
            bone.zones.forEachIndexed { index, path ->
                when (val style = SkeletonColoring.zoneStyle(state, index, colors)) {
                    is ZoneStyle.Fill -> drawPath(path, style.color)
                    ZoneStyle.Missing -> drawPath(path, Rock500, style = missingStroke)
                }
            }
        }
    }
}

private const val STROKE_DP = 1.5f
private const val DASH_DP = 4f
private const val GAP_DP = 3f

// --- Vorschauen ---

/** Beispielzustand: vollstaendige, zonenweise, nur nach Stufe gefaerbte und fehlende Knochen. */
private val previewBones = mapOf(
    "skull" to SkeletonBone(4, listOf(0.95, 0.92, 0.97, 0.9)),
    "jaw" to SkeletonBone(2, listOf(0.6, 0.3, null, 0.75)),
    "cervical" to SkeletonBone(null, null),
    "dorsal" to SkeletonBone(3, null),
    "ribs" to SkeletonBone(1, listOf(0.1, 0.25, 0.4, 0.05)),
    "pelvis" to SkeletonBone(null, null),
    "forelimb" to SkeletonBone(2, listOf(0.55, 0.65, 0.8, 0.95)),
    "hindlimb" to SkeletonBone(0, null),
)

@Composable
private fun SkeletonPreviewFrame(content: @Composable () -> Unit) {
    val colors = remember {
        runCatching { QualityColors.fromTiers(DataRepository().load().balance.qualityTiers) }
            .getOrDefault(QualityColors.EMPTY)
    }
    FossilCollectorTheme(qualityColors = colors) {
        Column(Modifier.background(Rock900).padding(8.dp)) { content() }
    }
}

@Preview(widthDp = 360)
@Composable
private fun SkeletonQuadrupedPreview() {
    SkeletonPreviewFrame { SkeletonView("smilodon", BodyPlan.QUADRUPED, previewBones) }
}

@Preview(widthDp = 360)
@Composable
private fun SkeletonBipedPreview() {
    SkeletonPreviewFrame { SkeletonView("velociraptor", BodyPlan.BIPED, previewBones) }
}

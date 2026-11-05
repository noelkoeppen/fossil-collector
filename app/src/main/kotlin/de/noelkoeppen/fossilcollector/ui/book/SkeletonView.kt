package de.noelkoeppen.fossilcollector.ui.book

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.noelkoeppen.fossilcollector.core.data.BodyPlan
import de.noelkoeppen.fossilcollector.ui.theme.Bone
import de.noelkoeppen.fossilcollector.ui.theme.LocalQualityColors
import de.noelkoeppen.fossilcollector.ui.theme.QualityColors
import de.noelkoeppen.fossilcollector.ui.theme.Rock500

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
 * Skelett-Grafik einer Art (Kapitel 8.3 und 8.5).
 *
 * **Platzhalter (AP-20):** schematische Compose-Zeichnung eines generischen Vier- bzw. Zweibeiners
 * aus einfachen Formen, je Knochen eine Form, eingefaerbt nach Stufe (bei bekannten Zonen in
 * Streifen je Zone), fehlende Knochen grau gestrichelt. AP-21 ersetzt den Inhalt dieser Funktion
 * durch den echten Renderer (SVG -> JSON, `PathParser`, Zonen-IDs `<boneId>__<zoneId>`); die
 * Signatur bleibt dafuer stabil.
 *
 * @param speciesId Art (fuer AP-21: Auswahl der Grafik `skeleton_<speciesId>.json`)
 * @param bodyPlan Koerperbau (Platzhalter bzw. Fallback ohne eigene Grafik)
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
    val layout = PlaceholderLayouts.forBodyPlan(bodyPlan)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(layout.aspectRatio)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
    ) {
        val placed = layout.place(bones.keys.toList())
        for ((boneId, shape) in placed) {
            val bone = bones[boneId] ?: continue
            drawBone(shape, bone, colors)
        }
    }
}

/** Zeichnet einen Knochen: gefuellt nach Stufe bzw. Zonen, fehlend grau gestrichelt. */
private fun DrawScope.drawBone(shape: BoneShape, bone: SkeletonBone, colors: QualityColors) {
    val rect = Rect(
        left = shape.box.left * size.width,
        top = shape.box.top * size.height,
        right = shape.box.right * size.width,
        bottom = shape.box.bottom * size.height,
    )
    val path = Path().apply {
        if (shape.oval) {
            addOval(rect)
        } else {
            val radius = minOf(rect.width, rect.height) * CORNER_FRACTION
            addRoundRect(RoundRect(rect, CornerRadius(radius, radius)))
        }
    }
    val strokeWidth = STROKE_DP.dp.toPx()
    val tierIndex = bone.tierIndex
    if (tierIndex == null) {
        drawPath(
            path = path,
            color = Rock500,
            style = Stroke(
                width = strokeWidth,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH_DP.dp.toPx(), GAP_DP.dp.toPx())),
            ),
        )
        return
    }
    val tierColor = colors.forTierIndex(tierIndex) ?: Bone
    val zones = bone.zoneQualities
    if (zones.isNullOrEmpty()) {
        drawPath(path, tierColor)
    } else {
        // Streifen je Zone entlang der laengeren Seite der Form.
        clipPath(path) {
            val horizontal = rect.width >= rect.height
            zones.forEachIndexed { i, quality ->
                val color = quality?.let { colors.forQuality(it) } ?: tierColor
                val segment = if (horizontal) {
                    val w = rect.width / zones.size
                    Rect(Offset(rect.left + i * w, rect.top), Size(w, rect.height))
                } else {
                    val h = rect.height / zones.size
                    Rect(Offset(rect.left, rect.top + i * h), Size(rect.width, h))
                }
                drawRect(color = color, topLeft = segment.topLeft, size = segment.size)
            }
        }
    }
    drawPath(path = path, color = Bone, style = Stroke(width = strokeWidth))
}

/** Form eines Knochens im Platzhalter: Rechteck in Anteilen 0..1 der Zeichenflaeche. */
private data class BoneShape(val box: Rect, val oval: Boolean = false)

/**
 * Schematische Platzhalter-Anordnung (reine Gestaltung, keine Spielwerte). Bekannte Knochen-IDs
 * bekommen ihre Position im Koerper; unbekannte (neue Knochen in `bones.json`) erscheinen als
 * Kacheln in einer Reihe am unteren Rand, damit nichts verloren geht.
 */
private class PlaceholderLayout(val aspectRatio: Float, private val shapes: Map<String, BoneShape>) {

    fun place(boneIds: List<String>): List<Pair<String, BoneShape>> {
        val unknown = boneIds.filter { it !in shapes }
        val tileWidth = if (unknown.isEmpty()) 0f else minOf(TILE_MAX_WIDTH, 1f / unknown.size)
        return boneIds.map { id ->
            val known = shapes[id]
            if (known != null) {
                id to known
            } else {
                val i = unknown.indexOf(id)
                val left = i * tileWidth + TILE_GAP / 2
                id to BoneShape(Rect(left, TILE_TOP, left + tileWidth - TILE_GAP, TILE_BOTTOM))
            }
        }
    }
}

private object PlaceholderLayouts {
    /** Vierbeiner, Blick nach rechts. */
    val QUADRUPED = PlaceholderLayout(
        aspectRatio = 2f,
        shapes = mapOf(
            "skull" to BoneShape(Rect(0.78f, 0.12f, 0.96f, 0.36f), oval = true),
            "jaw" to BoneShape(Rect(0.80f, 0.38f, 0.95f, 0.46f)),
            "cervical" to BoneShape(Rect(0.66f, 0.22f, 0.77f, 0.32f)),
            "dorsal" to BoneShape(Rect(0.32f, 0.24f, 0.65f, 0.32f)),
            "ribs" to BoneShape(Rect(0.38f, 0.34f, 0.63f, 0.56f), oval = true),
            "pelvis" to BoneShape(Rect(0.16f, 0.22f, 0.31f, 0.40f)),
            "forelimb" to BoneShape(Rect(0.58f, 0.48f, 0.64f, 0.88f)),
            "hindlimb" to BoneShape(Rect(0.20f, 0.42f, 0.27f, 0.88f)),
        ),
    )

    /** Zweibeiner (Theropode/Laufvogel), Blick nach rechts. */
    val BIPED = PlaceholderLayout(
        aspectRatio = 1.5f,
        shapes = mapOf(
            "skull" to BoneShape(Rect(0.70f, 0.06f, 0.90f, 0.24f), oval = true),
            "jaw" to BoneShape(Rect(0.72f, 0.26f, 0.89f, 0.32f)),
            "cervical" to BoneShape(Rect(0.62f, 0.18f, 0.69f, 0.38f)),
            "dorsal" to BoneShape(Rect(0.36f, 0.36f, 0.64f, 0.44f)),
            "ribs" to BoneShape(Rect(0.42f, 0.46f, 0.62f, 0.62f), oval = true),
            "pelvis" to BoneShape(Rect(0.22f, 0.34f, 0.35f, 0.50f)),
            "forelimb" to BoneShape(Rect(0.60f, 0.50f, 0.65f, 0.68f)),
            "hindlimb" to BoneShape(Rect(0.26f, 0.52f, 0.34f, 0.88f)),
        ),
    )

    fun forBodyPlan(plan: BodyPlan): PlaceholderLayout = when (plan) {
        BodyPlan.QUADRUPED -> QUADRUPED
        BodyPlan.BIPED -> BIPED
    }
}

private const val CORNER_FRACTION = 0.35f
private const val STROKE_DP = 1.5f
private const val DASH_DP = 4f
private const val GAP_DP = 3f
private const val TILE_MAX_WIDTH = 0.2f
private const val TILE_GAP = 0.02f
private const val TILE_TOP = 0.91f
private const val TILE_BOTTOM = 0.99f

package de.noelkoeppen.fossilcollector.ui.dig

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import de.noelkoeppen.fossilcollector.core.data.LayerTexture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * Farbband mit Gesteinstextur (Kapitel 8.5, AP-26) als Hintergrund-Ebene; gedacht als erstes Kind
 * einer `Box` mit `Modifier.matchParentSize()`.
 *
 * Leistung: Das Muster und die Pfade entstehen in `drawWithCache` nur, wenn sich Groesse, Schicht
 * oder Palette aendern, nicht bei jedem Tick. Die eigene `graphicsLayer` haelt die Zeichnung als
 * Anzeigeliste, damit Aenderungen am Text darueber sie nicht neu aufzeichnen.
 */
@Composable
fun LayerTextureBackground(
    texture: LayerTexture,
    seed: Int,
    palette: LayerTexturePalette,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .graphicsLayer()
            .drawWithCache {
                val pattern = LayerTexturePattern.generate(texture, seed, size.width, size.height, density)
                val paths = TexturePaths.build(pattern)
                val base = Color(palette.base)
                onDrawBehind {
                    drawRect(base)
                    for ((tone, path) in paths.fills) drawPath(path, Color(palette.tone(tone)))
                    for (stroke in paths.strokes) {
                        drawPath(
                            stroke.path,
                            Color(palette.tone(stroke.tone)),
                            style = Stroke(width = stroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            },
    )
}

/** Fertige Pfade eines Musters: Flaechen je Farbton und Linien mit eigener Breite. */
private class TexturePaths(val fills: List<Pair<TextureTone, Path>>, val strokes: List<StrokePath>) {

    class StrokePath(val path: Path, val width: Float, val tone: TextureTone)

    companion object {
        fun build(pattern: TexturePattern): TexturePaths {
            // Alle Koerner und Kiesel eines Tons in einem Pfad: wenige Zeichenaufrufe je Band.
            val fills = TextureTone.entries.associateWith { Path() }
            for (grain in pattern.grains) {
                fills.getValue(grain.tone).addOval(
                    Rect(Offset(grain.x, grain.y), grain.radius),
                )
            }
            for (pebble in pattern.pebbles) fills.getValue(pebble.tone).addPebble(pebble)
            val strokes = ArrayList<StrokePath>(pattern.lines.size + pattern.shells.size)
            for (line in pattern.lines) strokes += StrokePath(polyline(line.points), line.width, line.tone)
            for (shell in pattern.shells) strokes += StrokePath(shellPath(shell), shell.strokeWidth, shell.tone)
            return TexturePaths(fills.filterValues { !it.isEmpty }.toList(), strokes)
        }

        /** Gedrehte Ellipse als Polygon (Path kennt keine gedrehten Ovale). */
        private fun Path.addPebble(pebble: TexturePebble) {
            val rotation = pebble.rotationDegrees * DEG_TO_RAD
            val cosR = cos(rotation)
            val sinR = sin(rotation)
            for (i in 0 until PEBBLE_SEGMENTS) {
                val t = 2f * PI.toFloat() * i / PEBBLE_SEGMENTS
                val ex = cos(t) * pebble.radiusX
                val ey = sin(t) * pebble.radiusY
                val x = pebble.x + ex * cosR - ey * sinR
                val y = pebble.y + ex * sinR + ey * cosR
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }

        private fun polyline(points: List<Float>): Path = Path().apply {
            moveTo(points[0], points[1])
            var i = 2
            while (i + 1 < points.size) {
                lineTo(points[i], points[i + 1])
                i += 2
            }
        }

        private fun shellPath(shell: TextureShell): Path = when (shell.kind) {
            ShellKind.AMMONITE -> ammonite(shell)
            ShellKind.BIVALVE -> bivalve(shell)
        }

        /** Logarithmische Spirale mit gut zwei Windungen, aussen am Radius endend. */
        private fun ammonite(shell: TextureShell): Path = Path().apply {
            val start = shell.rotationDegrees * DEG_TO_RAD
            for (i in 0..AMMONITE_STEPS) {
                val t = AMMONITE_TURNS * 2f * PI.toFloat() * i / AMMONITE_STEPS
                val r = shell.radius * exp(AMMONITE_GROWTH * (t - AMMONITE_TURNS * 2f * PI.toFloat()))
                val x = shell.x + cos(start + t) * r
                val y = shell.y + sin(start + t) * r
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }

        /** Muschelschale: Bogen mit Schlossrand und drei Rippen zum Wirbel. */
        private fun bivalve(shell: TextureShell): Path = Path().apply {
            val rotation = shell.rotationDegrees * DEG_TO_RAD
            fun point(angle: Float, radius: Float): Offset {
                val a = rotation + angle
                return Offset(shell.x + cos(a) * radius, shell.y + sin(a) * radius)
            }
            val left = point(BIVALVE_HALF_ANGLE + PI.toFloat(), shell.radius)
            moveTo(shell.x, shell.y)
            lineTo(left.x, left.y)
            for (i in 0..BIVALVE_STEPS) {
                val angle = PI.toFloat() + BIVALVE_HALF_ANGLE + (PI.toFloat() - 2f * BIVALVE_HALF_ANGLE) * i / BIVALVE_STEPS
                val p = point(angle, shell.radius)
                lineTo(p.x, p.y)
            }
            lineTo(shell.x, shell.y)
            for (k in 1..BIVALVE_RIBS) {
                val angle = PI.toFloat() + BIVALVE_HALF_ANGLE + (PI.toFloat() - 2f * BIVALVE_HALF_ANGLE) * k / (BIVALVE_RIBS + 1)
                val p = point(angle, shell.radius * BIVALVE_RIB_LENGTH)
                moveTo(shell.x, shell.y)
                lineTo(p.x, p.y)
            }
        }

        private const val DEG_TO_RAD = (PI / 180.0).toFloat()
        private const val PEBBLE_SEGMENTS = 14
        private const val AMMONITE_STEPS = 48
        private const val AMMONITE_TURNS = 2.25f
        private const val AMMONITE_GROWTH = 0.16f
        private const val BIVALVE_STEPS = 10
        private const val BIVALVE_RIBS = 3
        private const val BIVALVE_HALF_ANGLE = 0.35f
        private const val BIVALVE_RIB_LENGTH = 0.8f
    }
}

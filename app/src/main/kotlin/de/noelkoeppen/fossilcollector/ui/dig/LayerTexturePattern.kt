package de.noelkoeppen.fossilcollector.ui.dig

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.data.LayerTexture
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Farbton eines Musterelements; die Farbe liefert [LayerTexturePalette] passend zur Bandfarbe.
 */
enum class TextureTone {
    /** Etwas heller als das Band (Sandkorn, Lichtkante). */
    LIGHT,

    /** Etwas dunkler als das Band (Kiesel, Schattenfuge). */
    DARK,

    /** Deutlich heller (Kreidesprenkel, Muschelschalen). */
    BRIGHT,
}

/** Rundes Korn (Sand, Kreidesprenkel); Koordinaten in Pixeln. */
@Immutable
data class TextureGrain(val x: Float, val y: Float, val radius: Float, val tone: TextureTone)

/** Kiesel bzw. Feuersteinknolle als gedrehte Ellipse; Koordinaten in Pixeln. */
@Immutable
data class TexturePebble(
    val x: Float,
    val y: Float,
    val radiusX: Float,
    val radiusY: Float,
    val rotationDegrees: Float,
    val tone: TextureTone,
)

/** Schichtungslinie als Polylinie `[x0, y0, x1, y1, ...]` in Pixeln. */
@Immutable
data class TextureLine(val points: List<Float>, val width: Float, val tone: TextureTone)

/** Art einer angedeuteten Muschel. */
enum class ShellKind { AMMONITE, BIVALVE }

/** Angedeutete Muschel bzw. Ammoniten-Spirale (nur Kontur); Koordinaten in Pixeln. */
@Immutable
data class TextureShell(
    val kind: ShellKind,
    val x: Float,
    val y: Float,
    val radius: Float,
    val rotationDegrees: Float,
    val strokeWidth: Float,
    val tone: TextureTone,
)

/** Vollstaendiges Muster eines Schichtbands fuer eine bestimmte Groesse. */
@Immutable
data class TexturePattern(
    val grains: List<TextureGrain>,
    val pebbles: List<TexturePebble>,
    val lines: List<TextureLine>,
    val shells: List<TextureShell>,
) {
    /** Anzahl aller Elemente (fuer die Obergrenze [LayerTexturePattern.MAX_ELEMENTS]). */
    val size: Int get() = grains.size + pebbles.size + lines.size + shells.size

    companion object {
        val EMPTY = TexturePattern(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * Erzeugt die Gesteinstextur eines Schichtbands (Kapitel 8.5, AP-26) als reine Funktion: gleiche
 * Eingaben liefern dasselbe Muster. Der Seed kommt aus der Schicht-ID ([seedFor]), damit jede Schicht
 * bei jedem Start gleich aussieht. Der Zufall ist reine Optik und laeuft daher nicht ueber `GameRandom`.
 *
 * Alle Groessen sind in dp angelegt und werden mit [generate]s `unit` (Pixel je dp) skaliert. Die
 * Elementzahl waechst mit der Flaeche, ist aber auf [MAX_ELEMENTS] begrenzt.
 */
object LayerTexturePattern {

    /** Obergrenze der Elemente je Band (Zeichenaufwand bei sehr grossen Flaechen). */
    const val MAX_ELEMENTS = 900

    /** Stabiler Seed aus der Schicht-ID (`String.hashCode` ist auf der JVM festgelegt). */
    fun seedFor(layerId: String): Int = layerId.hashCode()

    /**
     * @param width Breite des Bands in Pixeln
     * @param height Hoehe des Bands in Pixeln
     * @param unit Pixel je dp (Bildschirmdichte)
     */
    fun generate(texture: LayerTexture, seed: Int, width: Float, height: Float, unit: Float): TexturePattern {
        if (width <= 0f || height <= 0f || unit <= 0f || texture == LayerTexture.PLAIN) return TexturePattern.EMPTY
        val random = Random(seed * SEED_MIX + texture.ordinal)
        val builder = Builder(random, width, height, unit)
        when (texture) {
            LayerTexture.PLAIN -> Unit
            LayerTexture.GRAVEL -> builder.gravel()
            LayerTexture.BANDED -> builder.banded()
            LayerTexture.CHALK -> builder.chalk()
        }
        return builder.build()
    }

    private class Builder(val random: Random, val width: Float, val height: Float, val unit: Float) {
        val grains = mutableListOf<TextureGrain>()
        val pebbles = mutableListOf<TexturePebble>()
        val lines = mutableListOf<TextureLine>()
        val shells = mutableListOf<TextureShell>()

        /** Flaeche in dp² (Grundlage fuer die Dichten). */
        val areaDp = (width / unit) * (height / unit)

        fun count(perThousandDp2: Float): Int = (areaDp / AREA_UNIT * perThousandDp2).roundToInt()

        fun range(from: Float, to: Float): Float = from + random.nextFloat() * (to - from)

        fun grain(minDp: Float, maxDp: Float, tone: TextureTone) {
            grains += TextureGrain(range(0f, width), range(0f, height), range(minDp, maxDp) * unit, tone)
        }

        fun pebble(minDp: Float, maxDp: Float, tone: TextureTone) {
            val rx = range(minDp, maxDp) * unit
            val ry = rx * range(PEBBLE_MIN_ASPECT, 1f)
            pebbles += TexturePebble(
                x = range(0f, width),
                y = range(0f, height),
                radiusX = rx,
                radiusY = ry,
                rotationDegrees = range(-PEBBLE_MAX_TILT, PEBBLE_MAX_TILT),
                tone = tone,
            )
        }

        /** Leicht wellige, fast waagrechte Linie ueber die ganze Breite bei Hoehe [y]. */
        fun stratum(y: Float, amplitudeDp: Float, widthDp: Float, tone: TextureTone) {
            val wavelength = range(WAVE_MIN_DP, WAVE_MAX_DP) * unit
            val phase = range(0f, 2f * PI.toFloat())
            val tilt = range(-STRATUM_MAX_TILT, STRATUM_MAX_TILT) * unit
            val steps = max(2, (width / (STRATUM_STEP_DP * unit)).roundToInt())
            val points = ArrayList<Float>((steps + 1) * 2)
            for (i in 0..steps) {
                val x = width * i / steps
                val wave = sin(2f * PI.toFloat() * x / wavelength + phase) * amplitudeDp * unit
                points += x
                points += y + wave + tilt * (x / width - HALF)
            }
            lines += TextureLine(points, widthDp * unit, tone)
        }

        fun shell(kind: ShellKind, minDp: Float, maxDp: Float, tone: TextureTone) {
            val radius = range(minDp, maxDp) * unit
            shells += TextureShell(
                kind = kind,
                x = range(radius, max(radius, width - radius)),
                y = range(radius, max(radius, height - radius)),
                radius = radius,
                rotationDegrees = range(0f, FULL_TURN),
                strokeWidth = SHELL_STROKE_DP * unit,
                tone = tone,
            )
        }

        fun tone(lightShare: Float): TextureTone = if (random.nextFloat() < lightShare) TextureTone.LIGHT else TextureTone.DARK

        /** Quartaer: lockerer Kies und Sand. */
        fun gravel() {
            repeat(count(GRAVEL_GRAINS)) { grain(0.5f, 1.2f, tone(HALF)) }
            repeat(count(GRAVEL_PEBBLES)) { pebble(2.5f, 6f, tone(GRAVEL_PEBBLE_LIGHT_SHARE)) }
        }

        /** Neogen: feine, dichte Baenderung mit wenig Koernung. */
        fun banded() {
            var y = range(0f, BAND_MIN_GAP_DP) * unit
            var light = random.nextBoolean()
            while (y < height) {
                stratum(y, amplitudeDp = range(0.4f, 1.6f), widthDp = range(0.6f, 1.4f), tone = if (light) TextureTone.LIGHT else TextureTone.DARK)
                light = !light
                y += range(BAND_MIN_GAP_DP, BAND_MAX_GAP_DP) * unit
            }
            repeat(count(BANDED_GRAINS)) { grain(0.4f, 0.8f, tone(HALF)) }
            repeat(count(BANDED_PEBBLES)) { pebble(1.2f, 2.5f, TextureTone.DARK) }
        }

        /** Kreide: helle Sprenkel, wenige Feuersteinknollen und angedeutete Muscheln. */
        fun chalk() {
            repeat(count(CHALK_SPECKLES)) { grain(0.5f, 1.4f, TextureTone.BRIGHT) }
            repeat(count(CHALK_GRAINS)) { grain(0.4f, 0.9f, TextureTone.LIGHT) }
            repeat(count(CHALK_NODULES)) { pebble(2.5f, 5f, TextureTone.DARK) }
            // Ein bis zwei breite, helle Kreidelagen.
            repeat(1 + random.nextInt(2)) {
                stratum(range(0.2f, 0.8f) * height, amplitudeDp = range(0.5f, 1.5f), widthDp = range(1.5f, 3f), tone = TextureTone.LIGHT)
            }
            val shellCount = max(1, count(CHALK_SHELLS))
            repeat(shellCount) {
                val kind = if (random.nextFloat() < AMMONITE_SHARE) ShellKind.AMMONITE else ShellKind.BIVALVE
                shell(kind, 4f, 7f, TextureTone.BRIGHT)
            }
        }

        fun build(): TexturePattern {
            // Grenze einhalten, in dieser Reihenfolge der Wichtigkeit: Linien, Muscheln, Kiesel, Koerner.
            var budget = MAX_ELEMENTS
            fun <T> limited(items: List<T>): List<T> = items.take(min(items.size, budget)).also { budget -= it.size }
            val keptLines = limited(lines)
            val keptShells = limited(shells)
            val keptPebbles = limited(pebbles)
            val keptGrains = limited(grains)
            return TexturePattern(keptGrains, keptPebbles, keptLines, keptShells)
        }
    }

    private const val SEED_MIX = 31
    private const val AREA_UNIT = 1000f
    private const val HALF = 0.5f
    private const val FULL_TURN = 360f
    private const val PEBBLE_MIN_ASPECT = 0.55f
    private const val PEBBLE_MAX_TILT = 35f
    private const val WAVE_MIN_DP = 60f
    private const val WAVE_MAX_DP = 160f
    private const val STRATUM_MAX_TILT = 3f
    private const val STRATUM_STEP_DP = 6f
    private const val SHELL_STROKE_DP = 1.1f

    // Dichten je 1000 dp²
    private const val GRAVEL_GRAINS = 9f
    private const val GRAVEL_PEBBLES = 0.55f
    private const val GRAVEL_PEBBLE_LIGHT_SHARE = 0.35f
    private const val BAND_MIN_GAP_DP = 4f
    private const val BAND_MAX_GAP_DP = 8f
    private const val BANDED_GRAINS = 3f
    private const val BANDED_PEBBLES = 0.12f
    private const val CHALK_SPECKLES = 9f
    private const val CHALK_GRAINS = 2f
    private const val CHALK_NODULES = 0.12f
    private const val CHALK_SHELLS = 0.16f
    private const val AMMONITE_SHARE = 0.6f
}

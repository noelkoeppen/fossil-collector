package de.noelkoeppen.fossilcollector.ui.prep

import de.noelkoeppen.fossilcollector.ui.dig.PixelRect
import kotlin.math.floor
import kotlin.random.Random

/**
 * Rechnet aus der Restschicht der Nahansicht (AP2-08, Kapitel 5.1) ein kleines Rasterbild, das die UI
 * gestreckt und gefiltert ueber das Knochenbild legt ([pixels], ARGB ohne Vormultiplikation).
 *
 * - **Lose Erde:** heller, feinkoerniger Sand, leicht durchscheinend (je duenner, desto mehr
 *   schimmert der Knochen durch).
 * - **Harte Stellen:** dunkle, rotbraune, fleckige Kruste, deckend, mit dunklem Rand; so sind sie
 *   klar von loser Erde zu unterscheiden.
 * - **Frei:** durchsichtig. Die Kanten werden wie bei der Erde der Flaeche weich zwischen den
 *   Zellmitten interpoliert und mit festem Rauschen ausgefranst, damit kein Raster sichtbar ist.
 *
 * Alles ist reine Optik und deterministisch (fester Seed, kein `GameRandom`). [update] vergleicht mit
 * dem zuletzt gezeichneten Stand und rechnet nur die Pixel um geaenderte Zellen neu.
 *
 * @param cols Breite der Nahansicht in feinen Zellen
 * @param rows Hoehe in feinen Zellen
 * @param maxDepth groesste Dicke laut Daten (Bezug fuer die Deckkraft loser Erde)
 * @param sub Pixel je feiner Zelle und Achse (UI-Konstante)
 */
class ResidueRaster(
    val cols: Int,
    val rows: Int,
    private val maxDepth: Int,
    val sub: Int = SUB,
) {
    init {
        require(cols >= 1 && rows >= 1 && maxDepth >= 1 && sub >= 1)
    }

    /** Breite in Pixeln. */
    val width: Int = cols * sub

    /** Hoehe in Pixeln. */
    val height: Int = rows * sub

    /** Restschicht als ARGB ohne Vormultiplikation, zeilenweise. */
    val pixels: IntArray = IntArray(width * height)

    private val cells = cols * rows
    private var lastDepths: IntArray? = null
    private var lastHard: BooleanArray? = null

    private val cover = FloatArray(cells)
    private val hardness = FloatArray(cells)
    private val thickness = FloatArray(cells)
    private val noise: FloatArray = buildNoise()

    /**
     * Uebernimmt neue Dicken und harte Stellen und rechnet die betroffenen Pixel neu.
     *
     * @return geaendertes Rechteck oder `null`, wenn sich nichts geaendert hat
     */
    fun update(depths: IntArray, hard: BooleanArray): PixelRect? {
        require(depths.size == cells && hard.size == cells) { "Groesse passt nicht zu ${cols}x$rows" }
        val oldDepths = lastDepths
        val oldHard = lastHard
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (cell in 0 until cells) {
            val changed = oldDepths == null || oldHard == null || oldDepths[cell] != depths[cell] || oldHard[cell] != hard[cell]
            if (!changed) continue
            val d = depths[cell]
            cover[cell] = if (d > 0) 1f else 0f
            hardness[cell] = if (d > 0 && hard[cell]) 1f else 0f
            thickness[cell] = (d.toFloat() / maxDepth).coerceIn(0f, 1f)
            val x = cell % cols
            val y = cell / cols
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        lastDepths = depths.copyOf()
        lastHard = hard.copyOf()
        if (maxX < 0) return null
        // Ein Zellrand fuer die Interpolation bis zur Mitte der Nachbarzellen.
        val rect = PixelRect(
            left = maxOf(0, minX - 1) * sub,
            top = maxOf(0, minY - 1) * sub,
            right = minOf(cols, maxX + 2) * sub,
            bottom = minOf(rows, maxY + 2) * sub,
        )
        render(rect)
        return rect
    }

    private fun render(rect: PixelRect) {
        for (py in rect.top until rect.bottom) {
            val v = (py + HALF) / sub - HALF
            val j0 = floor(v).toInt()
            val fy = smooth(v - j0)
            val ja = j0.coerceIn(0, rows - 1) * cols
            val jb = (j0 + 1).coerceIn(0, rows - 1) * cols
            for (px in rect.left until rect.right) {
                val u = (px + HALF) / sub - HALF
                val i0 = floor(u).toInt()
                val fx = smooth(u - i0)
                val ia = i0.coerceIn(0, cols - 1)
                val ib = (i0 + 1).coerceIn(0, cols - 1)
                val p = py * width + px
                pixels[p] = pixel(
                    sample(cover, ja, jb, ia, ib, fx, fy),
                    sample(hardness, ja, jb, ia, ib, fx, fy),
                    sample(thickness, ja, jb, ia, ib, fx, fy),
                    noise[p],
                )
            }
        }
    }

    private fun pixel(coverValue: Float, hardValue: Float, thickValue: Float, n: Float): Int {
        val present = smoothstep(EDGE_LEVEL - EDGE_WIDTH, EDGE_LEVEL + EDGE_WIDTH, coverValue + n * EDGE_NOISE)
        if (present <= 0f) return 0
        // Harte Kruste mit leicht ausgefranstem Rand, innen fleckig, am Rand dunkler.
        val crust = smoothstep(CRUST_LEVEL - CRUST_EDGE, CRUST_LEVEL + CRUST_EDGE, hardValue + n * CRUST_NOISE)
        val rim = crust * (1f - smoothstep(RIM_FROM, RIM_TO, hardValue))
        val grain = 1f + n * LOOSE_GRAIN
        val mottle = 1f + n * HARD_MOTTLE - rim * RIM_DARKEN
        val r = mix(LOOSE_R * grain, HARD_R * mottle, crust)
        val g = mix(LOOSE_G * grain, HARD_G * mottle, crust)
        val b = mix(LOOSE_B * grain, HARD_B * mottle, crust)
        val looseAlpha = LOOSE_ALPHA_MIN + (LOOSE_ALPHA_MAX - LOOSE_ALPHA_MIN) * thickValue
        val alpha = present * mix(looseAlpha, HARD_ALPHA, crust)
        return (toByte(alpha) shl ALPHA_SHIFT) or (channel(r) shl RED_SHIFT) or (channel(g) shl GREEN_SHIFT) or channel(b)
    }

    private fun sample(field: FloatArray, ja: Int, jb: Int, ia: Int, ib: Int, fx: Float, fy: Float): Float {
        val top = field[ja + ia] + (field[ja + ib] - field[ja + ia]) * fx
        val bottom = field[jb + ia] + (field[jb + ib] - field[jb + ia]) * fx
        return top + (bottom - top) * fy
    }

    /** Festes Wertrauschen in -0.5..0.5: grob (halbe feine Zelle) plus feines Korn je Pixel. */
    private fun buildNoise(): FloatArray {
        val random = Random(NOISE_SEED)
        val step = maxOf(1, sub / NOISE_CELLS_PER_CELL)
        val lw = width / step + 2
        val lh = height / step + 2
        val lattice = FloatArray(lw * lh) { random.nextFloat() }
        val result = FloatArray(width * height)
        for (py in 0 until height) {
            val v = py.toFloat() / step
            val j = v.toInt()
            val fy = smooth(v - j)
            for (px in 0 until width) {
                val u = px.toFloat() / step
                val i = u.toInt()
                val fx = smooth(u - i)
                val a = lattice[j * lw + i] + (lattice[j * lw + i + 1] - lattice[j * lw + i]) * fx
                val c = lattice[(j + 1) * lw + i] + (lattice[(j + 1) * lw + i + 1] - lattice[(j + 1) * lw + i]) * fx
                val coarse = a + (c - a) * fy
                result[py * width + px] = COARSE_SHARE * coarse + (1f - COARSE_SHARE) * random.nextFloat() - HALF
            }
        }
        return result
    }

    companion object {
        /** Pixel je feiner Zelle (Darstellung, kein Spielwert). */
        const val SUB: Int = 8

        // Darstellungskonstanten (kein Spielwert)
        private const val EDGE_LEVEL = 0.5f
        private const val EDGE_WIDTH = 0.12f
        private const val EDGE_NOISE = 0.8f
        private const val CRUST_LEVEL = 0.5f
        private const val CRUST_EDGE = 0.08f
        private const val CRUST_NOISE = 0.9f
        private const val RIM_FROM = 0.55f
        private const val RIM_TO = 0.85f
        private const val RIM_DARKEN = 0.35f
        private const val LOOSE_GRAIN = 0.35f
        private const val HARD_MOTTLE = 0.6f
        private const val LOOSE_R = 0xD2
        private const val LOOSE_G = 0xBC
        private const val LOOSE_B = 0x8F
        private const val HARD_R = 0x7A
        private const val HARD_G = 0x4E
        private const val HARD_B = 0x2E
        private const val LOOSE_ALPHA_MIN = 0.6f
        private const val LOOSE_ALPHA_MAX = 0.85f
        private const val HARD_ALPHA = 1f
        private const val NOISE_SEED = 0x5A4D
        private const val NOISE_CELLS_PER_CELL = 2
        private const val COARSE_SHARE = 0.6f

        private const val HALF = 0.5f
        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val BYTE = 255f

        private fun toByte(value: Float): Int = (value.coerceIn(0f, 1f) * BYTE + HALF).toInt()

        private fun channel(value: Float): Int = (value + HALF).toInt().coerceIn(0, BYTE.toInt())

        private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float =
            smooth(((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f))

        /** Deckkraft (0..255) eines Pixels. */
        fun alphaOf(argb: Int): Int = (argb ushr ALPHA_SHIFT) and 0xFF

        /** Rotanteil (0..255) eines Pixels. */
        fun redOf(argb: Int): Int = (argb ushr RED_SHIFT) and 0xFF
    }
}

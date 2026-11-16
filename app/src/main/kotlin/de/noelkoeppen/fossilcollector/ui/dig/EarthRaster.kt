package de.noelkoeppen.fossilcollector.ui.dig

import kotlin.math.floor
import kotlin.random.Random

/** Rechteck in Pixeln des [EarthRaster] (rechts und unten exklusiv). */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Int get() = width * height
}

/**
 * Rechnet aus den Erdtiefen der Grabungsflaeche zwei kleine Rasterbilder (AP2-07, Kapitel 8), die
 * die UI gestreckt und gefiltert ueber Boden und Knochen legt:
 *
 * - [mask]: Deckkraft der Erde (weiss mit Alpha). 0 dort, wo bis auf den Grund gegraben ist, voll
 *   bei dicker Erde; liegt nur noch wenig Erde (hoechstens `hintDepth`), wird sie etwas durchscheinend.
 * - [shade]: Ueberzug der Erde: dunkler, je tiefer gegraben ist (Tiefen-Abstufung, erst unterhalb
 *   der kleinsten Anfangstiefe, damit die unberuehrte Flaeche gleichmaessig aussieht und die
 *   zufaelligen Anfangstiefen kein Raster zeigen), und eine helle, koernige Verfaerbung an Zellen mit Hinweis.
 *
 * Kein sichtbares Raster: Die Werte je Zelle werden mit weicher Gewichtung zwischen den
 * Zellmittelpunkten interpoliert, die Kante eines Lochs liegt bei der halben Hoehe und wird mit einem
 * festen Rauschen ausgefranst. Alles ist reine Optik und deterministisch (fester Seed, kein
 * `GameRandom`), der Spielzustand bleibt unberuehrt.
 *
 * Leistung: [update] vergleicht mit dem zuletzt gezeichneten Stand und rechnet nur die Pixel um
 * geaenderte Zellen neu (Zellen plus zwei Zellen Rand fuer Mittelung und Interpolation); das Ergebnis ist das
 * geaenderte Rechteck, das die UI ins Bitmap kopiert. Der Vergleich statt `ToolResult.changedCells`
 * ist Absicht: Der UI-Zustand kommt ueber einen `StateFlow`, der Zwischenstaende ueberspringen kann.
 *
 * @param cols Breite der Flaeche in Zellen
 * @param rows Hoehe der Flaeche in Zellen
 * @param depthMin kleinste Anfangstiefe (`excavation.depthMin`), Bezug der Tiefen-Abstufung
 * @param hintDepth `excavation.hintDepth`: ab hier wird die Erde durchscheinend
 * @param sub Pixel je Zelle und Achse (UI-Konstante, siehe [SUB])
 */
class EarthRaster(
    val cols: Int,
    val rows: Int,
    private val depthMin: Int,
    private val hintDepth: Int,
    val sub: Int = SUB,
) {
    init {
        require(cols >= 1 && rows >= 1) { "Flaeche muss mindestens 1x1 sein" }
        require(depthMin >= 1 && hintDepth >= 1 && sub >= 1)
    }

    /** Breite in Pixeln. */
    val width: Int = cols * sub

    /** Hoehe in Pixeln. */
    val height: Int = rows * sub

    /** Deckkraft der Erde als ARGB (weiss, Alpha = Deckkraft), zeilenweise. */
    val mask: IntArray = IntArray(width * height)

    /** Ueberzug der Erde (Tiefen-Abstufung und Hinweise) als ARGB ohne Vormultiplikation, zeilenweise. */
    val shade: IntArray = IntArray(width * height)

    private val cells = cols * rows
    private var lastDepths: IntArray? = null
    private var lastHinted: BooleanArray? = null

    // Werte je Zelle in 0..1
    private val open = FloatArray(cells)
    private val thin = FloatArray(cells)
    private val dug = FloatArray(cells)
    /** [dug] gemittelt ueber 3 x 3 Zellen: Die zufaelligen Anfangstiefen sollen keine Flecken je Zelle zeigen. */
    private val dugSmooth = FloatArray(cells)
    private val hint = FloatArray(cells)

    private val noise: FloatArray = buildNoise()

    /**
     * Uebernimmt neue Tiefen und Hinweise und rechnet die betroffenen Pixel neu.
     *
     * @return geaendertes Rechteck oder `null`, wenn sich nichts geaendert hat
     */
    fun update(depths: IntArray, hinted: BooleanArray): PixelRect? {
        require(depths.size == cells && hinted.size == cells) { "Groesse passt nicht zu ${cols}x$rows" }
        val oldDepths = lastDepths
        val oldHinted = lastHinted
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = -1
        var maxY = -1
        for (cell in 0 until cells) {
            val changed = oldDepths == null || oldHinted == null ||
                oldDepths[cell] != depths[cell] || oldHinted[cell] != hinted[cell]
            if (!changed) continue
            setCell(cell, depths[cell], hinted[cell])
            val x = cell % cols
            val y = cell / cols
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        lastDepths = depths.copyOf()
        lastHinted = hinted.copyOf()
        if (maxX < 0) return null
        smoothDug(maxOf(0, minX - 1), maxOf(0, minY - 1), minOf(cols - 1, maxX + 1), minOf(rows - 1, maxY + 1))
        // Zwei Zellen Rand: ein Rand fuer die Mittelung, einer fuer die Interpolation bis zur Mitte
        // der Nachbarzellen.
        val rect = PixelRect(
            left = maxOf(0, minX - 2) * sub,
            top = maxOf(0, minY - 2) * sub,
            right = minOf(cols, maxX + 3) * sub,
            bottom = minOf(rows, maxY + 3) * sub,
        )
        render(rect)
        return rect
    }

    private fun setCell(cell: Int, depth: Int, hinted: Boolean) {
        open[cell] = if (depth <= 0) 1f else 0f
        thin[cell] = if (depth in 1..hintDepth) 1f - depth.toFloat() / hintDepth else 0f
        dug[cell] = (1f - depth.toFloat() / depthMin).coerceIn(0f, 1f)
        hint[cell] = if (hinted) 1f else 0f
    }

    private fun smoothDug(x0: Int, y0: Int, x1: Int, y1: Int) {
        for (y in y0..y1) {
            for (x in x0..x1) {
                var sum = 0f
                var n = 0
                for (yy in maxOf(0, y - 1)..minOf(rows - 1, y + 1)) {
                    for (xx in maxOf(0, x - 1)..minOf(cols - 1, x + 1)) {
                        sum += dug[yy * cols + xx]
                        n++
                    }
                }
                dugSmooth[y * cols + x] = sum / n
            }
        }
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
                val n = noise[p]
                mask[p] = maskPixel(sample(open, ja, jb, ia, ib, fx, fy), sample(thin, ja, jb, ia, ib, fx, fy), n)
                shade[p] = shadePixel(sample(dugSmooth, ja, jb, ia, ib, fx, fy), sample(hint, ja, jb, ia, ib, fx, fy), n)
            }
        }
    }

    private fun maskPixel(openValue: Float, thinValue: Float, n: Float): Int {
        val hole = smoothstep(HOLE_LEVEL - HOLE_EDGE, HOLE_LEVEL + HOLE_EDGE, openValue + n * HOLE_NOISE)
        val alpha = (1f - hole) * (1f - THIN_FADE * thinValue)
        return (toByte(alpha) shl ALPHA_SHIFT) or WHITE_RGB
    }

    private fun shadePixel(dugValue: Float, hintValue: Float, n: Float): Int {
        val shadeAlpha = SHADE_MAX * dugValue
        // Koernige Verfaerbung: Rauschen bestimmt, wo die Knochenfarbe durch die Erde schimmert.
        val speckle = smoothstep(SPECKLE_FROM, SPECKLE_TO, n + HALF)
        val tintAlpha = HINT_ALPHA * hintValue * (SPECKLE_BASE + (1f - SPECKLE_BASE) * speckle)
        // Hinweisfarbe ueber Schwarz (Tiefen-Abstufung) legen.
        val outAlpha = tintAlpha + shadeAlpha * (1f - tintAlpha)
        if (outAlpha <= 0f) return 0
        val tintShare = tintAlpha / outAlpha
        val r = (HINT_R * tintShare).toInt()
        val g = (HINT_G * tintShare).toInt()
        val b = (HINT_B * tintShare).toInt()
        return (toByte(outAlpha) shl ALPHA_SHIFT) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
    }

    private fun sample(field: FloatArray, ja: Int, jb: Int, ia: Int, ib: Int, fx: Float, fy: Float): Float {
        val top = field[ja + ia] + (field[ja + ib] - field[ja + ia]) * fx
        val bottom = field[jb + ia] + (field[jb + ib] - field[jb + ia]) * fx
        return top + (bottom - top) * fy
    }

    /** Festes Wertrauschen in -0.5..0.5: grob (halbe Zelle) plus feines Korn je Pixel. */
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
                val b = lattice[(j + 1) * lw + i] + (lattice[(j + 1) * lw + i + 1] - lattice[(j + 1) * lw + i]) * fx
                val coarse = a + (b - a) * fy
                result[py * width + px] = COARSE_SHARE * coarse + (1f - COARSE_SHARE) * random.nextFloat() - HALF
            }
        }
        return result
    }

    companion object {
        /**
         * Pixel je Zelle: fein genug, dass die ausgefransten Kanten beim Strecken auf die Bildschirmgroesse
         * weich bleiben, klein genug fuer schnelle Updates (32 x 48 Zellen = 256 x 384 Pixel).
         * Darstellung, kein Spielwert.
         */
        const val SUB: Int = 8

        // Darstellungskonstanten (kein Spielwert): Lage und Breite der Lochkante, Staerke des Ausfransens,
        // Durchscheinen duenner Erde, Tiefen-Abstufung und Hinweis-Verfaerbung.
        private const val HOLE_LEVEL = 0.5f
        private const val HOLE_EDGE = 0.1f
        private const val HOLE_NOISE = 0.75f
        private const val THIN_FADE = 0.4f
        private const val SHADE_MAX = 0.5f
        private const val HINT_ALPHA = 0.6f
        private const val SPECKLE_FROM = 0.35f
        private const val SPECKLE_TO = 0.75f
        private const val SPECKLE_BASE = 0.25f
        private const val HINT_R = 0xE8
        private const val HINT_G = 0xDA
        private const val HINT_B = 0xB4
        private const val NOISE_SEED = 0x5EED
        private const val NOISE_CELLS_PER_CELL = 2
        private const val COARSE_SHARE = 0.65f

        private const val HALF = 0.5f
        private const val WHITE_RGB = 0x00FFFFFF
        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val BYTE = 255f

        private fun toByte(value: Float): Int = (value.coerceIn(0f, 1f) * BYTE + HALF).toInt()

        private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

        private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float =
            smooth(((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f))

        /** Deckkraft (0..255) eines Pixels aus [mask]. */
        fun alphaOf(argb: Int): Int = (argb ushr ALPHA_SHIFT) and 0xFF
    }
}

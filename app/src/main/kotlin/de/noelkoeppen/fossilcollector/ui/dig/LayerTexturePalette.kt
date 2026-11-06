package de.noelkoeppen.fossilcollector.ui.dig

import androidx.compose.runtime.Immutable
import kotlin.math.pow

/**
 * Farben eines Schichtbands (AP-26): Textfarbe und die Farbtoene der Textur, alle als deckende
 * ARGB-Werte. Die Toene werden so weit zur Bandfarbe zurueckgenommen, dass der Text auf **jedem**
 * Texturelement noch mindestens [MIN_CONTRAST] (WCAG AA fuer normalen Text) erreicht. Damit bleibt
 * die Schrift ohne zusaetzliches Overlay lesbar, egal wo ein Korn oder eine Linie liegt.
 *
 * Reine Funktionen auf Int-Farben, damit sie ohne Android getestet werden koennen.
 */
@Immutable
data class LayerTexturePalette(
    val base: Int,
    val text: Int,
    val light: Int,
    val dark: Int,
    val bright: Int,
) {
    fun tone(tone: TextureTone): Int = when (tone) {
        TextureTone.LIGHT -> light
        TextureTone.DARK -> dark
        TextureTone.BRIGHT -> bright
    }

    companion object {
        /** Mindestkontrast zwischen Text und jeder Farbe des Bands. */
        const val MIN_CONTRAST = 4.5

        /** Gewuenschte Staerke der Toene (Anteil Weiss bzw. Schwarz), bevor der Kontrast sie begrenzt. */
        const val LIGHT_AMOUNT = 0.16f
        const val DARK_AMOUNT = 0.22f
        const val BRIGHT_AMOUNT = 0.38f

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val STEP = 0.01f

        /**
         * Palette fuer eine offene Schicht: Text schwarz oder weiss, je nachdem, was auf der Bandfarbe
         * den hoeheren Kontrast hat.
         */
        fun forBand(base: Int): LayerTexturePalette = create(base, bestTextColor(base, WHITE, BLACK))

        /** Palette fuer eine vorgegebene Textfarbe (z. B. gesperrte Schicht mit Themenfarbe). */
        fun create(base: Int, text: Int): LayerTexturePalette {
            val opaque = opaque(base)
            return LayerTexturePalette(
                base = opaque,
                text = opaque(text),
                light = guardedBlend(opaque, WHITE, LIGHT_AMOUNT, text),
                dark = guardedBlend(opaque, BLACK, DARK_AMOUNT, text),
                bright = guardedBlend(opaque, WHITE, BRIGHT_AMOUNT, text),
            )
        }

        /** Von [light] und [dark] die Farbe mit dem hoeheren Kontrast zu [background]. */
        fun bestTextColor(background: Int, light: Int, dark: Int): Int =
            if (contrast(background, light) >= contrast(background, dark)) light else dark

        /**
         * Mischt [base] mit hoechstens [amount] Anteil [target]; der Anteil wird verringert, bis der
         * Kontrast zu [text] nicht unter `min(MIN_CONTRAST, Kontrast der Bandfarbe)` faellt.
         */
        fun guardedBlend(base: Int, target: Int, amount: Float, text: Int): Int {
            val required = minOf(MIN_CONTRAST, contrast(base, text))
            var a = amount
            while (a > 0f) {
                val candidate = blend(base, target, a)
                if (contrast(candidate, text) >= required) return candidate
                a -= STEP
            }
            return opaque(base)
        }

        /** Lineare Mischung im sRGB-Raum, Ergebnis deckend. */
        fun blend(from: Int, to: Int, amount: Float): Int {
            val t = amount.coerceIn(0f, 1f)
            fun channel(shift: Int): Int {
                val a = (from shr shift) and BYTE
                val b = (to shr shift) and BYTE
                return (a + (b - a) * t).toInt().coerceIn(0, BYTE)
            }
            return (BYTE shl ALPHA_SHIFT) or (channel(RED_SHIFT) shl RED_SHIFT) or (channel(GREEN_SHIFT) shl GREEN_SHIFT) or channel(0)
        }

        /** Kontrastverhaeltnis nach WCAG 2.x (1 bis 21). */
        fun contrast(a: Int, b: Int): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (maxOf(la, lb) + WCAG_OFFSET) / (minOf(la, lb) + WCAG_OFFSET)
        }

        /** Relative Leuchtdichte nach WCAG 2.x. */
        fun luminance(color: Int): Double {
            fun linear(shift: Int): Double {
                val c = ((color shr shift) and BYTE) / BYTE.toDouble()
                return if (c <= SRGB_THRESHOLD) c / SRGB_LINEAR_DIVISOR else ((c + SRGB_OFFSET) / SRGB_SCALE).pow(SRGB_GAMMA)
            }
            return R_WEIGHT * linear(RED_SHIFT) + G_WEIGHT * linear(GREEN_SHIFT) + B_WEIGHT * linear(0)
        }

        /** `#RRGGBB` als deckende ARGB-Zahl. */
        fun parseHex(hex: String): Int = (BYTE shl ALPHA_SHIFT) or hex.removePrefix("#").toInt(HEX_RADIX)

        /** Legt [color] mit Deckkraft [alpha] ueber den deckenden Grund [under]. */
        fun composite(color: Int, alpha: Float, under: Int): Int = blend(under, color, alpha)

        private fun opaque(color: Int): Int = color or (BYTE shl ALPHA_SHIFT)

        private const val BYTE = 0xFF
        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val HEX_RADIX = 16
        private const val WCAG_OFFSET = 0.05
        private const val SRGB_THRESHOLD = 0.04045
        private const val SRGB_LINEAR_DIVISOR = 12.92
        private const val SRGB_OFFSET = 0.055
        private const val SRGB_SCALE = 1.055
        private const val SRGB_GAMMA = 2.4
        private const val R_WEIGHT = 0.2126
        private const val G_WEIGHT = 0.7152
        private const val B_WEIGHT = 0.0722
    }
}

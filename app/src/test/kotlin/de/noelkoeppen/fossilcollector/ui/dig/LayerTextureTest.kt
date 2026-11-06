package de.noelkoeppen.fossilcollector.ui.dig

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.LayerTexture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Gesteinstextur der Schichtbaender (AP-26): deterministische Muster und lesbare Farben. */
class LayerTextureTest {

    private val data = DataRepository().load()

    /** Typische Bandgroesse: 360 x 110 dp bei 2,75 px/dp. */
    private val unit = 2.75f
    private val width = 360f * unit
    private val height = 110f * unit

    private fun pattern(texture: LayerTexture, seed: Int = 7) =
        LayerTexturePattern.generate(texture, seed, width, height, unit)

    @Test
    fun sameInputsGiveSamePattern() {
        LayerTexture.entries.forEach { texture ->
            assertEquals(pattern(texture), pattern(texture), "$texture nicht deterministisch")
        }
    }

    @Test
    fun seedComesFromLayerIdAndChangesThePattern() {
        assertEquals(LayerTexturePattern.seedFor("quaternary"), LayerTexturePattern.seedFor("quaternary"))
        assertNotEquals(
            pattern(LayerTexture.GRAVEL, LayerTexturePattern.seedFor("quaternary")),
            pattern(LayerTexture.GRAVEL, LayerTexturePattern.seedFor("neogene")),
        )
    }

    @Test
    fun texturesDifferInCharacter() {
        assertEquals(TexturePattern.EMPTY, pattern(LayerTexture.PLAIN))

        val gravel = pattern(LayerTexture.GRAVEL)
        assertTrue(gravel.pebbles.size >= 5, "Kies braucht Kiesel")
        assertTrue(gravel.lines.isEmpty() && gravel.shells.isEmpty())

        val banded = pattern(LayerTexture.BANDED)
        assertTrue(banded.lines.size >= 10, "Baenderung braucht viele Linien, hat ${banded.lines.size}")
        assertTrue(banded.lines.size > banded.pebbles.size)
        assertTrue(banded.shells.isEmpty())

        val chalk = pattern(LayerTexture.CHALK)
        assertTrue(chalk.shells.isNotEmpty(), "Kreide braucht angedeutete Muscheln")
        assertTrue(chalk.grains.count { it.tone == TextureTone.BRIGHT } > chalk.grains.size / 2, "Kreide ist ueberwiegend hell gesprenkelt")
    }

    @Test
    fun elementsStayInsideTheBand() {
        LayerTexture.entries.forEach { texture ->
            val p = pattern(texture)
            p.grains.forEach { assertTrue(it.x in 0f..width && it.y in 0f..height) }
            p.pebbles.forEach { assertTrue(it.x in 0f..width && it.y in 0f..height) }
            p.shells.forEach { assertTrue(it.x in 0f..width && it.y in 0f..height) }
            p.lines.forEach { line ->
                assertEquals(0, line.points.size % 2)
                assertTrue(line.points.size >= 4)
            }
        }
    }

    @Test
    fun elementCountIsCappedAndEmptySizesGiveNothing() {
        LayerTexture.entries.forEach { texture ->
            val huge = LayerTexturePattern.generate(texture, 1, 4000f, 4000f, 1f)
            assertTrue(huge.size <= LayerTexturePattern.MAX_ELEMENTS, "$texture: ${huge.size}")
            assertEquals(TexturePattern.EMPTY, LayerTexturePattern.generate(texture, 1, 0f, 100f, unit))
        }
    }

    @Test
    fun densityScalesWithArea() {
        val small = LayerTexturePattern.generate(LayerTexture.GRAVEL, 3, 200f * unit, 60f * unit, unit)
        val large = LayerTexturePattern.generate(LayerTexture.GRAVEL, 3, 400f * unit, 120f * unit, unit)
        assertTrue(large.grains.size > small.grains.size * 3)
    }

    // --- Farben und Kontrast ---

    @Test
    fun contrastFormulaMatchesWcag() {
        assertEquals(21.0, LayerTexturePalette.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, LayerTexturePalette.contrast(0xFF777777.toInt(), 0xFF777777.toInt()), 1e-9)
    }

    @Test
    fun textOnEveryUnlockedBandAndTextureToneIsReadable() {
        data.layers.forEach { layer ->
            val palette = LayerTexturePalette.forBand(LayerTexturePalette.parseHex(layer.color))
            assertReadable(layer.id, palette)
        }
    }

    @Test
    fun textOnEveryLockedBandIsReadable() {
        // Wie im DigScreen: Bandfarbe mit 35 % auf dem dunklen Grund, Text in Knochenfarbe.
        val surface = 0xFF1C1A17.toInt()
        val bone = 0xFFE8DFC8.toInt()
        data.layers.forEach { layer ->
            val base = LayerTexturePalette.composite(LayerTexturePalette.parseHex(layer.color), 0.35f, surface)
            assertReadable(layer.id, LayerTexturePalette.create(base, bone))
        }
    }

    @Test
    fun tonesStayVisibleOnMvpBands() {
        data.layers.forEach { layer ->
            val palette = LayerTexturePalette.forBand(LayerTexturePalette.parseHex(layer.color))
            TextureTone.entries.forEach { tone ->
                assertNotEquals(palette.base, palette.tone(tone), "${layer.id}: Ton $tone ist unsichtbar")
            }
        }
    }

    @Test
    fun guardReducesToneUntilContrastHolds() {
        // Mittleres Grau mit schwarzem Text: ein starker Schwarzanteil muss zurueckgenommen werden.
        val base = 0xFF8A8A8A.toInt()
        val black = 0xFF000000.toInt()
        val toned = LayerTexturePalette.guardedBlend(base, black, 0.9f, black)
        assertTrue(LayerTexturePalette.contrast(toned, black) >= LayerTexturePalette.MIN_CONTRAST)
        assertNotEquals(base, toned)
    }

    private fun assertReadable(id: String, palette: LayerTexturePalette) {
        val colors = listOf(palette.base) + TextureTone.entries.map(palette::tone)
        colors.forEach { color ->
            val contrast = LayerTexturePalette.contrast(color, palette.text)
            assertTrue(contrast >= LayerTexturePalette.MIN_CONTRAST, "$id: Kontrast $contrast auf ${Integer.toHexString(color)}")
        }
    }
}

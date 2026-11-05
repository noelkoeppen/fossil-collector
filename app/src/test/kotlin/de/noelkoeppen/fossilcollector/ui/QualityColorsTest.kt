package de.noelkoeppen.fossilcollector.ui

import androidx.compose.ui.graphics.Color
import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.ui.theme.QualityColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class QualityColorsTest {

    /** Laedt die echten Spieldaten ueber den Klassenpfad des app-Moduls (wie im APK). */
    private val colors = QualityColors.fromTiers(DataRepository().load().balance.qualityTiers)

    @Test
    fun colorsFromDesignChapter84() {
        assertEquals(Color(0xFF8A8A8A), colors.forTier("fragment"))
        assertEquals(Color(0xFFA0703C), colors.forTier("damaged"))
        assertEquals(Color(0xFF5FA35F), colors.forTier("good"))
        assertEquals(Color(0xFF4A90D9), colors.forTier("excellent"))
        assertEquals(Color(0xFFE2B93B), colors.forTier("pristine"))
        assertNull(colors.forTier("unknown"))
    }

    @Test
    fun colorByQualityUsesTierLowerBounds() {
        assertEquals(colors.forTier("fragment"), colors.forQuality(0.0))
        assertEquals(colors.forTier("good"), colors.forQuality(0.5))
        assertEquals(colors.forTier("excellent"), colors.forQuality(0.89))
        assertEquals(colors.forTier("pristine"), colors.forQuality(0.9))
    }

    @Test
    fun invalidHexIsGray() {
        assertEquals(Color.Gray, QualityColors.parseHexColor("#XYZ"))
        assertEquals(Color.Gray, QualityColors.parseHexColor("#12345"))
        assertEquals(Color(0x80112233), QualityColors.parseHexColor("#80112233"))
    }
}

package de.noelkoeppen.fossilcollector.ui.model3d

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Die 3D-Ansicht beleuchtet wie die 2D-Knochenbilder (AP2-10): Werte in [ModelShading] passen zu
 * `DEFAULTS` in `tools/models3d/bone_images.py` (Pfad als [SCRIPT_PROPERTY] aus `app/build.gradle.kts`).
 */
class ModelShadingTest {

    private val script = File(checkNotNull(System.getProperty(SCRIPT_PROPERTY)) { "Systemeigenschaft $SCRIPT_PROPERTY fehlt" }).readText()

    private fun number(key: String): Float =
        Regex("\"$key\":\\s*([0-9.]+)").find(script)?.groupValues?.get(1)?.toFloat() ?: error("$key fehlt im Skript")

    @Test
    fun shadingMatchesBoneImagesScript() {
        assertEquals(number("ambient"), ModelShading.AMBIENT)
        assertEquals(number("diffuse"), ModelShading.DIFFUSE)
        assertEquals(number("specular"), ModelShading.SPECULAR)
        val light = Regex("\"light\":\\s*\\[([^]]+)]").find(script)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim().toFloat() }?.toFloatArray() ?: error("light fehlt im Skript")
        assertArrayEquals(Mat4.normalize(light), ModelShading.LIGHT, 1e-6f)
        val color = Regex("\"color\":\\s*\\[([^]]+)]").find(script)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim().toFloat() }?.toFloatArray() ?: error("color fehlt im Skript")
        assertArrayEquals(color, BoneTint.FALLBACK_BONE_COLOR)
    }

    private companion object {
        const val SCRIPT_PROPERTY = "fossil.boneImagesScript"
    }
}

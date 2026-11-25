package de.noelkoeppen.fossilcollector.ui.model3d

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 3D-Modelle in den Assets (AP2-10): Fuer jeden Knochen jeder Art liegt genau eine `.glb` dort,
 * und jede Datei liest der eigene Renderer (unterstuetzte glTF-Teilmenge). Den Ordner fuellt die
 * Gradle-Aufgabe `syncModelAssets`, `app/build.gradle.kts` setzt ihn als [MODEL_ASSETS_DIR_PROPERTY].
 */
class ModelAssetsTest {

    private val data: GameData = DataRepository().load()

    private val assetsDir: File = File(
        checkNotNull(System.getProperty(MODEL_ASSETS_DIR_PROPERTY)) { "Systemeigenschaft $MODEL_ASSETS_DIR_PROPERTY fehlt" },
    )

    @Test
    fun everyBoneHasExactlyOneModel() {
        assertEquals(data.species.map { it.id }.toSet(), File(assetsDir, ModelAssets.ROOT).list().orEmpty().toSet())
        data.species.forEach { species ->
            val missing = species.bones.filterNot { File(assetsDir, ModelAssets.bonePath(species.id, it)).isFile }
            assertEquals(emptyList<String>(), missing, "Modelle fehlen fuer ${species.id}")
            val files = File(assetsDir, "${ModelAssets.speciesDir(species.id)}/bones").list().orEmpty().toSet()
            assertEquals(species.bones.map { "$it.glb" }.toSet(), files, "Ueberzaehlige Modelle fuer ${species.id}")
        }
    }

    @Test
    fun everyModelIsReadableWithColorAndNormals() {
        data.species.forEach { species ->
            var triangles = 0
            species.bones.forEach { bone ->
                val mesh = GlbReader.read(File(assetsDir, ModelAssets.bonePath(species.id, bone)).readBytes())
                assertTrue(mesh.triangleCount > 0, "$bone ohne Dreiecke")
                assertEquals(mesh.positions.size, mesh.normals.size, bone)
                assertNotNull(mesh.baseColor, "$bone ohne Knochenfarbe")
                triangles += mesh.triangleCount
            }
            // Mittelklasse-Geraete zeichnen das ohne Muehe; deutlich mehr waere ein Hinweis auf ein
            // versehentlich zu fein exportiertes Modell (Grenze: Darstellung, kein Spielwert).
            assertTrue(triangles <= MAX_TRIANGLES_PER_SPECIES, "${species.id}: $triangles Dreiecke")
        }
    }

    companion object {
        const val MODEL_ASSETS_DIR_PROPERTY = "fossil.modelAssetsDir"

        /** Hoehlenbaer: 274 650 Dreiecke in 105 Knochen. */
        const val MAX_TRIANGLES_PER_SPECIES = 500_000
    }
}

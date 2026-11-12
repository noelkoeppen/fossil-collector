package de.noelkoeppen.fossilcollector.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Datenvalidierung gegen die 3D-Modelle (`art/models3d/<speciesId>/`): Jeder Knochen jeder Art hat
 * ein Modell, und die Knochenliste entspricht der `bones.json` des Modells.
 *
 * Die Modelle liegen ausserhalb von `core`; den Ordner setzt `core/build.gradle.kts` als
 * Systemeigenschaft [MODELS_DIR_PROPERTY].
 */
class SpeciesModelFilesTest {

    private val data: GameData = DataRepository().load()

    private val modelsDir: File = File(
        checkNotNull(System.getProperty(MODELS_DIR_PROPERTY)) { "Systemeigenschaft $MODELS_DIR_PROPERTY fehlt" },
    )

    @Test
    fun modelsDirectoryExists() {
        assertTrue(modelsDir.isDirectory, "Modellordner fehlt: $modelsDir")
    }

    @Test
    fun everyBoneHasModel() {
        val missing = data.species.flatMap { species ->
            species.bones.map { File(modelsDir, "${species.id}/bones/$it.glb") }.filterNot { it.isFile }
        }
        assertEquals(emptyList<File>(), missing, "Knochen ohne Modell")
    }

    @Test
    fun boneListMatchesModelBonesJson() {
        data.species.forEach { species ->
            val file = File(modelsDir, "${species.id}/bones.json")
            assertTrue(file.isFile, "Modell-bones.json fehlt fuer ${species.id}")
            val model = Json.parseToJsonElement(file.readText()).jsonObject
            val ids = model.getValue("bones").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals(ids, species.bones, "species.json/${species.id} passt nicht zum Modell (tools/models3d/neighbors.py)")
            val glbs = File(modelsDir, "${species.id}/bones").list().orEmpty().filter { it.endsWith(".glb") }
            assertEquals(ids.map { "$it.glb" }.toSet(), glbs.toSet(), "Modelldateien ohne Knochen in species.json")
        }
    }

    companion object {
        const val MODELS_DIR_PROPERTY = "fossil.modelsDir"
    }
}

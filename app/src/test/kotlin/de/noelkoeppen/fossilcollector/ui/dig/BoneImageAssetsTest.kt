package de.noelkoeppen.fossilcollector.ui.dig

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.File

/**
 * Knochenbilder in den Assets (AP2-06): Fuer jeden Knochen jeder Art gibt es ein RGBA-Bild und eine
 * 1-Bit-Maske, deren Groesse zum Manifest passt. Den Asset-Ordner setzt `app/build.gradle.kts` als
 * Systemeigenschaft [ASSETS_DIR_PROPERTY].
 */
class BoneImageAssetsTest {

    private val data: GameData = DataRepository().load()

    private val assetsDir: File = File(
        checkNotNull(System.getProperty(ASSETS_DIR_PROPERTY)) { "Systemeigenschaft $ASSETS_DIR_PROPERTY fehlt" },
    )

    private fun manifest(speciesId: String): JsonObject {
        val file = File(assetsDir, BoneImageAssets.manifestPath(speciesId))
        assertTrue(file.isFile, "Manifest fehlt: $file (tools/models3d/bone_images.py ausfuehren)")
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    /** Breite, Hoehe, Bittiefe und Farbtyp aus dem IHDR-Block einer PNG-Datei. */
    private fun pngHeader(file: File): List<Int> = DataInputStream(file.inputStream().buffered()).use { input ->
        val signature = ByteArray(8).also { input.readFully(it) }
        assertTrue(signature.contentEquals(PNG_SIGNATURE), "Keine PNG-Datei: $file")
        input.readInt()
        val type = ByteArray(4).also { input.readFully(it) }
        assertEquals("IHDR", String(type, Charsets.US_ASCII), "IHDR fehlt: $file")
        listOf(input.readInt(), input.readInt(), input.readUnsignedByte(), input.readUnsignedByte())
    }

    @Test
    fun everyBoneHasImageAndMask() {
        val missing = data.species.flatMap { species ->
            species.bones.flatMap { bone ->
                listOf(BoneImageAssets.imagePath(species.id, bone), BoneImageAssets.maskPath(species.id, bone))
            }
        }.filterNot { File(assetsDir, it).isFile }
        assertEquals(emptyList<String>(), missing, "Knochenbilder fehlen (tools/models3d/bone_images.py ausfuehren)")
    }

    @Test
    fun manifestMatchesSpeciesBones() {
        data.species.forEach { species ->
            val ids = manifest(species.id).getValue("bones").jsonArray.map {
                it.jsonObject.getValue("id").jsonPrimitive.content
            }
            assertEquals(species.bones, ids, "Manifest von ${species.id} passt nicht zu species.json")
        }
    }

    @Test
    fun imageSizesMatchManifest() {
        data.species.forEach { species ->
            manifest(species.id).getValue("bones").jsonArray.map { it.jsonObject }.forEach { entry ->
                val bone = entry.getValue("id").jsonPrimitive.content
                val size = listOf(entry.getValue("width").jsonPrimitive.int, entry.getValue("height").jsonPrimitive.int)
                val image = pngHeader(File(assetsDir, BoneImageAssets.imagePath(species.id, bone)))
                val mask = pngHeader(File(assetsDir, BoneImageAssets.maskPath(species.id, bone)))
                assertEquals(size + listOf(8, COLOR_TYPE_RGBA), image, "Bild ${species.id}/$bone")
                assertEquals(size + listOf(1, COLOR_TYPE_GRAY), mask, "Maske ${species.id}/$bone")
            }
        }
    }

    @Test
    fun noAssetsForUnknownSpeciesOrBones() {
        val speciesIds = data.species.map { it.id }.toSet()
        val folders = File(assetsDir, BoneImageAssets.ROOT).list().orEmpty().toSet()
        assertEquals(speciesIds, folders, "Asset-Ordner ohne Art in species.json")
        data.species.forEach { species ->
            val expected = species.bones.map { "$it.png" }.toSet()
            listOf("images", "masks").forEach { sub ->
                val files = File(assetsDir, "${BoneImageAssets.speciesDir(species.id)}/$sub").list().orEmpty().toSet()
                assertEquals(expected, files, "Ueberzaehlige oder fehlende Dateien in ${species.id}/$sub")
            }
        }
    }

    companion object {
        const val ASSETS_DIR_PROPERTY = "fossil.assetsDir"
        private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        private const val COLOR_TYPE_GRAY = 0
        private const val COLOR_TYPE_RGBA = 6
    }
}

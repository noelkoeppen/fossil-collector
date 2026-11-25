package de.noelkoeppen.fossilcollector.ui.model3d

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimaler glTF-Leser (AP2-10) an kleinen, im Test gebauten `.glb`-Dateien. */
class GlbReaderTest {

    /** Baut eine `.glb` aus JSON-Text und Binaerblock. */
    private fun glb(json: String, bin: ByteArray): ByteArray {
        val jsonBytes = json.toByteArray(Charsets.UTF_8).let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
        val binBytes = bin + ByteArray((4 - bin.size % 4) % 4)
        val total = 12 + 8 + jsonBytes.size + 8 + binBytes.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67).putInt(2).putInt(total)
        out.putInt(jsonBytes.size).putInt(0x4E4F534A).put(jsonBytes)
        out.putInt(binBytes.size).putInt(0x004E4942).put(binBytes)
        return out.array()
    }

    private fun floats(vararg v: Float): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putFloat(it) } }.array()

    private fun shorts(vararg v: Int): ByteArray =
        ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putShort(it.toShort()) } }.array()

    private fun concat(vararg parts: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> parts.forEach { o.write(it) } }.toByteArray()

    /** Ein Dreieck in der xy-Ebene; Positionen ab Byte 8 (Indizes davor, auf 4 Byte aufgefuellt). */
    private fun triangle(extraJson: String = "", node: String = """{"mesh": 0}""", normals: Boolean = true): ByteArray {
        val idx = concat(shorts(0, 1, 2), ByteArray(2))
        val pos = floats(0f, 0f, 0f, 1f, 0f, 0f, 0f, 2f, 0f)
        val nrm = floats(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f)
        val normalAttr = if (normals) """, "NORMAL": 2""" else ""
        val json = """
            {"asset": {"version": "2.0"}, $extraJson
             "nodes": [$node], "meshes": [{"primitives": [{"attributes": {"POSITION": 1$normalAttr}, "indices": 0, "material": 0}]}],
             "materials": [{"pbrMetallicRoughness": {"baseColorFactor": [0.5, 0.25, 0.125, 1.0]}}],
             "accessors": [
               {"bufferView": 0, "componentType": 5123, "count": 3, "type": "SCALAR"},
               {"bufferView": 1, "componentType": 5126, "count": 3, "type": "VEC3"},
               {"bufferView": 2, "componentType": 5126, "count": 3, "type": "VEC3"}
             ],
             "bufferViews": [
               {"buffer": 0, "byteOffset": 0, "byteLength": 8},
               {"buffer": 0, "byteOffset": 8, "byteLength": 36},
               {"buffer": 0, "byteOffset": 44, "byteLength": 36}
             ],
             "buffers": [{"byteLength": 80}]}
        """.trimIndent()
        return glb(json, concat(idx, pos, nrm))
    }

    @Test
    fun readsTriangleWithShortIndicesNormalsAndColor() {
        val mesh = GlbReader.read(triangle())
        assertEquals(3, mesh.vertexCount)
        assertEquals(1, mesh.triangleCount)
        assertArrayEquals(intArrayOf(0, 1, 2), mesh.indices)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 2f, 0f), mesh.positions)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f), mesh.normals)
        assertArrayEquals(floatArrayOf(0.5f, 0.25f, 0.125f), mesh.baseColor)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), mesh.min)
        assertArrayEquals(floatArrayOf(1f, 2f, 0f), mesh.max)
    }

    @Test
    fun computesNormalsWhenMissing() {
        val mesh = GlbReader.read(triangle(normals = false))
        // Gegen den Uhrzeigersinn in der xy-Ebene: Normale +z.
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f), mesh.normals, 1e-6f)
    }

    @Test
    fun respectsByteStride() {
        // Positionen und Normalen verschraenkt in einem bufferView mit 24 Byte Abstand.
        val interleaved = floats(
            0f, 0f, 0f, 0f, 0f, 1f,
            1f, 0f, 0f, 0f, 0f, 1f,
            0f, 1f, 0f, 0f, 0f, 1f,
        )
        val json = """
            {"asset": {"version": "2.0"}, "nodes": [{"mesh": 0}],
             "meshes": [{"primitives": [{"attributes": {"POSITION": 0, "NORMAL": 1}}]}],
             "accessors": [
               {"bufferView": 0, "componentType": 5126, "count": 3, "type": "VEC3"},
               {"bufferView": 0, "byteOffset": 12, "componentType": 5126, "count": 3, "type": "VEC3"}
             ],
             "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": 72, "byteStride": 24}],
             "buffers": [{"byteLength": 72}]}
        """.trimIndent()
        val mesh = GlbReader.read(glb(json, interleaved))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), mesh.positions)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f), mesh.normals)
        // Ohne Indizes: Eckpunkte der Reihe nach, ohne Material keine Farbe.
        assertArrayEquals(intArrayOf(0, 1, 2), mesh.indices)
        assertNull(mesh.baseColor)
    }

    @Test
    fun rejectsUnsupportedFiles() {
        assertThrows<GlbFormatException> { GlbReader.read(ByteArray(20)) }
        assertThrows<GlbFormatException> { GlbReader.read(triangle(node = """{"mesh": 0, "translation": [1, 0, 0]}""")) }
        assertThrows<GlbFormatException> { GlbReader.read(triangle(extraJson = """"extensionsRequired": ["KHR_draco_mesh_compression"],""")) }
        // Abgeschnittene Datei: Block reicht ueber das Ende.
        val full = triangle()
        assertThrows<GlbFormatException> { GlbReader.read(full.copyOf(full.size - 8)) }
    }
}

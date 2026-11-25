package de.noelkoeppen.fossilcollector.ui.model3d

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Dreiecksnetz eines Knochens, wie es der Renderer braucht (AP2-10).
 *
 * @property positions Eckpunkte `x, y, z` hintereinander, im gemeinsamen Skelett-Koordinatensystem
 * @property normals Normalen `x, y, z` je Eckpunkt (aus der Datei oder berechnet)
 * @property indices Dreiecke als Eckpunkt-Indizes, je drei hintereinander
 * @property baseColor Knochenfarbe `r, g, b` (0..1) aus dem Material oder `null`
 */
class GlbMesh(
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: IntArray,
    val baseColor: FloatArray?,
) {
    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3

    /** Kleinste Koordinaten `x, y, z`. */
    val min: FloatArray by lazy { bound(true) }

    /** Groesste Koordinaten `x, y, z`. */
    val max: FloatArray by lazy { bound(false) }

    private fun bound(lower: Boolean): FloatArray {
        val out = FloatArray(3) { if (lower) Float.POSITIVE_INFINITY else Float.NEGATIVE_INFINITY }
        for (i in positions.indices) {
            val axis = i % 3
            out[axis] = if (lower) minOf(out[axis], positions[i]) else maxOf(out[axis], positions[i])
        }
        return out
    }
}

/** Datei entspricht nicht der unterstuetzten glTF-Teilmenge. */
class GlbFormatException(message: String) : Exception(message)

/**
 * Minimaler Leser fuer binaeres glTF 2.0 (`.glb`), genau fuer die Teilmenge, die die
 * Modell-Pipeline (`tools/models3d`, trimesh-Export) erzeugt und die `bone_images.py` ebenso liest:
 *
 * - Dreiecke (`mode` 4), `POSITION` als `float` VEC3, `NORMAL` optional (sonst berechnet),
 *   Indizes als `unsigned byte/short/int` oder ohne Indizes; `byteStride` wird beachtet,
 * - mehrere Meshes/Primitive werden zu einem Netz zusammengefasst,
 * - Knochenfarbe aus `pbrMetallicRoughness.baseColorFactor` des ersten Materials mit Farbe,
 * - keine Knoten-Transformationen (die Meshes liegen schon im Skelett-Koordinatensystem), keine
 *   Texturen, keine Erweiterungen in `extensionsRequired` (z. B. Draco).
 *
 * Alles andere ist ein [GlbFormatException]. Der App-Test `ModelAssetsTest` liest jede
 * eingebundene Datei, damit eine neue Art ausserhalb dieser Teilmenge schon im Build auffaellt.
 */
object GlbReader {

    fun read(bytes: ByteArray): GlbMesh {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < HEADER_SIZE || buffer.getInt(0) != GLB_MAGIC) throw GlbFormatException("keine GLB-Datei")
        if (buffer.getInt(4) != GLB_VERSION) throw GlbFormatException("nur glTF 2.0 wird unterstuetzt")
        var offset = HEADER_SIZE
        var json: JsonObject? = null
        var binOffset = -1
        var binLength = 0
        while (offset + CHUNK_HEADER_SIZE <= bytes.size) {
            val length = buffer.getInt(offset)
            val type = buffer.getInt(offset + 4)
            val start = offset + CHUNK_HEADER_SIZE
            if (length < 0 || start + length > bytes.size) throw GlbFormatException("Block ueber das Dateiende")
            when (type) {
                CHUNK_JSON -> json = Json.parseToJsonElement(String(bytes, start, length, Charsets.UTF_8)).jsonObject
                CHUNK_BIN -> if (binOffset < 0) { binOffset = start; binLength = length }
            }
            offset = start + length
        }
        val gltf = json ?: throw GlbFormatException("JSON-Block fehlt")
        if (binOffset < 0) throw GlbFormatException("BIN-Block fehlt")
        return Parser(gltf, buffer, binOffset, binLength).mesh()
    }

    private class Parser(
        private val gltf: JsonObject,
        private val buffer: ByteBuffer,
        private val binOffset: Int,
        private val binLength: Int,
    ) {
        fun mesh(): GlbMesh {
            gltf["extensionsRequired"]?.jsonArray?.takeIf { it.isNotEmpty() }?.let {
                throw GlbFormatException("Erweiterungen werden nicht unterstuetzt: $it")
            }
            array("nodes").forEach { node ->
                if (TRANSFORM_KEYS.any { it in node.jsonObject }) {
                    throw GlbFormatException("Knoten-Transformationen werden nicht unterstuetzt")
                }
            }
            val positions = ArrayList<FloatArray>()
            val normals = ArrayList<FloatArray?>()
            val indices = ArrayList<IntArray>()
            var color: FloatArray? = null
            var base = 0
            array("meshes").forEach { mesh ->
                (mesh.jsonObject["primitives"] as? JsonArray).orEmpty().forEach { element ->
                    val prim = element.jsonObject
                    val mode = prim["mode"]?.jsonPrimitive?.int ?: MODE_TRIANGLES
                    if (mode != MODE_TRIANGLES) throw GlbFormatException("nur Dreiecke werden unterstuetzt")
                    val attributes = prim["attributes"]?.jsonObject ?: throw GlbFormatException("Attribute fehlen")
                    val posIndex = attributes["POSITION"]?.jsonPrimitive?.int ?: throw GlbFormatException("POSITION fehlt")
                    val pos = floatAccessor(posIndex, VEC3)
                    val count = pos.size / 3
                    val idx = prim["indices"]?.jsonPrimitive?.int?.let(::indexAccessor) ?: IntArray(count) { it }
                    if (idx.size % 3 != 0) throw GlbFormatException("Indexanzahl ist kein Vielfaches von 3")
                    if (idx.any { it < 0 || it >= count }) throw GlbFormatException("Index ausserhalb der Eckpunkte")
                    val nrm = attributes["NORMAL"]?.jsonPrimitive?.int?.let { floatAccessor(it, VEC3) }
                    if (nrm != null && nrm.size != pos.size) throw GlbFormatException("NORMAL passt nicht zu POSITION")
                    positions += pos
                    normals += nrm
                    indices += IntArray(idx.size) { idx[it] + base }
                    base += count
                    if (color == null) color = prim["material"]?.jsonPrimitive?.int?.let(::materialColor)
                }
            }
            if (positions.isEmpty()) throw GlbFormatException("keine Meshes")
            val allPositions = concat(positions)
            val allIndices = concat(indices)
            val allNormals = if (normals.all { it != null }) concat(normals.map { it!! }) else vertexNormals(allPositions, allIndices)
            return GlbMesh(allPositions, allNormals, allIndices, color)
        }

        private fun array(key: String): JsonArray = (gltf[key] as? JsonArray) ?: JsonArray(emptyList())

        private fun materialColor(index: Int): FloatArray? {
            val material = array("materials").getOrNull(index)?.jsonObject ?: return null
            val factor = material["pbrMetallicRoughness"]?.jsonObject?.get("baseColorFactor")?.jsonArray ?: return null
            if (factor.size < 3) return null
            return FloatArray(3) { factor[it].jsonPrimitive.double.toFloat() }
        }

        /** Lage eines Accessors: (Startbyte, Anzahl, Abstand der Elemente in Byte). */
        private fun layout(index: Int, componentSize: Int, components: Int): Triple<Int, Int, Int> {
            val acc = array("accessors").getOrNull(index)?.jsonObject ?: throw GlbFormatException("Accessor $index fehlt")
            if ("sparse" in acc) throw GlbFormatException("Sparse-Accessoren werden nicht unterstuetzt")
            val count = acc["count"]?.jsonPrimitive?.int ?: throw GlbFormatException("Accessor $index ohne count")
            val viewIndex = acc["bufferView"]?.jsonPrimitive?.int ?: throw GlbFormatException("Accessor $index ohne bufferView")
            val view = array("bufferViews").getOrNull(viewIndex)?.jsonObject ?: throw GlbFormatException("bufferView $viewIndex fehlt")
            if ((view["buffer"]?.jsonPrimitive?.int ?: 0) != 0) throw GlbFormatException("nur der BIN-Block als Puffer")
            val item = componentSize * components
            val stride = view["byteStride"]?.jsonPrimitive?.int ?: item
            val start = (view["byteOffset"]?.jsonPrimitive?.int ?: 0) + (acc["byteOffset"]?.jsonPrimitive?.int ?: 0)
            val viewEnd = (view["byteOffset"]?.jsonPrimitive?.int ?: 0) + (view["byteLength"]?.jsonPrimitive?.int ?: 0)
            val end = if (count == 0) start else start + (count - 1) * stride + item
            if (stride < item || start < 0 || end > viewEnd || viewEnd > binLength) {
                throw GlbFormatException("Accessor $index liegt ausserhalb des Puffers")
            }
            return Triple(binOffset + start, count, stride)
        }

        private fun floatAccessor(index: Int, components: Int): FloatArray {
            val acc = array("accessors")[index].jsonObject
            if (acc["componentType"]?.jsonPrimitive?.int != COMPONENT_FLOAT || acc["type"]?.jsonPrimitive?.content != "VEC3") {
                throw GlbFormatException("Accessor $index ist nicht float VEC3")
            }
            val (start, count, stride) = layout(index, FLOAT_SIZE, components)
            return FloatArray(count * components) { i -> buffer.getFloat(start + (i / components) * stride + (i % components) * FLOAT_SIZE) }
        }

        private fun indexAccessor(index: Int): IntArray {
            val acc = array("accessors").getOrNull(index)?.jsonObject ?: throw GlbFormatException("Accessor $index fehlt")
            if (acc["type"]?.jsonPrimitive?.content != "SCALAR") throw GlbFormatException("Indizes sind nicht SCALAR")
            val size = when (acc["componentType"]?.jsonPrimitive?.int) {
                COMPONENT_UBYTE -> 1
                COMPONENT_USHORT -> 2
                COMPONENT_UINT -> 4
                else -> throw GlbFormatException("Indextyp wird nicht unterstuetzt")
            }
            val (start, count, stride) = layout(index, size, 1)
            return IntArray(count) { i ->
                val at = start + i * stride
                when (size) {
                    1 -> buffer.get(at).toInt() and 0xFF
                    2 -> buffer.getShort(at).toInt() and 0xFFFF
                    else -> buffer.getInt(at).also { if (it < 0) throw GlbFormatException("Index zu gross") }
                }
            }
        }
    }

    /** Flaechengewichtete Eckpunkt-Normalen (wie `_vertex_normals` in `bone_images.py`). */
    internal fun vertexNormals(positions: FloatArray, indices: IntArray): FloatArray {
        val out = FloatArray(positions.size)
        for (t in indices.indices step 3) {
            val a = indices[t] * 3
            val b = indices[t + 1] * 3
            val c = indices[t + 2] * 3
            val ux = positions[b] - positions[a]
            val uy = positions[b + 1] - positions[a + 1]
            val uz = positions[b + 2] - positions[a + 2]
            val vx = positions[c] - positions[a]
            val vy = positions[c + 1] - positions[a + 1]
            val vz = positions[c + 2] - positions[a + 2]
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            for (v in intArrayOf(a, b, c)) {
                out[v] += nx
                out[v + 1] += ny
                out[v + 2] += nz
            }
        }
        for (v in out.indices step 3) {
            val len = sqrt(out[v] * out[v] + out[v + 1] * out[v + 1] + out[v + 2] * out[v + 2])
            if (len > 0f) {
                out[v] /= len
                out[v + 1] /= len
                out[v + 2] /= len
            } else {
                out[v + 1] = 1f
            }
        }
        return out
    }

    private fun concat(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        parts.forEach { it.copyInto(out, at); at += it.size }
        return out
    }

    private fun concat(parts: List<IntArray>): IntArray {
        val out = IntArray(parts.sumOf { it.size })
        var at = 0
        parts.forEach { it.copyInto(out, at); at += it.size }
        return out
    }

    private const val GLB_MAGIC = 0x46546C67
    private const val GLB_VERSION = 2
    private const val CHUNK_JSON = 0x4E4F534A
    private const val CHUNK_BIN = 0x004E4942
    private const val HEADER_SIZE = 12
    private const val CHUNK_HEADER_SIZE = 8
    private const val MODE_TRIANGLES = 4
    private const val COMPONENT_UBYTE = 5121
    private const val COMPONENT_USHORT = 5123
    private const val COMPONENT_UINT = 5125
    private const val COMPONENT_FLOAT = 5126
    private const val FLOAT_SIZE = 4
    private const val VEC3 = 3
    private val TRANSFORM_KEYS = listOf("matrix", "translation", "rotation", "scale")
}

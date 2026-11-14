package de.noelkoeppen.fossilcollector.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Struktur und Speicherform der Grabungsflaeche (AP2-05). */
class ExcavationSiteTest {

    private val find = Find("cave_bear", listOf(Piece("cave_bear", "femur_l", 0.5), Piece("cave_bear", "tibia_l", 0.6)))
    private val placed = PlacedFind(find, mapOf("femur_l" to listOf(0, 1), "tibia_l" to listOf(2)))

    private fun site(depths: IntArray = IntArray(12) { 7 }, finds: List<PlacedFind> = listOf(placed), width: Int = 4, height: Int = 3) =
        ExcavationSite("quaternary", width, height, depths, finds)

    @Test
    fun lookupsWork() {
        val s = site()
        assertEquals(12, s.cellCount)
        assertEquals(6, s.cellIndex(2, 1))
        assertEquals(2, s.xOf(6))
        assertEquals(1, s.yOf(6))
        assertEquals(BoneRef(0, "femur_l"), s.boneAt(1, 0))
        assertEquals(BoneRef(0, "tibia_l"), s.boneAt(2))
        assertNull(s.boneAt(3, 0))
        assertEquals(listOf(BoneRef(0, "femur_l"), BoneRef(0, "tibia_l")), s.bones)
        assertEquals(0.6, s.piece(BoneRef(0, "tibia_l")).quality)
        assertThrows<IllegalArgumentException> { s.cellIndex(4, 0) }
    }

    @Test
    fun depthsAreCopied() {
        val depths = IntArray(12) { 7 }
        val s = site(depths)
        depths[0] = 0
        assertEquals(7, s.depthAt(0))
        s.depthsCopy()[0] = 0
        assertEquals(7, s.depthAt(0, 0))
    }

    @Test
    fun invalidStructureIsRejected() {
        assertThrows<IllegalArgumentException> { site(depths = IntArray(11)) }
        assertThrows<IllegalArgumentException> { site(depths = IntArray(12) { -1 }) }
        assertThrows<IllegalArgumentException> { site(depths = IntArray(12) { ExcavationSite.MAX_DEPTH + 1 }) }
        assertThrows<IllegalArgumentException> { site(depths = IntArray(0), width = 0, height = 0) }
        val outside = PlacedFind(find, mapOf("femur_l" to listOf(0), "tibia_l" to listOf(12)))
        assertThrows<IllegalArgumentException> { site(finds = listOf(outside)) }
        val overlapping = PlacedFind(find, mapOf("femur_l" to listOf(0, 1), "tibia_l" to listOf(1)))
        assertThrows<IllegalArgumentException> { site(finds = listOf(overlapping)) }
        // Zwei Funde auf derselben Zelle.
        val other = PlacedFind(Find("cave_bear", listOf(Piece("cave_bear", "sternum", 0.5))), mapOf("sternum" to listOf(2)))
        assertThrows<IllegalArgumentException> { site(finds = listOf(placed, other)) }
    }

    @Test
    fun placedFindNeedsCellsForExactlyItsBones() {
        assertThrows<IllegalArgumentException> { PlacedFind(find, mapOf("femur_l" to listOf(0))) }
        assertThrows<IllegalArgumentException> { PlacedFind(find, mapOf("femur_l" to listOf(0), "tibia_l" to emptyList())) }
        assertThrows<IllegalArgumentException> {
            PlacedFind(find, mapOf("femur_l" to listOf(0), "tibia_l" to listOf(1), "sternum" to listOf(2)))
        }
    }

    @Test
    fun discoveryNeedsOneExposedCell() {
        val depths = IntArray(12) { 7 }
        assertTrue(site(depths).discoveredBones.isEmpty())
        depths[1] = 0
        val s = site(depths)
        assertEquals(listOf(BoneRef(0, "femur_l")), s.discoveredBones)
        assertEquals(false, s.nothingLeftToFind)
        depths[2] = 0
        assertTrue(site(depths).nothingLeftToFind)
        assertTrue(site(finds = emptyList()).nothingLeftToFind, "Flaeche ohne Funde: nichts zu finden")
    }

    @Test
    fun equalityComparesContent() {
        assertEquals(site(), site())
        assertEquals(site().hashCode(), site().hashCode())
        assertNotEquals(site(), site(IntArray(12) { 8 }))
        assertNotEquals(site(), site(finds = emptyList()))
    }

    @Test
    fun depthEncodingRoundTripsWholeRange() {
        val depths = intArrayOf(0, 1, 255, 256, 4097, ExcavationSite.MAX_DEPTH)
        val encoded = ExcavationSite.encodeDepths(depths)
        assertTrue(encoded.matches(Regex("[A-Za-z0-9+/=]*")))
        assertTrue(depths.contentEquals(ExcavationSite.decodeDepths(encoded)))
        assertThrows<IllegalArgumentException> { ExcavationSite.decodeDepths("###") }
        assertThrows<IllegalArgumentException> { ExcavationSite.decodeDepths("AA==") }
    }

    @Test
    fun serializesCompactly() {
        val s = site()
        val text = Json.encodeToString(ExcavationSite.serializer(), s)
        val tree = Json.parseToJsonElement(text).jsonObject
        assertEquals(setOf("layerId", "width", "height", "depths", "finds"), tree.keys)
        assertEquals(ExcavationSite.encodeDepths(IntArray(12) { 7 }), tree.getValue("depths").jsonPrimitive.content)
        assertEquals(s, Json.decodeFromString(ExcavationSite.serializer(), text))
    }
}

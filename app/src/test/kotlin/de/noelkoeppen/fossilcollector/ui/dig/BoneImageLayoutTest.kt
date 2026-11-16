package de.noelkoeppen.fossilcollector.ui.dig

import de.noelkoeppen.fossilcollector.core.data.BoneFootprintDefinition
import de.noelkoeppen.fossilcollector.core.data.DataRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.zip.Inflater
import kotlin.math.floor

/** Lage der Knochenbilder passend zu ihren Zellen (AP2-07). */
class BoneImageLayoutTest {

    private val siteWidth = 32
    private val footprint = BoneFootprintDefinition("toy", listOf("##.", ".##"), imageLeft = -4, imageTop = 8)

    private fun cellsAt(x0: Int, y0: Int, fp: BoneFootprintDefinition) =
        fp.occupiedCells().map { (dx, dy) -> (y0 + dy) * siteWidth + x0 + dx }

    @Test
    fun imageFollowsFootprintOffset() {
        val rect = BoneImageLayout.imageRect(cellsAt(5, 7, footprint), siteWidth, footprint, pxPerCell = 48, imageWidth = 144, imageHeight = 72)
        assertEquals(CellRect(5 - 4 / 48.0, 7 + 8 / 48.0, 3.0, 1.5), rect)
    }

    @Test
    fun mismatchingCellsCenterTheImage() {
        // Alter Spielstand mit Platzhalter-Rechteck (4 x 3 Zellen): Bild mittig darauf.
        val cells = (0 until 3).flatMap { dy -> (0 until 4).map { dx -> (2 + dy) * siteWidth + 1 + dx } }
        assertFalse(BoneImageLayout.matches(cells, siteWidth, 1, 2, footprint))
        val rect = BoneImageLayout.imageRect(cells, siteWidth, footprint, pxPerCell = 48, imageWidth = 96, imageHeight = 48)
        assertEquals(CellRect(2.0, 3.0, 2.0, 1.0), rect)
        assertEquals(rect, BoneImageLayout.imageRect(cells, siteWidth, null, 48, 96, 48))
    }

    @Test
    fun maskPixelsOfRealBonesLieOnTheirCells() {
        // Echte Daten: Legt man die Maske an die berechnete Stelle, liegen fast alle Knochenpixel auf
        // belegten Zellen (Schwelle des Skripts), und jede belegte Zelle enthaelt Knochenpixel.
        val data = DataRepository().load()
        val assets = File(checkNotNull(System.getProperty("fossil.assetsDir")))
        data.species.forEach { species ->
            val footprints = data.footprints.getValue(species.id)
            val manifest = Json.parseToJsonElement(File(assets, BoneImageAssets.manifestPath(species.id)).readText()).jsonObject
            val sizes = manifest.getValue("bones").jsonArray.associate {
                val o = it.jsonObject
                o.getValue("id").jsonPrimitive.content to (o.getValue("width").jsonPrimitive.int to o.getValue("height").jsonPrimitive.int)
            }
            footprints.bones.forEach { fp ->
                val (w, h) = sizes.getValue(fp.id)
                val rect = BoneImageLayout.imageRect(cellsAt(0, 0, fp), siteWidth, fp, footprints.pxPerCell, w, h)
                val mask = readMask(File(assets, BoneImageAssets.maskPath(species.id, fp.id)))
                assertEquals(w, mask.width)
                assertEquals(h, mask.height)
                val occupied = fp.occupiedCells().toSet()
                val perCell = HashMap<Pair<Int, Int>, Int>()
                var total = 0
                var onCells = 0
                for (py in 0 until mask.height) {
                    for (px in 0 until mask.width) {
                        if (!mask.isBone(px, py)) continue
                        total++
                        val cx = floor(rect.left + (px + 0.5) / footprints.pxPerCell).toInt()
                        val cy = floor(rect.top + (py + 0.5) / footprints.pxPerCell).toInt()
                        if ((cx to cy) in occupied) onCells++
                        perCell.merge(cx to cy, 1, Int::plus)
                    }
                }
                assertTrue(onCells >= total * MIN_SHARE_ON_CELLS, "${fp.id}: nur $onCells von $total Pixeln auf Zellen")
                occupied.forEach { assertTrue((perCell[it] ?: 0) > 0, "${fp.id}: Zelle $it ohne Knochen") }
            }
        }
    }

    /** 1-Bit-Maske (Graustufen-PNG ohne Interlacing, wie von bone_images.py geschrieben). */
    private class Mask(val width: Int, val height: Int, private val rows: List<ByteArray>) {
        fun isBone(x: Int, y: Int): Boolean = (rows[y][x / 8].toInt() shr (7 - x % 8)) and 1 == 1
    }

    /** Minimaler PNG-Leser fuer die Masken (javax.imageio fehlt in Android-Unit-Tests). */
    private fun readMask(file: File): Mask = DataInputStream(file.inputStream().buffered()).use { input ->
        input.skipBytes(8)
        var width = 0
        var height = 0
        val idat = ByteArrayOutputStream()
        while (true) {
            val length = input.readInt()
            val type = String(ByteArray(4).also { input.readFully(it) }, Charsets.US_ASCII)
            val chunk = ByteArray(length).also { input.readFully(it) }
            input.readInt()
            when (type) {
                "IHDR" -> {
                    width = java.nio.ByteBuffer.wrap(chunk, 0, 4).int
                    height = java.nio.ByteBuffer.wrap(chunk, 4, 4).int
                    assertEquals(1, chunk[8].toInt(), "Bittiefe")
                    assertEquals(0, chunk[9].toInt(), "Farbtyp Graustufen")
                    assertEquals(0, chunk[12].toInt(), "kein Interlacing")
                }
                "IDAT" -> idat.write(chunk)
                "IEND" -> break
            }
        }
        val stride = (width + 7) / 8
        val raw = ByteArray((stride + 1) * height)
        Inflater().run {
            setInput(idat.toByteArray())
            var n = 0
            while (n < raw.size && !finished()) n += inflate(raw, n, raw.size - n)
            end()
        }
        val rows = ArrayList<ByteArray>(height)
        var prev = ByteArray(stride)
        for (y in 0 until height) {
            val filter = raw[y * (stride + 1)].toInt()
            val row = raw.copyOfRange(y * (stride + 1) + 1, (y + 1) * (stride + 1))
            for (i in 0 until stride) {
                val a = if (i > 0) row[i - 1].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i > 0) prev[i - 1].toInt() and 0xFF else 0
                val add = when (filter) {
                    0 -> 0
                    1 -> a
                    2 -> b
                    3 -> (a + b) / 2
                    4 -> paeth(a, b, c)
                    else -> error("Filter $filter")
                }
                row[i] = ((row[i].toInt() and 0xFF) + add).toByte()
            }
            rows += row
            prev = row
        }
        Mask(width, height, rows)
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private companion object {
        /** Wie im Skript gemessen: kleinster Anteil 0.81 (Mindestanteil je Zelle 5 %). */
        const val MIN_SHARE_ON_CELLS = 0.8
    }
}

package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.floor

/** Erzeugen der bruechigen Stellen beim Bergen (Kapitel 5.2, AP2-09), mit festem Seed. */
class RecoveryGeneratorTest {

    private val data: GameData = DataRepository().load()
    private val balance = data.balance.recovery

    private fun site(seed: Long = 11): ExcavationSite = ExcavationGenerator(data).generate("quaternary", SeededRandom(seed))

    private fun largest(site: ExcavationSite): BoneRef = site.bones.maxBy { site.cellsOf(it).size }

    @Test
    fun sameSeedGivesSameSpotsAndStartIsIdempotent() {
        val base = site()
        val bone = largest(base)
        val prepared = RecoveryTestSupport.prepared(data, base, bone)
        val generator = RecoveryGenerator(data)
        val a = generator.start(prepared, bone, SeededRandom(3))
        val b = generator.start(prepared, bone, SeededRandom(3))
        assertEquals(a, b)
        assertEquals(a.recovery(bone), generator.create(prepared, bone, SeededRandom(3)))
        assertSame(a, generator.start(a, bone, SeededRandom(99)), "zweiter Start aendert nichts")
        // Anderer Seed, andere Stellen (fuer den groessten Knochen mit mehreren Stellen).
        assertNotEquals(a.recovery(bone)?.spots, generator.create(prepared, bone, SeededRandom(4)).spots)
        // Sonst bleibt die Flaeche gleich.
        assertTrue(prepared.depthsCopy().contentEquals(a.depthsCopy()))
        assertEquals(prepared.finds, a.finds)
        assertEquals(prepared.preparations, a.preparations)
    }

    @Test
    fun spotsLieOnBoneCellsWithSpacingAndCountFromData() {
        val generator = RecoveryGenerator(data)
        var checked = 0
        for (seed in 1L..30L) {
            val base = site(seed)
            for (bone in base.bones) {
                val prepared = RecoveryTestSupport.prepared(data, base, bone)
                val prep = checkNotNull(prepared.preparation(bone))
                val rec = generator.create(prepared, bone, SeededRandom(seed * 31 + bone.findIndex))
                val n = base.cellsOf(bone).size
                val maxCount = floor(n * balance.fragileSpotsPerCell + 1).toInt().coerceIn(balance.fragileSpotsMin, balance.fragileSpotsMax)
                assertTrue(rec.spots.size <= maxCount, "zu viele Stellen fuer $bone: ${rec.spots.size}")
                assertTrue(rec.spots.isNotEmpty(), "mindestens fragileSpotsMin = ${balance.fragileSpotsMin} Stelle")
                assertTrue(rec.spots.all { prep.isBone(it) }, "Stellen auf dem Knochen")
                assertTrue(rec.secured.none { it }, "zu Beginn ungesichert")
                rec.spots.forEachIndexed { i, a ->
                    rec.spots.drop(i + 1).forEach { b ->
                        val dx = (prep.xOf(a) - prep.xOf(b)).toDouble()
                        val dy = (prep.yOf(a) - prep.yOf(b)).toDouble()
                        assertTrue(dx * dx + dy * dy >= balance.fragileSpotSpacing * balance.fragileSpotSpacing, "Abstand $a/$b")
                    }
                }
                checked++
            }
        }
        assertTrue(checked > 50)
    }

    @Test
    fun countFollowsSpotsPerCellWithinLimits() {
        val many = data.copy(balance = data.balance.copy(recovery = balance.copy(fragileSpotsPerCell = 10.0, fragileSpotsMax = 3, fragileSpotSpacing = 0.0)))
        val none = data.copy(balance = data.balance.copy(recovery = balance.copy(fragileSpotsPerCell = 0.0, fragileSpotsMin = 0)))
        val tight = data.copy(balance = data.balance.copy(recovery = balance.copy(fragileSpotsPerCell = 10.0, fragileSpotsMax = 50, fragileSpotSpacing = 1000.0)))
        val base = site()
        val bone = largest(base)
        val prepared = RecoveryTestSupport.prepared(data, base, bone)
        assertEquals(3, RecoveryGenerator(many).create(prepared, bone, SeededRandom(1)).spots.size, "gekappt auf fragileSpotsMax")
        assertEquals(0, RecoveryGenerator(none).create(prepared, bone, SeededRandom(1)).spots.size, "keine Stellen")
        assertEquals(1, RecoveryGenerator(tight).create(prepared, bone, SeededRandom(1)).spots.size, "kein Platz fuer eine zweite Stelle")
    }

    @Test
    fun startNeedsFinishedPreparation() {
        val base = site()
        val bone = largest(base)
        val generator = RecoveryGenerator(data)
        assertThrows<IllegalStateException> { generator.start(base, bone, SeededRandom(1)) }
        val discovered = base.with(base.depthsCopy().also { it[base.cellsOf(bone).first()] = 0 }, base.finds)
        val started = PreparationGenerator(data).start(discovered, bone, SeededRandom(1))
        assertThrows<IllegalStateException> { generator.start(started, bone, SeededRandom(1)) }
        assertThrows<NoSuchElementException> { generator.start(base, BoneRef(99, "femur_l"), SeededRandom(1)) }
    }
}

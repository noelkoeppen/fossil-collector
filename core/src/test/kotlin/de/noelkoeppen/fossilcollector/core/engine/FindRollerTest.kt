package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.FindBalance
import de.noelkoeppen.fossilcollector.core.data.FindSizeWeight
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.SpeciesDefinition
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.ReferenceValidator
import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import de.noelkoeppen.fossilcollector.core.modifier.ModifierCategory
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

/** Fundsystem (Kapitel 4, AP2-04): Wuerfeln mit festem Seed. */
class FindRollerTest {

    private val data: GameData = DataRepository().load()
    private val layerId = data.layers.first().id

    /** Spieldaten mit eigenen Arten (alle in [layerId]) und eigenen Fundparametern. */
    private fun testData(species: List<SpeciesDefinition>, finds: FindBalance = data.balance.finds): GameData =
        data.copy(species = species, balance = data.balance.copy(finds = finds))

    private fun chain(id: String, length: Int, weight: Double = 1.0) = SpeciesDefinition(
        id = id,
        layer = layerId,
        rarityWeight = weight,
        bones = (1..length).map { "b$it" },
        neighbors = (1 until length).map { listOf("b$it", "b${it + 1}") },
    )

    private fun rollMany(roller: FindRoller, count: Int, seed: Long): List<FindRoll> {
        val random = SeededRandom(seed)
        return List(count) { roller.roll(layerId, random) }
    }

    // --- Gueltigkeit einzelner Funde ---

    @Test
    fun findsAreConnectedAndQualitiesStayWithinDeviation() {
        val finds = data.balance.finds
        val sizes = finds.sizeWeights.map { it.size }.toSet()
        rollMany(FindRoller(data), 2_000, seed = 42).forEach { roll ->
            val find = roll.find
            assertEquals(emptyList<String>(), ReferenceValidator.validateFind(find, data))
            assertTrue(data.species(find.speciesId).isConnected(find.boneIds))
            assertTrue(find.pieces.size in sizes, "Fundgroesse ${find.pieces.size}")
            assertTrue(roll.baseQuality >= finds.baseQualityMin && roll.baseQuality < finds.baseQualityMax)
            find.pieces.forEach { piece ->
                assertTrue(abs(piece.quality - roll.baseQuality) <= finds.boneQualityDeviation + EPS, "$piece / ${roll.baseQuality}")
                assertTrue(piece.quality in Piece.MIN_QUALITY..Piece.MAX_QUALITY)
                assertEquals(find.speciesId, piece.speciesId)
            }
        }
    }

    @Test
    fun sameSeedGivesSameFinds() {
        val a = rollMany(FindRoller(data), 200, seed = 7)
        val b = rollMany(FindRoller(data), 200, seed = 7)
        assertEquals(a, b)
        assertNotEquals(a, rollMany(FindRoller(data), 200, seed = 8))
    }

    // --- Verteilung ueber 10 000 Wuerfe ---

    @Test
    fun sizeDistributionAndBaseQualityMatchBalance() {
        val finds = data.balance.finds
        val rolls = rollMany(FindRoller(data), ROLLS, seed = 1234)
        val total = finds.sizeWeights.sumOf { it.weight }
        val counts = rolls.groupingBy { it.find.pieces.size }.eachCount()
        finds.sizeWeights.forEach { entry ->
            val expected = entry.weight / total
            val actual = (counts[entry.size] ?: 0).toDouble() / ROLLS
            assertEquals(expected, actual, SHARE_TOLERANCE, "Anteil Fundgroesse ${entry.size}")
        }
        val bases = rolls.map { it.baseQuality }
        assertTrue(bases.all { it >= finds.baseQualityMin && it < finds.baseQualityMax })
        assertEquals((finds.baseQualityMin + finds.baseQualityMax) / 2, bases.average(), MEAN_TOLERANCE)
        // Die Abweichung ist symmetrisch: Mittel der Knochenqualitaeten nahe dem Mittel der Grundqualitaet.
        val deltas = rolls.flatMap { roll -> roll.find.pieces.map { it.quality - roll.baseQuality } }
        assertEquals(0.0, deltas.average(), finds.boneQualityDeviation / 10)
        // Startknochen ueber alle Knochen verteilt: jeder Knochen des Hoehlenbaeren kommt vor.
        val bear = data.species("cave_bear")
        assertEquals(bear.bones.toSet(), rolls.flatMap { it.find.boneIds }.toSet())
    }

    @Test
    fun speciesChoiceFollowsRarityWeights() {
        val common = chain("common", 10, weight = 30.0)
        val rare = chain("rare", 10, weight = 10.0)
        val roller = FindRoller(testData(listOf(common, rare)))
        val rolls = rollMany(roller, ROLLS, seed = 99)
        val share = rolls.count { it.find.speciesId == "common" }.toDouble() / ROLLS
        assertEquals(0.75, share, SHARE_TOLERANCE)
    }

    // --- Randfaelle ---

    @Test
    fun findSizeIsCappedAtBoneCountOfSpecies() {
        val tiny = chain("tiny", 3)
        val finds = data.balance.finds.copy(sizeWeights = listOf(FindSizeWeight(size = 10, weight = 1.0)))
        rollMany(FindRoller(testData(listOf(tiny), finds)), 50, seed = 3).forEach { roll ->
            assertEquals(setOf("b1", "b2", "b3"), roll.find.boneIds.toSet())
        }
    }

    @Test
    fun singleBoneSpeciesGivesSingleBoneFinds() {
        val single = SpeciesDefinition("single", layerId, 1.0, listOf("skull"), emptyList())
        rollMany(FindRoller(testData(listOf(single))), 50, seed = 5).forEach { roll ->
            assertEquals(listOf("skull"), roll.find.boneIds)
        }
    }

    @Test
    fun growthFollowsGraphInChain() {
        // In einer Kette ist jeder zusammenhaengende Fund ein lueckenloser Abschnitt.
        val finds = data.balance.finds.copy(sizeWeights = listOf(FindSizeWeight(size = 4, weight = 1.0)))
        val chainData = testData(listOf(chain("chain", 20)), finds)
        rollMany(FindRoller(chainData), 200, seed = 11).forEach { roll ->
            val indices = roll.find.boneIds.map { it.removePrefix("b").toInt() }.sorted()
            assertEquals((indices.first()..indices.last()).toList(), indices)
            assertEquals(emptyList<String>(), ReferenceValidator.validateFind(roll.find, chainData))
        }
    }

    @Test
    fun zeroDeviationGivesUniformQuality() {
        val finds = data.balance.finds.copy(boneQualityDeviation = 0.0)
        rollMany(FindRoller(testData(data.species, finds)), 100, seed = 17).forEach { roll ->
            assertTrue(roll.find.pieces.all { it.quality == roll.baseQuality })
        }
    }

    @Test
    fun qualityIsClampedToScale() {
        val high = data.balance.finds.copy(baseQualityMin = 0.98, baseQualityMax = 1.0, boneQualityDeviation = 0.1)
        val highQualities = rollMany(FindRoller(testData(data.species, high)), 300, seed = 21)
            .flatMap { roll -> roll.find.pieces.map { it.quality } }
        assertTrue(highQualities.all { it <= Piece.MAX_QUALITY })
        assertTrue(highQualities.any { it == Piece.MAX_QUALITY }, "Kein Knochen an der Obergrenze")

        val low = data.balance.finds.copy(baseQualityMin = 0.0, baseQualityMax = 0.02, boneQualityDeviation = 0.1)
        val lowQualities = rollMany(FindRoller(testData(data.species, low)), 300, seed = 22)
            .flatMap { roll -> roll.find.pieces.map { it.quality } }
        assertTrue(lowQualities.all { it >= Piece.MIN_QUALITY })
        assertTrue(lowQualities.any { it == Piece.MIN_QUALITY }, "Kein Knochen an der Untergrenze")
    }

    @Test
    fun unknownLayerIsRejected() {
        assertThrows<NoSuchElementException> { FindRoller(data).roll("jurassic", SeededRandom(1)) }
    }

    // --- Modifier quality_base_min ---

    @Test
    fun qualityBaseMinModifierRaisesLowerBound() {
        val finds = data.balance.finds
        val bonus = 0.2
        val engine = ModifierEngine.fromGameData(data)
        engine.setSource("upgrade:test", listOf(Modifier(StatId.QUALITY_BASE_MIN, ModifierOp.ADD, bonus, ModifierCategory.UPGRADE)))
        val bases = rollMany(FindRoller(data, engine), ROLLS, seed = 31).map { it.baseQuality }
        val min = finds.baseQualityMin + bonus
        assertTrue(bases.all { it >= min && it < finds.baseQualityMax })
        assertEquals((min + finds.baseQualityMax) / 2, bases.average(), MEAN_TOLERANCE)
    }

    @Test
    fun qualityBaseMinAboveMaxIsCappedAtMax() {
        val finds = data.balance.finds
        val engine = ModifierEngine.fromGameData(data)
        engine.setSource("upgrade:test", listOf(Modifier(StatId.QUALITY_BASE_MIN, ModifierOp.ADD, 1.0, ModifierCategory.UPGRADE)))
        rollMany(FindRoller(data, engine), 50, seed = 32).forEach { assertEquals(finds.baseQualityMax, it.baseQuality) }
    }

    private companion object {
        const val ROLLS = 10_000
        const val EPS = 1e-9

        /** Toleranz fuer Anteile bei 10 000 Wuerfen (etwa 4 Standardabweichungen bei p = 0.5). */
        const val SHARE_TOLERANCE = 0.02

        /** Toleranz fuer den Mittelwert der Grundqualitaet bei 10 000 Wuerfen. */
        const val MEAN_TOLERANCE = 0.01
    }
}

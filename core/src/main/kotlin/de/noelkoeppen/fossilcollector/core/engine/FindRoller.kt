package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.SpeciesDefinition
import de.noelkoeppen.fossilcollector.core.model.Find
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.random.GameRandom
import de.noelkoeppen.fossilcollector.core.random.pickWeighted

/**
 * Ergebnis eines Wurfs: der Fund und die Grundqualitaet, um die die Qualitaeten seiner Knochen streuen.
 */
data class FindRoll(
    val find: Find,
    val baseQuality: Double,
)

/**
 * Wuerfelt Funde nach Kapitel 4. Alle Parameter kommen aus `balance.json: finds` und den Arten der
 * Schicht; Zufall nur ueber das uebergebene [GameRandom].
 *
 * Ablauf eines Wurfs (in dieser Reihenfolge, damit gleicher Seed denselben Fund ergibt):
 * 1. Art: gewichtet nach `rarityWeight` unter den Arten der Schicht.
 * 2. Fundgroesse: gewichtet nach `finds.sizeWeights`, gekappt auf die Knochenanzahl der Art.
 * 3. Startknochen: gleichverteilt unter allen Knochen der Art.
 * 4. Erweiterung: Solange die Fundgroesse nicht erreicht ist, kommt ein gleichverteilt gewaehlter
 *    Nachbar der bisherigen Knochen dazu (Rand des Fundes im Nachbarschaftsgraph).
 * 5. Grundqualitaet: gleichverteilt in `[min, baseQualityMax)`, mit `min` = Stat `quality_base_min`
 *    auf Basis `baseQualityMin`, begrenzt auf `0..baseQualityMax`.
 * 6. Qualitaet je Knochen: Grundqualitaet plus gleichverteilte Abweichung in
 *    `[-boneQualityDeviation, +boneQualityDeviation)`, begrenzt auf 0..1.
 *
 * @param data Spieldaten (validiert)
 * @param modifiers Modifier-System fuer `quality_base_min`; Standard ohne Quellen mit den Clamps der Daten
 */
class FindRoller(
    private val data: GameData,
    private val modifiers: ModifierEngine = ModifierEngine.fromGameData(data),
) {
    private val balance = data.balance.finds

    /**
     * Wuerfelt einen Fund in der Schicht [layerId].
     *
     * @throws NoSuchElementException wenn die Schicht unbekannt ist
     * @throws IllegalArgumentException wenn die Schicht keine Arten hat
     */
    fun roll(layerId: String, random: GameRandom): FindRoll {
        val layer = data.layer(layerId)
        val candidates = data.speciesByLayer[layer.id].orEmpty()
        require(candidates.isNotEmpty()) { "Schicht ${layer.id} hat keine Arten" }

        val species = random.pickWeighted(candidates) { it.rarityWeight }
        val size = minOf(random.pickWeighted(balance.sizeWeights) { it.weight }.size, species.bones.size)
        val bones = growBones(species, size, random)
        val baseQuality = rollBaseQuality(random)
        val deviation = balance.boneQualityDeviation
        val pieces = bones.map { boneId ->
            val quality = (baseQuality + random.nextDouble(-deviation, deviation))
                .coerceIn(Piece.MIN_QUALITY, Piece.MAX_QUALITY)
            Piece(speciesId = species.id, boneId = boneId, quality = quality)
        }
        return FindRoll(Find(species.id, pieces), baseQuality)
    }

    /** Startknochen plus Erweiterung entlang der Nachbarn bis [size] Knochen. */
    private fun growBones(species: SpeciesDefinition, size: Int, random: GameRandom): List<String> {
        val chosen = linkedSetOf(species.bones[random.nextInt(species.bones.size)])
        while (chosen.size < size) {
            // Rand in Knochenreihenfolge der Art, damit der Wurf nur vom Seed abhaengt.
            val frontier = species.bones.filter { it !in chosen && species.neighborsOf(it).any { n -> n in chosen } }
            // Bei gueltigen Daten (zusammenhaengender Graph) nie leer; sonst bleibt der Fund kleiner.
            if (frontier.isEmpty()) break
            chosen += frontier[random.nextInt(frontier.size)]
        }
        return chosen.toList()
    }

    private fun rollBaseQuality(random: GameRandom): Double {
        val max = balance.baseQualityMax
        val min = modifiers.value(StatId.QUALITY_BASE_MIN, balance.baseQualityMin).coerceIn(Piece.MIN_QUALITY, max)
        return random.nextDouble(min, max)
    }
}

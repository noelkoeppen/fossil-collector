package de.noelkoeppen.fossilcollector.ui.book

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.SpeciesDefinition
import de.noelkoeppen.fossilcollector.core.engine.GameEngine
import de.noelkoeppen.fossilcollector.core.engine.QualityTiers
import de.noelkoeppen.fossilcollector.core.engine.SpeciesCompletion
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.PieceKey

/**
 * Baut den [BookUiState] aus Engine und Spielstand (ausserhalb der Composables, testbar).
 * Completion-Stufen und Boni stammen aus `GameEngine.bookOverview` (`Completion`), die Stufenfaktoren
 * aus `Completion.tierFactor`; hier wird keine Spiellogik berechnet.
 *
 * **Cache:** Der Mapper laeuft bei jedem `publish` (10/s). Das Ergebnis haengt nur von
 * `meta.book`, `meta.researchedSpecies` (beide per Instanz verglichen) und den freigeschalteten
 * Schichten ab; solange sich diese nicht aendern, liefert [map] dieselbe Instanz. Nicht threadsicher:
 * nur vom Spiel-Thread des ViewModels aufrufen.
 *
 * @param data Spieldaten (fest fuer die Lebensdauer des Mappers)
 */
class BookUiMapper(private val data: GameData) {

    private val tiers = QualityTiers.fromGameData(data)

    private var cachedBook: Map<PieceKey, BookEntry>? = null
    private var cachedResearched: Set<String>? = null
    private var cachedUnlocked: Int = -1
    private var cached: BookUiState? = null

    fun map(engine: GameEngine, state: GameState): BookUiState {
        val book = state.meta.book
        val researched = state.meta.researchedSpecies
        val unlockedCount = engine.dig.unlockedLayers(state.run).size
        val previous = cached
        if (previous != null && book === cachedBook && researched === cachedResearched && unlockedCount == cachedUnlocked) {
            return previous
        }
        val result = build(engine, state, unlockedCount)
        cached = result
        cachedBook = book
        cachedResearched = researched
        cachedUnlocked = unlockedCount
        return result
    }

    private fun build(engine: GameEngine, state: GameState, unlockedCount: Int): BookUiState {
        val overview = engine.bookOverview(state).associateBy { it.speciesId }
        val bestEntries = bestEntriesByBone(state.meta.book)
        val layers = data.layers.mapIndexed { index, layer ->
            BookLayerUi(
                layerId = layer.id,
                unlocked = index < unlockedCount,
                species = data.speciesByLayer[layer.id].orEmpty().mapNotNull { species ->
                    overview[species.id]?.let { speciesUi(engine, species, it, bestEntries) }
                },
            )
        }
        return BookUiState(layers)
    }

    private fun speciesUi(
        engine: GameEngine,
        species: SpeciesDefinition,
        completion: SpeciesCompletion,
        bestEntries: Map<String, Map<String, BookEntry>>,
    ): BookSpeciesUi {
        val entries = bestEntries[species.id].orEmpty()
        val bones = species.bones.map { boneId -> boneUi(boneId, entries[boneId]) }
        val bonusDef = species.bookBonus
        val maxTierIndex = (tiers.tiers.size - 1).coerceAtLeast(0)
        val minTier = completion.minTierIndex
        val researchedDef = data.balance.researchedBonus
        return BookSpeciesUi(
            speciesId = species.id,
            layerId = species.layer,
            bodyPlan = species.bodyPlan,
            discovered = completion.bonesInBook > 0 || completion.researched,
            level = completion.level,
            bonesInBook = completion.bonesInBook,
            bonesTotal = completion.bonesTotal,
            minTierIndex = minTier,
            minTierId = minTier?.let { tiers.tiers.getOrNull(it)?.id },
            bones = bones,
            skeleton = bones.associate { bone ->
                bone.boneId to SkeletonBone(
                    tierIndex = bone.tierIndex,
                    zoneQualities = if (bone.zones.any { it.quality != null }) bone.zones.map { it.quality } else null,
                )
            },
            bookBonus = BookBonusUi(
                stat = bonusDef.stat,
                op = bonusDef.op,
                baseValue = bonusDef.value,
                active = completion.bookBonus != null,
                value = completion.bookBonus?.value,
                factor = minTier?.let { engine.completion.tierFactor(it) },
                minValue = bonusDef.value * engine.completion.tierFactor(0),
                maxValue = bonusDef.value * engine.completion.tierFactor(maxTierIndex),
            ),
            researchedBonus = ResearchedBonusUi(
                stat = researchedDef.stat,
                op = researchedDef.op,
                value = completion.researchedBonus?.value ?: researchedDef.value,
                active = completion.researchedBonus != null,
            ),
        )
    }

    private fun boneUi(boneId: String, entry: BookEntry?): BookBoneUi {
        val zoneIds = data.bonesById[boneId]?.zones.orEmpty()
        val zoneQualities = entry?.zones?.takeIf { it.size == zoneIds.size }
        return BookBoneUi(
            boneId = boneId,
            quality = entry?.quality,
            tierId = entry?.let { tiers.tierOf(it.quality).id },
            tierIndex = entry?.let { tiers.indexOf(it.quality) },
            reconstructed = entry?.reconstructed ?: false,
            zones = zoneIds.mapIndexed { i, zoneId -> BookZoneUi(zoneId, zoneQualities?.get(i)) },
        )
    }

    /**
     * Bester Bucheintrag je Art und Knochen (ueber Alter/Geschlecht, wie `Completion.minTierIndex`).
     * Bei gleicher Qualitaet bleibt der zuerst gelesene Eintrag.
     */
    private fun bestEntriesByBone(book: Map<PieceKey, BookEntry>): Map<String, Map<String, BookEntry>> {
        val result = HashMap<String, HashMap<String, BookEntry>>()
        for ((key, entry) in book) {
            val bones = result.getOrPut(key.speciesId) { HashMap() }
            val current = bones[key.boneId]
            if (current == null || entry.quality > current.quality) bones[key.boneId] = entry
        }
        return result
    }
}

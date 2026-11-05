package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.SpeciesDefinition
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import de.noelkoeppen.fossilcollector.core.modifier.ModifierCategory

/**
 * Completion-Stufe einer Art (Kapitel 4.7). Im MVP gibt es nur die Stufen 0 bis 2; Stufe 3
 * (konsistentes Skelett) und 4 (vollstaendige Population) sind [Spaeter].
 *
 * @property level Zahl der Stufe (0, 1, 2), aufsteigend vergleichbar
 */
enum class CompletionLevel(val level: Int) {
    /** Weder erforscht noch vollstaendig im Buch. */
    NONE(0),

    /** Stufe 1: mindestens ein Stueck der Art im Labor abgeschlossen (`meta.researchedSpecies`). */
    RESEARCHED(1),

    /** Stufe 2: alle Knochen der Art im Sammelbuch vorhanden (Alter/Geschlecht egal). */
    SKELETON_COMPLETE(2),
}

/**
 * Stand einer Art im Sammelbuch (Abfrage fuer die UI, AP-20).
 *
 * @property speciesId Art-ID
 * @property layerId Schicht der Art
 * @property level erreichte Completion-Stufe (hoechste erfuellte Bedingung)
 * @property researched Bedingung Stufe 1 erfuellt
 * @property skeletonComplete Bedingung Stufe 2 erfuellt
 * @property bonesInBook Knochen der Art mit Bucheintrag (Alter/Geschlecht egal)
 * @property bonesTotal Knochen der Art laut `species.json`
 * @property minTierIndex niedrigste Qualitaetsstufe der Knochen im Buch (Index in
 *   `balance.qualityTiers`), `null` solange Stufe 2 nicht erreicht ist
 * @property bookBonus wirksamer Buch-Bonus (Stufe 2) oder `null`
 * @property researchedBonus wirksamer Pauschal-Bonus (Stufe 1) oder `null`
 */
data class SpeciesCompletion(
    val speciesId: String,
    val layerId: String,
    val level: CompletionLevel,
    val researched: Boolean,
    val skeletonComplete: Boolean,
    val bonesInBook: Int,
    val bonesTotal: Int,
    val minTierIndex: Int?,
    val bookBonus: Modifier?,
    val researchedBonus: Modifier?,
)

/**
 * Sammelbuch-Completion und Buch-Boni (GAME_DESIGN.md, Kapitel 4.7).
 *
 * - **Stufe 1 (erforscht):** Art in `meta.researchedSpecies` (gesetzt vom [LabSystem]).
 * - **Stufe 2 (Skelett vollstaendig):** [Skeletons.isCompleteInBook].
 * - Die Bedingungen sind unabhaengig (ein Buch kann ohne Laborforschung vollstaendig sein, z. B. nur
 *   aus Funden); [levelOf] liefert die hoechste erfuellte Stufe.
 * - **Buch-Bonus** (ab Stufe 2): `species.bookBonus` mit dem Wert
 *   `bookBonus.value * balance.bookTierFactor[minTier]`, Kategorie `book`. `minTier` ist die niedrigste
 *   Qualitaetsstufe ueber alle Knochen der Art; je Knochen zaehlt dabei der beste Bucheintrag
 *   (relevant erst mit Alter/Geschlecht [Spaeter]).
 * - **Pauschal-Bonus** `balance.researchedBonus` fuer jede erforschte Art. Er bleibt ab Stufe 2
 *   erhalten (kumulativ zum Buch-Bonus, Entscheidung AP-11).
 *
 * Reine Abfragen ohne Zustand; die Boni gelangen ueber [BookSources] in die
 * [de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine].
 */
class Completion(private val data: GameData) {

    private val tiers: QualityTiers = QualityTiers.fromGameData(data)

    /** Completion-Stufe der Art [speciesId] in [state]; unbekannte Arten haben [CompletionLevel.NONE]. */
    fun levelOf(state: GameState, speciesId: String): CompletionLevel {
        val species = data.speciesById[speciesId] ?: return CompletionLevel.NONE
        return level(isResearched(state, species), Skeletons.isCompleteInBook(state.meta.book, species))
    }

    /** `true`, wenn die Art erforscht ist (Stufe 1). */
    fun isResearched(state: GameState, species: SpeciesDefinition): Boolean =
        species.id in state.meta.researchedSpecies

    /**
     * Index der niedrigsten Qualitaetsstufe der Knochen von [species] im Buch (passend zu
     * `balance.bookTierFactor`), oder `null`, wenn nicht alle Knochen im Buch sind.
     */
    fun minTierIndex(book: Map<PieceKey, BookEntry>, species: SpeciesDefinition): Int? {
        if (species.bones.isEmpty()) return null
        val best = HashMap<String, Double>()
        for ((key, entry) in book) {
            if (key.speciesId != species.id) continue
            val current = best[key.boneId]
            if (current == null || entry.quality > current) best[key.boneId] = entry.quality
        }
        var minIndex = Int.MAX_VALUE
        for (bone in species.bones) {
            val quality = best[bone] ?: return null
            minIndex = minOf(minIndex, tiers.indexOf(quality))
        }
        return minIndex
    }

    /** Faktor `balance.bookTierFactor[index]`; Indizes ausserhalb werden auf den gueltigen Bereich begrenzt. */
    fun tierFactor(index: Int): Double {
        val factors = data.balance.bookTierFactor
        if (factors.isEmpty()) return 0.0
        return factors[index.coerceIn(0, factors.lastIndex)]
    }

    /** Wirksamer Buch-Bonus von [species] (Stufe 2) oder `null`. */
    fun bookBonus(book: Map<PieceKey, BookEntry>, species: SpeciesDefinition): Modifier? {
        val index = minTierIndex(book, species) ?: return null
        val bonus = species.bookBonus
        return Modifier(bonus.stat, bonus.op, bonus.value * tierFactor(index), ModifierCategory.BOOK)
    }

    /** Stand aller Arten in Datenreihenfolge (fuer die Sammelbuch-Liste). */
    fun overview(state: GameState): List<SpeciesCompletion> = data.species.map { completionOf(state, it) }

    /** Stand der Art [species]. */
    fun completionOf(state: GameState, species: SpeciesDefinition): SpeciesCompletion {
        val book = state.meta.book
        val researched = isResearched(state, species)
        val minTier = minTierIndex(book, species)
        val complete = minTier != null
        val present = book.keys.asSequence()
            .filter { it.speciesId == species.id && it.boneId in species.bones }
            .mapTo(HashSet()) { it.boneId }
        return SpeciesCompletion(
            speciesId = species.id,
            layerId = species.layer,
            level = level(researched, complete),
            researched = researched,
            skeletonComplete = complete,
            bonesInBook = present.size,
            bonesTotal = species.bones.size,
            minTierIndex = minTier,
            bookBonus = if (complete) bookBonus(book, species) else null,
            researchedBonus = if (researched) data.balance.researchedBonus else null,
        )
    }

    private fun level(researched: Boolean, complete: Boolean): CompletionLevel = when {
        complete -> CompletionLevel.SKELETON_COMPLETE
        researched -> CompletionLevel.RESEARCHED
        else -> CompletionLevel.NONE
    }
}

/**
 * Buch-Boni als Modifier-Quellen, Prefix `book:` (Kapitel 4.7 und 6.2):
 * - `book:<speciesId>`: Buch-Bonus ab Completion-Stufe 2 (Wert mal `bookTierFactor[minTier]`)
 * - `book:researched:<speciesId>`: `balance.researchedBonus` fuer jede erforschte Art
 *
 * Unbekannte Arten im Spielstand werden ignoriert. Die Boni sind permanent (Sammelbuch und
 * `researchedSpecies` liegen in `meta`).
 */
object BookSources : ModifierSourceProvider {
    override val prefix: String = "book:"

    /** Teil-Prefix der Pauschal-Boni fuer erforschte Arten. */
    const val RESEARCHED_PREFIX: String = "book:researched:"

    @Volatile
    private var cached: Pair<GameData, Completion>? = null

    /** Letztes Ergebnis mit seinen Eingaben (Identitaet von Daten, Buch und erforschten Arten). */
    private class LastResult(
        val data: GameData,
        val book: Any,
        val researched: Any,
        val sources: Map<String, List<Modifier>>,
    )

    @Volatile
    private var last: LastResult? = null

    /**
     * Quellen aus Buch und erforschten Arten. Haben sich `meta.book` und `meta.researchedSpecies`
     * (gleiche Instanzen) und die Daten nicht geaendert, wird das letzte Ergebnis (dieselbe
     * Map-Instanz) zurueckgegeben; [ModifierSources.sync] ueberspringt den Abgleich dann (AP-14).
     */
    override fun sources(state: GameState, data: GameData): Map<String, List<Modifier>> {
        val meta = state.meta
        last?.let {
            if (it.data === data && it.book === meta.book && it.researched === meta.researchedSpecies) return it.sources
        }
        val completion = completionFor(data)
        val result = LinkedHashMap<String, List<Modifier>>()
        for (species in data.species) {
            completion.bookBonus(meta.book, species)?.let { result[prefix + species.id] = listOf(it) }
            if (completion.isResearched(state, species)) {
                result[RESEARCHED_PREFIX + species.id] = listOf(data.balance.researchedBonus)
            }
        }
        last = LastResult(data, meta.book, meta.researchedSpecies, result)
        return result
    }

    /** Wiederverwendet die [Completion] fuer dieselben Daten (Abgleich laeuft bei jedem Tick). */
    private fun completionFor(data: GameData): Completion {
        val current = cached
        if (current != null && current.first === data) return current.second
        return Completion(data).also { cached = data to it }
    }
}

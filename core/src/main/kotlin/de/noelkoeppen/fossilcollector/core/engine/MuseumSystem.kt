package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.RunState
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Ein im Museum ausgestelltes Stueck (Abfrage fuer die UI, Kapitel 8.3).
 *
 * @property pieceKey Schluessel des Stuecks
 * @property piece das Stueck aus dem Run-Bestand
 * @property incomePerSecond Einnahmen dieses Stuecks pro Sekunde inkl. `museum_income`
 * @property skeletonComplete `true`, wenn das Skelett der Art im Durchlauf vollstaendig ist
 * @property specialExhibit `true`, wenn die Art die aktive Sonderausstellung ist
 */
data class ExhibitedPiece(
    val pieceKey: PieceKey,
    val piece: Piece,
    val incomePerSecond: GameNumber,
    val skeletonComplete: Boolean,
    val specialExhibit: Boolean,
)

/**
 * Museum (GAME_DESIGN.md, Kapitel 4.6). Laeuft in [GameEngine.defaultSystems] nach der
 * Praeparation und schreibt pro `advance` `incomePerSecond * dt` Geld gut.
 *
 * - **Plaetze:** `floor(halls) * balance.slotsPerHall`.
 * - **Ausstellung:** automatisch die wertvollsten Run-Funde, sortiert nach ihrem Einkommen
 *   (absteigend, bei Gleichstand nach Schluessel-Text, damit die Auswahl deterministisch ist).
 * - **Einkommen je Stueck:** `layer.value * (0.5 + q) * skeleton * special`, mit
 *   - `skeleton = mods(skeleton_bonus, Basis balance.skeletonMult)`, wenn das Skelett der Art im
 *     Durchlauf vollstaendig ist ([Skeletons.isCompleteInRun]), sonst 1. Fuer `add`-Modifier
 *     (Skill `curator_3`, Buch-Bonus Velociraptor) ist das `skeletonMult + skeleton_bonus`.
 *   - `special = special_exhibit_mult` (additiv, Basis 0, Capstone `curator_5`), wenn
 *     `run.specialExhibit` die Art des Stuecks ist, deren Skelett vollstaendig ist und der Stat
 *     > 0 ist; sonst 1. Der Faktor wirkt **zusaetzlich** zum Skelett-Faktor.
 *   - `layer` ist die Schicht der Art (`species.layer`).
 * - **Gesamt:** `Σ income(piece) * mods(museum_income)` ueber die ausgestellten Stuecke.
 *
 * **Sonderausstellung (Schnittstelle fuer AP-13):** Die Auswahl setzt `run.specialExhibit` auf eine
 * Art-ID (oder `null`). Dieses System prueft bei jeder Berechnung selbst, ob die Art vollstaendig
 * ist und der Capstone wirkt ([isSpecialExhibitActive]); eine ungueltige Auswahl hat einfach keine
 * Wirkung.
 *
 * **Performance:** Die Rangfolge der Stuecke wird nur neu berechnet, wenn sich `run.pieces`
 * (Instanz), `halls`, `specialExhibit` oder einer der Faktoren `skeleton_bonus` bzw.
 * `special_exhibit_mult` aendert; sonst nutzt [advance] die zwischengespeicherte Summe. Da Zustaende
 * unveraenderlich sind, genuegt fuer `run.pieces` ein Identitaetsvergleich. Eine Neuberechnung
 * kostet `O(n log n)` fuer n < 200 Stuecke und waere auch bei 10 Ticks/s unkritisch.
 * Wie [ModifierEngine] nicht thread-safe (Nutzung nur aus der Spielschleife).
 */
class MuseumSystem(
    private val data: GameData,
    private val modifiers: ModifierEngine,
) : AdvanceSystem {

    private class Ranking(
        val pieces: Map<PieceKey, Piece>,
        val halls: Double,
        val specialExhibit: String?,
        val skeletonFactor: Double,
        val specialFactor: Double,
        /** Ausgestellte Stuecke mit Einkommen vor `museum_income`. */
        val exhibited: List<ExhibitedPiece>,
        /** Summe der Einkommen vor `museum_income`. */
        val baseIncome: Double,
    )

    private var cache: Ranking? = null

    /** Anzahl der Ausstellungsplaetze: `floor(halls) * balance.slotsPerHall` (nie negativ). */
    fun slots(run: RunState): Double = max(0.0, floor(run.halls.toDouble())) * data.balance.slotsPerHall

    /** Skelett-Faktor fuer vollstaendige Arten: `mods(skeleton_bonus, Basis balance.skeletonMult)`. */
    fun skeletonFactor(): Double = modifiers.value(StatId.SKELETON_BONUS, data.balance.skeletonMult)

    /** Faktor der Sonderausstellung (`special_exhibit_mult`, additiv mit Basis 0); 0 = Capstone fehlt. */
    fun specialExhibitFactor(): Double = modifiers.additive(StatId.SPECIAL_EXHIBIT_MULT)

    /** `true`, wenn `run.specialExhibit` gesetzt ist, die Art vollstaendig ist und der Capstone wirkt. */
    fun isSpecialExhibitActive(state: GameState): Boolean {
        val speciesId = state.run.specialExhibit ?: return false
        val species = data.speciesById[speciesId] ?: return false
        return specialExhibitFactor() > 0.0 && Skeletons.isCompleteInRun(state.run, species)
    }

    /** Ausgestellte Stuecke, wertvollstes zuerst, Einkommen inkl. `museum_income`. */
    fun exhibitedPieces(state: GameState): List<ExhibitedPiece> {
        val museumFactor = modifiers.factor(StatId.MUSEUM_INCOME)
        return ranking(state.run).exhibited.map { it.copy(incomePerSecond = it.incomePerSecond * museumFactor) }
    }

    /** Gesamteinnahmen pro Sekunde: `Σ income(piece) * mods(museum_income)`. */
    fun incomePerSecond(state: GameState): GameNumber =
        GameNumber(ranking(state.run).baseIncome * modifiers.factor(StatId.MUSEUM_INCOME))

    /**
     * Einkommen eines einzelnen Stuecks pro Sekunde vor `museum_income` (unabhaengig davon, ob es
     * ausgestellt ist). [completeSpecies] sind die vollstaendigen Arten des Durchlaufs.
     */
    fun pieceIncome(key: PieceKey, piece: Piece, completeSpecies: Set<String>, specialExhibit: String?): Double =
        pieceIncome(key, piece, completeSpecies, specialExhibit, skeletonFactor(), specialExhibitFactor())

    override fun advance(state: GameState, dt: Double, context: AdvanceContext): GameState {
        val income = incomePerSecond(state)
        if (income.toDouble() == 0.0) return state
        return state.copy(run = state.run.copy(money = state.run.money + income * dt))
    }

    private fun pieceIncome(
        key: PieceKey,
        piece: Piece,
        completeSpecies: Set<String>,
        specialExhibit: String?,
        skeletonFactor: Double,
        specialFactor: Double,
    ): Double {
        val layer = data.layer(data.species(key.speciesId).layer)
        val complete = key.speciesId in completeSpecies
        var factor = if (complete) skeletonFactor else 1.0
        if (complete && specialFactor > 0.0 && key.speciesId == specialExhibit) factor *= specialFactor
        return layer.value * (FindClassifier.QUALITY_OFFSET + piece.quality) * factor
    }

    private fun ranking(run: RunState): Ranking {
        val skeleton = skeletonFactor()
        val special = specialExhibitFactor()
        val halls = run.halls.toDouble()
        cache?.let {
            if (it.pieces === run.pieces && it.halls == halls && it.specialExhibit == run.specialExhibit &&
                it.skeletonFactor == skeleton && it.specialFactor == special
            ) {
                return it
            }
        }

        val complete = Skeletons.completeSpeciesInRun(run, data)
        val slotCount = min(slots(run), run.pieces.size.toDouble()).toInt()
        val exhibited = run.pieces.entries
            .map { (key, piece) ->
                val activeSpecial = special > 0.0 && key.speciesId == run.specialExhibit && key.speciesId in complete
                ExhibitedPiece(
                    pieceKey = key,
                    piece = piece,
                    incomePerSecond = GameNumber(pieceIncome(key, piece, complete, run.specialExhibit, skeleton, special)),
                    skeletonComplete = key.speciesId in complete,
                    specialExhibit = activeSpecial,
                )
            }
            .sortedWith(
                compareByDescending<ExhibitedPiece> { it.incomePerSecond.toDouble() }.thenBy { it.pieceKey.toString() },
            )
            .take(slotCount)
        val ranking = Ranking(
            pieces = run.pieces,
            halls = halls,
            specialExhibit = run.specialExhibit,
            skeletonFactor = skeleton,
            specialFactor = special,
            exhibited = exhibited,
            baseIncome = exhibited.sumOf { it.incomePerSecond.toDouble() },
        )
        cache = ranking
        return ranking
    }
}

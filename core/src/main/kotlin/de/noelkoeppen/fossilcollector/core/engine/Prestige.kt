package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.MetaState
import de.noelkoeppen.fossilcollector.core.model.RunState
import de.noelkoeppen.fossilcollector.core.model.RunStartValues
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Ruhm-Beitrag eines im aktuellen Durchlauf vollstaendigen Skeletts (Kapitel 5.3).
 *
 * @property speciesId Art-ID
 * @property layerId Schicht der Art (`species.layer`)
 * @property fameWeight `layer.fameWeight`
 * @property avgQuality Mittel der Run-Stueck-Qualitaeten der Art (je Knochen das beste Stueck)
 * @property baseFame `fameWeight * (0.5 + avgQuality)`, vor `fame_gain` und Abrunden
 */
data class SkeletonFame(
    val speciesId: String,
    val layerId: String,
    val fameWeight: Double,
    val avgQuality: Double,
    val baseFame: Double,
)

/**
 * Daten fuer den Prestige-Bestaetigungsdialog (Kapitel 5.3, Ruhm-Screen AP-22): Ruhm-Gewinn, was
 * verloren geht und was bleibt. Mengen als [GameNumber], Anzahlen von Eintraegen (begrenzt durch die
 * Daten) als `Int`.
 *
 * @property fameGain Ruhm-Gewinn beim Prestige jetzt (abgerundet)
 * @property canPrestige `fameGain >= 1`
 * @property fameGainFactor wirksamer Faktor `fame_gain`
 * @property skeletons Beitraege der vollstaendigen Skelette in Datenreihenfolge
 * @property fameAfter unverteilter Ruhm nach dem Prestige
 * @property prestigeCountAfter Prestige-Zaehler nach dem Prestige
 * @property money verlorenes Geld
 * @property researchPoints verlorene FP
 * @property blocks verlorene Rohbloecke (alle Schichten)
 * @property teams verlorene Teams (alle Schichten, vor den Startwerten des neuen Durchlaufs)
 * @property preparators verlorene Praeparatoren
 * @property halls verlorene Hallen
 * @property labSlots verlorene Laborplaetze
 * @property toolLevel verlorene Werkzeugstufe
 * @property lockedLayers Schichten, die wieder gesperrt werden (`unlockedLayers - 1`)
 * @property runPieces Run-Funde (ausgestellte Stuecke), die verloren gehen
 * @property spareZones Ersatzteile, die verloren gehen
 * @property labItems Stuecke in Laborwarteschlange und laufender Forschung
 * @property specialExhibit aktuelle Sonderausstellung (geht verloren) oder `null`
 * @property bookEntries Eintraege im Sammelbuch (bleiben)
 * @property skills gekaufte Skills (bleiben)
 * @property milestonesDone erreichte Meilensteine (bleiben)
 */
data class PrestigeSummary(
    val fameGain: GameNumber,
    val canPrestige: Boolean,
    val fameGainFactor: Double,
    val skeletons: List<SkeletonFame>,
    val fameAfter: GameNumber,
    val prestigeCountAfter: GameNumber,
    val money: GameNumber,
    val researchPoints: GameNumber,
    val blocks: GameNumber,
    val teams: GameNumber,
    val preparators: GameNumber,
    val halls: GameNumber,
    val labSlots: GameNumber,
    val toolLevel: GameNumber,
    val lockedLayers: GameNumber,
    val runPieces: Int,
    val spareZones: Int,
    val labItems: Int,
    val specialExhibit: String?,
    val bookEntries: Int,
    val skills: Int,
    val milestonesDone: Int,
) {
    /** Anzahl der vollstaendigen Skelette im aktuellen Durchlauf. */
    val skeletonCount: Int get() = skeletons.size
}

/** Ergebnis der Spieleraktion Prestige. Der urspruengliche Zustand wird nie veraendert. */
sealed interface PrestigeResult {
    /**
     * Prestige ausgefuehrt.
     *
     * @property state neuer Spielstand (neuer Durchlauf)
     * @property events zuerst [GameEvent.Prestiged], danach ggf. [GameEvent.MilestoneReached]
     * @property fameGained gutgeschriebener Ruhm
     */
    data class Success(
        val state: GameState,
        val events: List<GameEvent>,
        val fameGained: GameNumber,
    ) : PrestigeResult

    /** Prestige abgelehnt; der Spielstand bleibt unveraendert. */
    data class Failure(val reason: PrestigeFailure) : PrestigeResult
}

/** Gruende fuer ein abgelehntes Prestige. */
enum class PrestigeFailure {
    /** Die Vorschau liegt unter 1 Ruhm. */
    NOT_ENOUGH_FAME,

    /** Das Ergebnis enthielte `NaN`/`Infinity` (siehe [NumericErrorHandler]). */
    NON_FINITE_RESULT,
}

/**
 * Schritt, der beim Prestige den Metazustand anpasst, **bevor** die Startwerte des neuen
 * Durchlaufs berechnet werden (Erweiterungsstelle fuer den Respec aus AP-13: Skills zurueckgeben,
 * Ruhm erstatten). Erhaelt den Metazustand mit bereits gutgeschriebenem Ruhm und erhoehtem
 * Prestige-Zaehler.
 */
fun interface PrestigeMetaStep {
    fun apply(meta: MetaState): MetaState

    companion object {
        /** Kein Eingriff (Standard bis AP-13). */
        val NONE: PrestigeMetaStep = PrestigeMetaStep { it }
    }
}

/**
 * Prestige und Ruhm (GAME_DESIGN.md, Kapitel 5.3). Reine Berechnung ohne Abgleich der
 * Modifier-Quellen; Aufruf ueber [GameEngine.fameGainPreview], [GameEngine.canPrestige],
 * [GameEngine.prestigeSummary] und [GameEngine.prestige], die vorher abgleichen.
 *
 * **Ruhm:** `fameGain = floor( Σ layer.fameWeight * (0.5 + avgQuality) * fame_gain )` ueber alle Arten
 * aus [Skeletons.completeSpeciesInRun]. `fame_gain` ist ein Faktor-Stat (Basis 1.0, Kapitel 6.2) und
 * wird **einmal auf die Summe** angewendet, vor dem Abrunden (gleichwertig zur Anwendung je Skelett,
 * weil der Faktor fuer alle gleich ist; so geht durch Rundung nichts verloren). Der Buch-Bonus des
 * Tyrannosaurus (`fame_gain` mult 0.03 je Stufenfaktor) wirkt also prozentual auf den Gesamtgewinn.
 *
 * **avgQuality:** Mittelwert ueber die Knochen der Art; je Knochen zaehlt das beste Run-Stueck mit
 * diesem `speciesId:boneId` (Alter/Geschlecht egal, wie bei der Skelett-Erkennung; im MVP gibt es
 * je Knochen genau ein Stueck).
 */
class Prestige(
    private val data: GameData,
    private val modifiers: ModifierEngine,
) {

    /** Beitraege aller im aktuellen Durchlauf vollstaendigen Skelette in Datenreihenfolge. */
    fun skeletons(state: GameState): List<SkeletonFame> {
        val complete = Skeletons.completeSpeciesInRun(state.run, data)
        if (complete.isEmpty()) return emptyList()
        val best = HashMap<Pair<String, String>, Double>()
        for ((key, piece) in state.run.pieces) {
            if (key.speciesId !in complete) continue
            val id = key.speciesId to key.boneId
            val q = piece.quality
            if (q > (best[id] ?: Double.NEGATIVE_INFINITY)) best[id] = q
        }
        return complete.map { speciesId ->
            val species = data.species(speciesId)
            val layer = data.layer(species.layer)
            val avg = species.bones.map { best.getValue(speciesId to it) }.average()
            SkeletonFame(
                speciesId = speciesId,
                layerId = layer.id,
                fameWeight = layer.fameWeight,
                avgQuality = avg,
                baseFame = layer.fameWeight * (QUALITY_OFFSET + avg),
            )
        }
    }

    /** Wirksamer Faktor `fame_gain` (Basis 1.0). */
    fun fameGainFactor(): Double = modifiers.factor(StatId.FAME_GAIN)

    /** Ruhm bei Neustart jetzt (Vorschau, abgerundet, nie negativ). */
    fun fameGainPreview(state: GameState): GameNumber = gainOf(skeletons(state))

    /** `true`, wenn die Vorschau mindestens 1 Ruhm zeigt. */
    fun canPrestige(state: GameState): Boolean = fameGainPreview(state) >= MIN_FAME

    /** Daten fuer den Bestaetigungsdialog. */
    fun summary(state: GameState): PrestigeSummary {
        val skeletons = skeletons(state)
        val gain = gainOf(skeletons)
        val run = state.run
        return PrestigeSummary(
            fameGain = gain,
            canPrestige = gain >= MIN_FAME,
            fameGainFactor = fameGainFactor(),
            skeletons = skeletons,
            fameAfter = state.meta.fame + gain,
            prestigeCountAfter = state.meta.prestigeCount + 1.0,
            money = run.money,
            researchPoints = run.researchPoints,
            blocks = sum(run.blocks.values),
            teams = sum(run.teams.values),
            preparators = run.preparators,
            halls = run.halls,
            labSlots = run.labSlots,
            toolLevel = run.toolLevel,
            lockedLayers = GameNumber.max(GameNumber.ZERO, run.unlockedLayers - 1.0),
            runPieces = run.pieces.size,
            spareZones = run.spareZones.size,
            labItems = run.labQueue.size + run.labJobs.size,
            specialExhibit = run.specialExhibit,
            bookEntries = state.meta.book.size,
            skills = state.meta.skills.size,
            milestonesDone = state.meta.milestonesDone.size,
        )
    }

    /**
     * Metazustand nach dem Prestige: Ruhm (`fame` und `fameTotal`) um [gain] erhoeht,
     * `prestigeCount + 1`. Alles andere (Skills, Buch, erforschte Arten, Statistiken, Meilensteine)
     * bleibt.
     */
    fun metaAfterPrestige(meta: MetaState, gain: GameNumber): MetaState = meta.copy(
        fame = meta.fame + gain,
        fameTotal = meta.fameTotal + gain,
        prestigeCount = meta.prestigeCount + 1.0,
    )

    /**
     * Neuer Durchlauf gemaess Reset-Tabelle 5.3: alles aus [RunState] wird ersetzt (Geld, FP,
     * Bloecke, `prepProgress`, Teams, Praeparatoren, Hallen, Laborplaetze, Werkzeugstufe, Schichten
     * ausser der ersten, Run-Funde, Ersatzteile, Laborwarteschlange und -jobs, Sonderausstellung).
     */
    fun newRun(start: RunStartValues): RunState = RunState.newRun(data.layers.first().id, start)

    /**
     * Leerer Durchlauf ohne Bestaende, nur fuer den Abgleich der Modifier-Quellen vor
     * [RunStart.values]: So wirken bei den Startwerten nur permanente Quellen (Skills, Buch,
     * Meilensteine), keine Upgrades des alten Durchlaufs.
     */
    fun emptyRun(): RunState = newRun(
        RunStartValues(
            money = GameNumber.ZERO,
            researchPoints = GameNumber.ZERO,
            teamsInFirstLayer = GameNumber.ZERO,
            preparators = GameNumber.ZERO,
            halls = GameNumber.ZERO,
            labSlots = GameNumber.ZERO,
            toolLevel = GameNumber.ZERO,
        ),
    )

    private fun gainOf(skeletons: List<SkeletonFame>): GameNumber {
        if (skeletons.isEmpty()) return GameNumber.ZERO
        val raw = skeletons.sumOf { it.baseFame } * fameGainFactor()
        return GameNumber(raw).floor().let { GameNumber.max(GameNumber.ZERO, it) }
    }

    private fun sum(values: Collection<GameNumber>): GameNumber =
        values.fold(GameNumber.ZERO) { acc, v -> acc + v }

    private companion object {
        /** Formelkonstante aus Kapitel 5.3 (`0.5 + avgQuality`), kein Balancing-Wert. */
        const val QUALITY_OFFSET: Double = 0.5

        /** Mindest-Ruhm fuer ein Prestige laut Kapitel 5.3 (Vorschau >= 1). */
        val MIN_FAME: GameNumber = GameNumber.ONE
    }
}

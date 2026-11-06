package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LabJob
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.SpareZoneKey
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Labor (GAME_DESIGN.md, Kapitel 4.5). Laeuft in [GameEngine.defaultSystems] nach dem Museum.
 *
 * - **Plaetze:** `floor(run.labSlots)` Forschungen laufen parallel (`run.labJobs`). Freie Plaetze
 *   werden in der Reihenfolge der Warteschlange (`run.labQueue`, FIFO) belegt.
 * - **Dauer:** `balance.labDuration * mods(lab_speed)`; `lab_speed` ist ein Faktor-Stat mit Basis
 *   1.0 und dem Clamp aus `balance.statLimits` (Skills `lab_1` und `lab_4`: Faktor 0.65). Die Dauer
 *   wird beim Start eines Jobs festgelegt; spaeter gekaufte Skills verkuerzen laufende Jobs nicht.
 * - **Abschluss** eines Jobs:
 *   - FP `(layer.order + 1) * mods(research_points)`, `layer` = Schicht der Art.
 *   - Ersatzteile: Fuer jede Zone des Knochens (`bones.json`) ersetzt die Zonen-Qualitaet des Stuecks
 *     das gespeicherte Ersatzteil `run.spareZones[SpareZoneKey(pieceKey, zoneId)]`, wenn sie **echt
 *     hoeher** ist (oder es noch keins gibt).
 *   - Die Art wird in `meta.researchedSpecies` aufgenommen (Completion-Stufe 1, Kapitel 4.7).
 *   - `meta.stats.researchCompleted` wird um 1 erhoeht (Meilenstein `research_completed`, AP-11).
 * - **Grosse `dt`:** Die Zeit wird ereignisweise abgearbeitet. Endet ein Job vor Ablauf von `dt`,
 *   startet auf dem frei gewordenen Platz sofort das naechste Stueck der Warteschlange und bekommt
 *   die Restzeit. Ergebnis und Ereignisse sind daher unabhaengig davon, ob `dt` in einem Schritt oder
 *   in vielen kleinen Ticks gerechnet wird (bis auf Rundung).
 *
 * Ereignisse: [GameEvent.LabJobStarted], [GameEvent.LabJobCompleted] und beim ersten Abschluss einer
 * Art [GameEvent.SpeciesResearched] (in dieser Reihenfolge je Job).
 *
 * Unbekannte Arten bzw. Knochen in einem Job (z. B. aus einem alten Spielstand mit geaenderten Daten)
 * bringen 0 FP bzw. keine Ersatzteile, brechen den Tick aber nicht ab.
 */
class LabSystem(
    private val data: GameData,
    private val modifiers: ModifierEngine,
) : AdvanceSystem {

    /** Anzahl paralleler Forschungen: `floor(run.labSlots)`, nie negativ. */
    fun slots(state: GameState): Int = max(0.0, floor(state.run.labSlots.toDouble())).toInt()

    /** Dauer einer neuen Forschung in Sekunden: `balance.labDuration * mods(lab_speed)`; wird beim Start als `LabJob.duration` gespeichert. */
    fun jobDuration(): Double = data.balance.labDuration * modifiers.factor(StatId.LAB_SPEED)

    /** FP fuer den Abschluss einer Forschung an [key]: `(layer.order + 1) * mods(research_points)`. */
    fun researchPointsFor(key: PieceKey): GameNumber {
        val species = data.speciesById[key.speciesId] ?: return GameNumber.ZERO
        val layer = data.layersById[species.layer] ?: return GameNumber.ZERO
        return GameNumber((layer.order + 1).toDouble() * modifiers.factor(StatId.RESEARCH_POINTS))
    }

    /**
     * Fortschritt eines Jobs von 0 bis 1 (fuer Fortschrittsbalken der UI) bezogen auf die beim Start
     * gespeicherte Dauer `job.duration` (AP-25). Fehlt sie (Spielstand vor AP-25), gilt die aktuelle
     * Dauer [jobDuration]; ist die Restzeit groesser als diese (z. B. nach einem Respec), zaehlt die
     * Restzeit als Gesamtdauer, der Balken steht dann bei 0 statt negativ.
     */
    fun progress(job: LabJob): Double {
        val duration = job.duration ?: max(jobDuration(), job.remaining)
        if (duration <= 0.0) return 1.0
        return (1.0 - job.remaining / duration).coerceIn(0.0, 1.0)
    }

    override fun advance(state: GameState, dt: Double, context: AdvanceContext): GameState {
        val slots = slots(state)
        if (state.run.labJobs.isEmpty() && (state.run.labQueue.isEmpty() || slots == 0)) return state

        var current = state
        var timeLeft = dt
        while (true) {
            current = startJobs(current, slots, context)
            val jobs = current.run.labJobs
            if (jobs.isEmpty() || timeLeft <= 0.0) break

            // Bis zum naechsten Abschluss oder bis die Zeit verbraucht ist.
            val step = min(timeLeft, jobs.minOf { it.remaining })
            timeLeft -= step
            val (finished, running) = jobs
                .map { it.copy(remaining = it.remaining - step) }
                .partition { it.remaining <= DONE_EPSILON }
            current = current.copy(run = current.run.copy(labJobs = running))
            for (job in finished) current = complete(current, job, context)
            if (finished.isEmpty()) break
        }
        return current
    }

    /** Belegt freie Plaetze mit Stuecken aus der Warteschlange (FIFO). */
    private fun startJobs(state: GameState, slots: Int, context: AdvanceContext): GameState {
        val run = state.run
        val free = slots - run.labJobs.size
        if (free <= 0 || run.labQueue.isEmpty()) return state
        val starting = run.labQueue.take(free)
        val duration = jobDuration()
        val newJobs = starting.map { LabJob(it.piece, it.zones, remaining = duration, duration = duration) }
        newJobs.forEach { context.emit(GameEvent.LabJobStarted(it.piece, duration)) }
        return state.copy(
            run = run.copy(
                labQueue = run.labQueue.drop(starting.size),
                labJobs = run.labJobs + newJobs,
            ),
        )
    }

    /** Schliesst [job] ab: FP, Ersatzteile, erforschte Art. */
    private fun complete(state: GameState, job: LabJob, context: AdvanceContext): GameState {
        val key = job.piece
        val points = researchPointsFor(key)

        var spareZones = state.run.spareZones
        val zoneIds = data.bonesById[key.boneId]?.zones.orEmpty()
        for ((index, zoneId) in zoneIds.withIndex()) {
            val quality = job.zones.getOrNull(index) ?: continue
            val spareKey = SpareZoneKey(key, zoneId)
            val stored = spareZones[spareKey]
            if (stored == null || quality > stored) spareZones = spareZones + (spareKey to quality)
        }

        context.emit(GameEvent.LabJobCompleted(key, points))
        var meta = state.meta.let { it.copy(stats = it.stats.copy(researchCompleted = it.stats.researchCompleted + 1.0)) }
        if (key.speciesId !in meta.researchedSpecies && key.speciesId in data.speciesById) {
            meta = meta.copy(researchedSpecies = meta.researchedSpecies + key.speciesId)
            context.emit(GameEvent.SpeciesResearched(key.speciesId))
        }
        return state.copy(
            meta = meta,
            run = state.run.copy(
                researchPoints = state.run.researchPoints + points,
                spareZones = spareZones,
            ),
        )
    }

    private companion object {
        /** Rundungstoleranz in Sekunden, ab der ein Job als fertig gilt (technisch, kein Spielwert). */
        const val DONE_EPSILON: Double = 1e-9
    }
}

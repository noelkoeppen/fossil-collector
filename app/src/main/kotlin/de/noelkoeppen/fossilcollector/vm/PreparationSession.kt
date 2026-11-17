package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.ToolStage
import de.noelkoeppen.fossilcollector.core.engine.DigPoint
import de.noelkoeppen.fossilcollector.core.engine.DigStroke
import de.noelkoeppen.fossilcollector.core.engine.PreparationGenerator
import de.noelkoeppen.fossilcollector.core.engine.Preparer
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.random.GameRandom

/**
 * Rueckmeldung der letzten Anwendungen beim feinen Freilegen. [sequence] steigt mit jeder neuen
 * Rueckmeldung, damit die UI eine kurze Markierung genau einmal abspielt.
 *
 * @property hitCells feine Zellen, an denen der Kratzer freiliegenden Knochen getroffen hat (leer = kein Schaden)
 * @property finished der Knochen ist mit diesen Anwendungen fertig freigelegt worden
 */
data class PrepFeedback(
    val sequence: Long,
    val hitCells: List<Int>,
    val finished: Boolean,
) {
    /** `true`, wenn der Knochen dabei Qualitaet verloren hat (oder verloren haette, bei Qualitaet 0). */
    val damaged: Boolean get() = hitCells.isNotEmpty()

    companion object {
        val NONE = PrepFeedback(0, emptyList(), false)
    }
}

/**
 * Ablauf des feinen Freilegens (AP2-08, Kapitel 5.1) zwischen UI und `core`, ohne Android:
 * Nahansicht eines entdeckten Knochens oeffnen (beim ersten Mal [PreparationGenerator.start]),
 * Pinsel oder Kratzer waehlen, Wischbewegungen in Anwendungen von [Preparer.applyTool] uebersetzen
 * ([DigStroke] mit `strokeSpacing` des Werkzeugs, in feinen Zellen), Nahansicht schliessen. Die
 * Regeln stehen in `core`; hier wird nur verdrahtet. Nicht threadsicher, laeuft im ViewModel auf
 * dem Spiel-Thread.
 *
 * Die in der laufenden Wischbewegung frei gewordenen Zellen sammelt die Sitzung und gibt sie an
 * [Preparer.applyTool] weiter (Schaden-Regel: nur Knochen, der schon vor dem Zug frei lag, zaehlt).
 *
 * Bedienzustand (geoeffneter Knochen, Werkzeug, laufende Wischbewegung, Rueckmeldung) wird nicht
 * gespeichert; Restschicht, Fortschritt und Qualitaet stehen in der Flaeche im [GameState].
 */
class PreparationSession(
    private val data: GameData,
    modifiers: ModifierEngine = ModifierEngine.fromGameData(data),
) {
    private val generator = PreparationGenerator(data)
    private val preparer = Preparer(data, modifiers)

    /** Knochen, dessen Nahansicht offen ist, oder `null`. */
    var openBone: BoneRef? = null
        private set

    /** Gewaehltes Werkzeug; zu Beginn das erste des feinen Freilegens aus `tools.json` (Pinsel). */
    var toolId: String = data.tools.first { it.stage == ToolStage.PREPARE }.id
        private set

    /** Letzte Rueckmeldung (Treffer, fertig). */
    var feedback: PrepFeedback = PrepFeedback.NONE
        private set

    private var stroke: DigStroke? = null
    private val strokeExposed = HashSet<Int>()

    /**
     * Nahansicht von [ref] oeffnen. Hat das Freilegen noch nicht begonnen, beginnt es jetzt (Zufall
     * fuer die Restschicht aus [random]). Nur fuer entdeckte Knochen der laufenden Ausgrabung; sonst
     * bleibt alles unveraendert.
     */
    fun open(state: GameState, ref: BoneRef, random: GameRandom): GameState {
        val site = state.excavation ?: return state
        if (ref !in site.bones || !site.isDiscovered(ref)) return state
        endStroke()
        openBone = ref
        feedback = PrepFeedback(feedback.sequence + 1, emptyList(), false)
        val next = generator.start(site, ref, random)
        return if (next === site) state else state.copy(excavation = next)
    }

    /** Nahansicht schliessen (zurueck zur Flaeche). */
    fun close() {
        endStroke()
        openBone = null
    }

    /** Werkzeug waehlen; unbekannte IDs und Werkzeuge des groben Buddelns werden ignoriert. */
    fun selectTool(id: String): Boolean {
        if (data.toolsById[id]?.stage != ToolStage.PREPARE || id == toolId) return false
        endStroke()
        toolId = id
        return true
    }

    /** Finger setzt auf ([x], [y] in feinen Zellkoordinaten): eine Anwendung. */
    fun strokeStart(state: GameState, x: Double, y: Double): GameState {
        if (current(state) == null) return state
        val newStroke = DigStroke(data.tool(toolId).strokeSpacing)
        stroke = newStroke
        strokeExposed.clear()
        return apply(state, newStroke.start(x, y))
    }

    /** Finger bewegt sich: Anwendungen je zurueckgelegter `strokeSpacing`-Strecke. */
    fun strokeMove(state: GameState, x: Double, y: Double): GameState {
        val currentStroke = stroke ?: return state
        if (current(state) == null) return state
        return apply(state, currentStroke.moveTo(x, y))
    }

    /** Finger hebt ab oder die Geste wird abgebrochen. */
    fun endStroke() {
        stroke?.end()
        stroke = null
        strokeExposed.clear()
    }

    /** Offener Knochen, wenn er in der laufenden Ausgrabung eine Nahansicht hat. */
    private fun current(state: GameState): BoneRef? {
        val ref = openBone ?: return null
        val site = state.excavation ?: return null
        return ref.takeIf { site.preparation(it) != null }
    }

    private fun apply(state: GameState, points: List<DigPoint>): GameState {
        val ref = current(state) ?: return state
        var site = state.excavation ?: return state
        if (points.isEmpty()) return state
        val hits = LinkedHashSet<Int>()
        var finished = false
        for (point in points) {
            val result = preparer.applyTool(site, ref, toolId, point.x, point.y, strokeExposed)
            site = result.site
            strokeExposed += result.exposedCells
            hits += result.hitCells
            finished = finished || result.newlyFinished
        }
        if (hits.isNotEmpty() || finished) {
            feedback = PrepFeedback(feedback.sequence + 1, hits.toList(), finished)
        }
        return if (site === state.excavation) state else state.copy(excavation = site)
    }
}

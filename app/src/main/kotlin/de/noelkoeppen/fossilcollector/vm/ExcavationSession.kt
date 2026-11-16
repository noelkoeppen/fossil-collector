package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.ToolStage
import de.noelkoeppen.fossilcollector.core.engine.DigPoint
import de.noelkoeppen.fossilcollector.core.engine.DigStroke
import de.noelkoeppen.fossilcollector.core.engine.ExcavationGenerator
import de.noelkoeppen.fossilcollector.core.engine.Excavator
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.random.GameRandom

/**
 * Rueckmeldung der letzten Grabungsaktionen fuer die Anzeige (Schaden, Entdeckung). [sequence]
 * steigt mit jeder neuen Rueckmeldung, damit die UI eine kurze Markierung genau einmal abspielt.
 *
 * @property damaged Knochen, die bei den letzten Anwendungen Qualitaet verloren haben
 * @property discovered Knochen, auf die man dabei gestossen ist
 * @property focus Knochen, dessen Qualitaet die UI zeigt (zuletzt beschaedigt oder entdeckt)
 */
data class DigFeedback(
    val sequence: Long,
    val damaged: List<BoneRef>,
    val discovered: List<BoneRef>,
    val focus: BoneRef?,
) {
    companion object {
        val NONE = DigFeedback(0, emptyList(), emptyList(), null)
    }
}

/**
 * Ablauf des groben Buddelns (AP2-07) zwischen UI und `core`, ohne Android: Ausgrabung starten,
 * Werkzeug waehlen, Wischbewegungen in Anwendungen von [Excavator.applyTool] uebersetzen
 * ([DigStroke], Abstand `strokeSpacing` des Werkzeugs), Ausgrabung beenden. Die Regeln selbst stehen
 * in `core`; hier wird nur verdrahtet. Nicht threadsicher, laeuft im ViewModel auf dem Spiel-Thread.
 *
 * Werkzeugwahl, laufende Wischbewegung und Rueckmeldung sind Bedienzustand und werden nicht
 * gespeichert; die Flaeche selbst steht im [GameState] und wird mit ihm gespeichert.
 */
class ExcavationSession(
    private val data: GameData,
    modifiers: ModifierEngine = ModifierEngine.fromGameData(data),
) {
    private val generator = ExcavationGenerator(data, modifiers)
    private val excavator = Excavator(data, modifiers)

    /** Gewaehltes Werkzeug; zu Beginn das erste des groben Buddelns aus `tools.json`. */
    var toolId: String = data.tools.first { it.stage == ToolStage.DIG }.id
        private set

    /** Letzte Rueckmeldung (Schaden, Entdeckung). */
    var feedback: DigFeedback = DigFeedback.NONE
        private set

    private var stroke: DigStroke? = null

    /** Schicht fuer neue Ausgrabungen: im ersten Stand die oberste (einzige) Schicht (Kapitel 9). */
    val startLayerId: String get() = data.layers.first().id

    /** Neue Ausgrabung, sofern keine laeuft; sonst bleibt der Stand unveraendert. */
    fun start(state: GameState, random: GameRandom): GameState {
        if (state.excavation != null) return state
        endStroke()
        feedback = DigFeedback(feedback.sequence + 1, emptyList(), emptyList(), null)
        return state.copy(excavation = generator.generate(startLayerId, random))
    }

    /**
     * Werkzeug waehlen; unbekannte IDs und Werkzeuge des feinen Freilegens werden ignoriert. Eine
     * laufende Wischbewegung endet.
     */
    fun selectTool(id: String): Boolean {
        if (data.toolsById[id]?.stage != ToolStage.DIG || id == toolId) return false
        endStroke()
        toolId = id
        return true
    }

    /** Finger setzt auf ([x], [y] in Zellkoordinaten): eine Anwendung. */
    fun digStart(state: GameState, x: Double, y: Double): GameState {
        if (state.excavation == null) return state
        val newStroke = DigStroke(data.tool(toolId).strokeSpacing)
        stroke = newStroke
        return apply(state, newStroke.start(x, y))
    }

    /** Finger bewegt sich: Anwendungen je zurueckgelegter `strokeSpacing`-Strecke. */
    fun digMove(state: GameState, x: Double, y: Double): GameState {
        val current = stroke ?: return state
        if (state.excavation == null) return state
        return apply(state, current.moveTo(x, y))
    }

    /** Finger hebt ab oder die Geste wird abgebrochen. */
    fun endStroke() {
        stroke?.end()
        stroke = null
    }

    /** Ausgrabung beenden: Die Flaeche entfaellt (Uebergang bis AP2-09, siehe GAME_DESIGN.md Kapitel 10). */
    fun end(state: GameState): GameState {
        endStroke()
        feedback = DigFeedback(feedback.sequence + 1, emptyList(), emptyList(), null)
        return if (state.excavation == null) state else state.copy(excavation = null)
    }

    private fun apply(state: GameState, points: List<DigPoint>): GameState {
        var site = state.excavation ?: return state
        if (points.isEmpty()) return state
        val damaged = LinkedHashSet<BoneRef>()
        val discovered = LinkedHashSet<BoneRef>()
        for (point in points) {
            val result = excavator.applyTool(site, toolId, point.x, point.y)
            site = result.site
            result.damage.forEach { damaged += it.bone }
            discovered += result.newlyDiscovered
        }
        if (damaged.isNotEmpty() || discovered.isNotEmpty()) {
            val focus = damaged.lastOrNull() ?: discovered.last()
            feedback = DigFeedback(feedback.sequence + 1, damaged.toList(), discovered.toList(), focus)
        }
        return if (site === state.excavation) state else state.copy(excavation = site)
    }
}

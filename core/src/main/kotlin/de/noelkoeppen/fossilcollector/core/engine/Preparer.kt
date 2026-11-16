package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.ToolDefinition
import de.noelkoeppen.fossilcollector.core.data.ToolStage
import de.noelkoeppen.fossilcollector.core.model.BonePreparation
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Ergebnis einer Anwendung beim feinen Freilegen.
 *
 * @property site neue Flaeche (mit neuer Nahansicht und ggf. neuer Qualitaet des Knochens)
 * @property preparation neue Nahansicht
 * @property removed insgesamt abgetragene Einheiten der Restschicht
 * @property changedCells feine Zellen, deren Restschicht sich geaendert hat (zum Neuzeichnen)
 * @property exposedCells feine Zellen, die mit dieser Anwendung frei geworden sind
 * @property hitCells Knochenzellen ohne Restschicht, die ein grobes Werkzeug beruehrt hat (Schaden)
 * @property damage Qualitaetsverlust des Knochens oder `null`, wenn er nicht getroffen wurde
 * @property newlyFinished `true`, wenn der Knochen mit dieser Anwendung fertig freigelegt ist
 */
data class PreparationResult(
    val site: ExcavationSite,
    val preparation: BonePreparation,
    val removed: Int,
    val changedCells: List<Int>,
    val exposedCells: List<Int>,
    val hitCells: List<Int>,
    val damage: BoneDamage?,
    val newlyFinished: Boolean,
) {
    /** Fortschritt nach der Anwendung (Anteil freiliegender Knochenzellen). */
    val progress: Double get() = preparation.progress
}

/**
 * Aktion „Werkzeug wirkt auf Bereich“ beim feinen Freilegen (Kapitel 5.1, AP2-08), analog zum
 * [Excavator]: kein Zufall, keine Simulation ueber Zeit, jede Anwendung ist eine Spieleraktion.
 * Nur Werkzeuge mit `stage` = `prepare`; Ansatzpunkt und Radius in feinen Zellen der Nahansicht.
 *
 * Regeln je feiner Zelle im Wirkbereich (Mittelpunkt hoechstens `radius` vom Ansatzpunkt):
 * - **Lose Erde** traegt jedes Werkzeug ab (`min(removal, Dicke)`), **harte Stellen** nur ein
 *   Werkzeug mit `removesHard` (Kratzer); der Pinsel laesst sie unberuehrt.
 * - **Treffer:** Ein grobes Werkzeug (`rough`, Kratzer) trifft den Knochen, wenn im Wirkbereich eine
 *   Knochenzelle liegt, auf der schon vor dieser Wischbewegung keine Restschicht mehr lag. Zellen,
 *   die erst in derselben Wischbewegung frei geworden sind ([strokeExposed]), zaehlen nicht: Wer mit
 *   einem Zug eine harte Stelle abkratzt, beschaedigt den Knochen nicht; wer ueber freiliegenden
 *   Knochen kratzt (auch im naechsten Zug ueber eben freigelegte Stellen), schon.
 * - **Schaden:** pro Anwendung mit Treffer einmal `boneDamage * (1 - damage_reduction)` (Stat
 *   begrenzt auf 0..1), Qualitaet hoechstens bis 0 (Kapitel 5: der Knochen bleibt erhalten).
 * - **Fortschritt** ist der Anteil der Knochenzellen ohne Restschicht ([BonePreparation.progress]).
 *   Erreicht er `preparation.finishedShare`, ist der Knochen **fertig freigelegt**; das bleibt so.
 *
 * @param data Spieldaten (validiert)
 * @param modifiers Modifier-System fuer `damage_reduction`
 */
class Preparer(
    private val data: GameData,
    private val modifiers: ModifierEngine = ModifierEngine.fromGameData(data),
) {
    private val finishedShare = data.balance.preparation.finishedShare

    /**
     * Wendet Werkzeug [toolId] in der Nahansicht von [ref] mit Ansatzpunkt ([centerX], [centerY]) in
     * feinen Zellkoordinaten an. Zelle (x, y) reicht von x bis x + 1. Ansatzpunkte ausserhalb sind
     * erlaubt; es wirken nur Zellen im Raster.
     *
     * @param strokeExposed feine Zellen, die in der laufenden Wischbewegung schon frei geworden sind
     *   (Summe von [PreparationResult.exposedCells] der vorherigen Anwendungen desselben Zugs)
     * @throws NoSuchElementException wenn das Werkzeug unbekannt ist oder das Freilegen von [ref]
     *   noch nicht begonnen hat
     * @throws IllegalArgumentException wenn das Werkzeug nicht zum feinen Freilegen gehoert oder der
     *   Ansatzpunkt nicht endlich ist
     */
    fun applyTool(
        site: ExcavationSite,
        ref: BoneRef,
        toolId: String,
        centerX: Double,
        centerY: Double,
        strokeExposed: Set<Int> = emptySet(),
    ): PreparationResult {
        val tool = data.tool(toolId)
        require(tool.stage == ToolStage.PREPARE) { "Werkzeug $toolId gehoert nicht zum feinen Freilegen" }
        val prep = site.preparation(ref) ?: throw NoSuchElementException("Freilegen von $ref hat nicht begonnen")
        val cells = cellsInReach(prep, tool, centerX, centerY)
        val depths = prep.depthsCopy()
        val hard = prep.hardCopy()
        var removed = 0
        val changed = mutableListOf<Int>()
        val exposed = mutableListOf<Int>()
        val hits = mutableListOf<Int>()
        for (cell in cells) {
            val before = depths[cell]
            if (before == 0) {
                if (tool.rough && prep.isBone(cell) && cell !in strokeExposed) hits += cell
                continue
            }
            if (hard[cell] && !tool.removesHard) continue
            val after = before - minOf(tool.removal, before)
            depths[cell] = after
            removed += before - after
            changed += cell
            if (after == 0) {
                hard[cell] = false
                exposed += cell
            }
        }
        val damage = if (hits.isNotEmpty()) damage(site, ref, tool) else null
        if (changed.isEmpty() && damage == null) {
            return PreparationResult(site, prep, 0, emptyList(), emptyList(), emptyList(), null, false)
        }
        val unfinished = prep.with(depths, hard, finished = false)
        val finished = prep.finished || unfinished.progress >= finishedShare
        val next = if (finished) prep.with(depths, hard, finished = true) else unfinished
        val finds = if (damage == null) {
            site.finds
        } else {
            site.finds.mapIndexed { index, placed ->
                if (index != ref.findIndex) return@mapIndexed placed
                val pieces = placed.find.pieces.map { if (it.boneId == ref.boneId) it.copy(quality = damage.qualityAfter) else it }
                placed.copy(find = placed.find.copy(pieces = pieces))
            }
        }
        val preparations = site.preparations.map { if (it.ref == ref) next else it }
        val nextSite = site.with(site.depthsCopy(), finds, preparations)
        return PreparationResult(
            site = nextSite,
            preparation = next,
            removed = removed,
            changedCells = changed,
            exposedCells = exposed,
            hitCells = hits,
            damage = damage,
            newlyFinished = finished && !prep.finished,
        )
    }

    /** Feine Zellen im Wirkbereich von [tool] um ([centerX], [centerY]), zeilenweise. */
    fun cellsInReach(prep: BonePreparation, tool: ToolDefinition, centerX: Double, centerY: Double): List<Int> {
        require(centerX.isFinite() && centerY.isFinite()) { "Ansatzpunkt muss endlich sein: ($centerX, $centerY)" }
        val r = tool.radius
        val r2 = r * r
        val minX = maxOf(0, floor(centerX - r).toInt())
        val maxX = minOf(prep.width - 1, ceil(centerX + r).toInt())
        val minY = maxOf(0, floor(centerY - r).toInt())
        val maxY = minOf(prep.height - 1, ceil(centerY + r).toInt())
        val result = mutableListOf<Int>()
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x + CELL_CENTER - centerX
                val dy = y + CELL_CENTER - centerY
                if (dx * dx + dy * dy <= r2) result += prep.cellIndex(x, y)
            }
        }
        return result
    }

    /** Schaden pro Anwendung mit Treffer nach `damage_reduction` (0 fuer nicht grobe Werkzeuge). */
    fun damagePerHit(tool: ToolDefinition): Double {
        val reduction = modifiers.additive(StatId.DAMAGE_REDUCTION).coerceIn(0.0, 1.0)
        return if (tool.rough) tool.boneDamage * (1.0 - reduction) else 0.0
    }

    /** `true`, wenn der Fortschritt von [prep] die Schwelle `finishedShare` erreicht oder [prep] schon fertig ist. */
    fun isFinished(prep: BonePreparation): Boolean = prep.finished || prep.progress >= finishedShare

    private fun damage(site: ExcavationSite, ref: BoneRef, tool: ToolDefinition): BoneDamage {
        val before = site.piece(ref).quality
        val after = (before - damagePerHit(tool)).coerceIn(Piece.MIN_QUALITY, Piece.MAX_QUALITY)
        return BoneDamage(ref, before, after)
    }

    private companion object {
        /** Mittelpunkt einer Zelle relativ zu ihrer Ecke (Geometrie, kein Spielwert). */
        const val CELL_CENTER: Double = 0.5
    }
}

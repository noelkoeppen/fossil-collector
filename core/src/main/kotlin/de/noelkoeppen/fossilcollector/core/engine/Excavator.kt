package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.ToolDefinition
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import kotlin.math.ceil
import kotlin.math.floor

/** Neuer Hinweis auf Knochen [bone] in den Zellen [cells] (Verfaerbung, Knochenspitze). */
data class BoneHint(
    val bone: BoneRef,
    val cells: List<Int>,
)

/** Qualitaetsverlust eines Knochens durch ein grobes Werkzeug. */
data class BoneDamage(
    val bone: BoneRef,
    val qualityBefore: Double,
    val qualityAfter: Double,
) {
    /** Tatsaechlich verlorene Qualitaet (nach der Begrenzung auf 0). */
    val loss: Double get() = qualityBefore - qualityAfter
}

/**
 * Ergebnis einer Anwendung eines Werkzeugs.
 *
 * @property site neue Flaeche
 * @property removedEarth insgesamt abgetragene Erdeinheiten
 * @property changedCells Zellen, deren Tiefe sich geaendert hat (zum Neuzeichnen)
 * @property newHints Knochenzellen, die mit dieser Anwendung erstmals einen Hinweis zeigen, je Knochen
 * @property damage beschaedigte Knochen (hoechstens ein Eintrag je Knochen)
 * @property newlyDiscovered Knochen, auf die man mit dieser Anwendung erstmals gestossen ist
 */
data class ToolResult(
    val site: ExcavationSite,
    val removedEarth: Int,
    val changedCells: List<Int>,
    val newHints: List<BoneHint>,
    val damage: List<BoneDamage>,
    val newlyDiscovered: List<BoneRef>,
) {
    /** `true`, wenn danach alle Knochen der Flaeche entdeckt sind (Zustand „nichts mehr zu finden“). */
    val nothingLeftToFind: Boolean get() = site.nothingLeftToFind
}

/**
 * Aktion „Werkzeug wirkt auf Bereich“ beim groben Buddeln (Kapitel 3, AP2-05). Kein Zufall, keine
 * Simulation ueber Zeit: Jede Anwendung ist eine Spieleraktion und liefert eine neue Flaeche.
 *
 * Regeln je Zelle im Wirkbereich (Mittelpunkt der Zelle hoechstens `radius` vom Ansatzpunkt):
 * - Es werden `min(removal, Tiefe)` Erdeinheiten abgetragen.
 * - Hinweis: Liegt ueber einer Knochenzelle danach hoechstens `excavation.hintDepth` Erde und vorher
 *   mehr, zeigt sie ab jetzt einen Hinweis. Weil `removal <= hintDepth` (Datenvalidierung), zeigt sich
 *   ein Hinweis immer mindestens eine Anwendung, bevor ein Werkzeug den Knochen trifft.
 * - Treffer: Ein grobes Werkzeug trifft einen Knochen, wenn es in einer seiner Zellen tiefer graebt,
 *   als Erde darueber liegt (`removal > Tiefe vorher`), also auch auf freiliegenden Knochen.
 * - Schaden: Jeder getroffene Knochen verliert pro Anwendung einmal
 *   `boneDamage * (1 - damage_reduction)` Qualitaet (Stat begrenzt auf 0..1), hoechstens bis 0. Der
 *   Knochen bleibt immer erhalten (Kapitel 5).
 * - Entdeckt: Ein Knochen ist entdeckt, sobald eine seiner Zellen freiliegt (Tiefe 0).
 *
 * @param data Spieldaten (validiert)
 * @param modifiers Modifier-System fuer `damage_reduction`
 */
class Excavator(
    private val data: GameData,
    private val modifiers: ModifierEngine = ModifierEngine.fromGameData(data),
) {
    private val hintDepth = data.balance.excavation.hintDepth

    /**
     * Wendet Werkzeug [toolId] mit Ansatzpunkt ([centerX], [centerY]) in Zellkoordinaten an. Zelle
     * (x, y) reicht von x bis x + 1 und y bis y + 1. Ansatzpunkte ausserhalb der Flaeche sind erlaubt;
     * es wirken nur Zellen im Raster.
     *
     * @throws NoSuchElementException wenn das Werkzeug unbekannt ist
     * @throws IllegalArgumentException wenn der Ansatzpunkt nicht endlich ist
     */
    fun applyTool(site: ExcavationSite, toolId: String, centerX: Double, centerY: Double): ToolResult {
        val tool = data.tool(toolId)
        val cells = cellsInReach(site, tool, centerX, centerY)
        val depths = site.depthsCopy()
        var removed = 0
        val changed = mutableListOf<Int>()
        val hints = LinkedHashMap<BoneRef, MutableList<Int>>()
        val hit = LinkedHashSet<BoneRef>()
        for (cell in cells) {
            val before = depths[cell]
            val after = before - minOf(tool.removal, before)
            val bone = site.boneAt(cell)
            if (bone != null) {
                if (before > hintDepth && after <= hintDepth) hints.getOrPut(bone) { mutableListOf() } += cell
                if (tool.rough && tool.removal > before) hit += bone
            }
            if (after != before) {
                depths[cell] = after
                removed += before - after
                changed += cell
            }
        }
        val damage = hit.map { damage(site, it, tool) }
        val finds = if (damage.isEmpty()) {
            site.finds
        } else {
            val byBone = damage.associateBy { it.bone }
            site.finds.mapIndexed { index, placed ->
                val pieces = placed.find.pieces.map { piece ->
                    byBone[BoneRef(index, piece.boneId)]?.let { piece.copy(quality = it.qualityAfter) } ?: piece
                }
                placed.copy(find = placed.find.copy(pieces = pieces))
            }
        }
        val next = if (changed.isEmpty() && damage.isEmpty()) site else site.with(depths, finds)
        val discovered = site.bones.filter { !site.isDiscovered(it) && next.isDiscovered(it) }
        return ToolResult(
            site = next,
            removedEarth = removed,
            changedCells = changed,
            newHints = hints.map { (bone, hintCells) -> BoneHint(bone, hintCells) },
            damage = damage,
            newlyDiscovered = discovered,
        )
    }

    /** Zellen im Wirkbereich von [tool] um ([centerX], [centerY]), zeilenweise. */
    fun cellsInReach(site: ExcavationSite, tool: ToolDefinition, centerX: Double, centerY: Double): List<Int> {
        require(centerX.isFinite() && centerY.isFinite()) { "Ansatzpunkt muss endlich sein: ($centerX, $centerY)" }
        val r = tool.radius
        val r2 = r * r
        val minX = maxOf(0, floor(centerX - r).toInt())
        val maxX = minOf(site.width - 1, ceil(centerX + r).toInt())
        val minY = maxOf(0, floor(centerY - r).toInt())
        val maxY = minOf(site.height - 1, ceil(centerY + r).toInt())
        val result = mutableListOf<Int>()
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val dx = x + CELL_CENTER - centerX
                val dy = y + CELL_CENTER - centerY
                if (dx * dx + dy * dy <= r2) result += site.cellIndex(x, y)
            }
        }
        return result
    }

    /** `true`, wenn Zelle [cell] einen Hinweis auf einen Knochen zeigt (noch bedeckt, aber hoechstens `hintDepth` Erde). */
    fun isHinted(site: ExcavationSite, cell: Int): Boolean =
        site.boneAt(cell) != null && site.depthAt(cell) in 1..hintDepth

    /** Alle Zellen mit Hinweis. */
    fun hintedCells(site: ExcavationSite): List<Int> = (0 until site.cellCount).filter { isHinted(site, it) }

    /** Schaden pro Anwendung fuer ein grobes Werkzeug nach `damage_reduction`. */
    fun damagePerHit(tool: ToolDefinition): Double {
        val reduction = modifiers.additive(StatId.DAMAGE_REDUCTION).coerceIn(0.0, 1.0)
        return if (tool.rough) tool.boneDamage * (1.0 - reduction) else 0.0
    }

    private fun damage(site: ExcavationSite, bone: BoneRef, tool: ToolDefinition): BoneDamage {
        val before = site.piece(bone).quality
        val after = (before - damagePerHit(tool)).coerceIn(Piece.MIN_QUALITY, Piece.MAX_QUALITY)
        return BoneDamage(bone, before, after)
    }

    private companion object {
        /** Mittelpunkt einer Zelle relativ zu ihrer Ecke (Geometrie, kein Spielwert). */
        const val CELL_CENTER: Double = 0.5
    }
}

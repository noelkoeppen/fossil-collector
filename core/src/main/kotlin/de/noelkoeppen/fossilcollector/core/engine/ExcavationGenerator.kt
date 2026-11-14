package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.SpeciesDefinition
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite
import de.noelkoeppen.fossilcollector.core.model.Find
import de.noelkoeppen.fossilcollector.core.model.PlacedFind
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.random.GameRandom
import de.noelkoeppen.fossilcollector.core.random.pickWeighted
import kotlin.math.floor

/**
 * Erzeugt eine Grabungsflaeche nach Kapitel 3 und 4 (AP2-05). Alle Werte kommen aus
 * `balance.json: excavation`, Zufall nur ueber das uebergebene [GameRandom].
 *
 * Ablauf (in dieser Reihenfolge, damit gleicher Seed dieselbe Flaeche ergibt):
 * 1. Erdtiefe jeder Zelle (zeilenweise) gleichverteilt in `[depthMin, depthMax]`.
 * 2. Anzahl Funde aus der gewichteten Tabelle `findCountWeights`.
 * 3. Pro Fund: Wuerfeln mit [FindRoller], dann Platzieren. Die Knochen werden in Breitensuche-
 *    Reihenfolge ueber den Nachbarschaftsgraph des Fundes gesetzt: der erste an eine gleichverteilt
 *    gewaehlte freie Stelle, jeder weitere an eine gleichverteilt gewaehlte freie Stelle, an der er
 *    (ueber eine Zellkante) an einen schon gesetzten Graph-Nachbarn grenzt. Zusammenhaengende Knochen
 *    liegen so beieinander. Kein Knochen ueberlappt einen anderen; Zellen verschiedener Funde haben
 *    mindestens `findSpacing` Zellen Abstand (Chebyshev). Findet ein Knochen keine Stelle, beginnt
 *    der Fund von vorn; nach `placementAttempts` Fehlversuchen wird er weggelassen.
 *
 * @param data Spieldaten (validiert)
 * @param modifiers Modifier-System, weitergereicht an den [FindRoller]
 * @param footprints Umrisse der Knochen; bis AP2-06 der Platzhalter aus den Daten
 */
class ExcavationGenerator(
    private val data: GameData,
    modifiers: ModifierEngine = ModifierEngine.fromGameData(data),
    private val footprints: BoneFootprints = PlaceholderFootprints.fromGameData(data),
) {
    private val balance = data.balance.excavation
    private val findRoller = FindRoller(data, modifiers)
    private val width = balance.width
    private val height = balance.height

    /**
     * Erzeugt eine neue Flaeche in der Schicht [layerId].
     *
     * @throws NoSuchElementException wenn die Schicht unbekannt ist
     * @throws IllegalArgumentException wenn die Schicht keine Arten hat
     */
    fun generate(layerId: String, random: GameRandom): ExcavationSite {
        data.layer(layerId)
        val depths = IntArray(width * height) { rollDepth(random) }
        val count = random.pickWeighted(balance.findCountWeights) { it.weight }.count
        // Zellen, die fuer weitere Funde gesperrt sind: belegt oder naeher als findSpacing.
        val blocked = BooleanArray(width * height)
        val placed = mutableListOf<PlacedFind>()
        repeat(count) {
            val find = findRoller.roll(layerId, random).find
            val cells = place(find, blocked, random) ?: return@repeat
            placed += PlacedFind(find, cells)
            block(cells.values.flatten(), blocked)
        }
        return ExcavationSite(layerId, width, height, depths, placed)
    }

    private fun rollDepth(random: GameRandom): Int {
        val value = floor(random.nextDouble(balance.depthMin.toDouble(), balance.depthMax + 1.0)).toInt()
        return value.coerceIn(balance.depthMin, balance.depthMax)
    }

    /** Zellen je Knochen oder `null`, wenn der Fund nach allen Versuchen nicht passt. */
    private fun place(find: Find, blocked: BooleanArray, random: GameRandom): Map<String, List<Int>>? {
        val species = data.species(find.speciesId)
        require(species.isConnected(find.boneIds)) { "Fund ${find.boneIds} haengt im Graph nicht zusammen" }
        val order = placementOrder(species, find.boneIds)
        repeat(balance.placementAttempts) {
            val result = tryPlace(species, order, blocked, random)
            if (result != null) return find.boneIds.associateWith { result.getValue(it) }
        }
        return null
    }

    private fun tryPlace(
        species: SpeciesDefinition,
        order: List<String>,
        blocked: BooleanArray,
        random: GameRandom,
    ): Map<String, List<Int>>? {
        val occupied = BooleanArray(width * height)
        val result = LinkedHashMap<String, List<Int>>()
        for (boneId in order) {
            val footprint = footprints.footprint(species.id, boneId)
            val neighborCells = species.neighborsOf(boneId).filter { it in result }.flatMap { result.getValue(it) }
            val candidates = candidates(footprint, neighborCells, blocked, occupied)
            if (candidates.isEmpty()) return null
            val cells = candidates[random.nextInt(candidates.size)]
            cells.forEach { occupied[it] = true }
            result[boneId] = cells
        }
        return result
    }

    /**
     * Alle gueltigen Lagen von [footprint] (als Zellisten, Anker zeilenweise). Ist [neighborCells]
     * nicht leer, muss die Lage an mindestens eine dieser Zellen ueber eine Kante grenzen.
     */
    private fun candidates(
        footprint: BoneFootprint,
        neighborCells: List<Int>,
        blocked: BooleanArray,
        occupied: BooleanArray,
    ): List<List<Int>> {
        val maxAx = width - footprint.width
        val maxAy = height - footprint.height
        if (maxAx < 0 || maxAy < 0) return emptyList()
        var xRange = 0..maxAx
        var yRange = 0..maxAy
        val isNeighbor = BooleanArray(width * height)
        if (neighborCells.isNotEmpty()) {
            neighborCells.forEach { isNeighbor[it] = true }
            val xs = neighborCells.map { it % width }
            val ys = neighborCells.map { it / width }
            xRange = maxOf(0, xs.min() - footprint.width)..minOf(maxAx, xs.max() + 1)
            yRange = maxOf(0, ys.min() - footprint.height)..minOf(maxAy, ys.max() + 1)
        }
        val result = mutableListOf<List<Int>>()
        for (ay in yRange) {
            for (ax in xRange) {
                val cells = footprint.cells.map { (ay + it.dy) * width + ax + it.dx }
                if (cells.any { blocked[it] || occupied[it] }) continue
                if (neighborCells.isNotEmpty() && cells.none { touches(it, isNeighbor) }) continue
                result += cells
            }
        }
        return result
    }

    /** `true`, wenn eine Kanten-Nachbarzelle von [cell] markiert ist. */
    private fun touches(cell: Int, marked: BooleanArray): Boolean {
        val x = cell % width
        val y = cell / width
        return (x > 0 && marked[cell - 1]) ||
            (x < width - 1 && marked[cell + 1]) ||
            (y > 0 && marked[cell - width]) ||
            (y < height - 1 && marked[cell + width])
    }

    /** Sperrt [cells] und alle Zellen im Abstand bis `findSpacing` fuer weitere Funde. */
    private fun block(cells: List<Int>, blocked: BooleanArray) {
        val spacing = balance.findSpacing
        cells.forEach { cell ->
            val cx = cell % width
            val cy = cell / width
            for (y in maxOf(0, cy - spacing)..minOf(height - 1, cy + spacing)) {
                for (x in maxOf(0, cx - spacing)..minOf(width - 1, cx + spacing)) blocked[y * width + x] = true
            }
        }
    }

    /** Breitensuche ab dem ersten Knochen des Fundes, nur ueber Knochen des Fundes. */
    private fun placementOrder(species: SpeciesDefinition, boneIds: List<String>): List<String> {
        val wanted = boneIds.toSet()
        val order = mutableListOf(boneIds.first())
        val seen = mutableSetOf(boneIds.first())
        var i = 0
        while (i < order.size) {
            species.neighborsOf(order[i++]).filter { it in wanted && seen.add(it) }.forEach { order += it }
        }
        return order
    }
}

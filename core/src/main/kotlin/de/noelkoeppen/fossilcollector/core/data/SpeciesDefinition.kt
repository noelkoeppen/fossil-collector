package de.noelkoeppen.fossilcollector.core.data

import kotlinx.serialization.Serializable

/**
 * Eine Art aus `species.json` (Kapitel 4).
 *
 * [bones] und [neighbors] werden nicht von Hand gepflegt, sondern mit `tools/models3d/neighbors.py`
 * aus dem 3D-Modell der Art (`art/models3d/<id>/`) abgeleitet. Eine neue Art braucht deshalb keinen
 * Code, nur Modellordner und Daten (Kapitel 0, Regel 7).
 */
@Serializable
data class SpeciesDefinition(
    val id: String,
    /** ID der Schicht, in der die Art gefunden wird. */
    val layer: String,
    /** Gewicht bei der Artenwahl innerhalb der Schicht. */
    val rarityWeight: Double,
    /** Einzelknochen des Skeletts in Modellreihenfolge; zu jedem gibt es `bones/<id>.glb` im Modellordner. */
    val bones: List<String>,
    /** Nachbarschaftsgraph: ungerichtete Kanten zwischen anatomisch benachbarten Knochen. */
    val neighbors: List<List<String>>,
) {
    /** Nachbarn je Knochen-ID (jede Kante in beide Richtungen); Knochen ohne Kante haben eine leere Menge. */
    val adjacency: Map<String, Set<String>> by lazy {
        val map = bones.associateWith { linkedSetOf<String>() }
        neighbors.filter { it.size == 2 }.forEach { (a, b) ->
            map[a]?.add(b)
            map[b]?.add(a)
        }
        map
    }

    /** Nachbarn eines Knochens; leer bei unbekannter ID. */
    fun neighborsOf(boneId: String): Set<String> = adjacency[boneId].orEmpty()

    /**
     * `true`, wenn [boneIds] nicht leer ist, nur Knochen dieser Art enthaelt und diese Knochen
     * innerhalb des Graphen (nur ueber Knochen aus [boneIds]) zusammenhaengen.
     */
    fun isConnected(boneIds: Collection<String>): Boolean {
        val wanted = boneIds.toSet()
        if (wanted.isEmpty() || !adjacency.keys.containsAll(wanted)) return false
        val start = wanted.first()
        val seen = mutableSetOf(start)
        val stack = ArrayDeque(listOf(start))
        while (stack.isNotEmpty()) {
            neighborsOf(stack.removeLast()).filter { it in wanted && seen.add(it) }.forEach { stack.addLast(it) }
        }
        return seen.size == wanted.size
    }
}

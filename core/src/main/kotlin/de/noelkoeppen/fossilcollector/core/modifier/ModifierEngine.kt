package de.noelkoeppen.fossilcollector.core.modifier

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.StatLimit

/**
 * Verrechnet alle aktiven Modifier zu Stat-Werten.
 *
 * Formel fuer einen Stat mit Basiswert `base`:
 * ```
 * final(stat) = clamp( (base + Σ add) * Π_über Kategorien (1 + Σ mult in dieser Kategorie) )
 * ```
 * Innerhalb einer Kategorie werden `mult`-Werte addiert, zwischen den Kategorien multipliziert.
 * Kategorien ohne `mult`-Modifier fuer den Stat tragen den Faktor 1 bei. Die Clamps aus
 * `balance.statLimits` gelten fuer den Endwert.
 *
 * Die Engine kennt den Spielzustand nicht. Sie verwaltet benannte **Quellen**: Jede Quelle hat eine
 * frei waehlbare ID und eine Liste von Modifiern. Spielsysteme (z. B. Upgrades) setzen ihre Quellen
 * mit [setSource] und entfernen sie mit [removeSource].
 * Empfohlene ID-Konvention: `<art>:<definitionsId>`, z. B. `upgrade:trowel`.
 *
 * Basiswerte kommen vom Aufrufer (aus den JSON-Daten); die Engine enthaelt
 * keine Spielwerte. Fuer Faktor-Stats (Basis 1.0) gibt es [factor], fuer rein additive Stats
 * (Basis 0.0) [additive].
 *
 * Cache: Ergebnisse werden pro Paar (Stat, Basiswert) gespeichert und bei jeder Aenderung der
 * Quellen ([setSource], [removeSource], [clearSources]) vollstaendig verworfen.
 *
 * Fehler: Ist das Ergebnis vor dem Clamp `NaN` oder unendlich, wirft die Engine eine
 * [IllegalStateException]. Ein solches Ergebnis wird nicht gecacht.
 *
 * Die Klasse ist **nicht thread-safe**. Sie ist fuer die Nutzung aus einem einzigen Thread
 * (Spiel-Thread) gedacht; bei Zugriffen aus mehreren Threads muss der Aufrufer synchronisieren.
 *
 * @param statLimits Clamps je Stat, in der Regel `GameData.balance.statLimits` (siehe [fromGameData]).
 */
class ModifierEngine(
    private val statLimits: Map<StatId, StatLimit> = emptyMap(),
) {
    private val sources = LinkedHashMap<String, List<Modifier>>()
    private val cache = HashMap<CacheKey, Double>()

    /**
     * Zaehler fuer Aenderungen der Quellen: steigt bei jedem [setSource], jedem erfolgreichen
     * [removeSource] und jedem [clearSources], das Quellen entfernt. Erlaubt Aufrufern, einen
     * Abgleich zu ueberspringen, wenn sich weder Spielstand noch Quellen
     * geaendert haben. Kein Spielwert.
     */
    var revision: Long = 0L
        private set

    /** IDs aller aktuell gesetzten Quellen in Einfuegereihenfolge. */
    val sourceIds: Set<String>
        get() = sources.keys.toSet()

    /**
     * Setzt die Quelle [id] auf [modifiers] oder ersetzt sie vollstaendig. Eine leere Liste ist
     * erlaubt (die Quelle existiert dann, wirkt aber nicht). Die Liste wird kopiert.
     */
    fun setSource(id: String, modifiers: List<Modifier>) {
        sources[id] = modifiers.toList()
        cache.clear()
        revision++
    }

    /** Entfernt die Quelle [id]. Gibt `true` zurueck, wenn sie vorhanden war. */
    fun removeSource(id: String): Boolean {
        val removed = sources.remove(id) != null
        if (removed) {
            cache.clear()
            revision++
        }
        return removed
    }

    /** Entfernt alle Quellen (z. B. beim Neuaufbau nach dem Laden). */
    fun clearSources() {
        if (sources.isNotEmpty()) {
            sources.clear()
            cache.clear()
            revision++
        }
    }

    /** Modifier der Quelle [id] oder `null`, wenn es sie nicht gibt. */
    fun source(id: String): List<Modifier>? = sources[id]

    /** Alle aktiven Modifier fuer [stat] ueber alle Quellen. */
    fun modifiers(stat: StatId): List<Modifier> =
        sources.values.flatMap { list -> list.filter { it.stat == stat } }

    /**
     * Endwert von [stat] fuer den Basiswert [base] nach Formel, Clamps und Cache.
     *
     * @throws IllegalStateException wenn das Ergebnis `NaN` oder unendlich ist.
     */
    fun value(stat: StatId, base: Double): Double {
        val key = CacheKey(stat, base)
        cache[key]?.let { return it }
        val result = compute(stat, base, modifiers(stat), statLimits[stat])
        cache[key] = result
        return result
    }

    /** Endwert eines Faktor-Stats mit Basis 1.0 (z. B. `sell_price`). */
    fun factor(stat: StatId): Double = value(stat, FACTOR_BASE)

    /**
     * Endwert eines additiven Stats mit Basis 0.0 (z. B. `damage_reduction`).
     * Hinweis: Bei Basis 0.0 und ohne `add`-Modifier ergeben `mult`-Modifier immer 0.
     */
    fun additive(stat: StatId): Double = value(stat, ADDITIVE_BASE)

    private data class CacheKey(val stat: StatId, val base: Double)

    companion object {
        /** Basiswert der Faktor-Stats. */
        const val FACTOR_BASE: Double = 1.0

        /** Basiswert rein additiver Stats. */
        const val ADDITIVE_BASE: Double = 0.0

        /** Engine mit den Clamps aus `balance.statLimits`. */
        fun fromGameData(data: GameData): ModifierEngine = ModifierEngine(data.balance.statLimits)

        /**
         * Reine Berechnung ohne Quellen und Cache. Modifier fuer andere Stats als [stat] werden
         * ignoriert.
         *
         * @throws IllegalStateException wenn das Ergebnis vor dem Clamp `NaN` oder unendlich ist.
         */
        fun compute(stat: StatId, base: Double, modifiers: List<Modifier>, limit: StatLimit?): Double {
            var addSum = 0.0
            val multSums = HashMap<ModifierCategory, Double>()
            for (m in modifiers) {
                if (m.stat != stat) continue
                when (m.op) {
                    ModifierOp.ADD -> addSum += m.value
                    ModifierOp.MULT -> multSums[m.category] = (multSums[m.category] ?: 0.0) + m.value
                }
            }
            var result = base + addSum
            for (sum in multSums.values) result *= 1.0 + sum
            check(result.isFinite()) {
                "Stat ${stat.serialName} ergibt $result (Basis $base, ${modifiers.size} Modifier)"
            }
            return applyLimit(result, limit)
        }

        private fun applyLimit(value: Double, limit: StatLimit?): Double {
            if (limit == null) return value
            var v = value
            limit.min?.let { if (v < it) v = it }
            limit.max?.let { if (v > it) v = it }
            return v
        }
    }
}

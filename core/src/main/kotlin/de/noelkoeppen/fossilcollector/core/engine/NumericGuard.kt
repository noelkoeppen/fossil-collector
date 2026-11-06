package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Ein Wert im Spielstand (oder das `dt` eines Ticks) ist `NaN` oder unendlich (Kapitel 6.6).
 *
 * @property path Fundstelle, z. B. `run.blocks[quaternary]` oder `dt`
 * @property value der fehlerhafte Wert
 */
class NonFiniteValueException(
    val path: String,
    val value: Double,
) : IllegalStateException("Nicht endlicher Wert in $path: $value")

/**
 * Reaktion auf nicht endliche Werte in [GameEngine.advance] (Kapitel 6.6: "als Fehler geloggt,
 * Debug: Exception").
 *
 * Verhalten von `advance`: Der Handler wird aufgerufen. Wirft er (Standard [THROW]), bricht
 * `advance` mit dieser Exception ab. Kehrt er zurueck (z. B. ein loggender Handler im Release-Build),
 * wird der Tick **verworfen**: `advance` gibt den unveraenderten Eingabe-Zustand ohne Ereignisse
 * zurueck. So gelangt ein `NaN` nie in den Spielstand bzw. Speicherstand.
 */
fun interface NumericErrorHandler {
    fun onNonFinite(error: NonFiniteValueException)

    companion object {
        /** Standard (Tests, Debug): wirft die Exception weiter. */
        val THROW: NumericErrorHandler = NumericErrorHandler { throw it }
    }
}

/** Sucht nicht endliche Zahlen im Spielstand. */
object StateValidator {

    /**
     * Erste nicht endliche Zahl in [state] als [NonFiniteValueException] (ohne sie zu werfen) oder
     * `null`, wenn alle Zahlen endlich sind. Geprueft werden alle [GameNumber]-Felder sowie die
     * `Double`-Werte (Qualitaeten, Restdauern) in Meta- und Run-Zustand.
     */
    fun findNonFinite(state: GameState): NonFiniteValueException? {
        val meta = state.meta
        val run = state.run
        number(meta.fame) { "meta.fame" }?.let { return it }
        number(meta.fameTotal) { "meta.fameTotal" }?.let { return it }
        number(meta.prestigeCount) { "meta.prestigeCount" }?.let { return it }
        number(meta.fameSpent) { "meta.fameSpent" }?.let { return it }
        number(meta.stats.piecesFound) { "meta.stats.piecesFound" }?.let { return it }
        number(meta.stats.pristineFound) { "meta.stats.pristineFound" }?.let { return it }
        number(meta.stats.researchCompleted) { "meta.stats.researchCompleted" }?.let { return it }
        number(meta.stats.reconstructions) { "meta.stats.reconstructions" }?.let { return it }
        for ((key, entry) in meta.book) {
            double(entry.quality) { "meta.book[$key].quality" }?.let { return it }
            doubles(entry.zones) { "meta.book[$key].zones" }?.let { return it }
        }

        number(run.money) { "run.money" }?.let { return it }
        number(run.researchPoints) { "run.researchPoints" }?.let { return it }
        number(run.unlockedLayers) { "run.unlockedLayers" }?.let { return it }
        for ((id, v) in run.teams) number(v) { "run.teams[$id]" }?.let { return it }
        number(run.preparators) { "run.preparators" }?.let { return it }
        number(run.halls) { "run.halls" }?.let { return it }
        number(run.labSlots) { "run.labSlots" }?.let { return it }
        number(run.toolLevel) { "run.toolLevel" }?.let { return it }
        for ((id, v) in run.blocks) number(v) { "run.blocks[$id]" }?.let { return it }
        number(run.prepProgress) { "run.prepProgress" }?.let { return it }
        for ((key, piece) in run.pieces) doubles(piece.zones) { "run.pieces[$key].zones" }?.let { return it }
        for ((key, v) in run.spareZones) double(v) { "run.spareZones[$key]" }?.let { return it }
        for ((i, q) in run.labQueue.withIndex()) doubles(q.zones) { "run.labQueue[$i].zones" }?.let { return it }
        for ((i, job) in run.labJobs.withIndex()) {
            doubles(job.zones) { "run.labJobs[$i].zones" }?.let { return it }
            double(job.remaining) { "run.labJobs[$i].remaining" }?.let { return it }
            job.duration?.let { d -> double(d) { "run.labJobs[$i].duration" }?.let { return it } }
        }
        return null
    }

    // Der Pfad wird nur im Fehlerfall gebaut (AP-14: die Pruefung laeuft offline in jedem Schritt).

    private inline fun number(value: GameNumber, path: () -> String): NonFiniteValueException? =
        if (value.isFinite) null else NonFiniteValueException(path(), value.toDouble())

    private inline fun double(value: Double, path: () -> String): NonFiniteValueException? =
        if (value.isFinite()) null else NonFiniteValueException(path(), value)

    private inline fun doubles(values: List<Double>, path: () -> String): NonFiniteValueException? {
        for (i in values.indices) {
            val v = values[i]
            if (!v.isFinite()) return NonFiniteValueException("${path()}[$i]", v)
        }
        return null
    }
}

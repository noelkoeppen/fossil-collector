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
        number("meta.fame", meta.fame)?.let { return it }
        number("meta.fameTotal", meta.fameTotal)?.let { return it }
        number("meta.prestigeCount", meta.prestigeCount)?.let { return it }
        number("meta.stats.piecesFound", meta.stats.piecesFound)?.let { return it }
        number("meta.stats.pristineFound", meta.stats.pristineFound)?.let { return it }
        number("meta.stats.researchCompleted", meta.stats.researchCompleted)?.let { return it }
        number("meta.stats.reconstructions", meta.stats.reconstructions)?.let { return it }
        for ((key, entry) in meta.book) {
            double("meta.book[$key].quality", entry.quality)?.let { return it }
            doubles("meta.book[$key].zones", entry.zones)?.let { return it }
        }

        number("run.money", run.money)?.let { return it }
        number("run.researchPoints", run.researchPoints)?.let { return it }
        number("run.unlockedLayers", run.unlockedLayers)?.let { return it }
        for ((id, v) in run.teams) number("run.teams[$id]", v)?.let { return it }
        number("run.preparators", run.preparators)?.let { return it }
        number("run.halls", run.halls)?.let { return it }
        number("run.labSlots", run.labSlots)?.let { return it }
        number("run.toolLevel", run.toolLevel)?.let { return it }
        for ((id, v) in run.blocks) number("run.blocks[$id]", v)?.let { return it }
        number("run.prepProgress", run.prepProgress)?.let { return it }
        for ((key, piece) in run.pieces) doubles("run.pieces[$key].zones", piece.zones)?.let { return it }
        for ((key, v) in run.spareZones) double("run.spareZones[$key]", v)?.let { return it }
        for ((i, q) in run.labQueue.withIndex()) doubles("run.labQueue[$i].zones", q.zones)?.let { return it }
        for ((i, job) in run.labJobs.withIndex()) {
            doubles("run.labJobs[$i].zones", job.zones)?.let { return it }
            double("run.labJobs[$i].remaining", job.remaining)?.let { return it }
        }
        return null
    }

    private fun number(path: String, value: GameNumber): NonFiniteValueException? =
        if (value.isFinite) null else NonFiniteValueException(path, value.toDouble())

    private fun double(path: String, value: Double): NonFiniteValueException? =
        if (value.isFinite()) null else NonFiniteValueException(path, value)

    private fun doubles(path: String, values: List<Double>): NonFiniteValueException? {
        for ((i, v) in values.withIndex()) double("$path[$i]", v)?.let { return it }
        return null
    }
}

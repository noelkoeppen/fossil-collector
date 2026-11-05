package de.noelkoeppen.fossilcollector.platform

/**
 * Zeitquelle der App (Kapitel 10.2, `platform`). Austauschbar fuer Tests.
 */
interface TimeSource {
    /** Wanduhr in Millisekunden seit 1970 (UTC), z. B. fuer `GameState.lastSavedAt`. */
    fun currentTimeMillis(): Long

    /** Monotone Zeit in Nanosekunden fuer Intervalle (Spielschleife, Autosave). */
    fun monotonicNanos(): Long

    /** Systemuhr. */
    object System : TimeSource {
        override fun currentTimeMillis(): Long = java.lang.System.currentTimeMillis()

        override fun monotonicNanos(): Long = java.lang.System.nanoTime()
    }
}

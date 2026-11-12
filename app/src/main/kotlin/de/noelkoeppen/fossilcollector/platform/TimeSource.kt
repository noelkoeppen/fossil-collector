package de.noelkoeppen.fossilcollector.platform

/**
 * Zeitquelle der App (`platform`). Austauschbar fuer Tests.
 */
interface TimeSource {
    /** Wanduhr in Millisekunden seit 1970 (UTC), z. B. fuer `GameState.lastSavedAt`. */
    fun currentTimeMillis(): Long

    /** Systemuhr. */
    object System : TimeSource {
        override fun currentTimeMillis(): Long = java.lang.System.currentTimeMillis()
    }
}

package de.noelkoeppen.fossilcollector.core.model

import kotlinx.serialization.Serializable

/**
 * Spielstand (Datenmodell 2.0, AP2-03).
 *
 * Spielstaende vor Version [OLDEST_SUPPORTED_SAVE_VERSION] (Incremental-Stand und Uebergangsstand
 * nach AP2-02) werden nicht migriert, sondern verworfen; uebernommen wird nur die Spracheinstellung
 * (siehe `SaveCodec` und `SaveException.ObsoleteVersion`). Weitere Felder (Sammelbuch, Geld,
 * Ansehen, Grabungsflaeche ...) kommen mit den jeweiligen Arbeitspaketen dazu.
 *
 * Unveraenderlich: Aenderungen erzeugen einen neuen Zustand.
 *
 * @property saveVersion Version des Speicherformats, siehe [CURRENT_SAVE_VERSION]
 * @property lastSavedAt Zeitpunkt des letzten Speicherns in Millisekunden seit 1970 (UTC);
 *   Zeitstempel, kein Spielwert
 * @property settings Einstellungen
 * @property inventory geborgene Knochen, die noch nicht am Skelett zugeordnet sind (gefuellt ab AP2-09)
 */
@Serializable
data class GameState(
    val saveVersion: Int = CURRENT_SAVE_VERSION,
    val lastSavedAt: Long,
    val settings: Settings = Settings(),
    val inventory: List<Piece> = emptyList(),
) {
    companion object {
        /** Aktuelle Version des Speicherformats. Bei jeder Formataenderung erhoehen und Migration ergaenzen. */
        const val CURRENT_SAVE_VERSION: Int = 3

        /**
         * Aelteste Version, die noch geladen (und bei Bedarf migriert) wird. Aeltere Spielstaende
         * stammen aus dem Incremental-Design und werden verworfen (Kapitel 12, AP2-03).
         */
        const val OLDEST_SUPPORTED_SAVE_VERSION: Int = 3

        /**
         * Neuer Spielstand (Startzustand).
         *
         * @param now aktueller Zeitpunkt in Millisekunden, wird als [lastSavedAt] gesetzt
         * @param settings Einstellungen, z. B. aus einem verworfenen alten Spielstand
         */
        fun newGame(now: Long, settings: Settings = Settings()): GameState =
            GameState(
                saveVersion = CURRENT_SAVE_VERSION,
                lastSavedAt = now,
                settings = settings,
                inventory = emptyList(),
            )
    }
}

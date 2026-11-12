package de.noelkoeppen.fossilcollector.core.model

import kotlinx.serialization.Serializable

/**
 * Spielstand.
 *
 * Uebergangsstand nach AP2-02: Die Idle-Systeme sind entfernt, das neue Datenmodell (Funde,
 * Sammelbuch, Geld und Ansehen) kommt mit AP2-03 (`saveVersion` 3). Bis dahin enthaelt der
 * Spielstand nur, was Speichern und Einstellungen brauchen. Aeltere Spielstaende der Version 1
 * laden weiter; ihre Felder `meta` und `run` werden beim Laden ignoriert (siehe `SaveCodec`).
 *
 * Unveraenderlich: Aenderungen erzeugen einen neuen Zustand.
 *
 * @property saveVersion Version des Speicherformats, siehe [CURRENT_SAVE_VERSION]
 * @property lastSavedAt Zeitpunkt des letzten Speicherns in Millisekunden seit 1970 (UTC);
 *   Zeitstempel, kein Spielwert
 * @property settings Einstellungen
 */
@Serializable
data class GameState(
    val saveVersion: Int = CURRENT_SAVE_VERSION,
    val lastSavedAt: Long,
    val settings: Settings = Settings(),
) {
    companion object {
        /** Aktuelle Version des Speicherformats. Bei jeder Formataenderung erhoehen und Migration ergaenzen. */
        const val CURRENT_SAVE_VERSION: Int = 1

        /**
         * Neuer Spielstand (Startzustand).
         *
         * @param now aktueller Zeitpunkt in Millisekunden, wird als [lastSavedAt] gesetzt
         */
        fun newGame(now: Long): GameState =
            GameState(
                saveVersion = CURRENT_SAVE_VERSION,
                lastSavedAt = now,
                settings = Settings(),
            )
    }
}

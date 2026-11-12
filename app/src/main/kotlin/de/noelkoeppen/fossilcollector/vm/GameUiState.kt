package de.noelkoeppen.fossilcollector.vm

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.data.QualityTierDefinition
import de.noelkoeppen.fossilcollector.core.data.TextProvider
import de.noelkoeppen.fossilcollector.core.model.Settings

/**
 * Unveraenderlicher UI-Zustand, veroeffentlicht von [GameViewModel.uiState].
 * Enthaelt Rohwerte; die Formatierung (Sprache) uebernimmt die UI.
 */
@Immutable
sealed interface GameUiState {

    /** Spieldaten und Spielstand werden geladen. */
    data object Loading : GameUiState

    /** Die Spieldaten sind ungueltig; das Spiel kann nicht starten (Programmfehler, kein Spielstand-Problem). */
    data object DataError : GameUiState

    /**
     * Geladenes Spiel. Nach AP2-02 noch ohne Spielinhalt (leerer Hauptbildschirm).
     *
     * @property settings Einstellungen des Spielstands
     * @property qualityTiers Qualitaetsstufen aus den Spieldaten (fuer die Qualitaetsfarben)
     * @property texts Inhaltstexte (Standardsprache); die UI waehlt die Sprache per `TextProvider.withLanguage`
     */
    @Immutable
    data class Ready(
        val settings: Settings,
        val qualityTiers: List<QualityTierDefinition>,
        val texts: TextProvider = TextProvider.EMPTY,
    ) : GameUiState
}

/** Einmalige Hinweise an die UI (z. B. Snackbar), siehe [GameViewModel.messages]. */
sealed interface GameMessage {

    /** Die Hauptdatei war unlesbar oder fehlte; es wurde das Backup `save.bak<backupIndex>` geladen. */
    data class LoadedFromBackup(val backupIndex: Int) : GameMessage

    /** Kein Spielstand war lesbar; es wurde ein neues Spiel gestartet. */
    data object SaveUnreadableNewGame : GameMessage

    /** Speichern ist fehlgeschlagen (wird beim naechsten Speichern erneut versucht). */
    data object SaveFailed : GameMessage

    /** Import uebernommen und gespeichert. */
    data object ImportSucceeded : GameMessage

    /** Import abgelehnt; der Spielstand ist unveraendert. */
    data class ImportFailed(val error: ImportError) : GameMessage

    /** Spielstand geloescht und neues Spiel gestartet. */
    data object SaveDeleted : GameMessage

    /** Neues Spiel gestartet, aber alte Spielstand-Dateien konnten nicht alle geloescht werden. */
    data object SaveDeletedIncomplete : GameMessage
}

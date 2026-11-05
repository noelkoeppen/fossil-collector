package de.noelkoeppen.fossilcollector.vm

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.data.QualityTierDefinition
import de.noelkoeppen.fossilcollector.core.data.TextProvider
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.ui.museum.MuseumUiState

/**
 * Unveraenderlicher UI-Zustand, veroeffentlicht von [GameViewModel.uiState] (Kapitel 10.3).
 * Enthaelt Rohwerte; die Formatierung (Sprache, Zahlenformat) uebernimmt die UI.
 */
@Immutable
sealed interface GameUiState {

    /** Spieldaten und Spielstand werden geladen. */
    data object Loading : GameUiState

    /** Die Spieldaten sind ungueltig; das Spiel kann nicht starten (Programmfehler, kein Spielstand-Problem). */
    data object DataError : GameUiState

    /**
     * Laufendes Spiel.
     *
     * @property money Geld
     * @property moneyPerSecond geglaettete Geldaenderung pro Sekunde durch die Zeit ([RateTracker])
     * @property researchPoints Forschungspunkte
     * @property fame unverteilter Ruhm
     * @property visibleTabs sichtbare Tabs in Anzeigereihenfolge ([TabUnlockRules])
     * @property settings Einstellungen des Spielstands (Sprache, Zahlenformat)
     * @property qualityTiers Qualitaetsstufen aus den Spieldaten (fuer die Qualitaetsfarben)
     * @property texts Inhaltstexte (Standardsprache); die UI waehlt die Sprache per `TextProvider.withLanguage`
     * @property dig Ausschnitt fuer den Screen Grabung (AP-17)
     * @property museum Ausschnitt fuer den Screen Museum (AP-18, [de.noelkoeppen.fossilcollector.ui.museum.MuseumUiMapper])
     */
    @Immutable
    data class Ready(
        val money: GameNumber,
        val moneyPerSecond: GameNumber,
        val researchPoints: GameNumber,
        val fame: GameNumber,
        val visibleTabs: List<GameTab>,
        val settings: Settings,
        val qualityTiers: List<QualityTierDefinition>,
        val texts: TextProvider = TextProvider.EMPTY,
        val dig: DigUiState = DigUiState.EMPTY,
        val museum: MuseumUiState = MuseumUiState.EMPTY,
    ) : GameUiState
}

/** Einmalige Hinweise an die UI (z. B. Snackbar), siehe [GameViewModel.messages]. */
sealed interface GameMessage {

    /** Die Hauptdatei war unlesbar oder fehlte; es wurde das Backup `save.bak<backupIndex>` geladen. */
    data class LoadedFromBackup(val backupIndex: Int) : GameMessage

    /** Kein Spielstand war lesbar; es wurde ein neues Spiel gestartet. */
    data object SaveUnreadableNewGame : GameMessage

    /** Speichern ist fehlgeschlagen (wird beim naechsten Autosave erneut versucht). */
    data object SaveFailed : GameMessage
}

package de.noelkoeppen.fossilcollector.vm

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.data.QualityTierDefinition
import de.noelkoeppen.fossilcollector.core.data.TextProvider
import de.noelkoeppen.fossilcollector.core.engine.PrestigeFailure
import de.noelkoeppen.fossilcollector.core.engine.ReconstructionFailure
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.ui.book.BookUiState
import de.noelkoeppen.fossilcollector.ui.fame.FameUiState
import de.noelkoeppen.fossilcollector.ui.lab.LabUiState
import de.noelkoeppen.fossilcollector.ui.museum.MuseumUiState
import de.noelkoeppen.fossilcollector.ui.welcome.WelcomeUiState

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
     * @property lab Ausschnitt fuer den Screen Labor (AP-19, [de.noelkoeppen.fossilcollector.ui.lab.LabUiMapper])
     * @property book Ausschnitt fuer den Screen Sammelbuch (AP-20, [de.noelkoeppen.fossilcollector.ui.book.BookUiMapper])
     * @property fameScreen Ausschnitt fuer den Screen Ruhm (AP-22, [de.noelkoeppen.fossilcollector.ui.fame.FameUiMapper]);
     *   heisst nicht `fame`, weil [fame] schon der Ruhm der oberen Leiste ist
     * @property welcome Willkommen-zurueck-Dialog (AP-23, [de.noelkoeppen.fossilcollector.ui.welcome.WelcomeUiMapper]);
     *   `null` = kein Dialog
     * @property badges Badges der Tabs (AP-25, [TabBadges]); Tabs ohne Badge fehlen
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
        val lab: LabUiState = LabUiState.EMPTY,
        val book: BookUiState = BookUiState.EMPTY,
        val fameScreen: FameUiState = FameUiState.EMPTY,
        val welcome: WelcomeUiState? = null,
        val badges: Map<GameTab, TabBadge> = emptyMap(),
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

    /** Eine Rekonstruktion wurde abgelehnt (AP-19); der Spielstand ist unveraendert. */
    data class ReconstructionFailed(val reason: ReconstructionFailure) : GameMessage

    /** Prestige ausgefuehrt (AP-22): neuer Durchlauf, [fameGained] Ruhm gutgeschrieben. */
    data class Prestiged(val fameGained: GameNumber) : GameMessage

    /** Prestige abgelehnt (AP-22); der Spielstand ist unveraendert. */
    data class PrestigeFailed(val reason: PrestigeFailure) : GameMessage

    /** Import uebernommen (AP-24) und gespeichert. */
    data object ImportSucceeded : GameMessage

    /** Import abgelehnt (AP-24); der Spielstand ist unveraendert. */
    data class ImportFailed(val error: ImportError) : GameMessage

    /** Spielstand geloescht und neues Spiel gestartet (AP-24). */
    data object SaveDeleted : GameMessage

    /** Neues Spiel gestartet, aber alte Spielstand-Dateien konnten nicht alle geloescht werden (AP-24). */
    data object SaveDeletedIncomplete : GameMessage

    /**
     * Meilenstein erreicht (AP-25, Kapitel 5.2): Toast mit Name und Belohnung (bereits gutgeschrieben).
     *
     * @property milestoneId ID aus `milestones.json`
     */
    data class MilestoneReached(
        val milestoneId: String,
        val money: GameNumber,
        val researchPoints: GameNumber,
    ) : GameMessage

    /** Der Tab [tab] ist zum ersten Mal erschienen (AP-25, Kapitel 8.2): ein Satz Hinweistext. */
    data class TabHint(val tab: GameTab) : GameMessage
}

package de.noelkoeppen.fossilcollector.ui.settings

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.number.NumberNotation
import de.noelkoeppen.fossilcollector.vm.GameViewModel
import de.noelkoeppen.fossilcollector.vm.ImportError
import de.noelkoeppen.fossilcollector.vm.SaveExportResult
import de.noelkoeppen.fossilcollector.vm.SaveImportResult

/**
 * Aktionen des Einstellungs-Screens (AP-24). Die Composables kennen nur diese Callbacks; die
 * Anbindung an das ViewModel macht [settingsActions].
 *
 * @property exportSave liefert den Spielstand als Base64-Text; das Kopieren macht die UI
 * @property checkImport prueft einen Import-Text, ohne ihn zu uebernehmen (Fehleranzeige vor der Bestaetigung)
 * @property onImport uebernimmt einen Import-Text (nach der Bestaetigung); Ergebnis als Hinweis
 * @property onDeleteSave loescht den Spielstand (nach doppelter Bestaetigung) und startet ein neues Spiel
 */
@Immutable
data class SettingsActions(
    val onSetLanguage: (LanguageSetting) -> Unit,
    val onSetNumberFormat: (NumberNotation) -> Unit,
    val exportSave: suspend () -> SaveExportResult,
    val checkImport: suspend (text: String) -> SaveImportResult,
    val onImport: (text: String) -> Unit,
    val onDeleteSave: () -> Unit,
) {
    companion object {
        /** Ohne Wirkung (Vorschauen). */
        val NONE = SettingsActions(
            onSetLanguage = {},
            onSetNumberFormat = {},
            exportSave = { SaveExportResult.Failure() },
            checkImport = { SaveImportResult.Failure(ImportError.EMPTY) },
            onImport = {},
            onDeleteSave = {},
        )
    }
}

/** Aktionen ueber das [GameViewModel] (Spiel-Thread, Speichern und Hinweise dort). */
fun settingsActions(viewModel: GameViewModel): SettingsActions = SettingsActions(
    onSetLanguage = viewModel::setLanguage,
    onSetNumberFormat = viewModel::setNumberFormat,
    exportSave = viewModel::exportSave,
    checkImport = viewModel::checkImport,
    onImport = viewModel::importSave,
    onDeleteSave = viewModel::deleteSave,
)

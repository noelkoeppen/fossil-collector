package de.noelkoeppen.fossilcollector.ui.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.noelkoeppen.fossilcollector.R
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.number.NumberFormatter
import de.noelkoeppen.fossilcollector.core.number.NumberNotation
import de.noelkoeppen.fossilcollector.ui.rememberGameNumberFormat
import de.noelkoeppen.fossilcollector.ui.theme.FossilCollectorTheme
import de.noelkoeppen.fossilcollector.vm.ImportError
import de.noelkoeppen.fossilcollector.vm.SaveExportResult
import de.noelkoeppen.fossilcollector.vm.SaveImportResult
import kotlinx.coroutines.launch

/**
 * Einstellungs-Screen (AP-24, Kapitel 8.3): Sprache, Zahlenformat, Export/Import, Spielstand
 * loeschen (doppelte Bestaetigung) und Version. Erreichbar ueber das Zahnrad der oberen Leiste;
 * Zurueck schliesst ihn (in der Shell).
 *
 * @param settings Einstellungen des Spielstands
 * @param versionName Versionsname der App (`BuildConfig.VERSION_NAME`)
 * @param onMessage zeigt einen kurzen Hinweis (Snackbar der Shell), z. B. "kopiert"
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    versionName: String,
    actions: SettingsActions,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    // Import-Text bewusst nicht in rememberSaveable: Spielstaende koennen gross werden (Bundle-Limit).
    var importText by remember { mutableStateOf("") }
    var importError by rememberSaveable { mutableStateOf<ImportError?>(null) }
    var dialog by rememberSaveable { mutableStateOf(SettingsDialog.NONE) }

    val copiedText = stringResource(R.string.settings_export_copied)
    val exportFailedText = stringResource(R.string.settings_export_failed)
    val clipLabel = stringResource(R.string.settings_export_clip_label)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        LanguageSection(selected = settings.language, onSelect = actions.onSetLanguage)

        NumberFormatSection(settings = settings, onSelect = actions.onSetNumberFormat)

        SaveSection(
            importText = importText,
            importError = importError,
            onImportTextChange = {
                importText = it
                importError = null
            },
            onExport = {
                scope.launch {
                    when (val result = actions.exportSave()) {
                        is SaveExportResult.Success -> {
                            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(clipLabel, result.text)))
                            onMessage(copiedText)
                        }
                        is SaveExportResult.Failure -> onMessage(exportFailedText)
                    }
                }
            },
            onPaste = {
                scope.launch {
                    val clip = clipboard.getClipEntry()?.clipData
                    val text = if (clip != null && clip.itemCount > 0) {
                        clip.getItemAt(0).coerceToText(context).toString()
                    } else {
                        ""
                    }
                    importText = text
                    importError = if (text.isBlank()) ImportError.EMPTY else null
                }
            },
            onImport = {
                val text = importText
                scope.launch {
                    when (val result = actions.checkImport(text)) {
                        is SaveImportResult.Success -> dialog = SettingsDialog.IMPORT_CONFIRM
                        is SaveImportResult.Failure -> importError = result.error
                    }
                }
            },
            onDelete = { dialog = SettingsDialog.DELETE_CONFIRM },
        )

        Text(
            text = stringResource(R.string.settings_version, versionName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    when (dialog) {
        SettingsDialog.NONE -> Unit
        SettingsDialog.IMPORT_CONFIRM -> ImportConfirmDialog(
            onConfirm = {
                actions.onImport(importText)
                importText = ""
                importError = null
                dialog = SettingsDialog.NONE
            },
            onDismiss = { dialog = SettingsDialog.NONE },
        )
        SettingsDialog.DELETE_CONFIRM -> DeleteConfirmDialog(
            onNext = { dialog = SettingsDialog.DELETE_FINAL },
            onDismiss = { dialog = SettingsDialog.NONE },
        )
        SettingsDialog.DELETE_FINAL -> DeleteFinalDialog(
            onConfirm = {
                actions.onDeleteSave()
                dialog = SettingsDialog.NONE
            },
            onDismiss = { dialog = SettingsDialog.NONE },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun LanguageSection(selected: LanguageSetting, onSelect: (LanguageSetting) -> Unit) {
    SectionCard(stringResource(R.string.settings_language_title)) {
        Column(modifier = Modifier.selectableGroup()) {
            for (option in LanguageSetting.entries) {
                RadioRow(
                    label = stringResource(option.labelRes),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                )
            }
        }
        Text(
            stringResource(R.string.settings_language_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NumberFormatSection(settings: Settings, onSelect: (NumberNotation) -> Unit) {
    val language = rememberGameNumberFormat(settings).language
    SectionCard(stringResource(R.string.settings_number_format_title)) {
        Column(modifier = Modifier.selectableGroup()) {
            for (option in NumberNotation.entries) {
                val example = NumberFormatter.format(SettingsLogic.EXAMPLE_NUMBER, language, option)
                RadioRow(
                    label = stringResource(option.labelRes, example),
                    selected = option == settings.numberFormat,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun SaveSection(
    importText: String,
    importError: ImportError?,
    onImportTextChange: (String) -> Unit,
    onExport: () -> Unit,
    onPaste: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
) {
    SectionCard(stringResource(R.string.settings_save_title)) {
        BodyText(stringResource(R.string.settings_export_text))
        Button(onClick = onExport) { Text(stringResource(R.string.settings_export_button)) }

        BodyText(stringResource(R.string.settings_import_text))
        OutlinedTextField(
            value = importText,
            onValueChange = onImportTextChange,
            label = { Text(stringResource(R.string.settings_import_field_label)) },
            isError = importError != null,
            supportingText = importError?.let { error -> { Text(stringResource(SettingsLogic.importErrorText(error))) } },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = IMPORT_FIELD_LINES,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPaste) { Text(stringResource(R.string.settings_import_paste)) }
            Button(onClick = onImport, enabled = importText.isNotBlank()) {
                Text(stringResource(R.string.settings_import_button))
            }
        }

        BodyText(stringResource(R.string.settings_delete_text))
        Button(
            onClick = onDelete,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Text(stringResource(R.string.settings_delete_button))
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ImportConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_import_confirm_title)) },
        text = { Text(stringResource(R.string.settings_import_confirm_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.settings_import_confirm_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun DeleteConfirmDialog(onNext: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_delete_confirm_title)) },
        text = { Text(stringResource(R.string.settings_delete_confirm_text)) },
        confirmButton = {
            TextButton(onClick = onNext) { Text(stringResource(R.string.settings_delete_confirm_next)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

/** Zweite Bestaetigung: Das Bestaetigungswort muss eingetippt werden. */
@Composable
private fun DeleteFinalDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val word = stringResource(R.string.settings_delete_word)
    var input by rememberSaveable { mutableStateOf("") }
    val matches = SettingsLogic.deleteWordMatches(input, word)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_delete_final_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_delete_final_text, word))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = matches) {
                Text(
                    stringResource(R.string.settings_delete_final_button),
                    color = if (matches) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

private val LanguageSetting.labelRes: Int
    get() = when (this) {
        LanguageSetting.SYSTEM -> R.string.settings_language_system
        LanguageSetting.DE -> R.string.settings_language_de
        LanguageSetting.EN -> R.string.settings_language_en
    }

private val NumberNotation.labelRes: Int
    get() = when (this) {
        NumberNotation.SHORT -> R.string.settings_number_format_short
        NumberNotation.SCIENTIFIC -> R.string.settings_number_format_scientific
    }

/** Sichtbare Zeilen des Import-Felds; laengere Texte scrollen im Feld. */
private const val IMPORT_FIELD_LINES = 4

@Preview
@Composable
private fun SettingsScreenPreview() {
    FossilCollectorTheme {
        Surface {
            SettingsScreen(
                settings = Settings(),
                versionName = "0.1.0",
                actions = SettingsActions.NONE,
                onMessage = {},
            )
        }
    }
}

@Preview
@Composable
private fun SettingsScreenScientificPreview() {
    FossilCollectorTheme {
        Surface {
            SettingsScreen(
                settings = Settings(language = LanguageSetting.EN, numberFormat = NumberNotation.SCIENTIFIC),
                versionName = "0.1.0",
                actions = SettingsActions.NONE,
                onMessage = {},
            )
        }
    }
}

@Preview
@Composable
private fun DeleteFinalDialogPreview() {
    FossilCollectorTheme {
        DeleteFinalDialog(onConfirm = {}, onDismiss = {})
    }
}

package de.noelkoeppen.fossilcollector.ui.settings

import androidx.annotation.StringRes
import de.noelkoeppen.fossilcollector.R
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.vm.ImportError

/**
 * Logik des Einstellungs-Screens ausserhalb der Composables (AP-24), als JVM-Test pruefbar.
 */
object SettingsLogic {

    /** Beispielzahl fuer die Zahlenformat-Auswahl (reine Anzeige, kein Spielwert). */
    val EXAMPLE_NUMBER: GameNumber = GameNumber(1_500_000.0)

    /**
     * Zweite Bestaetigung beim Loeschen: Die Eingabe muss dem Bestaetigungswort entsprechen
     * (Leerraum am Rand und Gross-/Kleinschreibung egal).
     */
    fun deleteWordMatches(input: String, word: String): Boolean =
        word.isNotBlank() && input.trim().equals(word.trim(), ignoreCase = true)

    /** Text zu einem abgelehnten Import. */
    @StringRes
    fun importErrorText(error: ImportError): Int = when (error) {
        ImportError.EMPTY -> R.string.settings_import_error_empty
        ImportError.INVALID_ENCODING -> R.string.settings_import_error_encoding
        ImportError.INVALID_JSON -> R.string.settings_import_error_json
        ImportError.MISSING_VERSION -> R.string.settings_import_error_missing_version
        ImportError.TOO_NEW -> R.string.settings_import_error_too_new
        ImportError.UNSUPPORTED_VERSION -> R.string.settings_import_error_unsupported
        ImportError.MIGRATION_FAILED -> R.string.settings_import_error_migration
        ImportError.INVALID_CONTENT -> R.string.settings_import_error_content
        ImportError.UNKNOWN_CONTENT -> R.string.settings_import_error_unknown_content
    }
}

/** Offener Dialog des Einstellungs-Screens. */
enum class SettingsDialog {
    NONE,

    /** "Aktueller Spielstand wird ueberschrieben" vor dem Import. */
    IMPORT_CONFIRM,

    /** Erste Bestaetigung beim Loeschen. */
    DELETE_CONFIRM,

    /** Zweite Bestaetigung beim Loeschen: Bestaetigungswort eintippen. */
    DELETE_FINAL,
}

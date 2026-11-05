package de.noelkoeppen.fossilcollector.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.number.NumberFormatter
import de.noelkoeppen.fossilcollector.core.number.NumberLanguage
import de.noelkoeppen.fossilcollector.core.number.NumberNotation

/**
 * Sprache der Zahlenformatierung (Kapitel 12): Einstellung DE/EN fest, bei "System" anhand der
 * Sprache der aktuellen Konfiguration. Deutsch fuer `de`, sonst Englisch.
 *
 * @param systemLanguage ISO-639-Sprachcode der Konfiguration, z. B. `de` oder `en`
 */
fun resolveNumberLanguage(setting: LanguageSetting, systemLanguage: String): NumberLanguage = when (setting) {
    LanguageSetting.DE -> NumberLanguage.DE
    LanguageSetting.EN -> NumberLanguage.EN
    LanguageSetting.SYSTEM -> if (systemLanguage.equals(GERMAN, ignoreCase = true)) NumberLanguage.DE else NumberLanguage.EN
}

private const val GERMAN = "de"

/** Formatiert Spielzahlen gemaess Einstellungen; alle Zahlen der UI laufen ueber [NumberFormatter]. */
class GameNumberFormat(
    val language: NumberLanguage,
    val notation: NumberNotation,
) {
    fun format(number: GameNumber): String = NumberFormatter.format(number, language, notation)
}

/** [GameNumberFormat] fuer [settings] und die Sprache der aktuellen Konfiguration. */
@Composable
fun rememberGameNumberFormat(settings: Settings): GameNumberFormat {
    val configuration = LocalConfiguration.current
    val systemLanguage = configuration.locales[0]?.language.orEmpty()
    return remember(settings.language, settings.numberFormat, systemLanguage) {
        GameNumberFormat(resolveNumberLanguage(settings.language, systemLanguage), settings.numberFormat)
    }
}

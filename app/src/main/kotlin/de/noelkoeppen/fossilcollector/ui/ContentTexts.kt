package de.noelkoeppen.fossilcollector.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import de.noelkoeppen.fossilcollector.core.data.TextProvider
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.model.Settings

/**
 * Sprachcode der Inhaltstexte (`data/locale/<code>.json`): Einstellung DE/EN fest, bei "System"
 * Deutsch fuer `de`, sonst Englisch.
 *
 * @param systemLanguage ISO-639-Sprachcode der Konfiguration, z. B. `de` oder `en`
 */
fun resolveContentLanguage(setting: LanguageSetting, systemLanguage: String): String = when (setting) {
    LanguageSetting.DE -> CONTENT_DE
    LanguageSetting.EN -> CONTENT_EN
    LanguageSetting.SYSTEM -> if (systemLanguage.equals(CONTENT_DE, ignoreCase = true)) CONTENT_DE else CONTENT_EN
}

private const val CONTENT_DE = "de"
private const val CONTENT_EN = "en"

/** [texts] in der Sprache aus [settings] und der aktuellen Konfiguration. */
@Composable
fun rememberTextProvider(texts: TextProvider, settings: Settings): TextProvider {
    val systemLanguage = LocalConfiguration.current.locales[0]?.language.orEmpty()
    return remember(texts, settings.language, systemLanguage) {
        texts.withLanguage(resolveContentLanguage(settings.language, systemLanguage))
    }
}

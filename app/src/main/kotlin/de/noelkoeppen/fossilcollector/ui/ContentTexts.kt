package de.noelkoeppen.fossilcollector.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import de.noelkoeppen.fossilcollector.core.data.TextProvider
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.number.NumberLanguage

/**
 * Sprachcode der Inhaltstexte (`data/locale/<code>.json`, Kapitel 12). Folgt derselben Regel wie
 * die Zahlenformatierung ([resolveNumberLanguage]): Einstellung DE/EN fest, bei "System" Deutsch
 * fuer `de`, sonst Englisch. So passen Texte und Zahlen immer zusammen.
 */
fun resolveContentLanguage(setting: LanguageSetting, systemLanguage: String): String =
    when (resolveNumberLanguage(setting, systemLanguage)) {
        NumberLanguage.DE -> CONTENT_DE
        NumberLanguage.EN -> CONTENT_EN
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

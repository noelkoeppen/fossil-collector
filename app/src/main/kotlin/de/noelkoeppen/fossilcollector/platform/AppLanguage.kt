package de.noelkoeppen.fossilcollector.platform

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.core.content.edit
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.ui.resolveContentLanguage
import java.util.Locale

/**
 * Sprache der UI-Texte (`strings.xml`) passend zur Einstellung im Spielstand (AP-24, Kapitel 12).
 *
 * Umsetzung ohne AppCompat: `MainActivity.attachBaseContext` ersetzt die Locale der Activity-
 * Ressourcen per [Context.createConfigurationContext] durch [uiLanguageTag]. Damit sehen alle
 * Composables (auch Dialoge, die eigene Fenster haben), `LocalConfiguration` und damit auch
 * Inhaltstexte (`rememberTextProvider`) und Zahlenformat (`rememberGameNumberFormat`) dieselbe Sprache.
 * Bei einer Aenderung wird die Activity neu erstellt (`recreate`, das ViewModel bleibt erhalten).
 *
 * Die Activity braucht die Sprache, bevor der Spielstand geladen ist; deshalb spiegelt
 * [AppLanguageStore] die Einstellung in den SharedPreferences. Massgeblich bleibt der Spielstand:
 * Weicht er ab (Import, erster Start nach dem Update), wird der Spiegel angepasst und neu erstellt.
 */
object AppLocale {

    /**
     * Sprachcode der UI (`de` oder `en`). Dieselbe Regel wie fuer Inhaltstexte und Zahlen
     * ([resolveContentLanguage]): DE/EN fest, bei "System" Deutsch fuer `de`, sonst Englisch.
     * Auch bei "System" wird die Locale ueberschrieben: Ohne Ueberschreiben bekaeme z. B. ein
     * franzoesisches System die Standardressourcen (Deutsch), Inhaltstexte und Zahlen aber Englisch.
     *
     * @param systemLanguage ISO-639-Sprachcode des Systems, z. B. `de`
     */
    fun uiLanguageTag(setting: LanguageSetting, systemLanguage: String): String =
        resolveContentLanguage(setting, systemLanguage)

    /**
     * `true`, wenn die Activity fuer [desired] neu erstellt werden muss, weil sich die UI-Sprache
     * gegenueber [applied] (Stand beim Erstellen) aendert. "System" und die gleichlautende feste
     * Sprache ergeben dieselbe UI, dann genuegt es, den Spiegel zu aktualisieren.
     */
    fun needsRecreate(applied: LanguageSetting, desired: LanguageSetting, systemLanguage: String): Boolean =
        uiLanguageTag(applied, systemLanguage) != uiLanguageTag(desired, systemLanguage)

    /** ISO-639-Sprachcode der Systemkonfiguration von [context] (vor dem Ueberschreiben). */
    fun systemLanguage(context: Context): String =
        context.resources.configuration.locales[0]?.language.orEmpty()

    /** [base] mit ueberschriebener Locale fuer `attachBaseContext`. */
    fun wrap(base: Context, setting: LanguageSetting): Context {
        val tag = uiLanguageTag(setting, systemLanguage(base))
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return base.createConfigurationContext(config)
    }
}

/**
 * Spiegel der Spracheinstellung in den SharedPreferences, damit die Activity sie beim Erstellen
 * synchron kennt (siehe [AppLocale]). Kein Spielwert und nicht Teil des Spielstands.
 */
class AppLanguageStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Gespiegelte Einstellung; ohne Eintrag oder bei unbekanntem Wert "System". */
    fun read(): LanguageSetting = parse(prefs.getString(KEY_LANGUAGE, null))

    fun write(setting: LanguageSetting) {
        prefs.edit { putString(KEY_LANGUAGE, setting.name) }
    }

    companion object {
        private const val PREFS_NAME = "app_language"
        private const val KEY_LANGUAGE = "language"

        /** Liest einen gespeicherten Wert tolerant (unbekannt oder fehlend = "System"). */
        fun parse(value: String?): LanguageSetting =
            LanguageSetting.entries.firstOrNull { it.name == value } ?: LanguageSetting.SYSTEM
    }
}

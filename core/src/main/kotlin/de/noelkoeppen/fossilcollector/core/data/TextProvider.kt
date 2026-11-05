package de.noelkoeppen.fossilcollector.core.data

/**
 * Loest Inhaltstexte (Arten, Knochen, Zonen, Schichten, Qualitaetsstufen ...) aus den
 * Locale-Tabellen `data/locale/<sprache>.json` auf (Kapitel 12). Die Schluessel bildet
 * ausschliesslich [LocaleKeys].
 *
 * Suchreihenfolge fuer einen Schluessel: [language] -> [fallbackLanguage] -> der Schluessel selbst.
 * Fehlende Texte sind ein Datenfehler (siehe `DataValidationTest`), fuehren hier aber nie zu einem
 * Absturz. Eine unbekannte Sprache verhaelt sich wie [fallbackLanguage].
 *
 * Unveraenderlich; [withLanguage] liefert eine Kopie fuer eine andere Sprache.
 *
 * @param locales Tabellen je Sprachcode (`GameData.locales`)
 * @param language gewuenschte Sprache, z. B. `de` oder `en`
 * @param fallbackLanguage Ersatzsprache, Standard ist die erste Sprache aus [DataRepository.LANGUAGES]
 */
class TextProvider(
    private val locales: Map<String, Map<String, String>>,
    val language: String = DEFAULT_LANGUAGE,
    val fallbackLanguage: String = DEFAULT_LANGUAGE,
) {
    /** Text zu [key] oder [key] selbst, wenn er in keiner der beiden Sprachen existiert. */
    fun text(key: String): String =
        locales[language]?.get(key) ?: locales[fallbackLanguage]?.get(key) ?: key

    /** Dieselben Tabellen in der Sprache [language]. */
    fun withLanguage(language: String): TextProvider =
        if (language == this.language) this else TextProvider(locales, language, fallbackLanguage)

    fun layerName(layerId: String): String = text(LocaleKeys.layerName(layerId))

    fun speciesName(speciesId: String): String = text(LocaleKeys.speciesName(speciesId))

    fun boneName(boneId: String): String = text(LocaleKeys.boneName(boneId))

    fun zoneName(boneId: String, zoneId: String): String = text(LocaleKeys.zoneName(boneId, zoneId))

    fun generatorName(generatorId: String): String = text(LocaleKeys.generatorName(generatorId))

    fun upgradeName(upgradeId: String): String = text(LocaleKeys.upgradeName(upgradeId))

    fun qualityTierName(tierId: String): String = text(LocaleKeys.qualityTierName(tierId))

    companion object {
        /** Standardsprache der Inhaltstexte (Deutsch, Kapitel 12). */
        val DEFAULT_LANGUAGE: String = DataRepository.LANGUAGES.first()

        /** Ohne Tabellen: jeder Text ist sein Schluessel (Vorschauen, Ladezustand). */
        val EMPTY: TextProvider = TextProvider(emptyMap())

        /** Texte der Spieldaten [data] in der Sprache [language]. */
        fun fromGameData(data: GameData, language: String = DEFAULT_LANGUAGE): TextProvider =
            TextProvider(data.locales, language)
    }
}

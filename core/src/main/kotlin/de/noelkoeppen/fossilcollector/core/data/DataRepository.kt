package de.noelkoeppen.fossilcollector.core.data

import kotlinx.serialization.json.Json

/**
 * Laedt alle Spieldefinitionen aus `core/src/main/resources/data/` und validiert sie
 * mit [DataValidator]. Bei Fehlern wird eine [DataValidationException] mit allen Meldungen geworfen.
 *
 * @param readResource liefert den Inhalt einer Datei relativ zu `data/` oder `null`, wenn sie fehlt.
 *   Standard ist der Klassenpfad; Tests und Werkzeuge koennen eigene Quellen einsetzen.
 */
class DataRepository(
    private val readResource: (String) -> String? = ::readClasspathResource,
) {

    /** Laedt und validiert alle Dateien. */
    fun load(): GameData {
        val data = parse()
        val errors = DataValidator.validate(data)
        if (errors.isNotEmpty()) throw DataValidationException(errors)
        return data
    }

    /** Laedt alle Dateien ohne Referenzpruefung (nur Struktur ueber die Deserialisierung). */
    fun parse(): GameData {
        val layers = decode<List<LayerDefinition>>(LAYERS)
        return GameData(
            layers = layers.sortedBy { it.order },
            species = decode(SPECIES),
            bones = decode(BONES),
            balance = decode(BALANCE),
            locales = LANGUAGES.associateWith { decode<Map<String, String>>(localeFile(it)) },
        )
    }

    private inline fun <reified T> decode(file: String): T {
        val text = readResource(file) ?: throw DataValidationException(listOf("$file: Datei fehlt"))
        return try {
            json.decodeFromString<T>(text)
        } catch (e: IllegalArgumentException) {
            throw DataValidationException(listOf("$file: ${e.message}"))
        }
    }

    companion object {
        const val RESOURCE_ROOT = "data/"
        const val LAYERS = "layers.json"
        const val SPECIES = "species.json"
        const val BONES = "bones.json"
        const val BALANCE = "balance.json"

        /** Unterstuetzte Sprachen der Inhaltstexte; die erste ist der Standard. */
        val LANGUAGES: List<String> = listOf("de", "en")

        /** Alle Datendateien relativ zu `data/`. */
        val ALL_FILES: List<String> =
            listOf(LAYERS, SPECIES, BONES, BALANCE) +
                LANGUAGES.map { localeFile(it) }

        fun localeFile(language: String) = "locale/$language.json"

        /** Strikter Parser: unbekannte Felder sind Fehler, damit Tippfehler in den Daten auffallen. */
        val json: Json = Json {
            ignoreUnknownKeys = false
            isLenient = false
        }

        /** Liest Dateien relativ zu `data/` aus dem Klassenpfad (Standardquelle, auch im APK). */
        val classpathReader: (String) -> String? = ::readClasspathResource

        private fun readClasspathResource(file: String): String? =
            DataRepository::class.java.classLoader
                .getResourceAsStream(RESOURCE_ROOT + file)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

/** Fehler beim Laden oder Validieren der Spieldaten. */
class DataValidationException(val errors: List<String>) :
    RuntimeException("Spieldaten ungueltig:\n" + errors.joinToString("\n") { "- $it" })

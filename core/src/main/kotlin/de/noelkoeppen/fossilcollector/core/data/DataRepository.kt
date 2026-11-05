package de.noelkoeppen.fossilcollector.core.data

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Fehler beim Laden oder Validieren der Spieldaten. Enthaelt alle gefundenen Probleme. */
class DataValidationException(val errors: List<String>) :
    IllegalStateException("Spieldaten ungueltig:\n" + errors.joinToString("\n") { "- $it" })

/**
 * Laedt die Spieldaten aus `data/` und validiert sie (GAME_DESIGN.md, Kapitel 11).
 *
 * @param source liefert den Inhalt einer Datei relativ zu `data/` oder `null`, wenn sie fehlt.
 *   Standard ist der Klassenpfad von `core`; Tests koennen eigene Inhalte einspeisen.
 */
class DataRepository(
    private val source: (path: String) -> String? = ::readClasspathResource,
) {

    /** Laedt alle Dateien. Wirft [DataValidationException], wenn etwas fehlt oder ungueltig ist. */
    fun load(): GameData {
        val errors = mutableListOf<String>()

        fun <T> read(path: String, strategy: DeserializationStrategy<T>): T? {
            val text = source(path)
            if (text == null) {
                errors += "$path: Datei fehlt"
                return null
            }
            return try {
                json.decodeFromString(strategy, text)
            } catch (e: SerializationException) {
                errors += "$path: ${e.message}"
                null
            } catch (e: IllegalArgumentException) {
                errors += "$path: ${e.message}"
                null
            }
        }

        val layers = read(FILE_LAYERS, ListSer.layers)
        val species = read(FILE_SPECIES, ListSer.species)
        val bones = read(FILE_BONES, ListSer.bones)
        val generators = read(FILE_GENERATORS, ListSer.generators)
        val upgrades = read(FILE_UPGRADES, ListSer.upgrades)
        val skills = read(FILE_SKILLTREE, ListSer.skills)
        val milestones = read(FILE_MILESTONES, ListSer.milestones)
        val balance = read(FILE_BALANCE, BalanceDef.serializer())
        val locales = GameData.SUPPORTED_LANGUAGES.associateWith { lang ->
            read(localeFile(lang), ListSer.locale)
        }

        if (errors.isNotEmpty()) throw DataValidationException(errors)

        val data = GameData(
            layers = layers!!,
            species = species!!,
            bones = bones!!,
            generators = generators!!,
            upgrades = upgrades!!,
            skills = skills!!,
            milestones = milestones!!,
            balance = balance!!,
            locales = locales.mapValues { it.value!! },
        )
        val problems = DataValidator.validate(data)
        if (problems.isNotEmpty()) throw DataValidationException(problems)
        return data
    }

    companion object {
        const val FILE_LAYERS = "layers.json"
        const val FILE_SPECIES = "species.json"
        const val FILE_BONES = "bones.json"
        const val FILE_GENERATORS = "generators.json"
        const val FILE_UPGRADES = "upgrades.json"
        const val FILE_SKILLTREE = "skilltree.json"
        const val FILE_MILESTONES = "milestones.json"
        const val FILE_BALANCE = "balance.json"

        fun localeFile(language: String) = "locale/$language.json"

        /** Strikt: unbekannte Felder sind ein Fehler, damit Tippfehler in den Daten auffallen. */
        val json = Json {
            ignoreUnknownKeys = false
            isLenient = false
            allowSpecialFloatingPointValues = false
        }

        private fun readClasspathResource(path: String): String? =
            DataRepository::class.java.classLoader
                .getResourceAsStream("data/$path")
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

private object ListSer {
    val layers = ListSerializer(LayerDef.serializer())
    val species = ListSerializer(SpeciesDef.serializer())
    val bones = ListSerializer(BoneDef.serializer())
    val generators = ListSerializer(GeneratorDef.serializer())
    val upgrades = ListSerializer(UpgradeDef.serializer())
    val skills = ListSerializer(SkillNodeDef.serializer())
    val milestones = ListSerializer(MilestoneDef.serializer())
    val locale = MapSerializer(String.serializer(), String.serializer())
}

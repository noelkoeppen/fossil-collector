package de.noelkoeppen.fossilcollector.core.data

/**
 * Referenz- und Plausibilitaetspruefungen fuer [GameData].
 * Unbekannte Stat-IDs, Enums und Felder scheitern bereits beim Parsen in [DataRepository].
 */
object DataValidator {

    private val ID_PATTERN = Regex("^[a-z][a-z0-9_]*$")
    private val COLOR_PATTERN = Regex("^#[0-9A-Fa-f]{6}$")

    /** Liefert alle gefundenen Fehler; eine leere Liste bedeutet gueltige Daten. */
    fun validate(data: GameData): List<String> {
        val errors = mutableListOf<String>()
        validateLayers(data, errors)
        validateBones(data, errors)
        validateSpecies(data, errors)
        validateBalance(data.balance, errors)
        validateLocales(data, errors)
        return errors
    }

    private fun validateIds(file: String, ids: List<String>, errors: MutableList<String>) {
        if (ids.isEmpty()) errors += "$file: keine Eintraege"
        ids.filterNot { ID_PATTERN.matches(it) }.forEach { errors += "$file: ID '$it' ist nicht snake_case" }
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { errors += "$file: ID '$it' ist doppelt" }
    }

    private fun validateLayers(data: GameData, errors: MutableList<String>) {
        val file = DataRepository.LAYERS
        validateIds(file, data.layers.map { it.id }, errors)
        val orders = data.layers.map { it.order }
        if (orders != data.layers.indices.toList()) {
            errors += "$file: 'order' muss lueckenlos bei 0 beginnen, ist $orders"
        }
        data.layers.forEach { layer ->
            val ctx = "$file/${layer.id}"
            if (!COLOR_PATTERN.matches(layer.color)) errors += "$ctx: color '${layer.color}' ist kein #RRGGBB"
        }
    }

    private fun validateBones(data: GameData, errors: MutableList<String>) {
        validateIds(DataRepository.BONES, data.bones.map { it.id }, errors)
    }

    private fun validateSpecies(data: GameData, errors: MutableList<String>) {
        val file = DataRepository.SPECIES
        validateIds(file, data.species.map { it.id }, errors)
        data.species.forEach { species ->
            val ctx = "$file/${species.id}"
            if (species.layer !in data.layersById) errors += "$ctx: unbekannte Schicht '${species.layer}'"
            if (species.rarityWeight <= 0) errors += "$ctx: rarityWeight muss > 0 sein"
            if (species.bones.isEmpty()) errors += "$ctx: keine Knochen"
            species.bones.filterNot { it in data.bonesById }
                .forEach { errors += "$ctx: unbekannter Knochen '$it'" }
            if (species.bones.toSet().size != species.bones.size) errors += "$ctx: Knochen doppelt"
        }
        data.layers.filter { data.speciesByLayer[it.id].isNullOrEmpty() }
            .forEach { errors += "$file: Schicht '${it.id}' hat keine Arten" }
    }

    private fun validateBalance(balance: BalanceDefinition, errors: MutableList<String>) {
        val ctx = DataRepository.BALANCE
        val tiers = balance.qualityTiers
        if (tiers.isEmpty()) {
            errors += "$ctx: qualityTiers leer"
        } else {
            validateIds("$ctx/qualityTiers", tiers.map { it.id }, errors)
            if (tiers.first().minQuality != 0.0) errors += "$ctx: erste Qualitaetsstufe muss bei 0 beginnen"
            if (tiers.zipWithNext().any { (a, b) -> a.minQuality >= b.minQuality }) {
                errors += "$ctx: qualityTiers muessen streng aufsteigend sein"
            }
            tiers.filterNot { COLOR_PATTERN.matches(it.color) }
                .forEach { errors += "$ctx: Farbe der Stufe '${it.id}' ist kein #RRGGBB" }
        }
        balance.statLimits.forEach { (stat, limit) ->
            if (limit.min == null && limit.max == null) errors += "$ctx: statLimits.${stat.serialName} ohne Grenze"
            if (limit.min != null && limit.max != null && limit.min > limit.max) {
                errors += "$ctx: statLimits.${stat.serialName} min > max"
            }
        }
    }

    private fun validateLocales(data: GameData, errors: MutableList<String>) {
        val required = LocaleKeys.requiredKeys(data)
        val allKeys = data.locales.values.flatMap { it.keys }.toSet()
        DataRepository.LANGUAGES.forEach { language ->
            val file = DataRepository.localeFile(language)
            val table = data.locales[language]
            if (table == null) {
                errors += "$file: fehlt"
                return@forEach
            }
            (required - table.keys).sorted().forEach { errors += "$file: Schluessel '$it' fehlt" }
            (allKeys - table.keys - required).sorted()
                .forEach { errors += "$file: Schluessel '$it' fehlt (in anderer Sprache vorhanden)" }
            table.filterValues { it.isBlank() }.keys.sorted()
                .forEach { errors += "$file: Schluessel '$it' ist leer" }
        }
    }
}

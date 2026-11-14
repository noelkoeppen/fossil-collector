package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.model.ExcavationSite

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
        validateSpecies(data, errors)
        validateBalance(data.balance, errors)
        validateTools(data, errors)
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

    private fun validateSpecies(data: GameData, errors: MutableList<String>) {
        val file = DataRepository.SPECIES
        validateIds(file, data.species.map { it.id }, errors)
        data.species.forEach { species ->
            val ctx = "$file/${species.id}"
            if (species.layer !in data.layersById) errors += "$ctx: unbekannte Schicht '${species.layer}'"
            if (species.rarityWeight <= 0) errors += "$ctx: rarityWeight muss > 0 sein"
            if (species.bones.isEmpty()) errors += "$ctx: keine Knochen"
            species.bones.filterNot { ID_PATTERN.matches(it) }
                .forEach { errors += "$ctx: Knochen-ID '$it' ist nicht snake_case" }
            species.bones.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                .forEach { errors += "$ctx: Knochen '$it' doppelt" }
            validateNeighbors(species, ctx, errors)
        }
        data.layers.filter { data.speciesByLayer[it.id].isNullOrEmpty() }
            .forEach { errors += "$file: Schicht '${it.id}' hat keine Arten" }
    }

    /** Nachbarschaftsgraph: gueltige Kanten, keine Duplikate, alle Knochen zusammenhaengend (Kapitel 4). */
    private fun validateNeighbors(species: SpeciesDefinition, ctx: String, errors: MutableList<String>) {
        val known = species.bones.toSet()
        val seen = mutableSetOf<Set<String>>()
        species.neighbors.forEach { edge ->
            when {
                edge.size != 2 -> errors += "$ctx: Kante $edge hat nicht genau zwei Knochen"
                edge[0] == edge[1] -> errors += "$ctx: Kante $edge verbindet einen Knochen mit sich selbst"
                else -> {
                    edge.filterNot { it in known }.forEach { errors += "$ctx: Kante $edge mit unbekanntem Knochen '$it'" }
                    if (!seen.add(edge.toSet())) errors += "$ctx: Kante $edge doppelt"
                }
            }
        }
        if (species.bones.isNotEmpty() && !species.isConnected(species.bones)) {
            errors += "$ctx: Nachbarschaftsgraph ist nicht zusammenhaengend"
        }
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
        validateFinds(balance.finds, "$ctx/finds", errors)
        validateExcavation(balance.excavation, "$ctx/excavation", errors)
    }

    /**
     * Grabungsflaeche (Kapitel 3): positive Groessen, Tiefenbereich im Speicherformat, Hinweis-Schwelle
     * unter der kleinsten Tiefe (sonst waeren Knochen von Anfang an angedeutet), gueltige
     * Fundanzahl-Tabelle, Platzhalter-Umriss passt in die Flaeche.
     */
    private fun validateExcavation(ex: ExcavationBalance, ctx: String, errors: MutableList<String>) {
        if (ex.width < 1 || ex.height < 1) errors += "$ctx: width und height muessen >= 1 sein"
        if (ex.depthMin < 1) errors += "$ctx: depthMin muss >= 1 sein"
        if (ex.depthMin > ex.depthMax) errors += "$ctx: depthMin > depthMax"
        if (ex.depthMax > ExcavationSite.MAX_DEPTH) errors += "$ctx: depthMax muss <= ${ExcavationSite.MAX_DEPTH} sein"
        if (ex.hintDepth < 1) errors += "$ctx: hintDepth muss >= 1 sein"
        if (ex.hintDepth >= ex.depthMin) errors += "$ctx: hintDepth muss kleiner als depthMin sein"
        if (ex.findCountWeights.isEmpty()) errors += "$ctx: findCountWeights leer"
        ex.findCountWeights.forEach { entry ->
            if (entry.count < 1) errors += "$ctx: findCountWeights.count ${entry.count} muss >= 1 sein"
            if (!(entry.weight > 0.0 && entry.weight.isFinite())) {
                errors += "$ctx: findCountWeights.weight fuer Anzahl ${entry.count} muss > 0 sein"
            }
        }
        ex.findCountWeights.groupingBy { it.count }.eachCount().filterValues { it > 1 }.keys
            .forEach { errors += "$ctx: findCountWeights.count $it doppelt" }
        if (ex.findSpacing < 0) errors += "$ctx: findSpacing muss >= 0 sein"
        if (ex.placementAttempts < 1) errors += "$ctx: placementAttempts muss >= 1 sein"
        val fp = ex.placeholderFootprint
        if (fp.width < 1 || fp.height < 1) errors += "$ctx: placeholderFootprint muss mindestens 1x1 sein"
        if (fp.width > ex.width || fp.height > ex.height) errors += "$ctx: placeholderFootprint ist groesser als die Flaeche"
    }

    /**
     * Werkzeuge (Kapitel 3): gueltige IDs, Abtrag >= 1 und hoechstens `hintDepth` (damit vor jeder
     * Beruehrung eines Knochens ein Hinweis erscheint), Radius > 0, Schaden in 0..1, Schaden > 0 genau
     * bei groben Werkzeugen.
     */
    private fun validateTools(data: GameData, errors: MutableList<String>) {
        val file = DataRepository.TOOLS
        validateIds(file, data.tools.map { it.id }, errors)
        val hintDepth = data.balance.excavation.hintDepth
        data.tools.forEach { tool ->
            val ctx = "$file/${tool.id}"
            if (tool.removal < 1) errors += "$ctx: removal muss >= 1 sein"
            if (tool.removal > hintDepth) errors += "$ctx: removal ${tool.removal} ist groesser als hintDepth $hintDepth"
            if (!(tool.radius > 0.0 && tool.radius.isFinite())) errors += "$ctx: radius muss > 0 sein"
            if (tool.boneDamage !in 0.0..1.0) errors += "$ctx: boneDamage ${tool.boneDamage} liegt nicht in 0..1"
            if (tool.rough && tool.boneDamage <= 0.0) errors += "$ctx: grobes Werkzeug braucht boneDamage > 0"
            if (!tool.rough && tool.boneDamage != 0.0) errors += "$ctx: nicht grobes Werkzeug muss boneDamage 0 haben"
        }
    }

    /** Fundsystem (Kapitel 4): Groessen >= 1 ohne Duplikate, Gewichte > 0, Qualitaeten in 0..1. */
    private fun validateFinds(finds: FindBalance, ctx: String, errors: MutableList<String>) {
        if (finds.sizeWeights.isEmpty()) errors += "$ctx: sizeWeights leer"
        finds.sizeWeights.forEach { entry ->
            if (entry.size < 1) errors += "$ctx: sizeWeights.size ${entry.size} muss >= 1 sein"
            if (!(entry.weight > 0.0 && entry.weight.isFinite())) {
                errors += "$ctx: sizeWeights.weight fuer Groesse ${entry.size} muss > 0 sein"
            }
        }
        finds.sizeWeights.groupingBy { it.size }.eachCount().filterValues { it > 1 }.keys
            .forEach { errors += "$ctx: sizeWeights.size $it doppelt" }
        val fractions = listOf(
            "baseQualityMin" to finds.baseQualityMin,
            "baseQualityMax" to finds.baseQualityMax,
            "boneQualityDeviation" to finds.boneQualityDeviation,
        )
        fractions.filterNot { (_, value) -> value in 0.0..1.0 }
            .forEach { (name, value) -> errors += "$ctx: $name $value liegt nicht in 0..1" }
        if (finds.baseQualityMin > finds.baseQualityMax) errors += "$ctx: baseQualityMin > baseQualityMax"
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

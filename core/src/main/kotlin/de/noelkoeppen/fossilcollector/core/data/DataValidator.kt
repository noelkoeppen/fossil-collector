package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.model.BonePreparation
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
        validateFootprints(data, errors)
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
        validatePreparation(balance.preparation, "$ctx/preparation", errors)
    }

    /**
     * Feines Freilegen (Kapitel 5.1, AP2-08): Unterteilung >= 1, Rand >= 0, Dicken in
     * 1..[BonePreparation.MAX_DEPTH] mit Untergrenze <= Obergrenze, Anzahl harter Stellen >= 0,
     * Radien > 0 mit Untergrenze <= Obergrenze, Schwelle fuer fertig in (0, 1].
     */
    private fun validatePreparation(prep: PreparationBalance, ctx: String, errors: MutableList<String>) {
        if (prep.subdivision < 1) errors += "$ctx: subdivision muss >= 1 sein"
        if (prep.margin < 0) errors += "$ctx: margin muss >= 0 sein"
        val max = BonePreparation.MAX_DEPTH
        listOf(
            Triple("looseDepth", prep.looseDepthMin, prep.looseDepthMax),
            Triple("hardDepth", prep.hardDepthMin, prep.hardDepthMax),
        ).forEach { (name, min, upper) ->
            if (min < 1 || upper > max) errors += "$ctx: ${name}Min/${name}Max muessen in 1..$max liegen"
            if (min > upper) errors += "$ctx: ${name}Min > ${name}Max"
        }
        if (!(prep.hardSpotsPerCell >= 0.0 && prep.hardSpotsPerCell.isFinite())) errors += "$ctx: hardSpotsPerCell muss >= 0 sein"
        if (!(prep.hardSpotRadiusMin > 0.0 && prep.hardSpotRadiusMax.isFinite())) errors += "$ctx: hardSpotRadiusMin/Max muessen > 0 sein"
        if (prep.hardSpotRadiusMin > prep.hardSpotRadiusMax) errors += "$ctx: hardSpotRadiusMin > hardSpotRadiusMax"
        if (!(prep.finishedShare > 0.0 && prep.finishedShare <= 1.0)) errors += "$ctx: finishedShare muss in (0, 1] liegen"
    }

    /**
     * Grabungsflaeche (Kapitel 3): positive Groessen, Tiefenbereich im Speicherformat, Hinweis-Schwelle
     * unter der kleinsten Tiefe (sonst waeren Knochen von Anfang an angedeutet), gueltige
     * Fundanzahl-Tabelle, Massstab der Umrisse >= 1.
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
        if (ex.cellsPerUnit < 1) errors += "$ctx: cellsPerUnit muss >= 1 sein"
    }

    /**
     * Knochenumrisse (AP2-07): je Art eine Datei passend zur Art, gerechnet mit
     * `excavation.cellsPerUnit`; genau ein Umriss je Knochen; jeder Umriss ist ein Rechteck aus Zeilen
     * gleicher Laenge mit nur `#` und `.`, ohne leere Randzeile oder -spalte, und passt in die Flaeche.
     */
    private fun validateFootprints(data: GameData, errors: MutableList<String>) {
        val ex = data.balance.excavation
        data.species.forEach { species ->
            val file = DataRepository.footprintFile(species.id)
            val footprints = data.footprints[species.id]
            if (footprints == null) {
                errors += "$file: fehlt"
                return@forEach
            }
            if (footprints.formatVersion != SpeciesFootprints.FORMAT_VERSION) {
                errors += "$file: formatVersion ${footprints.formatVersion} wird nicht unterstuetzt"
            }
            if (footprints.species != species.id) errors += "$file: species '${footprints.species}' passt nicht zur Datei"
            if (footprints.cellsPerUnit != ex.cellsPerUnit) {
                errors += "$file: cellsPerUnit ${footprints.cellsPerUnit} passt nicht zu balance.json (${ex.cellsPerUnit}), bone_footprints.py ausfuehren"
            }
            if (footprints.cellsPerUnit < 1 || footprints.pxPerUnit < 1 || footprints.pxPerUnit % footprints.cellsPerUnit != 0) {
                errors += "$file: pxPerUnit ${footprints.pxPerUnit} muss ein Vielfaches von cellsPerUnit sein"
            }
            val sub = data.balance.preparation.subdivision
            if (footprints.subdivision != sub) {
                errors += "$file: subdivision ${footprints.subdivision} passt nicht zu balance.json ($sub), bone_footprints.py ausfuehren"
            }
            val ids = footprints.bones.map { it.id }
            ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { errors += "$file: Knochen '$it' doppelt" }
            (species.bones - ids.toSet()).forEach { errors += "$file: Umriss fuer Knochen '$it' fehlt" }
            (ids.toSet() - species.bones.toSet()).forEach { errors += "$file: unbekannter Knochen '$it'" }
            footprints.bones.forEach {
                validateFootprint(it, ex, "$file/${it.id}", errors)
                validateFineFootprint(it, footprints.subdivision, "$file/${it.id}", errors)
            }
        }
        (data.footprints.keys - data.speciesById.keys).forEach { errors += "footprints: Umrisse fuer unbekannte Art '$it'" }
    }

    private fun validateFootprint(bone: BoneFootprintDefinition, ex: ExcavationBalance, ctx: String, errors: MutableList<String>) {
        val rows = bone.rows
        val allowed = setOf(BoneFootprintDefinition.OCCUPIED, BoneFootprintDefinition.FREE)
        when {
            rows.isEmpty() || rows.first().isEmpty() -> errors += "$ctx: Umriss ohne Zeilen"
            rows.any { it.length != rows.first().length } -> errors += "$ctx: Zeilen unterschiedlich lang"
            rows.any { row -> row.any { it !in allowed } } -> errors += "$ctx: nur '#' und '.' erlaubt"
            else -> {
                val occupied = bone.occupiedCells()
                if (occupied.isEmpty()) {
                    errors += "$ctx: Umriss ohne belegte Zelle"
                    return
                }
                if (occupied.minOf { it.first } != 0 || occupied.maxOf { it.first } != bone.width - 1 ||
                    occupied.minOf { it.second } != 0 || occupied.maxOf { it.second } != bone.height - 1
                ) {
                    errors += "$ctx: leere Randzeile oder -spalte"
                }
                if (bone.width > ex.width || bone.height > ex.height) {
                    errors += "$ctx: Umriss ${bone.width}x${bone.height} passt nicht in die Flaeche ${ex.width}x${ex.height}"
                }
            }
        }
    }

    /**
     * Feiner Umriss (AP2-08): `fineRows` ist das Rechteck des Umrisses mal [sub] (Zeilen gleicher
     * Laenge), nur `#` und `.`, mindestens eine Knochenzelle.
     */
    private fun validateFineFootprint(bone: BoneFootprintDefinition, sub: Int, ctx: String, errors: MutableList<String>) {
        val rows = bone.fineRows
        val allowed = setOf(BoneFootprintDefinition.OCCUPIED, BoneFootprintDefinition.FREE)
        when {
            rows.size != bone.height * sub || rows.any { it.length != bone.width * sub } ->
                errors += "$ctx: fineRows muessen ${bone.width * sub}x${bone.height * sub} sein"
            rows.any { row -> row.any { it !in allowed } } -> errors += "$ctx: fineRows: nur '#' und '.' erlaubt"
            bone.fineCells().isEmpty() -> errors += "$ctx: fineRows ohne Knochenzelle"
        }
    }

    /**
     * Werkzeuge (Kapitel 3 und 5.1): gueltige IDs, Abtrag >= 1, Radius > 0, Schaden in 0..1,
     * Schaden > 0 genau bei groben Werkzeugen, Abstand der Anwendungen beim Wischen > 0. Grobes
     * Buddeln (`dig`): Abtrag hoechstens `hintDepth` (damit vor jeder Beruehrung eines Knochens ein
     * Hinweis erscheint), loest keine harten Stellen. Feines Freilegen (`prepare`): mindestens ein
     * Werkzeug loest harte Stellen (sonst liessen sich Knochen nicht fertig freilegen). Beide Bereiche
     * brauchen mindestens ein Werkzeug.
     */
    private fun validateTools(data: GameData, errors: MutableList<String>) {
        val file = DataRepository.TOOLS
        validateIds(file, data.tools.map { it.id }, errors)
        val hintDepth = data.balance.excavation.hintDepth
        data.tools.forEach { tool ->
            val ctx = "$file/${tool.id}"
            if (tool.removal < 1) errors += "$ctx: removal muss >= 1 sein"
            if (tool.stage == ToolStage.DIG) {
                if (tool.removal > hintDepth) errors += "$ctx: removal ${tool.removal} ist groesser als hintDepth $hintDepth"
                if (tool.removesHard) errors += "$ctx: removesHard gibt es nur beim feinen Freilegen (stage prepare)"
            }
            if (!(tool.radius > 0.0 && tool.radius.isFinite())) errors += "$ctx: radius muss > 0 sein"
            if (tool.boneDamage !in 0.0..1.0) errors += "$ctx: boneDamage ${tool.boneDamage} liegt nicht in 0..1"
            if (tool.rough && tool.boneDamage <= 0.0) errors += "$ctx: grobes Werkzeug braucht boneDamage > 0"
            if (!tool.rough && tool.boneDamage != 0.0) errors += "$ctx: nicht grobes Werkzeug muss boneDamage 0 haben"
            if (!(tool.strokeSpacing > 0.0 && tool.strokeSpacing.isFinite())) errors += "$ctx: strokeSpacing muss > 0 sein"
        }
        ToolStage.entries.filter { stage -> data.tools.none { it.stage == stage } }
            .forEach { errors += "$file: kein Werkzeug fuer stage '${it.name.lowercase()}'" }
        if (data.tools.none { it.stage == ToolStage.PREPARE && it.removesHard }) {
            errors += "$file: kein Werkzeug zum feinen Freilegen loest harte Stellen (removesHard)"
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

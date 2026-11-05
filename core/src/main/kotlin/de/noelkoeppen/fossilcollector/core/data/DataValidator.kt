package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import de.noelkoeppen.fossilcollector.core.modifier.ModifierCategory

/**
 * Referenz- und Plausibilitaetspruefungen fuer [GameData] (Kapitel 10.4, 12).
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
        validateGenerators(data, errors)
        validateUpgrades(data, errors)
        validateSkills(data, errors)
        validateMilestones(data, errors)
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
        data.layers.firstOrNull()?.let {
            if (it.unlockCost != 0.0) errors += "$file: erste Schicht '${it.id}' muss unlockCost 0 haben"
        }
        data.layers.forEach { layer ->
            val ctx = "$file/${layer.id}"
            if (layer.unlockCost < 0) errors += "$ctx: unlockCost negativ"
            if (layer.teamBaseCost <= 0) errors += "$ctx: teamBaseCost muss > 0 sein"
            if (layer.teamGrowth <= 1) errors += "$ctx: teamGrowth muss > 1 sein"
            if (layer.teamRate <= 0) errors += "$ctx: teamRate muss > 0 sein"
            if (layer.value <= 0) errors += "$ctx: value muss > 0 sein"
            if (layer.fragility !in 0.0..1.0) errors += "$ctx: fragility muss in [0, 1] liegen"
            if (layer.fameWeight <= 0) errors += "$ctx: fameWeight muss > 0 sein"
            if (!COLOR_PATTERN.matches(layer.color)) errors += "$ctx: color '${layer.color}' ist kein #RRGGBB"
        }
    }

    private fun validateBones(data: GameData, errors: MutableList<String>) {
        val file = DataRepository.BONES
        validateIds(file, data.bones.map { it.id }, errors)
        data.bones.forEach { bone ->
            val ctx = "$file/${bone.id}"
            if (bone.zones.isEmpty()) errors += "$ctx: keine Zonen"
            validateIds(ctx, bone.zones, errors)
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
            species.bones.filterNot { it in data.bonesById }
                .forEach { errors += "$ctx: unbekannter Knochen '$it'" }
            if (species.bones.toSet().size != species.bones.size) errors += "$ctx: Knochen doppelt"
            if (species.art.isBlank()) errors += "$ctx: art fehlt"
            if (species.bookBonus.value <= 0) errors += "$ctx: bookBonus.value muss > 0 sein"
        }
        data.layers.filter { data.speciesByLayer[it.id].isNullOrEmpty() }
            .forEach { errors += "$file: Schicht '${it.id}' hat keine Arten" }
    }

    private fun validateGenerators(data: GameData, errors: MutableList<String>) {
        val file = DataRepository.GENERATORS
        validateIds(file, data.generators.map { it.id }, errors)
        data.generators.forEach { gen ->
            val ctx = "$file/${gen.id}"
            validateCost(ctx, gen.baseCost, gen.growth, errors)
            if (gen.free < 0) errors += "$ctx: free negativ"
            validateModifiers(ctx, gen.effects, ModifierCategory.UPGRADE, errors)
        }
    }

    private fun validateUpgrades(data: GameData, errors: MutableList<String>) {
        val file = DataRepository.UPGRADES
        validateIds(file, data.upgrades.map { it.id }, errors)
        data.upgrades.forEach { upgrade ->
            val ctx = "$file/${upgrade.id}"
            validateCost(ctx, upgrade.baseCost, upgrade.growth, errors)
            upgrade.maxLevel?.let { if (it < 1) errors += "$ctx: maxLevel muss >= 1 oder null sein" }
            if (upgrade.effectsPerLevel.isEmpty()) errors += "$ctx: effectsPerLevel leer"
            validateModifiers(ctx, upgrade.effectsPerLevel, ModifierCategory.UPGRADE, errors)
        }
    }

    private fun validateSkills(data: GameData, errors: MutableList<String>) {
        val file = DataRepository.SKILLTREE
        validateIds(file, data.skills.map { it.id }, errors)
        data.skills.forEach { skill ->
            val ctx = "$file/${skill.id}"
            if (skill.depth < 1) errors += "$ctx: depth muss >= 1 sein"
            if (skill.baseCost <= 0) errors += "$ctx: baseCost muss > 0 sein"
            if (skill.effects.isEmpty()) errors += "$ctx: keine Effekte"
            validateModifiers(ctx, skill.effects, ModifierCategory.SKILL, errors)
            skill.requires.forEach { reqId ->
                val req = data.skillsById[reqId]
                when {
                    req == null -> errors += "$ctx: unbekannte Voraussetzung '$reqId'"
                    req.depth >= skill.depth -> errors += "$ctx: Voraussetzung '$reqId' liegt nicht tiefer im Baum"
                }
            }
            if (skill.depth > 1) {
                val hasPredecessor = skill.requires.any {
                    val req = data.skillsById[it]
                    req != null && req.branch == skill.branch && req.depth == skill.depth - 1
                }
                if (!hasPredecessor) errors += "$ctx: vorheriger Knoten im selben Ast fehlt in 'requires'"
            }
        }
        data.skills.groupBy { it.branch }.forEach { (branch, nodes) ->
            val maxDepth = nodes.maxOf { it.depth }
            nodes.groupingBy { it.depth }.eachCount().filterValues { it > 1 }.keys
                .forEach { errors += "$file: Ast '${branch.serialName}' hat mehrere Knoten mit Tiefe $it" }
            nodes.filter { it.capstone != (it.depth == maxDepth) }
                .forEach { errors += "$file/${it.id}: capstone muss genau beim tiefsten Knoten des Asts gesetzt sein" }
        }
    }

    private fun validateMilestones(data: GameData, errors: MutableList<String>) {
        val file = DataRepository.MILESTONES
        validateIds(file, data.milestones.map { it.id }, errors)
        data.milestones.forEach { milestone ->
            val ctx = "$file/${milestone.id}"
            if (milestone.condition.value <= 0) errors += "$ctx: condition.value muss > 0 sein"
            val reward = milestone.reward
            if (reward.money < 0 || reward.researchPoints < 0) errors += "$ctx: negative Belohnung"
            if (reward.money == 0.0 && reward.researchPoints == 0.0 && reward.effects.isEmpty()) {
                errors += "$ctx: keine Belohnung"
            }
            validateModifiers(ctx, reward.effects, ModifierCategory.MILESTONE, errors)
        }
    }

    private fun validateBalance(balance: BalanceDefinition, errors: MutableList<String>) {
        val ctx = DataRepository.BALANCE
        fun probability(name: String, value: Double) {
            if (value !in 0.0..1.0) errors += "$ctx: $name muss in [0, 1] liegen"
        }
        fun positive(name: String, value: Double) {
            if (value <= 0) errors += "$ctx: $name muss > 0 sein"
        }
        probability("findChance", balance.findChance)
        probability("qualityBaseMin", balance.qualityBaseMin)
        probability("qualityBaseMax", balance.qualityBaseMax)
        probability("reconstructionCap", balance.reconstructionCap)
        probability("reconstructionMissingZoneQuality", balance.reconstructionMissingZoneQuality)
        probability("offlineEfficiency", balance.offlineEfficiency)
        if (balance.qualityBaseMin > balance.qualityBaseMax) errors += "$ctx: qualityBaseMin > qualityBaseMax"
        if (balance.damageRollMin < 0 || balance.damageRollMin > balance.damageRollMax) {
            errors += "$ctx: damageRollMin muss in [0, damageRollMax] liegen"
        }
        positive("rockSellFactor", balance.rockSellFactor)
        positive("duplicateSellFactor", balance.duplicateSellFactor)
        positive("labDuration", balance.labDuration)
        positive("labQueuePerSlot", balance.labQueuePerSlot)
        positive("skeletonMult", balance.skeletonMult)
        positive("slotsPerHall", balance.slotsPerHall)
        positive("missingBoneWeight", balance.missingBoneWeight)
        positive("tapDigAmount", balance.tapDigAmount)
        positive("tapPrepAmount", balance.tapPrepAmount)
        positive("offlineCapHours", balance.offlineCapHours)
        positive("maxDetailedOfflineFinds", balance.maxDetailedOfflineFinds)
        positive("teamMilestoneFactor", balance.teamMilestoneFactor)
        if (balance.skillCostGrowth < 1) errors += "$ctx: skillCostGrowth muss >= 1 sein"

        val milestones = balance.teamMilestones
        if (milestones.any { it <= 0 } || milestones.zipWithNext().any { (a, b) -> a >= b }) {
            errors += "$ctx: teamMilestones muessen positiv und streng aufsteigend sein"
        }

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
            // Kernregel 4.5: Museumsreif ist nur durch echte Funde erreichbar.
            if (balance.reconstructionCap >= tiers.last().minQuality) {
                errors += "$ctx: reconstructionCap muss unter der hoechsten Qualitaetsstufe liegen"
            }
        }
        if (balance.bookTierFactor.size != tiers.size) {
            errors += "$ctx: bookTierFactor braucht genau einen Wert pro Qualitaetsstufe"
        }
        if (balance.bookTierFactor.any { it <= 0 } ||
            balance.bookTierFactor.zipWithNext().any { (a, b) -> a > b }
        ) {
            errors += "$ctx: bookTierFactor muss positiv und aufsteigend sein"
        }
        validateModifiers("$ctx/researchedBonus", listOf(balance.researchedBonus), ModifierCategory.BOOK, errors)
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

    private fun validateCost(ctx: String, baseCost: Double, growth: Double, errors: MutableList<String>) {
        if (baseCost <= 0) errors += "$ctx: baseCost muss > 0 sein"
        if (growth <= 1) errors += "$ctx: growth muss > 1 sein"
    }

    private fun validateModifiers(
        ctx: String,
        modifiers: List<Modifier>,
        expected: ModifierCategory,
        errors: MutableList<String>,
    ) {
        modifiers.forEach {
            if (it.category != expected) {
                errors += "$ctx: Modifier fuer '${it.stat.serialName}' hat Kategorie ${it.category}, erwartet $expected"
            }
            if (!it.value.isFinite()) errors += "$ctx: Modifier fuer '${it.stat.serialName}' hat ungueltigen Wert"
        }
    }
}

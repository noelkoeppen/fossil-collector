package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.ModifierCategory

/**
 * Inhaltliche Pruefung der Spieldaten (Referenzen, Wertebereiche, Vollstaendigkeit).
 * Die Struktur (Felder, Typen, bekannte Stat-IDs) prueft bereits die Deserialisierung.
 *
 * Gibt alle gefundenen Probleme zurueck, statt beim ersten abzubrechen.
 */
object DataValidator {

    private val COLOR = Regex("^#[0-9A-Fa-f]{6}$")
    private val ID = Regex("^[a-z][a-z0-9_]*$")

    fun validate(data: GameData): List<String> {
        val e = mutableListOf<String>()
        checkLayers(data, e)
        checkBones(data, e)
        checkSpecies(data, e)
        checkGenerators(data, e)
        checkUpgrades(data, e)
        checkSkills(data, e)
        checkMilestones(data, e)
        checkBalance(data, e)
        checkLocales(data, e)
        return e
    }

    private fun checkIds(file: String, ids: List<String>, e: MutableList<String>) {
        ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            .forEach { e += "$file: doppelte ID '$it'" }
        ids.filterNot { ID.matches(it) }.forEach { e += "$file: ID '$it' ist nicht snake_case" }
    }

    private fun checkLayers(data: GameData, e: MutableList<String>) {
        val f = DataRepository.FILE_LAYERS
        if (data.layers.isEmpty()) e += "$f: keine Schichten"
        checkIds(f, data.layers.map { it.id }, e)
        val orders = data.layers.map { it.order }.sorted()
        if (orders != data.layers.indices.toList()) {
            e += "$f: 'order' muss lueckenlos bei 0 beginnen, ist $orders"
        }
        val first = data.layersInOrder.firstOrNull()
        if (first != null && first.unlockCost != 0.0) {
            e += "$f: erste Schicht '${first.id}' muss unlockCost 0 haben"
        }
        data.layersInOrder.zipWithNext().forEach { (a, b) ->
            if (b.unlockCost <= a.unlockCost) e += "$f: unlockCost von '${b.id}' muss groesser sein als von '${a.id}'"
            if (b.fragility < a.fragility) e += "$f: fragility darf mit der Tiefe nicht sinken ('${b.id}')"
        }
        data.layers.forEach { l ->
            val p = "$f '${l.id}'"
            if (l.teamBaseCost <= 0) e += "$p: teamBaseCost muss > 0 sein"
            if (l.teamGrowth <= 1) e += "$p: teamGrowth muss > 1 sein"
            if (l.teamRate <= 0) e += "$p: teamRate muss > 0 sein"
            if (l.value <= 0) e += "$p: value muss > 0 sein"
            if (l.fragility !in 0.0..1.0) e += "$p: fragility muss in [0, 1] liegen"
            if (l.fameWeight <= 0) e += "$p: fameWeight muss > 0 sein"
            if (!COLOR.matches(l.color)) e += "$p: color '${l.color}' ist kein #RRGGBB"
        }
    }

    private fun checkBones(data: GameData, e: MutableList<String>) {
        val f = DataRepository.FILE_BONES
        checkIds(f, data.bones.map { it.id }, e)
        data.bones.forEach { b ->
            if (b.zones.isEmpty()) e += "$f '${b.id}': keine Zonen"
            checkIds("$f '${b.id}' zones", b.zones, e)
        }
    }

    private fun checkSpecies(data: GameData, e: MutableList<String>) {
        val f = DataRepository.FILE_SPECIES
        checkIds(f, data.species.map { it.id }, e)
        val layerIds = data.layers.map { it.id }.toSet()
        val boneIds = data.bones.map { it.id }.toSet()
        data.species.forEach { s ->
            val p = "$f '${s.id}'"
            if (s.layer !in layerIds) e += "$p: unbekannte Schicht '${s.layer}'"
            if (s.rarityWeight <= 0) e += "$p: rarityWeight muss > 0 sein"
            if (s.bones.isEmpty()) e += "$p: keine Knochen"
            s.bones.filterNot { it in boneIds }.forEach { e += "$p: unbekannter Knochen '$it'" }
            if (s.bones.toSet().size != s.bones.size) e += "$p: doppelte Knochen"
            if (s.art.isBlank()) e += "$p: art fehlt"
            if (s.bookBonus.value <= 0) e += "$p: bookBonus.value muss > 0 sein"
        }
        data.layers.forEach { l ->
            if (data.speciesInLayer(l.id).isEmpty()) e += "$f: Schicht '${l.id}' hat keine Arten"
        }
    }

    private fun checkGenerators(data: GameData, e: MutableList<String>) {
        val f = DataRepository.FILE_GENERATORS
        checkIds(f, data.generators.map { it.id }, e)
        val ids = data.generators.map { it.id }.toSet()
        GameData.REQUIRED_GENERATORS.filterNot { it in ids }.forEach { e += "$f: Pflicht-Generator '$it' fehlt" }
        data.generators.forEach { g ->
            val p = "$f '${g.id}'"
            if (g.baseCost <= 0) e += "$p: baseCost muss > 0 sein"
            if (g.growth <= 1) e += "$p: growth muss > 1 sein"
            if (g.free < 0) e += "$p: free darf nicht negativ sein"
            g.effects.filter { it.category != ModifierCategory.UPGRADE }
                .forEach { e += "$p: Effekt auf ${it.stat} muss Kategorie 'upgrade' haben" }
        }
    }

    private fun checkUpgrades(data: GameData, e: MutableList<String>) {
        val f = DataRepository.FILE_UPGRADES
        checkIds(f, data.upgrades.map { it.id }, e)
        val ids = data.upgrades.map { it.id }.toSet()
        GameData.REQUIRED_UPGRADES.filterNot { it in ids }.forEach { e += "$f: Pflicht-Upgrade '$it' fehlt" }
        data.upgrades.forEach { u ->
            val p = "$f '${u.id}'"
            if (u.baseCost <= 0) e += "$p: baseCost muss > 0 sein"
            if (u.growth <= 1) e += "$p: growth muss > 1 sein"
            if (u.maxLevel != null && u.maxLevel < 1) e += "$p: maxLevel muss >= 1 oder null sein"
            if (u.effectsPerLevel.isEmpty()) e += "$p: keine Effekte"
            u.effectsPerLevel.filter { it.category != ModifierCategory.UPGRADE }
                .forEach { e += "$p: Effekt auf ${it.stat} muss Kategorie 'upgrade' haben" }
        }
    }

    private fun checkSkills(data: GameData, e: MutableList<String>) {
        val f = DataRepository.FILE_SKILLTREE
        checkIds(f, data.skills.map { it.id }, e)
        val byId = data.skills.associateBy { it.id }
        data.skills.forEach { s ->
            val p = "$f '${s.id}'"
            if (s.depth < 1) e += "$p: depth muss >= 1 sein"
            if (s.baseCost <= 0) e += "$p: baseCost muss > 0 sein"
            if (s.effects.isEmpty()) e += "$p: keine Effekte"
            s.effects.filter { it.category != ModifierCategory.SKILL }
                .forEach { e += "$p: Effekt auf ${it.stat} muss Kategorie 'skill' haben" }
            if (s.depth == 1 && s.requires.isNotEmpty()) e += "$p: Knoten der Tiefe 1 darf nichts voraussetzen"
            if (s.depth > 1 && s.requires.isEmpty()) e += "$p: Knoten ab Tiefe 2 braucht eine Voraussetzung"
            s.requires.forEach { r ->
                val req = byId[r]
                when {
                    req == null -> e += "$p: Voraussetzung '$r' existiert nicht"
                    req.branch != s.branch -> e += "$p: Voraussetzung '$r' liegt in einem anderen Ast"
                    req.depth != s.depth - 1 -> e += "$p: Voraussetzung '$r' muss Tiefe ${s.depth - 1} haben"
                }
            }
        }
        data.skills.groupBy { it.branch }.forEach { (branch, nodes) ->
            val capstones = nodes.filter { it.capstone }
            if (capstones.size > 1) e += "$f: Ast $branch hat mehr als einen Capstone"
            val maxDepth = nodes.maxOf { it.depth }
            capstones.filter { it.depth != maxDepth }
                .forEach { e += "$f: Capstone '${it.id}' muss der tiefste Knoten im Ast sein" }
        }
    }

    private fun checkMilestones(data: GameData, e: MutableList<String>) {
        val f = DataRepository.FILE_MILESTONES
        checkIds(f, data.milestones.map { it.id }, e)
        data.milestones.forEach { m ->
            val p = "$f '${m.id}'"
            if (m.condition.value <= 0) e += "$p: condition.value muss > 0 sein"
            val r = m.reward
            if (r.money < 0 || r.researchPoints < 0) e += "$p: Belohnung darf nicht negativ sein"
            if (r.money == 0.0 && r.researchPoints == 0.0) e += "$p: keine Belohnung"
        }
    }

    private fun checkBalance(data: GameData, e: MutableList<String>) {
        val b = data.balance
        val p = DataRepository.FILE_BALANCE
        fun prob(name: String, v: Double) {
            if (v !in 0.0..1.0) e += "$p: $name muss in [0, 1] liegen, ist $v"
        }
        fun positive(name: String, v: Double) {
            if (v <= 0) e += "$p: $name muss > 0 sein, ist $v"
        }
        prob("findChance", b.findChance)
        prob("qualityBaseMin", b.qualityBaseMin)
        prob("qualityBaseMax", b.qualityBaseMax)
        prob("reconstructionCap", b.reconstructionCap)
        prob("reconstructionFillQuality", b.reconstructionFillQuality)
        prob("offlineEfficiency", b.offlineEfficiency)
        if (b.qualityBaseMin > b.qualityBaseMax) e += "$p: qualityBaseMin > qualityBaseMax"
        if (b.damageRollMin < 0 || b.damageRollMin > b.damageRollMax) e += "$p: damageRollMin/Max ungueltig"
        if (b.qualityValueOffset < 0) e += "$p: qualityValueOffset darf nicht negativ sein"
        positive("rockSellFactor", b.rockSellFactor)
        positive("duplicateSellFactor", b.duplicateSellFactor)
        positive("labDuration", b.labDuration)
        positive("skeletonMult", b.skeletonMult)
        positive("missingBoneWeight", b.missingBoneWeight)
        positive("tapDigAmount", b.tapDigAmount)
        positive("tapPrepAmount", b.tapPrepAmount)
        positive("offlineCapHours", b.offlineCapHours)
        positive("teamMilestoneMultiplier", b.teamMilestoneMultiplier)
        if (b.labQueuePerSlot < 1) e += "$p: labQueuePerSlot muss >= 1 sein"
        if (b.slotsPerHall < 1) e += "$p: slotsPerHall muss >= 1 sein"
        if (b.maxDetailedOfflineFinds < 1) e += "$p: maxDetailedOfflineFinds muss >= 1 sein"
        if (b.skillCostGrowth < 1) e += "$p: skillCostGrowth muss >= 1 sein"
        if (b.teamMilestones.any { it <= 0 } || b.teamMilestones != b.teamMilestones.sorted().distinct()) {
            e += "$p: teamMilestones muessen positiv und streng aufsteigend sein"
        }
        if (b.researchedBonus.category != ModifierCategory.BOOK) e += "$p: researchedBonus muss Kategorie 'book' haben"

        // Qualitaetsstufen: alle Stufen genau einmal, in Reihenfolge, streng aufsteigend ab 0.
        val tiers = b.qualityTiers
        if (tiers.map { it.id } != QualityTierId.entries.toList()) {
            e += "$p: qualityTiers muessen genau ${QualityTierId.entries} in dieser Reihenfolge enthalten"
        }
        if (tiers.firstOrNull()?.min != 0.0) e += "$p: erste Qualitaetsstufe muss bei 0 beginnen"
        if (tiers.zipWithNext().any { (a, c) -> c.min <= a.min } || tiers.any { it.min > 1.0 }) {
            e += "$p: Grenzen der Qualitaetsstufen muessen streng aufsteigend in [0, 1] liegen"
        }
        tiers.filterNot { COLOR.matches(it.color) }.forEach { e += "$p: Farbe '${it.color}' ist kein #RRGGBB" }
        if (b.bookTierFactor.size != tiers.size) e += "$p: bookTierFactor braucht ${tiers.size} Eintraege"
        if (b.bookTierFactor.zipWithNext().any { (a, c) -> c < a }) e += "$p: bookTierFactor darf nicht sinken"

        // Kernregel: Rekonstruktion erreicht nie die hoechste Stufe (Kapitel 4.5).
        val top = tiers.lastOrNull()
        if (top != null && b.reconstructionCap >= top.min) {
            e += "$p: reconstructionCap muss unter der Grenze von ${top.id} liegen"
        }

        b.statLimits.forEach { (stat, limit) ->
            if (limit.min == null && limit.max == null) e += "$p: statLimits.$stat ohne Grenze"
            if (limit.min != null && limit.max != null && limit.min > limit.max) e += "$p: statLimits.$stat min > max"
        }
    }

    private fun checkLocales(data: GameData, e: MutableList<String>) {
        val required = LocaleKeys.requiredKeys(data)
        val keySets = data.locales.mapValues { it.value.keys }
        data.locales.forEach { (lang, texts) ->
            val f = DataRepository.localeFile(lang)
            (required - texts.keys).sorted().forEach { e += "$f: Schluessel '$it' fehlt" }
            texts.filterValues { it.isBlank() }.keys.sorted().forEach { e += "$f: Text fuer '$it' ist leer" }
        }
        // Beide Sprachen muessen dieselben Schluessel haben, auch ueber die Pflichtschluessel hinaus.
        val all = keySets.values.flatten().toSet()
        keySets.forEach { (lang, keys) ->
            (all - keys - required).sorted()
                .forEach { e += "${DataRepository.localeFile(lang)}: Schluessel '$it' fehlt (in anderer Sprache vorhanden)" }
        }
    }
}

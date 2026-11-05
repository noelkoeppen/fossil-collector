package de.noelkoeppen.fossilcollector.core.data

/**
 * Alle statischen Spieldaten, geladen und validiert durch [DataRepository].
 * Unveraenderlich; Lookups per ID sind vorberechnet.
 */
class GameData(
    val layers: List<LayerDef>,
    val species: List<SpeciesDef>,
    val bones: List<BoneDef>,
    val generators: List<GeneratorDef>,
    val upgrades: List<UpgradeDef>,
    val skills: List<SkillNodeDef>,
    val milestones: List<MilestoneDef>,
    val balance: BalanceDef,
    /** Sprachcode (z. B. "de") -> Schluessel -> Text. */
    val locales: Map<String, Map<String, String>>,
) {
    /** Schichten sortiert nach `order` (oben jung, unten alt). */
    val layersInOrder: List<LayerDef> = layers.sortedBy { it.order }

    private val layerById = layers.associateBy { it.id }
    private val speciesById = species.associateBy { it.id }
    private val boneById = bones.associateBy { it.id }
    private val generatorById = generators.associateBy { it.id }
    private val upgradeById = upgrades.associateBy { it.id }
    private val skillById = skills.associateBy { it.id }
    private val speciesByLayer = species.groupBy { it.layer }

    fun layer(id: String): LayerDef = layerById[id] ?: unknown("layer", id)
    fun species(id: String): SpeciesDef = speciesById[id] ?: unknown("species", id)
    fun bone(id: String): BoneDef = boneById[id] ?: unknown("bone", id)
    fun generator(id: String): GeneratorDef = generatorById[id] ?: unknown("generator", id)
    fun upgrade(id: String): UpgradeDef = upgradeById[id] ?: unknown("upgrade", id)
    fun skill(id: String): SkillNodeDef = skillById[id] ?: unknown("skill", id)

    fun speciesInLayer(layerId: String): List<SpeciesDef> = speciesByLayer[layerId].orEmpty()

    private fun unknown(kind: String, id: String): Nothing =
        throw NoSuchElementException("Unbekannte $kind-ID: $id")

    companion object {
        /** IDs, auf die die Engine direkt zugreift. Muessen in den Daten vorhanden sein. */
        const val GENERATOR_PREPARATOR = "preparator"
        const val GENERATOR_HALL = "hall"
        const val GENERATOR_LAB_SLOT = "lab_slot"
        const val UPGRADE_TOOLS = "tools"

        val REQUIRED_GENERATORS = listOf(GENERATOR_PREPARATOR, GENERATOR_HALL, GENERATOR_LAB_SLOT)
        val REQUIRED_UPGRADES = listOf(UPGRADE_TOOLS)

        /** Sprachen, die vollstaendig vorhanden sein muessen (Kapitel 12). */
        val SUPPORTED_LANGUAGES = listOf("de", "en")
    }
}

/** Aufbau der Text-Schluessel fuer Inhaltstexte (Kapitel 12). */
object LocaleKeys {
    fun layerName(id: String) = "layer.$id.name"
    fun speciesName(id: String) = "species.$id.name"
    fun boneName(id: String) = "bone.$id.name"
    fun zoneName(boneId: String, zoneId: String) = "zone.$boneId.$zoneId"
    fun skillName(id: String) = "skill.$id.name"
    fun skillDescription(id: String) = "skill.$id.desc"
    fun skillBranchName(branch: SkillBranch) = "skill_branch.${branchKey(branch)}.name"
    fun milestoneName(id: String) = "milestone.$id.name"
    fun milestoneDescription(id: String) = "milestone.$id.desc"
    fun qualityTierName(tier: QualityTierId) = "quality.${tier.name.lowercase()}.name"

    private fun branchKey(branch: SkillBranch) = branch.name.lowercase()

    /** Alle Schluessel, die die Daten voraussetzen. Jede Sprache muss sie enthalten. */
    fun requiredKeys(data: GameData): Set<String> = buildSet {
        data.layers.forEach { add(layerName(it.id)) }
        data.species.forEach { add(speciesName(it.id)) }
        data.bones.forEach { bone ->
            add(boneName(bone.id))
            bone.zones.forEach { add(zoneName(bone.id, it)) }
        }
        data.skills.forEach {
            add(skillName(it.id))
            add(skillDescription(it.id))
        }
        data.skills.map { it.branch }.distinct().forEach { add(skillBranchName(it)) }
        data.milestones.forEach {
            add(milestoneName(it.id))
            add(milestoneDescription(it.id))
        }
        data.balance.qualityTiers.forEach { add(qualityTierName(it.id)) }
    }
}

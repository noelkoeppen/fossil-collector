package de.noelkoeppen.fossilcollector.core.data

/**
 * Alle geladenen Spieldefinitionen. Wird von [DataRepository] erzeugt und ist unveraenderlich.
 * Listen behalten die Reihenfolge der JSON-Dateien; Schichten sind nach `order` sortiert.
 */
data class GameData(
    val layers: List<LayerDefinition>,
    val species: List<SpeciesDefinition>,
    val bones: List<BoneDefinition>,
    val generators: List<GeneratorDefinition>,
    val upgrades: List<UpgradeDefinition>,
    val skills: List<SkillDefinition>,
    val milestones: List<MilestoneDefinition>,
    val balance: BalanceDefinition,
    /** Inhaltstexte je Sprachcode (`de`, `en`): Schluessel -> Text. */
    val locales: Map<String, Map<String, String>>,
) {
    val layersById: Map<String, LayerDefinition> = layers.associateBy { it.id }
    val speciesById: Map<String, SpeciesDefinition> = species.associateBy { it.id }
    val bonesById: Map<String, BoneDefinition> = bones.associateBy { it.id }
    val generatorsById: Map<String, GeneratorDefinition> = generators.associateBy { it.id }
    val upgradesById: Map<String, UpgradeDefinition> = upgrades.associateBy { it.id }
    val skillsById: Map<String, SkillDefinition> = skills.associateBy { it.id }
    val milestonesById: Map<String, MilestoneDefinition> = milestones.associateBy { it.id }

    /** Arten je Schicht-ID in Dateireihenfolge. */
    val speciesByLayer: Map<String, List<SpeciesDefinition>> = species.groupBy { it.layer }

    fun layer(id: String): LayerDefinition =
        layersById[id] ?: throw NoSuchElementException("Unbekannte Schicht: $id")

    fun species(id: String): SpeciesDefinition =
        speciesById[id] ?: throw NoSuchElementException("Unbekannte Art: $id")

    fun bone(id: String): BoneDefinition =
        bonesById[id] ?: throw NoSuchElementException("Unbekannter Knochen: $id")

    fun generator(id: String): GeneratorDefinition =
        generatorsById[id] ?: throw NoSuchElementException("Unbekannter Generator: $id")

    fun upgrade(id: String): UpgradeDefinition =
        upgradesById[id] ?: throw NoSuchElementException("Unbekanntes Upgrade: $id")

    fun skill(id: String): SkillDefinition =
        skillsById[id] ?: throw NoSuchElementException("Unbekannter Skill: $id")

    fun milestone(id: String): MilestoneDefinition =
        milestonesById[id] ?: throw NoSuchElementException("Unbekannter Meilenstein: $id")
}

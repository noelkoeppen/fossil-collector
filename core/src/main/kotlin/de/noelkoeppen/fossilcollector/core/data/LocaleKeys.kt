package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.StatId

/**
 * Schluesselschema fuer Inhaltstexte in `data/locale/<sprache>.json` (Kapitel 12).
 * Einzige Stelle, an der die Schluessel gebildet werden.
 */
object LocaleKeys {
    fun layerName(layerId: String) = "layer.$layerId.name"
    fun speciesName(speciesId: String) = "species.$speciesId.name"
    fun boneName(boneId: String) = "bone.$boneId.name"
    fun zoneName(boneId: String, zoneId: String) = "zone.$boneId.$zoneId"
    fun generatorName(generatorId: String) = "generator.$generatorId.name"
    fun upgradeName(upgradeId: String) = "upgrade.$upgradeId.name"
    fun skillBranchName(branch: SkillBranch) = "skill_branch.${branch.serialName}.name"
    fun skillName(skillId: String) = "skill.$skillId.name"
    fun skillDescription(skillId: String) = "skill.$skillId.desc"
    fun milestoneName(milestoneId: String) = "milestone.$milestoneId.name"
    fun milestoneDescription(milestoneId: String) = "milestone.$milestoneId.desc"
    fun qualityTierName(tierId: String) = "quality.$tierId.name"

    /** Anzeigename eines Stats (z. B. fuer Buch-Boni im Sammelbuch, AP-20). */
    fun statName(stat: StatId) = "stat.${stat.serialName}.name"

    /** Alle Schluessel, die fuer die gegebenen Daten in jeder Sprache vorhanden sein muessen. */
    fun requiredKeys(data: GameData): Set<String> = buildSet {
        data.layers.forEach { add(layerName(it.id)) }
        data.species.forEach { add(speciesName(it.id)) }
        data.bones.forEach { bone ->
            add(boneName(bone.id))
            bone.zones.forEach { add(zoneName(bone.id, it)) }
        }
        data.generators.forEach { add(generatorName(it.id)) }
        data.upgrades.forEach { add(upgradeName(it.id)) }
        data.skills.map { it.branch }.distinct().forEach { add(skillBranchName(it)) }
        data.skills.forEach {
            add(skillName(it.id))
            add(skillDescription(it.id))
        }
        data.milestones.forEach {
            add(milestoneName(it.id))
            add(milestoneDescription(it.id))
        }
        data.balance.qualityTiers.forEach { add(qualityTierName(it.id)) }
        StatId.entries.forEach { add(statName(it)) }
    }
}

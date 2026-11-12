package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.StatId

/**
 * Schluesselschema fuer Inhaltstexte in `data/locale/<sprache>.json`.
 * Einzige Stelle, an der die Schluessel gebildet werden.
 */
object LocaleKeys {
    fun layerName(layerId: String) = "layer.$layerId.name"
    fun speciesName(speciesId: String) = "species.$speciesId.name"
    /** Knochennamen sind pro Art, weil jede Art ihre eigene Knochenliste aus dem Modell hat. */
    fun boneName(speciesId: String, boneId: String) = "bone.$speciesId.$boneId.name"
    fun qualityTierName(tierId: String) = "quality.$tierId.name"

    /** Anzeigename eines Stats. */
    fun statName(stat: StatId) = "stat.${stat.serialName}.name"

    /** Alle Schluessel, die fuer die gegebenen Daten in jeder Sprache vorhanden sein muessen. */
    fun requiredKeys(data: GameData): Set<String> = buildSet {
        data.layers.forEach { add(layerName(it.id)) }
        data.species.forEach { species ->
            add(speciesName(species.id))
            species.bones.forEach { add(boneName(species.id, it)) }
        }
        data.balance.qualityTiers.forEach { add(qualityTierName(it.id)) }
        StatId.entries.forEach { add(statName(it)) }
    }
}

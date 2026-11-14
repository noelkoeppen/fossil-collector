package de.noelkoeppen.fossilcollector.core.data

/**
 * Alle geladenen Spieldefinitionen. Wird von [DataRepository] erzeugt und ist unveraenderlich.
 * Listen behalten die Reihenfolge der JSON-Dateien; Schichten sind nach `order` sortiert.
 */
data class GameData(
    val layers: List<LayerDefinition>,
    val species: List<SpeciesDefinition>,
    val balance: BalanceDefinition,
    /** Grabungswerkzeuge in Dateireihenfolge (AP2-05). */
    val tools: List<ToolDefinition>,
    /** Inhaltstexte je Sprachcode (`de`, `en`): Schluessel -> Text. */
    val locales: Map<String, Map<String, String>>,
) {
    val layersById: Map<String, LayerDefinition> = layers.associateBy { it.id }
    val speciesById: Map<String, SpeciesDefinition> = species.associateBy { it.id }
    val toolsById: Map<String, ToolDefinition> = tools.associateBy { it.id }

    /** Arten je Schicht-ID in Dateireihenfolge. */
    val speciesByLayer: Map<String, List<SpeciesDefinition>> = species.groupBy { it.layer }

    fun layer(id: String): LayerDefinition =
        layersById[id] ?: throw NoSuchElementException("Unbekannte Schicht: $id")

    fun species(id: String): SpeciesDefinition =
        speciesById[id] ?: throw NoSuchElementException("Unbekannte Art: $id")

    fun tool(id: String): ToolDefinition =
        toolsById[id] ?: throw NoSuchElementException("Unbekanntes Werkzeug: $id")
}

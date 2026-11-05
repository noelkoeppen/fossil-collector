package de.noelkoeppen.fossilcollector.core.data

import kotlinx.serialization.Serializable

/**
 * Ein Knochentyp aus `bones.json` (Kapitel 4.3). Die Anzahl der Zonen ist pro Knochen
 * konfigurierbar; Code darf nicht fest von 4 ausgehen.
 */
@Serializable
data class BoneDefinition(
    val id: String,
    val zones: List<String>,
)

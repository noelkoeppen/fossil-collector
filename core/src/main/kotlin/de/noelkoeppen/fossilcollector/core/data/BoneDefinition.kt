package de.noelkoeppen.fossilcollector.core.data

import kotlinx.serialization.Serializable

/**
 * Ein Knochentyp aus `bones.json`. Jeder Knochen hat genau eine Qualitaet (Kapitel 4).
 */
@Serializable
data class BoneDefinition(
    val id: String,
)

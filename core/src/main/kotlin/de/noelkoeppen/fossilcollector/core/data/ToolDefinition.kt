package de.noelkoeppen.fossilcollector.core.data

import kotlinx.serialization.Serializable

/**
 * Ein Grabungswerkzeug fuer das grobe Buddeln aus `tools.json` (Kapitel 3, AP2-05).
 *
 * Eine Anwendung wirkt auf alle Zellen, deren Mittelpunkt hoechstens [radius] Zellen vom
 * Ansatzpunkt entfernt ist, und traegt dort bis zu [removal] Erdeinheiten ab. Ein grobes Werkzeug
 * ([rough]) beschaedigt jeden Knochen, den es trifft, um [boneDamage] (vor `damage_reduction`).
 * Preise und Freischaltung kommen mit AP2-14.
 */
@Serializable
data class ToolDefinition(
    val id: String,
    /** Abgetragene Erde pro Anwendung und Zelle in Erdeinheiten. */
    val removal: Int,
    /** Wirkradius um den Ansatzpunkt in Zellen. */
    val radius: Double,
    /** Grobes Werkzeug (z. B. Schaufel): beschaedigt getroffene Knochen. */
    val rough: Boolean,
    /** Qualitaetsverlust pro Anwendung und getroffenem Knochen; nur bei groben Werkzeugen > 0. */
    val boneDamage: Double,
)

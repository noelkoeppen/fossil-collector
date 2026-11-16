package de.noelkoeppen.fossilcollector.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Einsatzbereich eines Werkzeugs: grobes Buddeln in der Grabungsflaeche ([DIG], Kapitel 3) oder
 * feines Freilegen in der Nahansicht eines Knochens ([PREPARE], Kapitel 5.1, AP2-08). Ein Werkzeug
 * wirkt nur in seinem Bereich; die Einheiten von [ToolDefinition.radius] und
 * [ToolDefinition.strokeSpacing] sind Zellen der Flaeche bzw. feine Zellen der Nahansicht.
 */
@Serializable
enum class ToolStage {
    @SerialName("dig") DIG,
    @SerialName("prepare") PREPARE,
}

/**
 * Ein Werkzeug aus `tools.json`: grobes Buddeln (Kapitel 3, AP2-05) oder feines Freilegen
 * (Kapitel 5.1, AP2-08), siehe [stage].
 *
 * Eine Anwendung wirkt auf alle Zellen, deren Mittelpunkt hoechstens [radius] Zellen vom
 * Ansatzpunkt entfernt ist, und traegt dort bis zu [removal] Erdeinheiten ab. Ein grobes Werkzeug
 * ([rough]) beschaedigt jeden Knochen, den es trifft, um [boneDamage] (vor `damage_reduction`).
 * Beim Wischen wirkt es alle [strokeSpacing] Zellen zurueckgelegter Strecke einmal.
 * Preise und Freischaltung kommen mit AP2-14.
 *
 * Beim feinen Freilegen ([ToolStage.PREPARE]) zaehlen die Werte in feinen Zellen und Einheiten der
 * Restschicht; harte Stellen loest nur ein Werkzeug mit [removesHard] (Kratzer), lose Erde jedes
 * (Pinsel und Kratzer). Ein grobes Werkzeug trifft dort den Knochen, wenn es eine Knochenzelle
 * beruehrt, auf der keine Restschicht mehr liegt (Regel in `Preparer`).
 */
@Serializable
data class ToolDefinition(
    val id: String,
    /** Einsatzbereich: grobes Buddeln oder feines Freilegen. */
    val stage: ToolStage = ToolStage.DIG,
    /** Abgetragene Erde pro Anwendung und Zelle in Erdeinheiten. */
    val removal: Int,
    /** Wirkradius um den Ansatzpunkt in Zellen. */
    val radius: Double,
    /** Grobes Werkzeug (z. B. Schaufel, Kratzer): beschaedigt getroffene Knochen. */
    val rough: Boolean,
    /** Qualitaetsverlust pro Anwendung und getroffenem Knochen; nur bei groben Werkzeugen > 0. */
    val boneDamage: Double,
    /**
     * Abstand in Zellen zwischen zwei Anwendungen entlang einer Wischbewegung (AP2-07): Die UI loest
     * die Aktion beim Aufsetzen des Fingers einmal aus und danach jedes Mal, wenn der Finger diese
     * Strecke weiter gewischt hat (siehe `DigStroke`).
     */
    val strokeSpacing: Double,
    /** Loest harte Stellen der Restschicht (nur feines Freilegen, AP2-08; beim groben Buddeln immer `false`). */
    val removesHard: Boolean = false,
)

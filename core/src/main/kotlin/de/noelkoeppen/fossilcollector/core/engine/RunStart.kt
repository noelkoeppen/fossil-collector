package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DefinitionIds
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.RunStartValues
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import kotlin.math.floor

/**
 * Startwerte eines neuen Durchlaufs (Spielbeginn und nach dem Prestige, Kapitel 5.3).
 *
 * Gehoert zu AP-06, weil die Gratis-Einheiten (`generators.json: free`) direkt den Preisindex der
 * Kostenkurven bestimmen (siehe [de.noelkoeppen.fossilcollector.core.engine.purchase.Purchases]).
 *
 * - Teams in der ersten Schicht: `start_teams` (additiv, Basis 0, abgerundet)
 * - Laborplaetze: `lab_slot.free` + `start_lab_slots` (additiv, abgerundet)
 * - Hallen: `hall.free`; Praeparatoren: `preparator.free`
 * - Geld, FP und Werkzeugstufe: 0
 *
 * Voraussetzung: Die Skill-Quellen der [ModifierEngine] sind aktuell (z. B. per
 * [ModifierSources.sync] mit dem Metazustand nach dem Prestige bzw. Respec).
 */
object RunStart {

    fun values(data: GameData, modifiers: ModifierEngine): RunStartValues = RunStartValues(
        money = GameNumber.ZERO,
        researchPoints = GameNumber.ZERO,
        teamsInFirstLayer = whole(modifiers.additive(StatId.START_TEAMS)),
        preparators = whole(data.generator(DefinitionIds.PREPARATOR).free.toDouble()),
        halls = whole(data.generator(DefinitionIds.HALL).free.toDouble()),
        labSlots = whole(
            data.generator(DefinitionIds.LAB_SLOT).free + modifiers.additive(StatId.START_LAB_SLOTS),
        ),
        toolLevel = GameNumber.ZERO,
    )

    /** Ganze, nicht negative Anzahl; kleine Rundungsfehler der Summen (z. B. 4.9999999) zaehlen voll. */
    private fun whole(value: Double): GameNumber =
        GameNumber(floor(value + ROUNDING_TOLERANCE).coerceAtLeast(0.0))

    /** Toleranz gegen Gleitkomma-Rundung, kein Spielwert. */
    private const val ROUNDING_TOLERANCE: Double = 1e-9
}

package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.GameState

/**
 * Markiert alle Meilensteine als erreicht. Fuer Integrationstests ueber [GameEngine.advance], die
 * exakte Geld-/FP-Werte oder Ereignislisten pruefen und deshalb keine Meilenstein-Belohnungen
 * (AP-11) erwarten.
 */
internal fun GameState.withAllMilestonesDone(data: GameData): GameState =
    copy(meta = meta.copy(milestonesDone = data.milestones.mapTo(LinkedHashSet()) { it.id }))

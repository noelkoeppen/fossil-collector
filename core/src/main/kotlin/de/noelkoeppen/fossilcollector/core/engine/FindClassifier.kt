package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.QueuedPiece
import de.noelkoeppen.fossilcollector.core.model.RunState
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Funde einordnen (GAME_DESIGN.md, Kapitel 4.4), als [FindHandler] der Praeparation.
 *
 * Fuer jeden Fund mit Qualitaet `q` (Mittelwert der Zonen):
 * 1. **Sammelbuch:** Gibt es keinen Eintrag fuer den `pieceKey` oder ist `q` **echt hoeher**, wird
 *    der Eintrag ersetzt (`BookEntry(q, reconstructed = false, zones)`), Ereignis
 *    [GameEvent.BookUpdated]. Das Buch ist permanent und unabhaengig vom Run-Bestand.
 * 2. **Run-Bestand:** Gibt es keinen Run-Fund, wird der Fund aufgenommen ([GameEvent.NewPiece],
 *    ggf. [GameEvent.SkeletonCompleted]). Ist `q` echt hoeher als der Run-Fund, ersetzt er diesen
 *    ([GameEvent.PieceImproved]); das **ersetzte** Stueck ist dann das Duplikat.
 * 3. **Duplikat** (der Fund selbst bei gleicher oder schlechterer Qualitaet, sonst das ersetzte
 *    Stueck): in die Laborwarteschlange, solange `labQueue.size < labSlots * labQueuePerSlot`
 *    ([GameEvent.DuplicateQueued]); sonst sofort verkauft fuer
 *    `layer.value * (0.5 + q) * duplicateSellFactor * mods(sell_price)` ([GameEvent.DuplicateSold]).
 *    `layer` ist die Schicht der Art. Die Verarbeitung der Warteschlange ist AP-10.
 *
 * Gleiche Qualitaet gilt nicht als besser (keine Ersetzung). Die Modifier-Quellen muessen aktuell
 * sein; [GameEngine] gleicht sie vor jedem Aufruf ab.
 */
class FindClassifier(
    private val data: GameData,
    private val modifiers: ModifierEngine,
) : FindHandler {

    /** Maximale Laenge der Laborwarteschlange: `labSlots * balance.labQueuePerSlot`. */
    fun labQueueMax(run: RunState): Double = run.labSlots.toDouble() * data.balance.labQueuePerSlot

    /** `true`, wenn in der Laborwarteschlange von [run] noch Platz ist. */
    fun hasQueueSpace(run: RunState): Boolean = run.labQueue.size.toDouble() < labQueueMax(run)

    /**
     * Verkaufserloes eines Duplikats mit Schluessel [key] und Qualitaet [quality]:
     * `layer.value * (0.5 + q) * balance.duplicateSellFactor * mods(sell_price)`.
     */
    fun sellValue(key: PieceKey, quality: Double): GameNumber {
        val layer = data.layer(data.species(key.speciesId).layer)
        val value = layer.value * (QUALITY_OFFSET + quality) * data.balance.duplicateSellFactor *
            modifiers.factor(StatId.SELL_PRICE)
        return GameNumber(value)
    }

    override fun onFind(state: GameState, find: Find, context: AdvanceContext): GameState {
        val key = find.pieceKey
        val quality = find.quality

        // 1. Sammelbuch (permanent)
        var meta = state.meta
        val bookEntry = meta.book[key]
        if (bookEntry == null || quality > bookEntry.quality) {
            meta = meta.copy(book = meta.book + (key to BookEntry(quality, reconstructed = false, zones = find.zones)))
            context.emit(GameEvent.BookUpdated(key, quality, bookEntry?.quality))
        }

        // 2. Run-Bestand
        val run = state.run
        val existing = run.pieces[key]
        if (existing == null) {
            val newRun = run.copy(pieces = run.pieces + (key to find.toPiece()))
            context.emit(GameEvent.NewPiece(key, quality))
            val species = data.speciesById[key.speciesId]
            if (species != null && !Skeletons.isCompleteInRun(run, species) && Skeletons.isCompleteInRun(newRun, species)) {
                context.emit(GameEvent.SkeletonCompleted(species.id))
            }
            return state.copy(meta = meta, run = newRun)
        }

        val (keptRun, duplicate) = if (quality > existing.quality) {
            context.emit(GameEvent.PieceImproved(key, quality, existing.quality))
            run.copy(pieces = run.pieces + (key to find.toPiece())) to existing
        } else {
            run to find.toPiece()
        }

        // 3. Duplikat: Laborwarteschlange oder Verkauf
        return state.copy(meta = meta, run = handleDuplicate(keptRun, key, duplicate, context::emit))
    }

    /**
     * Behandelt [piece] als Duplikat (Schritt 3): Laborwarteschlange, falls Platz ist, sonst
     * Sofortverkauf. Wird auch von der Rekonstruktion fuer das ersetzte Run-Stueck genutzt.
     */
    internal fun handleDuplicate(run: RunState, key: PieceKey, piece: Piece, emit: (GameEvent) -> Unit): RunState {
        if (hasQueueSpace(run)) {
            emit(GameEvent.DuplicateQueued(key, piece.quality))
            return run.copy(labQueue = run.labQueue + QueuedPiece(key, piece.zones))
        }
        val money = sellValue(key, piece.quality)
        emit(GameEvent.DuplicateSold(key, piece.quality, money))
        return run.copy(money = run.money + money)
    }

    companion object {
        /** Formelbestandteil `(0.5 + q)` aus Kapitel 4.4 und 4.6 (kein Balancing-Wert). */
        internal const val QUALITY_OFFSET: Double = 0.5
    }
}

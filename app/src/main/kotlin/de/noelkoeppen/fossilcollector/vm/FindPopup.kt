package de.noelkoeppen.fossilcollector.vm

import androidx.compose.runtime.Immutable
import de.noelkoeppen.fossilcollector.core.engine.GameEvent
import de.noelkoeppen.fossilcollector.core.engine.QualityTiers
import de.noelkoeppen.fossilcollector.core.model.PieceKey

/** Art eines Fund-Popups (Kapitel 8.3). */
enum class FindPopupKind {
    /** Erster Fund dieses Knochens im Durchlauf ([GameEvent.NewPiece]). */
    NEW,

    /** Besser als der bisherige Run-Fund ([GameEvent.PieceImproved]). */
    IMPROVED,
}

/**
 * Inhalt eines Fund-Popups: Art, Knochen (aus [pieceKey]) und Qualitaetsstufe.
 *
 * @property quality Qualitaet des Stuecks (0..1)
 * @property tierId Qualitaetsstufe (Farbe und Name)
 * @property tierIndex Rang der Stufe, 0 = niedrigste (Prioritaet beim Buendeln)
 */
@Immutable
data class FindPopup(
    val pieceKey: PieceKey,
    val quality: Double,
    val tierId: String,
    val tierIndex: Int,
    val kind: FindPopupKind,
) {
    companion object {
        /**
         * Popup fuer ein Ereignis aus `core`: nur [GameEvent.NewPiece] und [GameEvent.PieceImproved]
         * ("neuer oder besserer Fund"), sonst `null`. Die Stufe kommt aus [QualityTiers].
         */
        fun fromEvent(event: GameEvent, tiers: QualityTiers): FindPopup? {
            val (key, quality, kind) = when (event) {
                is GameEvent.NewPiece -> Triple(event.pieceKey, event.quality, FindPopupKind.NEW)
                is GameEvent.PieceImproved -> Triple(event.pieceKey, event.quality, FindPopupKind.IMPROVED)
                else -> return null
            }
            val index = tiers.indexOf(quality)
            return FindPopup(key, quality, tiers.tiers[index].id, index, kind)
        }
    }
}

/**
 * Buendelt Fund-Popups (Kapitel 8.3): hoechstens eines je [intervalNanos], bei mehreren
 * Kandidaten gewinnt die bessere Qualitaetsstufe (bei gleicher Stufe die hoehere Qualitaet, bei
 * Gleichstand der fruehere Fund).
 *
 * Ablauf: Jeder neue Kandidat geht ueber [offer] in einen Ein-Platz-Puffer, der nur den besten
 * behaelt. [poll] gibt ihn heraus, sobald seit dem letzten gezeigten Popup mindestens
 * [intervalNanos] vergangen sind, und leert den Puffer. Kandidaten, die waehrend der Sperrzeit
 * kommen, werden also zu einem Popup zusammengefasst; schwaechere verfallen.
 *
 * Zeit: monotone Nanosekunden des Aufrufers. Nicht thread-safe (Nutzung auf dem Spiel-Thread).
 */
class FindPopupBundler(private val intervalNanos: Long = DEFAULT_INTERVAL_NANOS) {

    private var pending: FindPopup? = null
    private var lastShownAt: Long? = null

    /** Merkt [popup] vor, wenn es besser ist als der bisher vorgemerkte Kandidat. */
    fun offer(popup: FindPopup) {
        val current = pending
        if (current == null || isBetter(popup, current)) pending = popup
    }

    /** Das naechste anzuzeigende Popup zum Zeitpunkt [nowNanos] oder `null`. */
    fun poll(nowNanos: Long): FindPopup? {
        val candidate = pending ?: return null
        val last = lastShownAt
        if (last != null && nowNanos - last < intervalNanos) return null
        pending = null
        lastShownAt = nowNanos
        return candidate
    }

    /** Verwirft den vorgemerkten Kandidaten und die Sperrzeit (z. B. nach dem Laden). */
    fun reset() {
        pending = null
        lastShownAt = null
    }

    companion object {
        /** Hoechstens ein Popup pro Sekunde (Kapitel 8.3). */
        const val DEFAULT_INTERVAL_NANOS: Long = 1_000_000_000L

        /** `true`, wenn [candidate] Vorrang vor [current] hat. */
        fun isBetter(candidate: FindPopup, current: FindPopup): Boolean =
            candidate.tierIndex > current.tierIndex ||
                (candidate.tierIndex == current.tierIndex && candidate.quality > current.quality)
    }
}

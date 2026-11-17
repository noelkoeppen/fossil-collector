package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.LiftSample
import de.noelkoeppen.fossilcollector.core.engine.Recoverer
import de.noelkoeppen.fossilcollector.core.engine.RecoveryGenerator
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.random.GameRandom

/**
 * Rueckmeldung beim Bergen. [sequence] steigt mit jeder neuen Rueckmeldung, damit die UI eine kurze
 * Markierung genau einmal abspielt.
 *
 * @property jerks Rucke der laufenden bzw. letzten Hebebewegung
 * @property newJerk diese Rueckmeldung meldet einen neuen Ruck
 * @property securedSpot mit dem letzten Tippen gesicherte Stelle (Index in `spots`) oder `null`
 * @property missedTap das letzte Tippen hat keine ungesicherte Stelle getroffen
 * @property dropped die letzte Hebebewegung endete vor dem Herausheben (Knochen faellt zurueck)
 * @property loss Qualitaetsverlust der letzten abgeschlossenen Hebebewegung
 */
data class RecoveryFeedback(
    val sequence: Long,
    val jerks: Int = 0,
    val newJerk: Boolean = false,
    val securedSpot: Int? = null,
    val missedTap: Boolean = false,
    val dropped: Boolean = false,
    val loss: Double = 0.0,
) {
    companion object {
        val NONE = RecoveryFeedback(0)
    }
}

/**
 * Ergebnis eines erfolgreichen Bergens fuer die Anzeige. Gezaehlt wird seit dem Oeffnen des
 * Bergens, also auch Rucke bei Versuchen, bei denen der Knochen zurueckgefallen ist.
 *
 * @property piece der Knochen, wie er ins Inventar kam
 * @property qualityBefore Qualitaet beim Oeffnen des Bergens
 * @property jerks Rucke seit dem Oeffnen
 * @property unsecuredSpots ungesicherte Stellen beim Herausheben
 */
data class RecoveryOutcome(
    val piece: Piece,
    val qualityBefore: Double,
    val jerks: Int,
    val unsecuredSpots: Int,
) {
    /** Verlust beim Bergen. */
    val loss: Double get() = qualityBefore - piece.quality
}

/**
 * Ablauf des Bergens (AP2-09, Kapitel 5.2) zwischen UI und `core`, ohne Android: Bergen eines fertig
 * freigelegten Knochens oeffnen (beim ersten Mal [RecoveryGenerator.start]), Tippen sichert eine
 * Stelle ([Recoverer.secure]), Ziehen sammelt die Bewegung als [LiftSample]s und laesst sie von
 * [Recoverer.evaluate] bewerten; ist der Knochen heraus oder hebt der Finger ab, wendet
 * [Recoverer.lift] die Bewegung an. Die Regeln stehen in `core`. Nicht threadsicher, laeuft im
 * ViewModel auf dem Spiel-Thread.
 *
 * Tippen und Ziehen unterscheidet die gemeinsame Wisch-Bedienung (`digGestures`): Ein Ziehen ruft
 * nach dem Aufsetzen [drag] auf, ein Tippen nicht.
 *
 * Bedienzustand (offener Knochen, laufende Bewegung, Rueckmeldung, Ergebnis) wird nicht
 * gespeichert; bruechige Stellen, Sicherungen, Qualitaet und Inventar stehen im [GameState].
 */
class RecoverySession(
    data: GameData,
    modifiers: ModifierEngine = ModifierEngine.fromGameData(data),
) {
    private val generator = RecoveryGenerator(data)
    private val recoverer = Recoverer(data, modifiers)

    /** Knochen, dessen Bergen offen ist, oder `null`. */
    var openBone: BoneRef? = null
        private set

    /** Letzte Rueckmeldung. */
    var feedback: RecoveryFeedback = RecoveryFeedback.NONE
        private set

    /** Ergebnis, sobald der offene Knochen geborgen ist, sonst `null`. */
    var outcome: RecoveryOutcome? = null
        private set

    private val samples = ArrayList<LiftSample>()
    private var touch: Pair<Double, Double>? = null
    private var dragging = false
    private var openQuality = 0.0
    private var jerkTotal = 0

    /**
     * Bergen von [ref] oeffnen. Hat es noch nicht begonnen, beginnt es jetzt (Zufall fuer die
     * bruechigen Stellen aus [random]). Nur fuer fertig freigelegte, nicht geborgene Knochen der
     * laufenden Ausgrabung; sonst bleibt alles unveraendert.
     */
    fun open(state: GameState, ref: BoneRef, random: GameRandom): GameState {
        val site = state.excavation ?: return state
        if (ref !in site.bones || !site.isPrepared(ref) || site.isRecovered(ref)) return state
        reset()
        openBone = ref
        outcome = null
        feedback = RecoveryFeedback(feedback.sequence + 1)
        val next = generator.start(site, ref, random)
        openQuality = next.piece(ref).quality
        jerkTotal = 0
        return if (next === site) state else state.copy(excavation = next)
    }

    /** Bergen schliessen. */
    fun close() {
        reset()
        openBone = null
        outcome = null
    }

    /** Finger setzt auf bzw. Tippen an ([x], [y]) in feinen Zellkoordinaten der Nahansicht. */
    fun touchStart(x: Double, y: Double) {
        touch = x to y
    }

    /**
     * Sample der Fingerbewegung: Zeit in ms, Position in dp. [down] beginnt eine neue Beruehrung.
     * Aendert den Spielstand nicht; bewertet wird in [drag].
     */
    fun sample(timeMillis: Long, x: Double, y: Double, down: Boolean) {
        if (down) reset()
        if (samples.isNotEmpty() && timeMillis < samples.last().timeMillis) return
        samples += LiftSample(timeMillis, x, y)
    }

    /**
     * Der Finger zieht (Bewegung ueber die Beruehrungs-Toleranz): Bewegung bewerten; ist der
     * Knochen heraus, kommt er ins Inventar.
     */
    fun drag(state: GameState): GameState {
        val ref = active(state) ?: return state
        dragging = true
        val evaluation = recoverer.evaluate(samples)
        if (evaluation.completed) return finishLift(state, ref)
        if (evaluation.jerks > feedback.jerks || feedback.securedSpot != null || feedback.missedTap || feedback.dropped) {
            feedback = RecoveryFeedback(feedback.sequence + 1, jerks = evaluation.jerks, newJerk = evaluation.jerks > feedback.jerks)
        }
        return state
    }

    /** Finger hebt ab: Tippen sichert eine Stelle, ein Ziehen ohne Herausheben laesst den Knochen fallen. */
    fun touchEnd(state: GameState): GameState {
        val ref = active(state)
        if (ref == null) {
            reset()
            return state
        }
        val result = if (dragging) {
            finishLift(state, ref)
        } else {
            val (x, y) = touch ?: return state.also { reset() }
            val site = checkNotNull(state.excavation)
            val secured = recoverer.secure(site, ref, x, y)
            feedback = RecoveryFeedback(feedback.sequence + 1, securedSpot = secured.securedSpot, missedTap = secured.securedSpot == null)
            if (secured.site === site) state else state.copy(excavation = secured.site)
        }
        reset()
        return result
    }

    private fun finishLift(state: GameState, ref: BoneRef): GameState {
        val result = recoverer.lift(state, ref, samples.toList())
        reset()
        jerkTotal += result.evaluation.jerks
        if (result.recovered) {
            outcome = RecoveryOutcome(checkNotNull(result.piece), openQuality, jerkTotal, result.unsecuredSpots)
            feedback = RecoveryFeedback(feedback.sequence + 1, jerks = result.evaluation.jerks, loss = result.loss)
        } else {
            feedback = RecoveryFeedback(feedback.sequence + 1, jerks = result.evaluation.jerks, dropped = true, loss = result.loss)
        }
        return result.state
    }

    /** Offener Knochen, solange er in der laufenden Ausgrabung noch geborgen werden kann. */
    private fun active(state: GameState): BoneRef? {
        val ref = openBone ?: return null
        if (outcome != null) return null
        val site = state.excavation ?: return null
        val recovery = site.recovery(ref) ?: return null
        return ref.takeUnless { recovery.recovered }
    }

    private fun reset() {
        samples.clear()
        touch = null
        dragging = false
    }
}

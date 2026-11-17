package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.BoneRecovery
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import kotlin.math.hypot

/**
 * Ein Sample der Hebebewegung, geliefert von der UI: Zeitpunkt in Millisekunden (beliebiger, aber
 * monotoner Nullpunkt, z. B. Systemzeit seit dem Start) und Fingerposition in dp.
 */
data class LiftSample(
    val timeMillis: Long,
    val x: Double,
    val y: Double,
)

/**
 * Bewertung einer Hebebewegung ([Recoverer.evaluate]).
 *
 * @property distance groesster Abstand des Fingers vom Startpunkt in dp (bis zum Herausheben)
 * @property completed der Knochen ist heraus (`distance >= liftDistance`)
 * @property usedSamples Anzahl der bewerteten Samples (Samples nach dem Herausheben zaehlen nicht)
 * @property jerkTimes Zeitpunkte, zu denen ein Ruck begann (Ende des ersten Fensters des Rucks)
 * @property peakSpeedChange groesster Geschwindigkeitswechsel zwischen zwei Fenstern in dp/s
 * @property peakSpeed groesste Geschwindigkeit eines Fensters in dp/s
 */
data class LiftEvaluation(
    val distance: Double,
    val completed: Boolean,
    val usedSamples: Int,
    val jerkTimes: List<Long>,
    val peakSpeedChange: Double,
    val peakSpeed: Double,
) {
    /** Anzahl Rucke. */
    val jerks: Int get() = jerkTimes.size

    companion object {
        /** Bewertung ohne Bewegung. */
        val NONE = LiftEvaluation(0.0, false, 0, emptyList(), 0.0, 0.0)
    }
}

/** Ergebnis von [Recoverer.secure]: neue Flaeche und gesicherte Stelle (Index in `spots`) oder `null`. */
data class SecureResult(
    val site: ExcavationSite,
    val securedSpot: Int?,
)

/**
 * Ergebnis von [Recoverer.lift].
 *
 * @property state neuer Spielstand
 * @property evaluation Bewertung der Bewegung
 * @property unsecuredSpots ungesicherte Stellen beim Herausheben (0, wenn nicht heraus)
 * @property jerkLoss Schaden durch Rucke nach `damage_reduction` (vor der Begrenzung auf 0)
 * @property unsecuredLoss Schaden durch ungesicherte Stellen nach `damage_reduction` (vor der Begrenzung)
 * @property qualityBefore Qualitaet vor der Bewegung
 * @property qualityAfter Qualitaet danach (nie unter 0)
 * @property piece der geborgene Knochen, wie er ins Inventar kam, oder `null`, wenn er nicht heraus ist
 */
data class LiftResult(
    val state: GameState,
    val evaluation: LiftEvaluation,
    val unsecuredSpots: Int,
    val jerkLoss: Double,
    val unsecuredLoss: Double,
    val qualityBefore: Double,
    val qualityAfter: Double,
    val piece: Piece?,
) {
    /** Knochen ist heraus und liegt im Inventar. */
    val recovered: Boolean get() = piece != null

    /** Tatsaechlich verlorene Qualitaet. */
    val loss: Double get() = qualityBefore - qualityAfter
}

/**
 * Bergen (Kapitel 5.2, AP2-09): bruechige Stellen sichern und den Knochen herausheben. Keine
 * Simulation ueber Zeit: Jede Aktion ist eine Spieleraktion, die Bewertung der Bewegung ist eine
 * reine Funktion der Samples, die die UI liefert (Zeitstempel sind Eingabe).
 *
 * Regeln (Parameter aus `balance.json: recovery`):
 * - **Sichern:** Antippen sichert die ungesicherte Stelle, deren Mittelpunkt dem Tippunkt am
 *   naechsten ist, wenn sie hoechstens `secureRadius` feine Zellen entfernt ist (Gleichstand: die
 *   fruehere Stelle).
 * - **Heben:** Der Knochen ist heraus, sobald ein Sample mindestens `liftDistance` dp vom ersten
 *   Sample entfernt ist; spaetere Samples zaehlen nicht.
 * - **Bewertung:** Die Bewegung wird linear zwischen den Samples interpoliert und in Fenster von
 *   `sampleWindowMillis` ms ab dem ersten Sample geteilt (ein angebrochenes letztes Fenster zaehlt
 *   nicht). Je Fenster ist `v` die mittlere Geschwindigkeit; vor dem ersten Fenster ruht der Knochen
 *   (`v = 0`). Ein Fenster ist ruckartig, wenn `|v - v_vorher| > jerkSpeedChange` oder
 *   `|v| > maxSpeed`. Aufeinanderfolgende ruckartige Fenster sind ein Ruck.
 * - **Schaden:** jeder Ruck `jerkDamage`, beim Herausheben jede ungesicherte Stelle
 *   `unsecuredDamage`, beides mal `1 - damage_reduction` (Stat begrenzt auf 0..1), zusammen
 *   hoechstens bis Qualitaet 0 (Kapitel 5: der Knochen bleibt erhalten). Rucke kosten auch dann,
 *   wenn der Finger vor dem Herausheben loslaesst (der Knochen faellt zurueck), ungesicherte
 *   Stellen nur beim Herausheben.
 * - **Geborgen:** Der Knochen kommt mit seiner Qualitaet ins Inventar, das Bergen wird als
 *   geborgen markiert.
 *
 * @param data Spieldaten (validiert)
 * @param modifiers Modifier-System fuer `damage_reduction`
 */
class Recoverer(
    data: GameData,
    private val modifiers: ModifierEngine = ModifierEngine.fromGameData(data),
) {
    private val balance = data.balance.recovery

    /** Zugweg in dp, ab dem der Knochen heraus ist (fuer die Anzeige). */
    val liftDistance: Double get() = balance.liftDistance

    /**
     * Tippen an ([x], [y]) in feinen Zellkoordinaten der Nahansicht von [ref] sichert eine Stelle.
     *
     * @throws NoSuchElementException wenn das Bergen von [ref] nicht begonnen hat
     * @throws IllegalStateException wenn [ref] schon geborgen ist
     */
    fun secure(site: ExcavationSite, ref: BoneRef, x: Double, y: Double): SecureResult {
        val recovery = current(site, ref)
        val prep = checkNotNull(site.preparation(ref))
        if (!x.isFinite() || !y.isFinite()) return SecureResult(site, null)
        val r2 = balance.secureRadius * balance.secureRadius
        var best = -1
        var bestDistance = Double.MAX_VALUE
        recovery.spots.forEachIndexed { i, cell ->
            if (recovery.secured[i]) return@forEachIndexed
            val dx = prep.xOf(cell) + CELL_CENTER - x
            val dy = prep.yOf(cell) + CELL_CENTER - y
            val d2 = dx * dx + dy * dy
            if (d2 <= r2 && d2 < bestDistance) {
                best = i
                bestDistance = d2
            }
        }
        if (best < 0) return SecureResult(site, null)
        val next = recovery.withSecured(best)
        return SecureResult(site.withRecoveries(site.finds, site.recoveries.map { if (it.ref == ref) next else it }), best)
    }

    /**
     * Bewertet eine Hebebewegung (reine Funktion, siehe Klassenbeschreibung).
     *
     * @throws IllegalArgumentException wenn Zeitstempel rueckwaerts laufen oder Positionen nicht endlich sind
     */
    fun evaluate(samples: List<LiftSample>): LiftEvaluation {
        if (samples.isEmpty()) return LiftEvaluation.NONE
        require(samples.all { it.x.isFinite() && it.y.isFinite() }) { "Positionen muessen endlich sein" }
        require(samples.zipWithNext().all { (a, b) -> b.timeMillis >= a.timeMillis }) { "Zeitstempel laufen rueckwaerts" }
        val first = samples.first()
        var used = samples.size
        var distance = 0.0
        var completed = false
        for ((i, s) in samples.withIndex()) {
            val d = hypot(s.x - first.x, s.y - first.y)
            if (d > distance) distance = d
            if (d >= balance.liftDistance) {
                used = i + 1
                completed = true
                break
            }
        }
        val path = samples.subList(0, used)
        val window = balance.sampleWindowMillis.toLong()
        val windows = (path.last().timeMillis - first.timeMillis) / window
        val seconds = window / MILLIS_PER_SECOND
        val jerkTimes = mutableListOf<Long>()
        var peakChange = 0.0
        var peakSpeed = 0.0
        var prevX = first.x
        var prevY = first.y
        var prevVx = 0.0
        var prevVy = 0.0
        var inJerk = false
        var segment = 0
        for (k in 1..windows) {
            val t = first.timeMillis + k * window
            while (segment < path.size - 2 && path[segment + 1].timeMillis <= t) segment++
            val (px, py) = positionAt(path, segment, t)
            val vx = (px - prevX) / seconds
            val vy = (py - prevY) / seconds
            val change = hypot(vx - prevVx, vy - prevVy)
            val speed = hypot(vx, vy)
            if (change > peakChange) peakChange = change
            if (speed > peakSpeed) peakSpeed = speed
            val jerky = change > balance.jerkSpeedChange || speed > balance.maxSpeed
            if (jerky && !inJerk) jerkTimes += t
            inJerk = jerky
            prevX = px
            prevY = py
            prevVx = vx
            prevVy = vy
        }
        return LiftEvaluation(distance, completed, used, jerkTimes, peakChange, peakSpeed)
    }

    /**
     * Wendet eine abgeschlossene Hebebewegung (Finger hat losgelassen oder der Knochen ist heraus)
     * auf das Bergen von [ref] an: Schaden fuer Rucke, bei Erfolg auch fuer ungesicherte Stellen,
     * dann Knochen ins Inventar. Ohne Ruck und ohne Herausheben bleibt [state] unveraendert.
     *
     * @throws IllegalStateException wenn keine Ausgrabung laeuft oder [ref] schon geborgen ist
     * @throws NoSuchElementException wenn das Bergen von [ref] nicht begonnen hat
     */
    fun lift(state: GameState, ref: BoneRef, samples: List<LiftSample>): LiftResult {
        val site = checkNotNull(state.excavation) { "Keine laufende Ausgrabung" }
        val recovery = current(site, ref)
        val evaluation = evaluate(samples)
        val before = site.piece(ref).quality
        val factor = 1.0 - modifiers.additive(StatId.DAMAGE_REDUCTION).coerceIn(0.0, 1.0)
        val jerkLoss = evaluation.jerks * balance.jerkDamage * factor
        val unsecured = if (evaluation.completed) recovery.unsecuredCount else 0
        val unsecuredLoss = unsecured * balance.unsecuredDamage * factor
        val after = (before - jerkLoss - unsecuredLoss).coerceIn(Piece.MIN_QUALITY, Piece.MAX_QUALITY)
        if (!evaluation.completed && evaluation.jerks == 0) {
            return LiftResult(state, evaluation, 0, 0.0, 0.0, before, before, null)
        }
        val finds = if (after == before) {
            site.finds
        } else {
            site.finds.mapIndexed { index, placed ->
                if (index != ref.findIndex) return@mapIndexed placed
                val pieces = placed.find.pieces.map { if (it.boneId == ref.boneId) it.copy(quality = after) else it }
                placed.copy(find = placed.find.copy(pieces = pieces))
            }
        }
        val recoveries = if (evaluation.completed) {
            site.recoveries.map { if (it.ref == ref) it.copy(recovered = true) else it }
        } else {
            site.recoveries
        }
        val nextSite = site.withRecoveries(finds, recoveries)
        val piece = if (evaluation.completed) nextSite.piece(ref) else null
        val nextState = state.copy(
            excavation = nextSite,
            inventory = if (piece != null) state.inventory + piece else state.inventory,
        )
        return LiftResult(nextState, evaluation, unsecured, jerkLoss, unsecuredLoss, before, after, piece)
    }

    /** Laufendes, noch nicht geborgenes Bergen von [ref]. */
    private fun current(site: ExcavationSite, ref: BoneRef): BoneRecovery {
        val recovery = site.recovery(ref) ?: throw NoSuchElementException("Bergen von $ref hat nicht begonnen")
        check(!recovery.recovered) { "Knochen $ref ist schon geborgen" }
        return recovery
    }

    /** Linear interpolierte Position zum Zeitpunkt [t] im Abschnitt ab [segment]. */
    private fun positionAt(path: List<LiftSample>, segment: Int, t: Long): Pair<Double, Double> {
        val a = path[segment]
        val b = path[minOf(segment + 1, path.size - 1)]
        if (b.timeMillis <= a.timeMillis || t >= b.timeMillis) return if (t >= b.timeMillis) b.x to b.y else a.x to a.y
        val f = (t - a.timeMillis).toDouble() / (b.timeMillis - a.timeMillis)
        return (a.x + (b.x - a.x) * f) to (a.y + (b.y - a.y) * f)
    }

    private companion object {
        /** Mittelpunkt einer Zelle relativ zu ihrer Ecke (Geometrie, kein Spielwert). */
        const val CELL_CENTER: Double = 0.5

        /** Millisekunden je Sekunde (Einheit, kein Spielwert). */
        const val MILLIS_PER_SECOND: Double = 1000.0
    }
}

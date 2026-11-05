package de.noelkoeppen.fossilcollector.core.random

import kotlin.random.Random

/**
 * Zufallsquelle der Spiellogik (GAME_DESIGN.md, Kapitel 0 Regel 7 und Kapitel 10.2).
 *
 * `core` erzeugt nie selbst Zufall, sondern bekommt eine Instanz injiziert (z. B. ueber
 * `GameEngine.advance(state, dt, rng)`). Tests verwenden [SeededRandom] mit festem Seed.
 *
 * Implementierungen sind in der Regel zustandsbehaftet (jeder Aufruf schreitet die Folge fort)
 * und nicht thread-safe.
 */
interface GameRandom {
    /** Gleichverteilte Zahl in `[0, 1)`. */
    fun nextDouble(): Double

    /**
     * Gleichverteilte Zahl in `[from, until)`. Bei `from == until` wird `from` zurueckgegeben
     * (z. B. wenn ein Modifier eine Untergrenze bis an die Obergrenze anhebt).
     *
     * @throws IllegalArgumentException wenn `from > until` oder eine Grenze nicht endlich ist
     */
    fun nextDouble(from: Double, until: Double): Double

    /**
     * Gleichverteilte ganze Zahl in `[0, bound)`. Nur fuer Indizes (z. B. Auswahl aus einer Liste),
     * nicht fuer Spielwerte.
     *
     * @throws IllegalArgumentException wenn `bound <= 0`
     */
    fun nextInt(bound: Int): Int
}

/**
 * Deterministische [GameRandom]-Implementierung auf Basis von [kotlin.random.Random] mit Seed.
 * Gleicher Seed ergibt dieselbe Folge (auf derselben Kotlin-Version).
 */
class SeededRandom(val seed: Long) : GameRandom {
    private val random: Random = Random(seed)

    override fun nextDouble(): Double = random.nextDouble()

    override fun nextDouble(from: Double, until: Double): Double {
        require(from.isFinite() && until.isFinite()) { "Grenzen muessen endlich sein: [$from, $until)" }
        require(from <= until) { "Untergrenze groesser als Obergrenze: [$from, $until)" }
        if (from == until) return from
        return random.nextDouble(from, until)
    }

    override fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound muss positiv sein: $bound" }
        return random.nextInt(bound)
    }
}

package de.noelkoeppen.fossilcollector.core.engine.purchase

import de.noelkoeppen.fossilcollector.core.number.GameNumber
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.pow

/**
 * Exponentielle Kostenkurve (GAME_DESIGN.md, Kapitel 6.3).
 *
 * ```
 * price(n)   = baseCost * growth^n
 * cost(n, k) = baseCost * growth^n * (growth^k - 1) / (growth - 1)
 * maxK(n, M) = floor( log( M * (growth - 1) / (baseCost * growth^n) + 1 ) / log(growth) )
 * ```
 * `n` ist die Anzahl bereits **bezahlter** Kaeufe (Gratis-Einheiten zaehlen nicht, siehe
 * [Purchases]). Eingaben und Ergebnisse sind [GameNumber]; intern wird mit `Double` gerechnet.
 *
 * Rabatt-Faktoren (z. B. `hall_cost`) werden vom Aufrufer bereits in [baseCost] eingerechnet.
 *
 * @property baseCost Preis des ersten bezahlten Kaufs, > 0
 * @property growth Wachstumsfaktor pro Kauf, > 1
 */
data class CostCurve(val baseCost: Double, val growth: Double) {

    init {
        require(baseCost.isFinite() && baseCost > 0.0) { "baseCost muss endlich und > 0 sein: $baseCost" }
        require(growth.isFinite() && growth > 1.0) { "growth muss endlich und > 1 sein: $growth" }
    }

    /** Preis des Kaufs Nummer [n] (0-basiert), also bei einem Bestand von [n] bezahlten Einheiten. */
    fun price(n: GameNumber): GameNumber = GameNumber(priceRaw(n.toDouble()))

    /** Gesamtkosten fuer [k] Einheiten ab Bestand [n]. `cost(n, 0) = 0`, `cost(n, 1) = price(n)` exakt. */
    fun cost(n: GameNumber, k: GameNumber): GameNumber = GameNumber(costRaw(n.toDouble(), k.toDouble()))

    /**
     * Hoechste Anzahl `k`, die mit [money] ab Bestand [n] bezahlbar ist, optional gedeckelt auf [cap].
     *
     * Das Ergebnis der Formel aus 6.3 wird gegen Rundungsfehler korrigiert, sodass immer gilt:
     * `cost(n, k) <= money` und (sofern nicht durch [cap] begrenzt) `cost(n, k + 1) > money`.
     * Bei `money <= 0` ist das Ergebnis 0.
     *
     * @throws IllegalArgumentException wenn [money] `NaN` oder unendlich ist
     */
    fun maxAffordable(n: GameNumber, money: GameNumber, cap: GameNumber? = null): GameNumber {
        val m = money.toDouble()
        require(m.isFinite()) { "Geldbetrag muss endlich sein: $m" }
        val nd = n.toDouble()
        val limit = cap?.toDouble()?.let { floor(it).coerceAtLeast(0.0) } ?: Double.POSITIVE_INFINITY
        if (m <= 0.0 || limit == 0.0) return GameNumber.ZERO
        val first = priceRaw(nd)
        if (!first.isFinite() || first > m) return GameNumber.ZERO

        // Formel 6.3; ln1p fuer bessere Genauigkeit bei kleinen Verhaeltnissen.
        var k = floor(ln1p(m * (growth - 1.0) / first) / ln(growth))
        if (!k.isFinite() || k < 0.0) k = 0.0
        if (k > limit) k = limit
        // Korrektur nach unten: cost(n, k) <= M.
        while (k > 0.0 && costRaw(nd, k) > m) k -= 1.0
        // Korrektur nach oben: cost(n, k + 1) > M (bzw. Deckel erreicht).
        while (k < limit && costRaw(nd, k + 1.0) <= m) k += 1.0
        return GameNumber(k)
    }

    private fun priceRaw(n: Double): Double = baseCost * growth.pow(n)

    private fun costRaw(n: Double, k: Double): Double {
        if (k <= 0.0) return 0.0
        // Reihenfolge so gewaehlt, dass fuer k = 1 der Bruch exakt 1 ist.
        return priceRaw(n) * ((growth.pow(k) - 1.0) / (growth - 1.0))
    }
}

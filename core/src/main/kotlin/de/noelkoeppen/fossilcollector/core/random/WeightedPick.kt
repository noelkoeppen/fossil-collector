package de.noelkoeppen.fossilcollector.core.random

/**
 * Waehlt ein Element aus [items] mit Wahrscheinlichkeit proportional zu seinem Gewicht [weight].
 * Verbraucht genau einen Wert von [GameRandom.nextDouble].
 *
 * @throws IllegalArgumentException wenn [items] leer ist oder ein Gewicht nicht endlich und > 0 ist
 */
fun <T> GameRandom.pickWeighted(items: List<T>, weight: (T) -> Double): T {
    require(items.isNotEmpty()) { "Keine Elemente zur Auswahl" }
    val weights = items.map(weight)
    require(weights.all { it > 0.0 && it.isFinite() }) { "Gewichte muessen endlich und > 0 sein: $weights" }
    val target = nextDouble() * weights.sum()
    var cumulative = 0.0
    for (i in items.indices) {
        cumulative += weights[i]
        if (target < cumulative) return items[i]
    }
    // Rundung bei der Summe: Ein Wert knapp unter der Gesamtsumme faellt auf das letzte Element.
    return items.last()
}

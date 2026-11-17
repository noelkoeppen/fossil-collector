package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.BoneRecovery
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite
import de.noelkoeppen.fossilcollector.core.random.GameRandom
import kotlin.math.floor

/**
 * Beginnt das Bergen eines fertig freigelegten Knochens (Kapitel 5.2, AP2-09): legt die bruechigen
 * Stellen fest. Alle Werte kommen aus `balance.json: recovery`, Zufall nur ueber das uebergebene
 * [GameRandom].
 *
 * Ablauf (in dieser Reihenfolge, damit gleicher Seed dieselben Stellen ergibt):
 * 1. Anzahl `floor(n * fragileSpotsPerCell + u)` mit `n` = Zellen des Knochens in der Flaeche und
 *    `u` gleichverteilt in `[0, 1)`, begrenzt auf `[fragileSpotsMin, fragileSpotsMax]`.
 * 2. Je Stelle: gleichverteilt eine Knochenzelle der Nahansicht, deren Mittelpunkt mindestens
 *    `fragileSpotSpacing` feine Zellen von allen schon gewaehlten Stellen entfernt ist (Kandidaten
 *    zeilenweise). Gibt es keine solche Zelle mehr, bleibt es bei weniger Stellen.
 *
 * Die Stellen haengen nur von der Form des Knochens (Knochenzellen) und dem Zufall ab, nicht von
 * der Restschicht.
 *
 * @param data Spieldaten (validiert)
 */
class RecoveryGenerator(private val data: GameData) {
    private val balance = data.balance.recovery

    /**
     * Neue Flaeche, in der das Bergen von [ref] begonnen hat. Hat es schon begonnen, kommt [site]
     * unveraendert zurueck.
     *
     * @throws NoSuchElementException wenn [ref] nicht in der Flaeche liegt
     * @throws IllegalStateException wenn der Knochen noch nicht fertig freigelegt ist
     */
    fun start(site: ExcavationSite, ref: BoneRef, random: GameRandom): ExcavationSite {
        if (ref !in site.bones) throw NoSuchElementException("Knochen $ref liegt nicht in der Flaeche")
        if (site.recovery(ref) != null) return site
        check(site.isPrepared(ref)) { "Knochen $ref ist noch nicht fertig freigelegt" }
        return site.withRecoveries(site.finds, site.recoveries + create(site, ref, random))
    }

    /** Bergen von [ref] nach den Schritten 1 und 2 (ohne die Flaeche zu aendern). */
    fun create(site: ExcavationSite, ref: BoneRef, random: GameRandom): BoneRecovery {
        val prep = checkNotNull(site.preparation(ref)) { "Freilegen von $ref hat nicht begonnen" }
        val n = site.cellsOf(ref).size
        val count = floor(n * balance.fragileSpotsPerCell + random.nextDouble()).toInt()
            .coerceIn(balance.fragileSpotsMin, balance.fragileSpotsMax)
        val boneCells = (0 until prep.cellCount).filter { prep.isBone(it) }
        val spacing2 = balance.fragileSpotSpacing * balance.fragileSpotSpacing
        val spots = mutableListOf<Int>()
        repeat(count) {
            val candidates = boneCells.filter { cell ->
                cell !in spots && spots.all { other ->
                    val dx = (prep.xOf(cell) - prep.xOf(other)).toDouble()
                    val dy = (prep.yOf(cell) - prep.yOf(other)).toDouble()
                    dx * dx + dy * dy >= spacing2
                }
            }
            if (candidates.isEmpty()) return@repeat
            spots += candidates[random.nextInt(candidates.size)]
        }
        return BoneRecovery(ref.findIndex, ref.boneId, spots, List(spots.size) { false })
    }
}

package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.BoneRef
import de.noelkoeppen.fossilcollector.core.model.ExcavationSite
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import kotlin.math.PI
import kotlin.math.cos

/** Gemeinsame Hilfen fuer die Tests des Bergens (AP2-09). */
internal object RecoveryTestSupport {

    /**
     * Legt [ref] in [site] frei, ohne zu spielen: eine Zelle aufdecken (entdeckt), Nahansicht mit
     * festem Seed beginnen, dann die ganze Restschicht entfernen und als fertig markieren.
     */
    fun prepared(data: GameData, site: ExcavationSite, ref: BoneRef, seed: Long = 5): ExcavationSite {
        var s = site
        if (!s.isDiscovered(ref)) s = s.with(s.depthsCopy().also { it[s.cellsOf(ref).first()] = 0 }, s.finds)
        s = PreparationGenerator(data).start(s, ref, SeededRandom(seed))
        val prep = checkNotNull(s.preparation(ref))
        val done = prep.with(IntArray(prep.cellCount), BooleanArray(prep.cellCount), finished = true)
        return s.with(s.depthsCopy(), s.finds, s.preparations.map { if (it.ref == ref) done else it })
    }

    /**
     * Ruhiges Ziehen: [distance] dp nach rechts in [durationMillis] ms mit weichem Anfahren und
     * Abbremsen (Kosinus-Rampe), ein Sample alle [stepMillis] ms ab [startMillis].
     */
    fun calmPull(distance: Double, durationMillis: Long = 1_000, stepMillis: Long = 10, startMillis: Long = 1_000): List<LiftSample> =
        (0..durationMillis / stepMillis).map { i ->
            val t = i * stepMillis
            val f = (1 - cos(PI * t / durationMillis)) / 2
            LiftSample(startMillis + t, 100.0 + distance * f, 300.0)
        }

    /**
     * Ruckartiges Ziehen: [holdMillis] ms Stillstand, dann ein Satz um [jump] dp in 20 ms, wieder
     * Stillstand, danach ruhig weiter bis [distance] dp.
     */
    fun jerkyPull(distance: Double, jump: Double = 60.0, holdMillis: Long = 200): List<LiftSample> {
        val rest = (0..holdMillis / 10).map { LiftSample(1_000 + it * 10, 100.0, 300.0) }
        val jumpEnd = LiftSample(1_000 + holdMillis + 20, 100.0 + jump, 300.0)
        val hold = (1..20).map { LiftSample(jumpEnd.timeMillis + it * 10, jumpEnd.x, 300.0) }
        val tail = calmPull(distance - jump + 1, startMillis = hold.last().timeMillis + 10).map { it.copy(x = it.x - 100.0 + jumpEnd.x) }
        return rest + jumpEnd + hold + tail
    }
}

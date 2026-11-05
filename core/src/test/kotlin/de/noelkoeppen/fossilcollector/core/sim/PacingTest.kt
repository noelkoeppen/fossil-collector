package de.noelkoeppen.fossilcollector.core.sim

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Pacing-Test (GAME_DESIGN.md Kapitel 5.1, 6.7, 10.4, AP-15): ein 60-Minuten-Durchlauf mit der
 * gierigen Kauf-KI [GreedyBot] auf den echten JSON-Daten, je Seed ein Durchlauf.
 *
 * Akzeptanz (Kapitel 14, AP-15):
 * - Schicht 2 (Neogen) wird zwischen 15 und 40 Minuten freigeschaltet.
 * - Nach 60 Minuten liegt die Ruhm-Vorschau ([de.noelkoeppen.fossilcollector.core.engine.GameEngine.fameGainPreview])
 *   zwischen 8 und 40.
 *
 * Der Verlauf (alle 5 Minuten) steht in der Standardausgabe des Tests (Gradle-Testbericht).
 * Die Grenzen sind Pruefkriterien aus dem Design, keine Spielwerte.
 */
class PacingTest {

    private val data: GameData = DataRepository().load()

    @ParameterizedTest(name = "Seed {0}")
    @ValueSource(longs = [1L, 2L, 3L])
    fun `erster Durchlauf trifft das Ziel-Pacing`(seed: Long) {
        val bot = GreedyBot(data, seed)
        val started = System.nanoTime()
        println("PacingTest Seed $seed")
        bot.run(untilSeconds = RUN_MINUTES * 60.0, snapshotEvery = LOG_EVERY_MINUTES * 60.0) { println(it.format()) }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        val secondLayer = data.layers[1].id
        val unlockedAt = bot.layerUnlockedAt[secondLayer]
        val fame = bot.fameGainPreview().toDouble()
        println(
            "Ergebnis Seed $seed: $secondLayer nach ${unlockedAt?.let { "%.1f min".format(it / 60.0) } ?: "-"}, " +
                "erstes Skelett nach ${bot.firstSkeletonAt?.let { "%.1f min".format(it / 60.0) } ?: "-"}, " +
                "Ruhm-Vorschau nach $RUN_MINUTES min: $fame, Laufzeit $elapsedMs ms",
        )

        assertNotNull(unlockedAt, "Schicht 2 wurde in $RUN_MINUTES Minuten nicht freigeschaltet")
        val minutes = unlockedAt!! / 60.0
        assertTrue(
            minutes in LAYER_2_MIN_MINUTES..LAYER_2_MAX_MINUTES,
            "Schicht 2 nach %.1f min, erwartet $LAYER_2_MIN_MINUTES..$LAYER_2_MAX_MINUTES".format(minutes),
        )
        assertTrue(fame in FAME_MIN..FAME_MAX, "Ruhm-Vorschau $fame, erwartet $FAME_MIN..$FAME_MAX")
    }

    private companion object {
        const val RUN_MINUTES = 60.0
        const val LOG_EVERY_MINUTES = 5.0
        const val LAYER_2_MIN_MINUTES = 15.0
        const val LAYER_2_MAX_MINUTES = 40.0
        const val FAME_MIN = 8.0
        const val FAME_MAX = 40.0
    }
}

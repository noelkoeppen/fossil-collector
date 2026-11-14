package de.noelkoeppen.fossilcollector.core.save

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.engine.ExcavationGenerator
import de.noelkoeppen.fossilcollector.core.engine.Excavator
import de.noelkoeppen.fossilcollector.core.random.SeededRandom
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.Settings

/** Gemeinsame Testdaten fuer die Save-Tests. */
internal object SaveFixtures {

    /** Liest eine Datei aus `core/src/test/resources/save/`. */
    fun read(path: String): String {
        val url = SaveFixtures::class.java.getResource("/save/$path")
            ?: error("Fixture fehlt: save/$path")
        return url.readText(Charsets.UTF_8)
    }

    /** Erwarteter Inhalt von `save/v3_full.json` nach der Migration auf die aktuelle Version (ohne Ausgrabung). */
    val fullV3: GameState = GameState(
        saveVersion = GameState.CURRENT_SAVE_VERSION,
        lastSavedAt = 1762351200000L,
        settings = Settings(LanguageSetting.EN),
        inventory = listOf(
            Piece("cave_bear", "femur_l", 0.83),
            Piece("cave_bear", "patella_l", 0.0),
            Piece("cave_bear", "occipital", 1.0),
        ),
    )

    /** Spielstand mit grossem Zeitstempel, fester Sprache und Knochen mit gesetztem Alter/Geschlecht. */
    val rich: GameState = fullV3.copy(
        lastSavedAt = 1_800_000_000_123L,
        settings = Settings(LanguageSetting.DE),
        inventory = fullV3.inventory + Piece("cave_bear", "sternum", 0.123456789, age = "adult", sex = "female"),
    )

    /**
     * Spielstand mit laufender Ausgrabung: erzeugt mit festem Seed, danach an allen Zellen des ersten
     * Knochens gegraben, bis er frei liegt und durch die Schaufel beschaedigt ist.
     */
    fun withExcavation(): GameState {
        val data = DataRepository().load()
        val excavator = Excavator(data)
        var site = ExcavationGenerator(data).generate("quaternary", SeededRandom(2026))
        val bone = site.bones.first()
        repeat(8) {
            site.cellsOf(bone).forEach { cell -> site = excavator.applyTool(site, "shovel", site.xOf(cell) + 0.5, site.yOf(cell) + 0.5).site }
        }
        check(site.piece(bone).quality < ExcavationGenerator(data).generate("quaternary", SeededRandom(2026)).piece(bone).quality)
        return rich.copy(excavation = site)
    }
}

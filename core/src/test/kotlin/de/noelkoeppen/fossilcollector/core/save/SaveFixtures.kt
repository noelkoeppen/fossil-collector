package de.noelkoeppen.fossilcollector.core.save

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

    /** Erwarteter Inhalt von `save/v3_full.json`. */
    val fullV3: GameState = GameState(
        saveVersion = 3,
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
}

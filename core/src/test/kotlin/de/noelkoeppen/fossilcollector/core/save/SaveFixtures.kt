package de.noelkoeppen.fossilcollector.core.save

import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.model.Settings

/** Gemeinsame Testdaten fuer die Save-Tests. */
internal object SaveFixtures {

    /** Liest eine Datei aus `core/src/test/resources/save/`. */
    fun read(path: String): String {
        val url = SaveFixtures::class.java.getResource("/save/$path")
            ?: error("Fixture fehlt: save/$path")
        return url.readText(Charsets.UTF_8)
    }

    /** Erwarteter Inhalt von `save/v1_full.json`. */
    val fullV1: GameState = GameState(
        saveVersion = 1,
        lastSavedAt = 1762351200000L,
        settings = Settings(LanguageSetting.EN),
    )

    /** Spielstand mit grossem Zeitstempel und fester Sprache. */
    val rich: GameState = fullV1.copy(
        lastSavedAt = 1_800_000_000_123L,
        settings = Settings(LanguageSetting.DE),
    )
}

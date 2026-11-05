package de.noelkoeppen.fossilcollector.core.save

import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LabJob
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.model.MetaState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.QueuedPiece
import de.noelkoeppen.fossilcollector.core.model.RunState
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.model.SpareZoneKey
import de.noelkoeppen.fossilcollector.core.model.Stats
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.number.NumberNotation

/** Gemeinsame Testdaten fuer die Save-Tests. */
internal object SaveFixtures {

    fun n(value: Double) = GameNumber(value)

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
        meta = MetaState(
            fame = n(4.0),
            fameTotal = n(19.0),
            prestigeCount = n(2.0),
            skills = setOf("dig_1", "dig_2"),
            book = mapOf(
                PieceKey("parasaurolophus", "dorsal") to BookEntry(0.83, reconstructed = false),
                PieceKey("cave_bear", "skull", "adult", "female") to BookEntry(0.89, reconstructed = true),
            ),
            researchedSpecies = setOf("cave_bear"),
            milestonesDone = setOf("first_find"),
            stats = Stats(piecesFound = n(1520.0)),
        ),
        run = RunState(
            money = n(1234.5),
            researchPoints = n(12.0),
            unlockedLayers = n(2.0),
            teams = mapOf("quaternary" to n(41.0), "neogene" to n(5.0)),
            preparators = n(26.0),
            halls = n(4.0),
            labSlots = n(2.0),
            toolLevel = n(3.0),
            blocks = mapOf("quaternary" to n(2.4), "neogene" to n(0.7)),
            pieces = mapOf(
                PieceKey("cave_bear", "skull") to Piece(listOf(0.9, 0.7, 0.8, 0.6)),
                PieceKey("cave_bear", "jaw", age = "juvenile") to Piece(listOf(0.5, 0.5, 0.25)),
            ),
            spareZones = mapOf(
                SpareZoneKey(PieceKey("cave_bear", "skull"), "snout") to 0.92,
                SpareZoneKey(PieceKey("cave_bear", "skull", "adult", "male"), "occiput") to 0.4,
            ),
            labQueue = listOf(QueuedPiece(PieceKey("hipparion", "ribs"), listOf(0.1, 0.2, 0.3, 0.4))),
            labJobs = listOf(LabJob(PieceKey("cave_bear", "skull"), listOf(0.92, 0.5, 0.5, 0.5), remaining = 12.0)),
            specialExhibit = null,
        ),
        settings = Settings(LanguageSetting.SYSTEM, NumberNotation.SHORT),
    )

    /** Spielstand mit krummen Werten, grossen Zahlen und allen Kombinationen von Alter/Geschlecht. */
    val rich: GameState = fullV1.copy(
        lastSavedAt = 1_800_000_000_123L,
        meta = fullV1.meta.copy(
            fame = n(1.0e300),
            book = fullV1.meta.book + mapOf(
                PieceKey("t_rex", "skull", null, "male") to BookEntry(0.1 + 0.2, reconstructed = false),
                PieceKey("t_rex", "jaw", "subadult", null) to BookEntry(1.0 / 3.0, reconstructed = true),
            ),
        ),
        run = fullV1.run.copy(
            money = n(123456789.123456789),
            researchPoints = n(1.0e-7),
            spareZones = fullV1.run.spareZones +
                (SpareZoneKey(PieceKey("t_rex", "skull", null, "female"), "snout") to 0.123456789),
            specialExhibit = "cave_bear",
        ),
        settings = Settings(LanguageSetting.DE, NumberNotation.SCIENTIFIC),
    )
}

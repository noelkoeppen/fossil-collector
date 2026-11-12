package de.noelkoeppen.fossilcollector.platform

import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.save.SaveCodec
import de.noelkoeppen.fossilcollector.core.save.SaveException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SaveStorageTest {

    @TempDir
    lateinit var dir: File

    /** Spielstand, der sich ueber [id] (Zeitstempel) unterscheiden laesst. */
    private fun state(id: Long): GameState = GameState.newGame(now = id)

    private fun storage() = SaveStorage(dir)

    private fun idOf(file: File): Long = SaveCodec.DEFAULT.decode(file.readText()).lastSavedAt

    private fun loaded(result: SaveLoadResult): SaveLoadResult.Loaded = assertInstanceOf(SaveLoadResult.Loaded::class.java, result)

    @Test
    fun emptyDirectoryHasNoSave() {
        assertEquals(SaveLoadResult.NoSave, storage().load())
    }

    @Test
    fun missingDirectoryIsCreatedOnSave() {
        val nested = SaveStorage(File(dir, "a/b"))
        nested.save(state(1))
        assertEquals(1L, loaded(nested.load()).state.lastSavedAt)
    }

    @Test
    fun saveAndLoadRoundTrip() {
        val storage = storage()
        storage.save(state(42))
        val result = loaded(storage.load())
        assertEquals(state(42), result.state)
        assertEquals(SaveSource.Main, result.source)
        assertFalse(result.fromBackup)
        assertTrue(result.failures.isEmpty())
        assertFalse(storage.tempFile.exists(), "Temp-Datei muss nach dem Speichern umbenannt sein")
    }

    @Test
    fun backupsRotateNewestFirstAndKeepThree() {
        val storage = storage()
        for (id in 1L..5L) storage.save(state(id))

        assertEquals(5L, idOf(storage.mainFile))
        assertEquals(listOf(4L, 3L, 2L), storage.backupFiles.map(::idOf))
        assertFalse(File(dir, "save.bak4").exists())
    }

    @Test
    fun firstSavesCreateOnlyAsManyBackupsAsNeeded() {
        val storage = storage()
        storage.save(state(1))
        assertTrue(storage.backupFiles.none { it.exists() })
        storage.save(state(2))
        assertEquals(1L, idOf(storage.backupFiles[0]))
        assertFalse(storage.backupFiles[1].exists())
    }

    @Test
    fun corruptMainFallsBackToNewestBackup() {
        val storage = storage()
        for (id in 1L..4L) storage.save(state(id))
        storage.mainFile.writeText("{ kaputt")

        val result = loaded(SaveStorage(dir).load())
        assertEquals(3L, result.state.lastSavedAt)
        assertEquals(SaveSource.Backup(1), result.source)
        assertTrue(result.fromBackup)
        assertEquals(listOf<SaveSource>(SaveSource.Main), result.failures.map { it.source })
        assertInstanceOf(SaveException.InvalidJson::class.java, result.failures.single().error)
    }

    @Test
    fun skipsUnreadableBackups() {
        val storage = storage()
        for (id in 1L..4L) storage.save(state(id))
        storage.mainFile.writeText("")
        storage.backupFiles[0].writeText("""{"saveVersion": 3}""")

        val result = loaded(SaveStorage(dir).load())
        assertEquals(2L, result.state.lastSavedAt)
        assertEquals(SaveSource.Backup(2), result.source)
        assertEquals(listOf(SaveSource.Main, SaveSource.Backup(1)), result.failures.map { it.source })
    }

    @Test
    fun saveFromNewerAppVersionFallsBackToBackup() {
        val storage = storage()
        storage.save(state(1))
        storage.save(state(2))
        storage.mainFile.writeText("""{"saveVersion": 999}""")

        val result = loaded(SaveStorage(dir).load())
        assertEquals(1L, result.state.lastSavedAt)
        val error = assertInstanceOf(SaveException.UnsupportedVersion::class.java, result.failures.single().error)
        assertTrue(error.isTooNew)
    }

    @Test
    fun missingMainUsesBackup() {
        val storage = storage()
        storage.save(state(1))
        storage.save(state(2))
        assertTrue(storage.mainFile.delete())

        val result = loaded(SaveStorage(dir).load())
        assertEquals(1L, result.state.lastSavedAt)
        assertEquals(SaveSource.Backup(1), result.source)
        assertTrue(result.failures.isEmpty())
    }

    @Test
    fun allUnreadableReportsFailuresAndPreservesMain() {
        val storage = storage()
        for (id in 1L..4L) storage.save(state(id))
        storage.mainFile.writeText("main kaputt")
        storage.backupFiles.forEach { it.writeText("bak kaputt") }

        val result = assertInstanceOf(SaveLoadResult.Unreadable::class.java, storage.load())
        assertEquals(4, result.failures.size)
        assertEquals("main kaputt", storage.corruptFile.readText())
    }

    @Test
    fun obsoleteSaveIsDiscardedWithLanguageAndNotPreservedAsCorrupt() {
        val storage = storage()
        storage.mainFile.writeText(LEGACY_EN)
        storage.backupFiles[0].writeText("""{"saveVersion": 1, "lastSavedAt": 0, "settings": {"language": "de"}}""")

        val result = assertInstanceOf(SaveLoadResult.Discarded::class.java, storage.load())
        assertEquals(Settings(LanguageSetting.EN), result.legacySettings)
        assertEquals(2, result.failures.size)
        assertTrue(result.failures.all { it.error is SaveException.ObsoleteVersion })
        assertFalse(storage.corruptFile.exists())

        // Das neue Spiel ersetzt den alten Stand; dieser wird nicht als Backup uebernommen.
        storage.save(state(10))
        assertEquals(10L, idOf(storage.mainFile))
        assertTrue(storage.backupFiles.none { it.isFile && it.readText() == LEGACY_EN })
    }

    @Test
    fun obsoleteSaveWithUnreadableSettingsIsDiscardedWithoutSettings() {
        val storage = storage()
        storage.mainFile.writeText("""{"saveVersion": 2, "lastSavedAt": 0, "settings": {"language": "fr"}}""")
        val result = assertInstanceOf(SaveLoadResult.Discarded::class.java, storage.load())
        assertEquals(null, result.legacySettings)
    }

    @Test
    fun newSaveInBackupWinsOverObsoleteMain() {
        val storage = storage()
        storage.save(state(1))
        storage.save(state(2))
        storage.mainFile.writeText(LEGACY_EN)
        val result = loaded(storage.load())
        assertEquals(1L, result.state.lastSavedAt)
        assertTrue(result.fromBackup)
    }

    @Test
    fun corruptMainIsNotRotatedIntoBackups() {
        val storage = storage()
        for (id in 1L..4L) storage.save(state(id))
        storage.mainFile.writeText("kaputt")

        val reloaded = SaveStorage(dir)
        loaded(reloaded.load())
        reloaded.save(state(10))

        assertEquals(10L, idOf(reloaded.mainFile))
        val existing = reloaded.backupFiles.filter { it.exists() }
        assertTrue(existing.none { it.readText() == "kaputt" }, "Defekte Hauptdatei darf kein Backup werden")
        assertEquals(listOf(3L, 2L), existing.map(::idOf))
        // Danach wird wieder normal rotiert.
        reloaded.save(state(11))
        assertEquals(10L, idOf(reloaded.backupFiles[0]))
    }

    @Test
    fun leftoverTempFileIsIgnoredAndOverwritten() {
        val storage = storage()
        storage.save(state(1))
        storage.tempFile.writeText("abgebrochen")

        assertEquals(1L, loaded(storage.load()).state.lastSavedAt)
        storage.save(state(2))
        assertEquals(2L, idOf(storage.mainFile))
        assertFalse(storage.tempFile.exists())
    }

    @Test
    fun onlyTempFileMeansNoSave() {
        storage().tempFile.writeText(SaveCodec.DEFAULT.encode(state(1)))
        assertEquals(SaveLoadResult.NoSave, storage().load())
    }

    @Test
    fun deleteAllRemovesMainBackupsAndCorruptCopy() {
        val storage = storage()
        for (id in 1L..5L) storage.save(state(id))
        storage.corruptFile.writeText("kaputt")
        storage.tempFile.writeText("halb")
        assertTrue(storage.backupFiles.all { it.isFile })

        storage.deleteAll()

        assertFalse(storage.mainFile.exists())
        assertFalse(storage.tempFile.exists())
        assertFalse(storage.corruptFile.exists())
        assertTrue(storage.backupFiles.none { it.exists() })
        assertEquals(SaveLoadResult.NoSave, storage.load())
    }

    @Test
    fun deleteAllThenSaveStartsWithoutBackups() {
        val storage = storage()
        for (id in 1L..3L) storage.save(state(id))
        storage.deleteAll()
        storage.save(state(10))

        assertEquals(10L, loaded(storage.load()).state.lastSavedAt)
        assertTrue(storage.backupFiles.none { it.exists() })
    }

    @Test
    fun deleteAllOnEmptyDirectoryIsNoOp() {
        storage().deleteAll()
        assertEquals(SaveLoadResult.NoSave, storage().load())
    }

    private companion object {
        /** Spielstand aus dem Incremental-Design (Version 1) mit englischer Spracheinstellung. */
        const val LEGACY_EN = """{"saveVersion": 1, "lastSavedAt": 0, "meta": {}, "settings": {"language": "en", "numberFormat": "short"}}"""
    }
}

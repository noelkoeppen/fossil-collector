package de.noelkoeppen.fossilcollector.platform

import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.save.SaveCodec
import de.noelkoeppen.fossilcollector.core.save.SaveException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Spielstand als Datei mit atomarem Schreiben und rotierenden Backups (GAME_DESIGN.md, Kapitel 7.2).
 *
 * Bewusst reine Datei-Logik mit `java.io.File` ohne Android-Abhaengigkeit, damit Rotation und
 * Fallback als JVM-Unit-Tests (`app/src/test`) pruefbar sind. Die App uebergibt `Context.filesDir`.
 * Die Klasse liegt in `app/platform` (Kapitel 10.2) und nicht in `core`, weil Dateizugriff
 * Plattformsache ist; `core` liefert nur die Kodierung ([SaveCodec]).
 *
 * Dateien in [directory]:
 * - `save.json`: aktueller Spielstand
 * - `save.json.tmp`: wird vollstaendig geschrieben und erst dann umbenannt
 * - `save.bak1` (neuestes) bis `save.bak3` (aeltestes): vorige Staende
 * - `save.corrupt.json`: Kopie einer unlesbaren Hauptdatei, wenn kein Stand lesbar war
 *
 * Schreiben ([save]):
 * 1. JSON vollstaendig in `save.json.tmp` schreiben und mit `fsync` sichern.
 * 2. Backups rotieren: `bak2 -> bak3`, `bak1 -> bak2`, `save.json` nach `bak1` kopieren.
 *    War die Hauptdatei beim letzten [load] unlesbar, wird sie nicht als Backup uebernommen,
 *    damit sie keine lesbaren Backups verdraengt.
 * 3. `save.json.tmp` atomar in `save.json` umbenennen (ersetzt die alte Datei).
 *
 * Laden ([load]): `save.json`, dann `bak1`, `bak2`, `bak3`. Der erste lesbare Stand gewinnt.
 * `save.json.tmp` wird nie gelesen, weil es ein abgebrochener Schreibvorgang sein kann.
 *
 * Nicht thread-safe: Aufrufe muessen vom Aufrufer serialisiert werden (z. B. per Mutex).
 *
 * @param directory Verzeichnis der Spielstand-Dateien (wird bei Bedarf angelegt)
 * @param codec Kodierung des Spielstands
 */
class SaveStorage(
    private val directory: File,
    private val codec: SaveCodec = SaveCodec.DEFAULT,
) {
    /** Hauptdatei `save.json`. */
    val mainFile: File get() = File(directory, MAIN_NAME)

    /** Temporaere Datei fuer das atomare Schreiben. */
    val tempFile: File get() = File(directory, TEMP_NAME)

    /** Kopie einer unlesbaren Hauptdatei (nur wenn gar kein Stand lesbar war). */
    val corruptFile: File get() = File(directory, CORRUPT_NAME)

    /** Backup-Dateien vom neuesten (`bak1`) zum aeltesten (`bak3`). */
    val backupFiles: List<File> get() = (1..BACKUP_COUNT).map { File(directory, "$BACKUP_PREFIX$it") }

    /** `true`, wenn die Hauptdatei beim letzten [load] vorhanden, aber unlesbar war. */
    private var mainUnreadable: Boolean = false

    /**
     * Schreibt [state] atomar und rotiert die Backups. [state] wird unveraendert geschrieben;
     * `lastSavedAt` setzt der Aufrufer vorher.
     *
     * @throws SaveException.NotEncodable wenn der Zustand nicht kodierbar ist (dann bleibt alles unveraendert)
     * @throws IOException bei Schreibfehlern; die bisherige Hauptdatei bleibt dann erhalten
     */
    fun save(state: GameState) {
        val text = codec.encode(state)
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Verzeichnis kann nicht angelegt werden: $directory")
        }
        writeFully(tempFile, text)
        rotateBackups()
        moveReplacing(tempFile, mainFile)
        mainUnreadable = false
    }

    /**
     * Laedt den neuesten lesbaren Spielstand.
     *
     * @return [SaveLoadResult.NoSave], wenn keine Datei existiert; [SaveLoadResult.Loaded] mit
     *   Quelle; [SaveLoadResult.Unreadable], wenn Dateien existieren, aber keine lesbar ist
     */
    fun load(): SaveLoadResult {
        val candidates = listOf(SaveSource.Main to mainFile) +
            backupFiles.mapIndexed { i, file -> SaveSource.Backup(i + 1) to file }
        val failures = mutableListOf<SaveLoadFailure>()
        var mainExists = false
        for ((source, file) in candidates) {
            if (!file.isFile) continue
            if (source == SaveSource.Main) mainExists = true
            try {
                val state = codec.decode(file.readText(Charsets.UTF_8))
                mainUnreadable = mainExists && source != SaveSource.Main
                return SaveLoadResult.Loaded(state, source, failures.toList())
            } catch (e: SaveException) {
                failures += SaveLoadFailure(source, e)
            } catch (e: IOException) {
                failures += SaveLoadFailure(source, e)
            }
        }
        mainUnreadable = mainExists
        if (failures.isEmpty()) return SaveLoadResult.NoSave
        if (mainExists) preserveCorruptMain()
        return SaveLoadResult.Unreadable(failures.toList())
    }

    /**
     * Loescht alle Spielstand-Dateien: Hauptdatei, temporaere Datei, Backups und die Kopie einer
     * unlesbaren Hauptdatei ("Spielstand loeschen", AP-24). Danach liefert [load] [SaveLoadResult.NoSave].
     *
     * Die Backups werden mit geloescht: [load] faellt bei einer defekten Hauptdatei auf sie zurueck,
     * ein geloeschter Spielstand koennte sonst spaeter wieder auftauchen.
     *
     * @throws IOException wenn eine vorhandene Datei nicht geloescht werden kann
     */
    fun deleteAll() {
        val files = listOf(mainFile, tempFile, corruptFile) + backupFiles
        for (file in files) {
            Files.deleteIfExists(file.toPath())
        }
        mainUnreadable = false
    }

    private fun rotateBackups() {
        val backups = backupFiles
        for (i in backups.lastIndex downTo 1) {
            if (backups[i - 1].isFile) moveReplacing(backups[i - 1], backups[i])
        }
        val main = mainFile
        if (main.isFile) {
            if (mainUnreadable) {
                main.delete()
            } else {
                Files.copy(main.toPath(), backups[0].toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** Sichert eine unlesbare Hauptdatei, bevor ein neuer Spielstand sie ueberschreibt (Fehlersuche). */
    private fun preserveCorruptMain() {
        try {
            Files.copy(mainFile.toPath(), corruptFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            // Nur eine Diagnosehilfe; ein Fehler hier verhindert das Weiterspielen nicht.
        }
    }

    private fun writeFully(file: File, text: String) {
        FileOutputStream(file).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
    }

    private fun moveReplacing(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val MAIN_NAME: String = "save.json"
        const val TEMP_NAME: String = "save.json.tmp"
        const val BACKUP_PREFIX: String = "save.bak"
        const val CORRUPT_NAME: String = "save.corrupt.json"

        /** Anzahl rotierender Backups (Kapitel 7.2). */
        const val BACKUP_COUNT: Int = 3
    }
}

/** Herkunft eines geladenen Spielstands. */
sealed interface SaveSource {
    /** Hauptdatei `save.json`. */
    data object Main : SaveSource

    /** Backup `save.bak<index>`, 1 = neuestes. */
    data class Backup(val index: Int) : SaveSource
}

/** Eine Datei, die beim Laden nicht lesbar war. */
data class SaveLoadFailure(val source: SaveSource, val error: Exception)

/** Ergebnis von [SaveStorage.load]. */
sealed interface SaveLoadResult {
    /** Es gibt keinen Spielstand (erster Start). */
    data object NoSave : SaveLoadResult

    /**
     * Ein Spielstand wurde geladen.
     *
     * @property source Hauptdatei oder Backup; bei einem Backup soll die UI den Spieler informieren
     * @property failures vorher gepruefte, unlesbare Dateien (leer, wenn die Hauptdatei lesbar war)
     */
    data class Loaded(
        val state: GameState,
        val source: SaveSource,
        val failures: List<SaveLoadFailure>,
    ) : SaveLoadResult {
        /** `true`, wenn ein Backup statt der Hauptdatei geladen wurde. */
        val fromBackup: Boolean get() = source != SaveSource.Main
    }

    /** Es gibt Spielstand-Dateien, aber keine ist lesbar. */
    data class Unreadable(val failures: List<SaveLoadFailure>) : SaveLoadResult
}

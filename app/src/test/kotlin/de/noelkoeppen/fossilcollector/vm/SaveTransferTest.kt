package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.data.DataRepository
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.RunStart
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.LanguageSetting
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.Settings
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.number.NumberNotation
import de.noelkoeppen.fossilcollector.core.save.SaveCodec
import de.noelkoeppen.fossilcollector.core.save.SaveException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/** Tests fuer den Export-/Import-Ablauf und den Neustart des Einstellungs-Screens (AP-24). */
class SaveTransferTest {

    private val data: GameData = DataRepository().load()
    private val transfer = SaveTransfer(data)
    private val skull = PieceKey("cave_bear", "skull")

    private fun newGame(now: Long = 1_000L): GameState =
        GameState.newGame(data.layers.first().id, RunStart.values(data, ModifierEngine.fromGameData(data)), now)

    /** Ein Stand mit Fortschritt (Ruhm, Skill, Buch, Geld) und eigenen Einstellungen. */
    private val played: GameState = newGame().let {
        it.copy(
            meta = it.meta.copy(
                fame = GameNumber(3.0),
                fameTotal = GameNumber(12.0),
                prestigeCount = GameNumber.ONE,
                skills = setOf(data.skills.first().id),
                book = mapOf(skull to BookEntry(0.7)),
            ),
            run = it.run.copy(money = GameNumber(4_321.0)),
            settings = Settings(LanguageSetting.EN, NumberNotation.SCIENTIFIC),
        )
    }

    private fun base64(text: String): String = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

    private fun exported(state: GameState): String =
        assertInstanceOf(SaveExportResult.Success::class.java, transfer.export(state)).text

    private fun importError(text: String): ImportError =
        assertInstanceOf(SaveImportResult.Failure::class.java, transfer.import(text, NOW)).error

    @Test
    fun `Export und Import ergeben denselben Stand mit lastSavedAt jetzt`() {
        val text = exported(played)
        val result = assertInstanceOf(SaveImportResult.Success::class.java, transfer.import(text, NOW))

        assertEquals(played.copy(lastSavedAt = NOW), result.state)
    }

    @Test
    fun `Import setzt lastSavedAt auf jetzt, damit kein Offline-Fortschritt entsteht`() {
        val old = played.copy(lastSavedAt = 5L)
        val result = assertInstanceOf(SaveImportResult.Success::class.java, transfer.import(exported(old), NOW))

        assertEquals(NOW, result.state.lastSavedAt)
    }

    @Test
    fun `Leerraum und Zeilenumbrueche beim Kopieren stoeren nicht`() {
        val text = exported(played).chunked(40).joinToString("\n", prefix = "  ", postfix = " \n")
        assertInstanceOf(SaveImportResult.Success::class.java, transfer.import(text, NOW))
    }

    @Test
    fun `leerer Text`() {
        assertEquals(ImportError.EMPTY, importError(""))
        assertEquals(ImportError.EMPTY, importError("  \n "))
    }

    @Test
    fun `kein Base64 bzw kein UTF-8`() {
        assertEquals(ImportError.INVALID_ENCODING, importError("das ist kein spielstand!"))
        assertEquals(ImportError.INVALID_ENCODING, importError(Base64.getEncoder().encodeToString(byteArrayOf(0xC3.toByte(), 0x28))))
    }

    @Test
    fun `abgeschnittener Export`() {
        val text = exported(played)
        // Abgeschnitten mitten im JSON: Base64 bleibt ggf. gueltig, der Inhalt nicht.
        val error = importError(text.substring(0, text.length / 2))
        assertTrue(error == ImportError.INVALID_ENCODING || error == ImportError.INVALID_JSON, "war $error")
    }

    @Test
    fun `kein JSON-Objekt`() {
        assertEquals(ImportError.INVALID_JSON, importError(base64("[1, 2, 3]")))
        assertEquals(ImportError.INVALID_JSON, importError(base64("{ kaputt")))
    }

    @Test
    fun `Versionsfehler`() {
        assertEquals(ImportError.MISSING_VERSION, importError(base64("""{"lastSavedAt": 1}""")))
        assertEquals(ImportError.TOO_NEW, importError(base64("""{"saveVersion": 999}""")))
        assertEquals(ImportError.UNSUPPORTED_VERSION, importError(base64("""{"saveVersion": 0}""")))
    }

    @Test
    fun `falsche Struktur`() {
        assertEquals(ImportError.INVALID_CONTENT, importError(base64("""{"saveVersion": 1, "lastSavedAt": 1}""")))
    }

    @Test
    fun `unbekannte IDs werden abgelehnt`() {
        val bad = played.copy(meta = played.meta.copy(skills = setOf("does_not_exist")))
        val text = Base64.getEncoder().encodeToString(SaveCodec.DEFAULT.encode(bad).toByteArray(Charsets.UTF_8))

        val failure = assertInstanceOf(SaveImportResult.Failure::class.java, transfer.import(text, NOW))
        assertEquals(ImportError.UNKNOWN_CONTENT, failure.error)
        assertEquals("meta.skills: does_not_exist", failure.detail)
    }

    @Test
    fun `nicht kodierbarer Stand liefert Export-Fehler`() {
        val broken = played.copy(run = played.run.copy(money = GameNumber(Double.NaN)))
        assertInstanceOf(SaveExportResult.Failure::class.java, transfer.export(broken))
    }

    @Test
    fun `jeder Ladefehler hat eine Zuordnung`() {
        assertEquals(ImportError.MIGRATION_FAILED, SaveTransfer.errorOf(SaveException.MigrationFailed(1, IllegalStateException("x"))))
        assertEquals(ImportError.TOO_NEW, SaveTransfer.errorOf(SaveException.UnsupportedVersion(3, 1)))
        assertEquals(ImportError.UNSUPPORTED_VERSION, SaveTransfer.errorOf(SaveException.UnsupportedVersion(0, 1)))
        assertEquals(ImportError.INVALID_CONTENT, SaveTransfer.errorOf(SaveException.NotEncodable("x")))
    }

    @Test
    fun `neues Spiel nach dem Loeschen behaelt nur die Einstellungen`() {
        val fresh = transfer.newGame(played, NOW)

        assertEquals(newGame(NOW).copy(settings = played.settings), fresh)
        assertEquals(GameNumber.ZERO, fresh.meta.fame)
        assertTrue(fresh.meta.skills.isEmpty())
        assertTrue(fresh.meta.book.isEmpty())
    }

    @Test
    fun `Startwerte des neuen Spiels haengen nicht von alten Skills ab`() {
        // Auch wenn die Modifier-Engine des laufenden Spiels noch Skills kennt: neue Engine ohne Quellen.
        val fresh = transfer.newGame(null, NOW)
        assertEquals(newGame(NOW), fresh)
        assertEquals(Settings(), fresh.settings)
    }

    private companion object {
        const val NOW = 1_762_400_000_000L
    }
}

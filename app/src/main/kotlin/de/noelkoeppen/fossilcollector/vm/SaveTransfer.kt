package de.noelkoeppen.fossilcollector.vm

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.engine.RunStart
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.save.SaveCodec
import de.noelkoeppen.fossilcollector.core.save.SaveException
import de.noelkoeppen.fossilcollector.core.save.SaveReferenceValidator

/**
 * Export, Import und Neustart des Spielstands fuer den Einstellungs-Screen (AP-24, Kapitel 7.2 und
 * 8.3). Reine Logik ohne Android und ohne Dateizugriff, damit der Ablauf als JVM-Test pruefbar ist;
 * das [GameViewModel] uebernimmt Threading, Modifier-Abgleich und Speichern.
 *
 * @param data Spieldaten (fuer die Referenzpruefung und die Startwerte eines neuen Spiels)
 * @param codec Kodierung des Spielstands
 */
class SaveTransfer(
    private val data: GameData,
    private val codec: SaveCodec = SaveCodec.DEFAULT,
) {
    /** Spielstand als Base64-Text fuer die Zwischenablage ([SaveCodec.exportBase64]). */
    fun export(state: GameState): SaveExportResult =
        try {
            SaveExportResult.Success(codec.exportBase64(state))
        } catch (e: SaveException) {
            SaveExportResult.Failure(e.message)
        }

    /**
     * Liest einen Import-Text: Base64 dekodieren, validieren und migrieren ([SaveCodec.importBase64]),
     * dann die IDs gegen die Spieldaten pruefen ([SaveReferenceValidator]).
     *
     * Der Offline-Fortschritt wird fuer importierte Staende **nicht** angewendet: `lastSavedAt` wird
     * auf [now] gesetzt. Ein Export kann beliebig alt sein; sonst braechte jeder Import bis zu
     * `offline_cap_hours` Fortschritt (und alte Exporte waeren eine Offline-Quelle ohne Abwesenheit).
     *
     * @param now aktuelle Zeit in Millisekunden, neues `lastSavedAt`
     */
    fun import(text: String, now: Long): SaveImportResult {
        if (text.isBlank()) return SaveImportResult.Failure(ImportError.EMPTY)
        val state = try {
            codec.importBase64(text)
        } catch (e: SaveException) {
            return SaveImportResult.Failure(errorOf(e), e.message)
        }
        SaveReferenceValidator.findUnknownReference(state, data)?.let {
            return SaveImportResult.Failure(ImportError.UNKNOWN_CONTENT, it)
        }
        return SaveImportResult.Success(state.copy(lastSavedAt = now))
    }

    /**
     * Neues Spiel nach "Spielstand loeschen": `GameState.newGame` mit [RunStart.values]. Die
     * Startwerte kommen aus einer frischen [ModifierEngine] ohne Quellen (leerer Metazustand), weil
     * die Engine des laufenden Spiels noch Skills und Buch-Boni des geloeschten Stands kennt.
     *
     * Die Einstellungen (Sprache, Zahlenformat) bleiben erhalten: Sie sind Bedienvorlieben, kein
     * Spielfortschritt (wie beim Prestige, Kapitel 5.3).
     */
    fun newGame(previous: GameState?, now: Long): GameState {
        val fresh = GameState.newGame(
            firstLayerId = data.layers.first().id,
            start = RunStart.values(data, ModifierEngine.fromGameData(data)),
            now = now,
        )
        return if (previous == null) fresh else fresh.copy(settings = previous.settings)
    }

    companion object {
        /** Ordnet einen Ladefehler einer Meldung zu. */
        fun errorOf(error: SaveException): ImportError = when (error) {
            is SaveException.InvalidEncoding -> ImportError.INVALID_ENCODING
            is SaveException.InvalidJson -> ImportError.INVALID_JSON
            is SaveException.MissingVersion -> ImportError.MISSING_VERSION
            is SaveException.UnsupportedVersion ->
                if (error.isTooNew) ImportError.TOO_NEW else ImportError.UNSUPPORTED_VERSION
            is SaveException.MigrationFailed -> ImportError.MIGRATION_FAILED
            is SaveException.InvalidContent -> ImportError.INVALID_CONTENT
            // Kommt beim Lesen nicht vor; der Vollstaendigkeit halber als ungueltiger Inhalt.
            is SaveException.NotEncodable -> ImportError.INVALID_CONTENT
        }
    }
}

/** Ergebnis von [SaveTransfer.export]. */
sealed interface SaveExportResult {
    /** Base64-Text fuer die Zwischenablage. */
    data class Success(val text: String) : SaveExportResult

    /**
     * Kein Export moeglich: Spielstand nicht kodierbar (z. B. `NaN`, sollte im Spiel nicht
     * vorkommen) oder noch kein Spiel geladen.
     *
     * @property detail technische Beschreibung (nur fuers Log)
     */
    data class Failure(val detail: String? = null) : SaveExportResult
}

/** Ergebnis von [SaveTransfer.import]. */
sealed interface SaveImportResult {
    /** Gueltiger, migrierter Spielstand mit `lastSavedAt = now`. */
    data class Success(val state: GameState) : SaveImportResult

    /**
     * Import abgelehnt; der aktuelle Spielstand bleibt unveraendert.
     *
     * @property detail technische Beschreibung (nur fuers Log, nicht fuer die Anzeige)
     */
    data class Failure(val error: ImportError, val detail: String? = null) : SaveImportResult
}

/** Gruende fuer einen abgelehnten Import; die UI zeigt je Grund einen eigenen Text. */
enum class ImportError {
    /** Kein Text (leeres Feld bzw. leere Zwischenablage). */
    EMPTY,

    /** Kein gueltiges Base64 bzw. UTF-8, meist unvollstaendig kopiert. */
    INVALID_ENCODING,

    /** Dekodiert, aber kein JSON-Objekt. */
    INVALID_JSON,

    /** Feld `saveVersion` fehlt. */
    MISSING_VERSION,

    /** Spielstand aus einer neueren App-Version. */
    TOO_NEW,

    /** Version kleiner als 1. */
    UNSUPPORTED_VERSION,

    /** Ein Migrationsschritt ist fehlgeschlagen. */
    MIGRATION_FAILED,

    /** Struktur passt nicht zu `GameState`. */
    INVALID_CONTENT,

    /** IDs, die es in den Spieldaten nicht gibt ([SaveReferenceValidator]). */
    UNKNOWN_CONTENT,
}

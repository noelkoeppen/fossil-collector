package de.noelkoeppen.fossilcollector.core.save

import de.noelkoeppen.fossilcollector.core.model.GameState
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Wandelt [GameState] in JSON und zurueck (Kapitel 7.2). Reines Kotlin/JVM ohne Dateizugriff:
 * atomares Schreiben und Backups sind Aufgabe von `app/platform/SaveStorage` (AP-16).
 *
 * Laden: Text -> JSON-Objekt -> `saveVersion` pruefen -> [SaveMigrator] -> `GameState`.
 * Alle Fehler werden als [SaveException] gemeldet.
 *
 * JSON-Konfiguration:
 * - `encodeDefaults = true`: Jeder Spielstand enthaelt alle Felder. Spaetere Aenderungen von
 *   Standardwerten veraendern so keine bestehenden Spielstaende.
 * - `ignoreUnknownKeys = true`: Unbekannte Felder werden beim Laden ignoriert. Neuere
 *   Spielstaende werden ohnehin ueber `saveVersion` abgewiesen; innerhalb einer Version soll ein
 *   ueberzaehliges Feld (z. B. von einer Migration vergessenes Altfeld oder ein Export aus
 *   einem Entwicklungsstand) den Spielstand nicht unbrauchbar machen. Fehlende Pflichtfelder,
 *   falsche Typen und ungueltige Schluessel fuehren weiterhin zu [SaveException.InvalidContent].
 * - `NaN`/`Infinity` sind nicht erlaubt (Standard), weder beim Schreiben noch beim Lesen.
 *
 * @param migrator Migrationskette; im Spiel immer [SaveMigrator.DEFAULT], eigene Instanzen fuer Tests
 */
class SaveCodec(
    private val migrator: SaveMigrator = SaveMigrator.DEFAULT,
) {
    /**
     * Schreibt [state] als kompaktes JSON.
     *
     * @throws SaveException.NotEncodable wenn ein Wert nicht schreibbar ist (z. B. `NaN`)
     */
    fun encode(state: GameState): String =
        try {
            JSON.encodeToString(GameState.serializer(), state)
        } catch (e: SerializationException) {
            throw SaveException.NotEncodable("Spielstand nicht schreibbar: ${e.message}", e)
        }

    /**
     * Liest einen Spielstand, migriert ihn bei Bedarf und prueft die Struktur.
     *
     * @throws SaveException bei defektem JSON, fehlender oder nicht unterstuetzter Version,
     *   fehlgeschlagener Migration oder unpassender Struktur
     */
    fun decode(text: String): GameState {
        val element: JsonElement = try {
            JSON.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw SaveException.InvalidJson("Spielstand ist kein gueltiges JSON: ${e.message}", e)
        }
        val root = element as? JsonObject
            ?: throw SaveException.InvalidJson("Spielstand ist kein JSON-Objekt")
        val migrated = migrator.migrate(root)
        return try {
            JSON.decodeFromJsonElement(GameState.serializer(), migrated)
        } catch (e: IllegalArgumentException) {
            // Deckt SerializationException (Unterklasse) und require()-Fehler der Modellklassen ab.
            throw SaveException.InvalidContent("Spielstand hat eine ungueltige Struktur: ${e.message}", e)
        }
    }

    /**
     * Export fuer die Zwischenablage (Kapitel 7.2): JSON als UTF-8, Base64-kodiert (ohne Zeilenumbrueche).
     *
     * @throws SaveException.NotEncodable wie [encode]
     */
    fun exportBase64(state: GameState): String =
        Base64.getEncoder().encodeToString(encode(state).toByteArray(Charsets.UTF_8))

    /**
     * Import aus der Zwischenablage: Base64 dekodieren, dann wie [decode] validieren und migrieren.
     * Leerraum (Zeilenumbrueche, Leerzeichen beim Kopieren) wird vorher entfernt.
     *
     * @throws SaveException.InvalidEncoding bei ungueltigem Base64 oder UTF-8
     * @throws SaveException alle Fehler aus [decode]
     */
    fun importBase64(text: String): GameState {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) throw SaveException.InvalidEncoding("Import-Text ist leer")
        val bytes = try {
            Base64.getDecoder().decode(compact)
        } catch (e: IllegalArgumentException) {
            throw SaveException.InvalidEncoding("Import-Text ist kein gueltiges Base64: ${e.message}", e)
        }
        val json = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw SaveException.InvalidEncoding("Import-Text ist kein gueltiges UTF-8", e)
        }
        return decode(json)
    }

    companion object {
        /** Codec mit der echten Migrationskette. */
        val DEFAULT: SaveCodec = SaveCodec()

        private val JSON: Json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

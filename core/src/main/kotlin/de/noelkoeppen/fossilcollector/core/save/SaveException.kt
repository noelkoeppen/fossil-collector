package de.noelkoeppen.fossilcollector.core.save

import de.noelkoeppen.fossilcollector.core.model.Settings

/**
 * Fehler beim Lesen oder Schreiben eines Spielstands.
 *
 * Die App (AP-16 `SaveStorage`, AP-24 Import) unterscheidet anhand des Typs, ob sie auf ein
 * Backup ausweicht bzw. welche Meldung sie zeigt.
 */
sealed class SaveException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Der Text ist kein gueltiges JSON bzw. kein JSON-Objekt. */
    class InvalidJson(message: String, cause: Throwable? = null) : SaveException(message, cause)

    /** Der Import-Text ist kein gueltiges Base64 bzw. kein gueltiges UTF-8. */
    class InvalidEncoding(message: String, cause: Throwable? = null) : SaveException(message, cause)

    /** Das Feld `saveVersion` fehlt oder ist keine ganze Zahl. */
    class MissingVersion(message: String) : SaveException(message)

    /**
     * Die Version wird nicht unterstuetzt: neuer als [supportedVersion] (Spielstand aus einer
     * neueren App-Version) oder kleiner als 1 (ungueltig).
     */
    class UnsupportedVersion(val foundVersion: Int, val supportedVersion: Int) :
        SaveException("Spielstand-Version $foundVersion wird nicht unterstuetzt (aktuell: $supportedVersion)") {
        /** `true`, wenn der Spielstand aus einer neueren App-Version stammt. */
        val isTooNew: Boolean get() = foundVersion > supportedVersion
    }

    /**
     * Spielstand aus einem alten Design (Version kleiner als [oldestSupportedVersion]). Er wird
     * nicht migriert, sondern verworfen; die App startet ein neues Spiel und zeigt einen Hinweis.
     *
     * @property legacySettings Einstellungen aus dem alten Spielstand, soweit lesbar, sonst `null`.
     *   Sie sind Bedienvorlieben, kein Spielfortschritt, und duerfen uebernommen werden.
     */
    class ObsoleteVersion(
        val foundVersion: Int,
        val oldestSupportedVersion: Int,
        val legacySettings: Settings? = null,
    ) : SaveException(
        "Spielstand-Version $foundVersion ist veraltet und wird verworfen (aelteste unterstuetzte: $oldestSupportedVersion)",
    )

    /** Ein Migrationsschritt ist fehlgeschlagen. */
    class MigrationFailed(val fromVersion: Int, cause: Throwable) :
        SaveException("Migration von Version $fromVersion fehlgeschlagen: ${cause.message}", cause)

    /** Das JSON passt nicht zur Struktur von `GameState` (fehlende Felder, falsche Typen, ungueltige Schluessel). */
    class InvalidContent(message: String, cause: Throwable? = null) : SaveException(message, cause)

    /** Der Spielstand laesst sich nicht schreiben, z. B. wegen `NaN` oder `Infinity` in einem Spielwert. */
    class NotEncodable(message: String, cause: Throwable? = null) : SaveException(message, cause)
}

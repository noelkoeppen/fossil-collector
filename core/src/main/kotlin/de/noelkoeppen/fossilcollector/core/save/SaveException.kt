package de.noelkoeppen.fossilcollector.core.save

/**
 * Fehler beim Lesen oder Schreiben eines Spielstands (Kapitel 7.2).
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
     * neueren App-Version) oder kleiner als 1.
     */
    class UnsupportedVersion(val foundVersion: Int, val supportedVersion: Int) :
        SaveException("Spielstand-Version $foundVersion wird nicht unterstuetzt (aktuell: $supportedVersion)") {
        /** `true`, wenn der Spielstand aus einer neueren App-Version stammt. */
        val isTooNew: Boolean get() = foundVersion > supportedVersion
    }

    /** Ein Migrationsschritt ist fehlgeschlagen. */
    class MigrationFailed(val fromVersion: Int, cause: Throwable) :
        SaveException("Migration von Version $fromVersion fehlgeschlagen: ${cause.message}", cause)

    /** Das JSON passt nicht zur Struktur von `GameState` (fehlende Felder, falsche Typen, ungueltige Schluessel). */
    class InvalidContent(message: String, cause: Throwable? = null) : SaveException(message, cause)

    /** Der Spielstand laesst sich nicht schreiben, z. B. wegen `NaN` oder `Infinity` in einem Spielwert. */
    class NotEncodable(message: String, cause: Throwable? = null) : SaveException(message, cause)
}

package de.noelkoeppen.fossilcollector.core.save

import de.noelkoeppen.fossilcollector.core.model.GameState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Ein Migrationsschritt `v(fromVersion) -> v(fromVersion + 1)` auf JSON-Ebene.
 *
 * Arbeitet auf [JsonObject] statt auf `GameState`, weil alte Formate nicht mehr zu den
 * aktuellen Klassen passen. Das Feld `saveVersion` setzt der [SaveMigrator], nicht der Schritt.
 */
interface SaveMigration {
    /** Ausgangsversion; das Ergebnis hat die Version `fromVersion + 1`. */
    val fromVersion: Int

    /** Wandelt einen Spielstand der Version [fromVersion] in die Folgeversion um. */
    fun migrate(save: JsonObject): JsonObject
}

/**
 * Bringt einen Spielstand per Migrationskette auf [currentVersion].
 *
 * Versionen ab 1, aber unter [oldestSupportedVersion] gelten als veraltet und werden nicht migriert
 * ([SaveException.ObsoleteVersion]). Die Kette muss lueckenlos sein: fuer jede Version von
 * [oldestSupportedVersion] bis `currentVersion - 1` genau ein Schritt. Das wird beim Erzeugen
 * geprueft. Im Spiel wird [DEFAULT] verwendet; eigene Instanzen sind fuer Tests gedacht.
 */
class SaveMigrator(
    migrations: List<SaveMigration>,
    val currentVersion: Int,
    val oldestSupportedVersion: Int = FIRST_VERSION,
) {
    private val steps: Map<Int, SaveMigration> = migrations.associateBy { it.fromVersion }

    init {
        require(oldestSupportedVersion >= FIRST_VERSION) { "oldestSupportedVersion muss mindestens $FIRST_VERSION sein" }
        require(currentVersion >= oldestSupportedVersion) { "currentVersion muss mindestens $oldestSupportedVersion sein" }
        require(steps.size == migrations.size) { "Doppelte Migrationsschritte: ${migrations.map { it.fromVersion }}" }
        val expected = (oldestSupportedVersion until currentVersion).toSet()
        require(steps.keys == expected) {
            "Migrationskette unvollstaendig: erwartet Schritte ab $expected, vorhanden ${steps.keys.sorted()}"
        }
    }

    /**
     * Liest `saveVersion` aus [save]. Wirft [SaveException.MissingVersion], wenn das Feld fehlt
     * oder keine ganze Zahl ist.
     */
    fun versionOf(save: JsonObject): Int {
        val element = save[VERSION_FIELD] ?: throw SaveException.MissingVersion("Feld '$VERSION_FIELD' fehlt")
        val primitive = element as? JsonPrimitive
        val version = primitive?.takeUnless { it.isString }?.intOrNull
        return version ?: throw SaveException.MissingVersion("Feld '$VERSION_FIELD' ist keine ganze Zahl: $element")
    }

    /**
     * Migriert [save] schrittweise auf [currentVersion] und setzt nach jedem Schritt `saveVersion`.
     *
     * @throws SaveException.MissingVersion wenn `saveVersion` fehlt
     * @throws SaveException.UnsupportedVersion wenn die Version kleiner 1 oder neuer als [currentVersion] ist
     * @throws SaveException.ObsoleteVersion wenn die Version kleiner als [oldestSupportedVersion] ist
     *   (ohne `legacySettings`; die ergaenzt `SaveCodec`)
     * @throws SaveException.MigrationFailed wenn ein Schritt eine Exception wirft
     */
    fun migrate(save: JsonObject): JsonObject {
        var version = versionOf(save)
        if (version < FIRST_VERSION || version > currentVersion) {
            throw SaveException.UnsupportedVersion(version, currentVersion)
        }
        if (version < oldestSupportedVersion) {
            throw SaveException.ObsoleteVersion(version, oldestSupportedVersion)
        }
        var current = save
        while (version < currentVersion) {
            val step = steps.getValue(version)
            val migrated = try {
                step.migrate(current)
            } catch (e: Exception) {
                throw SaveException.MigrationFailed(version, e)
            }
            version += 1
            current = JsonObject(migrated + (VERSION_FIELD to JsonPrimitive(version)))
        }
        return current
    }

    companion object {
        /** Name des Versionsfelds im Spielstand. */
        const val VERSION_FIELD: String = "saveVersion"

        /** Erste Version des Speicherformats. */
        const val FIRST_VERSION: Int = 1

        /**
         * Alle Migrationsschritte des Spiels ab [GameState.OLDEST_SUPPORTED_SAVE_VERSION], aufsteigend.
         * Bei Version 3 noch leer.
         */
        val MIGRATIONS: List<SaveMigration> = emptyList()

        /** Migrator mit der echten Kette von [GameState.OLDEST_SUPPORTED_SAVE_VERSION] bis [GameState.CURRENT_SAVE_VERSION]. */
        val DEFAULT: SaveMigrator =
            SaveMigrator(MIGRATIONS, GameState.CURRENT_SAVE_VERSION, GameState.OLDEST_SUPPORTED_SAVE_VERSION)
    }
}

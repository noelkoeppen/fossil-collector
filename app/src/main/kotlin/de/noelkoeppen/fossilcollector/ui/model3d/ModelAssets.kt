package de.noelkoeppen.fossilcollector.ui.model3d

/**
 * Pfade der 3D-Modelle in den App-Assets (AP2-10, `GAME_DESIGN.md` Kapitel 8).
 *
 * Die Dateien werden nicht eingecheckt, sondern beim Build von der Gradle-Aufgabe
 * `syncModelAssets` (`app/build.gradle.kts`) aus `art/models3d/<speciesId>/bones/<boneId>.glb`
 * kopiert, fuer jede Art aus `species.json`. Die Pfade ergeben sich nur aus Art- und Knochen-ID,
 * eine neue Art braucht keinen Code.
 */
object ModelAssets {
    /** Wurzelordner in den Assets. */
    const val ROOT = "models3d"

    fun speciesDir(speciesId: String): String = "$ROOT/$speciesId"

    fun bonePath(speciesId: String, boneId: String): String = "${speciesDir(speciesId)}/bones/$boneId.glb"
}

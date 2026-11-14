package de.noelkoeppen.fossilcollector.ui.dig

/**
 * Pfade der vorgerenderten Knochenbilder in den App-Assets (AP2-06, `GAME_DESIGN.md` Kapitel 8).
 *
 * Erzeugt von `tools/models3d/bone_images.py` aus `art/models3d/<speciesId>/`, ein Ordner pro Art:
 * - `manifest.json`: Render-Parameter (Blickrichtung, Pixel pro Modelleinheit) und pro Knochen
 *   Bildgroesse und Lage im gemeinsamen Pixelraster der Art,
 * - `images/<boneId>.png`: Draufsicht mit Transparenz,
 * - `masks/<boneId>.png`: Umrissmaske (1 Bit).
 *
 * Die Pfade ergeben sich nur aus Art- und Knochen-ID, eine neue Art braucht keinen Code.
 */
object BoneImageAssets {
    /** Wurzelordner in `app/src/main/assets`. */
    const val ROOT = "bones"

    fun speciesDir(speciesId: String): String = "$ROOT/$speciesId"

    fun manifestPath(speciesId: String): String = "${speciesDir(speciesId)}/manifest.json"

    fun imagePath(speciesId: String, boneId: String): String = "${speciesDir(speciesId)}/images/$boneId.png"

    fun maskPath(speciesId: String, boneId: String): String = "${speciesDir(speciesId)}/masks/$boneId.png"
}

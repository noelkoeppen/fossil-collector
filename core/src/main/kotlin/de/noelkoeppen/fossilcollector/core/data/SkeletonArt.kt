package de.noelkoeppen.fossilcollector.core.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable

/**
 * Skelett-Grafik aus `data/skeletons/skeleton_<name>.json` (Kapitel 8.5), erzeugt von
 * `tools/svg2json.py` aus `art/skeletons/<name>.svg`.
 *
 * @property name Name der Quelle (Art-ID oder Koerperbau des Platzhalters)
 * @property viewBox Koordinatensystem der Pfade
 * @property bones Knochen in Zeichenreihenfolge
 */
@Serializable
data class SkeletonArt(
    val name: String,
    val viewBox: SkeletonViewBox,
    val bones: List<SkeletonArtBone>,
) {
    val bonesById: Map<String, SkeletonArtBone> by lazy { bones.associateBy { it.id } }
}

/** viewBox des SVG: Ursprung und Groesse in Pfadkoordinaten. */
@Serializable
data class SkeletonViewBox(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

/**
 * Ein Knochen der Grafik (SVG-Gruppe `id="<boneId>"`). Die Zonen stehen in derselben Reihenfolge
 * wie in `bones.json` (prueft [SkeletonArtValidator]); damit passt Index i zu
 * `zoneQualities[i]` der Funde.
 */
@Serializable
data class SkeletonArtBone(
    val id: String,
    val zones: List<SkeletonArtZone>,
)

/** Eine Zone (SVG-Pfad `id="<boneId>__<zoneId>"`) mit den Pfaddaten `d` in SVG-Syntax. */
@Serializable
data class SkeletonArtZone(
    val id: String,
    val d: String,
)

/**
 * Laedt Skelett-Grafiken aus `data/skeletons/` und waehlt die Grafik einer Art.
 *
 * **Fallback-Regel:** Eine Art nutzt `skeleton_<speciesId>.json` (entspricht `species.art`, siehe
 * [DataValidator]). Fehlt diese Datei (bis AP-27 fuer alle Arten), gilt der Platzhalter ihres
 * Koerperbaus `skeleton_<bodyPlan>.json` (`skeleton_quadruped.json`, `skeleton_biped.json`).
 *
 * @param readResource liefert den Inhalt einer Datei relativ zu `data/` oder `null`, wenn sie fehlt
 */
class SkeletonArtRepository(
    private val readResource: (String) -> String? = DataRepository.classpathReader,
) {

    /** Laedt die Datei [fileName] aus `data/skeletons/`; `null`, wenn sie fehlt. */
    fun load(fileName: String): SkeletonArt? {
        val path = DIRECTORY + fileName
        val text = readResource(path) ?: return null
        return try {
            DataRepository.json.decodeFromString<SkeletonArt>(text)
        } catch (e: SerializationException) {
            throw DataValidationException(listOf("$path: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            throw DataValidationException(listOf("$path: ${e.message}"))
        }
    }

    /** Erste vorhandene Datei aus [candidates] fuer die Art; `null`, wenn keine existiert. */
    fun resolveFile(speciesId: String, bodyPlan: BodyPlan): String? =
        candidates(speciesId, bodyPlan).firstOrNull { readResource(DIRECTORY + it) != null }

    /** Grafik der Art nach der Fallback-Regel; `null`, wenn weder Art- noch Platzhalterdatei existiert. */
    fun forSpecies(speciesId: String, bodyPlan: BodyPlan): SkeletonArt? =
        candidates(speciesId, bodyPlan).firstNotNullOfOrNull { load(it) }

    companion object {
        /** Unterordner von `data/`. */
        const val DIRECTORY = "skeletons/"

        /** Dateiname der Grafik `skeleton_<name>.json` (Art-ID oder Koerperbau). */
        fun fileName(name: String): String = "skeleton_$name.json"

        /** Dateiname des Platzhalters fuer [bodyPlan]. */
        fun placeholderFile(bodyPlan: BodyPlan): String = fileName(bodyPlan.serialName)

        /** Kandidaten in Prioritaetsreihenfolge: eigene Grafik der Art, dann Platzhalter des Koerperbaus. */
        fun candidates(speciesId: String, bodyPlan: BodyPlan): List<String> =
            listOf(fileName(speciesId), placeholderFile(bodyPlan))
    }
}

/**
 * Prueft eine [SkeletonArt] gegen `bones.json`: Jeder Knochen hat eine Gruppe, jede Zone einen
 * Pfad in der Reihenfolge aus `bones.json`; keine unbekannten Knochen oder Zonen.
 */
object SkeletonArtValidator {

    fun validate(file: String, art: SkeletonArt, bones: List<BoneDefinition>): List<String> {
        val errors = mutableListOf<String>()
        val box = art.viewBox
        val finite = listOf(box.x, box.y, box.width, box.height).all { it.isFinite() }
        if (!finite || box.width <= 0 || box.height <= 0) errors += "$file: viewBox ungueltig ($box)"

        art.bones.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
            .forEach { errors += "$file: Knochen '$it' doppelt" }
        val known = bones.associateBy { it.id }
        art.bones.filterNot { it.id in known }.forEach { errors += "$file: unbekannter Knochen '${it.id}'" }
        bones.filterNot { it.id in art.bonesById }.forEach { errors += "$file: Knochen '${it.id}' fehlt" }

        art.bones.forEach { bone ->
            val def = known[bone.id] ?: return@forEach
            val zoneIds = bone.zones.map { it.id }
            if (zoneIds != def.zones) {
                errors += "$file/${bone.id}: Zonen $zoneIds, erwartet ${def.zones} (gleiche Reihenfolge wie bones.json)"
            }
            bone.zones.forEach { zone ->
                val d = zone.d.trimStart()
                if (d.isEmpty() || d[0] !in "Mm") errors += "$file/${bone.id}/${zone.id}: Pfad muss mit M/m beginnen"
            }
        }
        return errors
    }
}

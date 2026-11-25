package de.noelkoeppen.fossilcollector.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64

/**
 * Ein Knochen in der Grabungsflaeche: Fund [findIndex] in [ExcavationSite.finds], Knochen [boneId].
 */
data class BoneRef(
    val findIndex: Int,
    val boneId: String,
)

/**
 * Ein platzierter Fund: der Fund aus dem Fundsystem und die Zellen, die jeder seiner Knochen in der
 * Flaeche belegt (Zellindex `y * width + x`).
 *
 * Die Zellen werden gespeichert und nicht aus den Umrissen neu berechnet. So bleibt eine laufende
 * Ausgrabung gueltig, wenn sich die Umrisse aendern (Platzhalter, spaeter Umrisse aus den Masken von AP2-06).
 *
 * @property find der Fund mit den aktuellen Qualitaeten seiner Knochen
 * @property cells Knochen-ID -> belegte Zellen; Schluessel sind genau die Knochen des Fundes
 */
@Serializable
data class PlacedFind(
    val find: Find,
    val cells: Map<String, List<Int>>,
) {
    init {
        require(cells.keys == find.boneIds.toSet()) { "Zellen ${cells.keys} passen nicht zu den Knochen ${find.boneIds}" }
        require(cells.values.all { it.isNotEmpty() }) { "Knochen ohne Zellen im Fund ${find.speciesId}" }
    }
}

/**
 * Laufende Ausgrabung (Kapitel 3, AP2-05): internes Raster aus [width] x [height] Zellen mit Erdtiefe
 * pro Zelle und platzierten Funden. Unveraenderlich; Aktionen (`Excavator`) erzeugen eine neue Flaeche.
 *
 * Erdtiefe: Erdeinheiten ueber dem, was in der Zelle liegt. Bei einer Knochenzelle mit Tiefe 0 liegt der
 * Knochen dort frei. Ein Knochen gilt als **entdeckt**, sobald mindestens eine seiner Zellen freiliegt
 * (man ist auf ihn gestossen). Sind alle Knochen entdeckt, ist in der Flaeche nichts mehr zu finden
 * ([nothingLeftToFind]).
 *
 * Geprueft wird hier nur die Struktur (Groessen, Tiefen, Zellen im Raster, keine Ueberlappung).
 * Referenzen auf die Spieldaten prueft [ReferenceValidator].
 *
 * Gespeichert wird die Flaeche kompakt: die Tiefen als Base64 von 16-Bit-Werten (siehe [MAX_DEPTH]).
 *
 * Feines Freilegen (AP2-08): [preparations] enthaelt die Nahansicht jedes Knochens, dessen Freilegen
 * begonnen hat (hoechstens eine je Knochen), samt Restschicht und Zustand „fertig freigelegt“.
 *
 * Bergen (AP2-09): [recoveries] enthaelt das Bergen jedes Knochens, dessen Bergen begonnen hat
 * (hoechstens eines je Knochen, nur fuer fertig freigelegte Knochen), samt bruechigen Stellen und
 * Zustand „geborgen“. Geborgene Knochen liegen im Inventar; in der Flaeche zaehlen sie nicht mehr
 * als Knochen (kein Hinweis, kein Treffer, keine Nahansicht).
 *
 * @property layerId Schicht der Ausgrabung
 * @property width Breite in Zellen
 * @property height Hoehe in Zellen
 * @property finds platzierte Funde; [BoneRef.findIndex] zeigt in diese Liste
 * @property preparations begonnene Nahansichten (Kapitel 5.1) in Reihenfolge des Beginns
 * @property recoveries begonnenes Bergen (Kapitel 5.2) in Reihenfolge des Beginns
 */
@Serializable(with = ExcavationSiteSerializer::class)
class ExcavationSite(
    val layerId: String,
    val width: Int,
    val height: Int,
    depths: IntArray,
    val finds: List<PlacedFind>,
    val preparations: List<BonePreparation> = emptyList(),
    val recoveries: List<BoneRecovery> = emptyList(),
) {
    private val depthArray: IntArray = depths.copyOf()

    /** Alle Knochen der Flaeche in Fund- und Knochenreihenfolge. */
    val bones: List<BoneRef> = finds.flatMapIndexed { i, placed -> placed.find.boneIds.map { BoneRef(i, it) } }

    /** Zelle -> Index in [bones] oder -1. */
    private val owner: IntArray = IntArray(width * height) { NO_BONE }

    init {
        require(width >= 1 && height >= 1) { "Flaeche muss mindestens 1x1 sein: ${width}x$height" }
        require(depthArray.size == width * height) { "Tiefen: ${depthArray.size} Werte fuer ${width}x$height Zellen" }
        require(depthArray.all { it in 0..MAX_DEPTH }) { "Tiefen muessen in 0..$MAX_DEPTH liegen" }
        bones.forEachIndexed { index, ref ->
            finds[ref.findIndex].cells.getValue(ref.boneId).forEach { cell ->
                require(cell in owner.indices) { "Zelle $cell von ${ref.boneId} liegt ausserhalb der Flaeche" }
                require(owner[cell] == NO_BONE) { "Zelle $cell ist doppelt belegt" }
                owner[cell] = index
            }
        }
        preparations.forEach { prep ->
            require(prep.findIndex in finds.indices && prep.boneId in finds[prep.findIndex].cells) {
                "Nahansicht fuer unbekannten Knochen ${prep.findIndex}/${prep.boneId}"
            }
        }
        require(preparations.map { it.ref }.toSet().size == preparations.size) { "Nahansicht doppelt" }
        recoveries.forEach { rec ->
            val prep = preparations.firstOrNull { it.ref == rec.ref }
            require(prep != null && prep.finished) { "Bergen von ${rec.findIndex}/${rec.boneId} ohne fertig freigelegte Nahansicht" }
            require(rec.spots.all { it < prep.cellCount && prep.isBone(it) }) { "Bruechige Stelle ausserhalb des Knochens ${rec.boneId}" }
        }
        require(recoveries.map { it.ref }.toSet().size == recoveries.size) { "Bergen doppelt" }
    }

    /** Anzahl Zellen. */
    val cellCount: Int get() = width * height

    /** Zellindex von ([x], [y]). */
    fun cellIndex(x: Int, y: Int): Int {
        require(contains(x, y)) { "Zelle ($x, $y) liegt ausserhalb von ${width}x$height" }
        return y * width + x
    }

    /** `true`, wenn ([x], [y]) im Raster liegt. */
    fun contains(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    /** Spalte einer Zelle. */
    fun xOf(cell: Int): Int = cell % width

    /** Zeile einer Zelle. */
    fun yOf(cell: Int): Int = cell / width

    /** Erdtiefe der Zelle [cell]. */
    fun depthAt(cell: Int): Int = depthArray[cell]

    /** Erdtiefe der Zelle ([x], [y]). */
    fun depthAt(x: Int, y: Int): Int = depthArray[cellIndex(x, y)]

    /** Kopie aller Tiefen (Zellindex `y * width + x`). */
    fun depthsCopy(): IntArray = depthArray.copyOf()

    /** Knochen in Zelle [cell] oder `null`. */
    fun boneAt(cell: Int): BoneRef? = owner[cell].takeIf { it != NO_BONE }?.let { bones[it] }

    /** Knochen in Zelle ([x], [y]) oder `null`. */
    fun boneAt(x: Int, y: Int): BoneRef? = boneAt(cellIndex(x, y))

    /** Zellen des Knochens [ref]. */
    fun cellsOf(ref: BoneRef): List<Int> = finds[ref.findIndex].cells.getValue(ref.boneId)

    /** Knochen [ref] mit aktueller Qualitaet. */
    fun piece(ref: BoneRef): Piece = finds[ref.findIndex].find.pieces.first { it.boneId == ref.boneId }

    /** `true`, wenn mindestens eine Zelle von [ref] freiliegt (Tiefe 0). */
    fun isDiscovered(ref: BoneRef): Boolean = cellsOf(ref).any { depthArray[it] == 0 }

    /** Entdeckte Knochen in [bones]-Reihenfolge. */
    val discoveredBones: List<BoneRef> get() = bones.filter { isDiscovered(it) }

    /** Zustand „nichts mehr zu finden“: alle Knochen der Flaeche sind entdeckt (auch bei einer Flaeche ohne Funde). */
    val nothingLeftToFind: Boolean get() = bones.all { isDiscovered(it) }

    /** Nahansicht von [ref] oder `null`, wenn das Freilegen noch nicht begonnen hat. */
    fun preparation(ref: BoneRef): BonePreparation? = preparations.firstOrNull { it.findIndex == ref.findIndex && it.boneId == ref.boneId }

    /** `true`, wenn [ref] fertig freigelegt ist (Kapitel 5.1). */
    fun isPrepared(ref: BoneRef): Boolean = preparation(ref)?.finished == true

    /** Bergen von [ref] oder `null`, wenn es noch nicht begonnen hat (Kapitel 5.2). */
    fun recovery(ref: BoneRef): BoneRecovery? = recoveries.firstOrNull { it.findIndex == ref.findIndex && it.boneId == ref.boneId }

    /** `true`, wenn [ref] geborgen ist (liegt im Inventar). */
    fun isRecovered(ref: BoneRef): Boolean = recovery(ref)?.recovered == true

    /** Geborgene Knochen in [bones]-Reihenfolge. */
    val recoveredBones: List<BoneRef> get() = bones.filter { isRecovered(it) }

    /** Noch nicht geborgene Knochen in [bones]-Reihenfolge (entdeckt oder nicht). */
    val unrecoveredBones: List<BoneRef> get() = bones.filterNot { isRecovered(it) }

    /** Entdeckte, aber noch nicht geborgene Knochen in [bones]-Reihenfolge. */
    val discoveredUnrecoveredBones: List<BoneRef> get() = bones.filter { isDiscovered(it) && !isRecovered(it) }

    /** Alle Knochen der Flaeche sind geborgen (auch bei einer Flaeche ohne Funde). */
    val allRecovered: Boolean get() = bones.all { isRecovered(it) }

    /**
     * Knochen in Zelle [cell], der noch in der Flaeche liegt (nicht geborgen), oder `null`. Geborgene
     * Knochen zaehlen beim Graben nicht mehr.
     */
    fun activeBoneAt(cell: Int): BoneRef? = boneAt(cell)?.takeUnless { isRecovered(it) }

    /** Neue Flaeche mit gleichem Raster, neuen Tiefen und Funden (fuer Aktionen in `engine`). */
    fun with(depths: IntArray, finds: List<PlacedFind>): ExcavationSite =
        ExcavationSite(layerId, width, height, depths, finds, preparations, recoveries)

    /** Neue Flaeche mit neuen Tiefen, Funden und Nahansichten (fuer Aktionen in `engine`). */
    fun with(depths: IntArray, finds: List<PlacedFind>, preparations: List<BonePreparation>): ExcavationSite =
        ExcavationSite(layerId, width, height, depths, finds, preparations, recoveries)

    /** Neue Flaeche mit neuen Funden und neuem Bergen, sonst unveraendert (fuer Aktionen in `engine`). */
    fun withRecoveries(finds: List<PlacedFind>, recoveries: List<BoneRecovery>): ExcavationSite =
        ExcavationSite(layerId, width, height, depthArray, finds, preparations, recoveries)

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is ExcavationSite &&
                layerId == other.layerId &&
                width == other.width &&
                height == other.height &&
                depthArray.contentEquals(other.depthArray) &&
                finds == other.finds &&
                preparations == other.preparations &&
                recoveries == other.recoveries
            )

    override fun hashCode(): Int {
        var result = layerId.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + depthArray.contentHashCode()
        result = 31 * result + finds.hashCode()
        result = 31 * result + preparations.hashCode()
        result = 31 * result + recoveries.hashCode()
        return result
    }

    override fun toString(): String = "ExcavationSite(layerId=$layerId, ${width}x$height, finds=$finds)"

    companion object {
        /** Groesste speicherbare Erdtiefe (16 Bit im Speicherformat), keine Balancing-Grenze. */
        const val MAX_DEPTH: Int = 0xFFFF

        private const val NO_BONE: Int = -1

        /** Tiefen als Base64 von 16-Bit-Werten (Big Endian). */
        internal fun encodeDepths(depths: IntArray): String {
            val bytes = ByteArray(depths.size * 2)
            depths.forEachIndexed { i, d ->
                bytes[2 * i] = (d ushr 8).toByte()
                bytes[2 * i + 1] = d.toByte()
            }
            return Base64.getEncoder().encodeToString(bytes)
        }

        /** Gegenstueck zu [encodeDepths]. */
        internal fun decodeDepths(text: String): IntArray {
            val bytes = try {
                Base64.getDecoder().decode(text)
            } catch (e: IllegalArgumentException) {
                throw SerializationException("Tiefen sind kein gueltiges Base64: ${e.message}")
            }
            if (bytes.size % 2 != 0) throw SerializationException("Tiefen haben eine ungerade Byteanzahl")
            return IntArray(bytes.size / 2) { i ->
                ((bytes[2 * i].toInt() and 0xFF) shl 8) or (bytes[2 * i + 1].toInt() and 0xFF)
            }
        }
    }
}

/** Speicherform von [ExcavationSite] mit kompakt kodierten Tiefen. */
@Serializable
@SerialName("ExcavationSite")
private class ExcavationSiteSurrogate(
    val layerId: String,
    val width: Int,
    val height: Int,
    /** Base64 von 16-Bit-Werten, Zellindex `y * width + x`. */
    val depths: String,
    val finds: List<PlacedFind>,
    /** Begonnene Nahansichten (seit Version 5, AP2-08). */
    val preparations: List<BonePreparation> = emptyList(),
    /** Begonnenes Bergen (seit Version 6, AP2-09). */
    val recoveries: List<BoneRecovery> = emptyList(),
)

/** Serialisiert [ExcavationSite] ueber [ExcavationSiteSurrogate]. */
internal object ExcavationSiteSerializer : KSerializer<ExcavationSite> {
    override val descriptor = ExcavationSiteSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ExcavationSite) {
        val surrogate = ExcavationSiteSurrogate(
            layerId = value.layerId,
            width = value.width,
            height = value.height,
            depths = ExcavationSite.encodeDepths(value.depthsCopy()),
            finds = value.finds,
            preparations = value.preparations,
            recoveries = value.recoveries,
        )
        encoder.encodeSerializableValue(ExcavationSiteSurrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): ExcavationSite {
        val s = decoder.decodeSerializableValue(ExcavationSiteSurrogate.serializer())
        return ExcavationSite(s.layerId, s.width, s.height, ExcavationSite.decodeDepths(s.depths), s.finds, s.preparations, s.recoveries)
    }
}

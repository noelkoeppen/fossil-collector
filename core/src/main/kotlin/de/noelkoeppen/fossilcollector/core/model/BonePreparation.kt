package de.noelkoeppen.fossilcollector.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64

/**
 * Nahansicht eines Knochens beim feinen Freilegen (Kapitel 5.1, AP2-08): ein feines Raster aus
 * [width] x [height] feinen Zellen ueber dem Rechteck des Knochens in der Flaeche plus Rand. Eine
 * Zelle der Flaeche entspricht [subdivision] x [subdivision] feinen Zellen; die linke obere Ecke des
 * Rasters liegt bei Zelle ([originX], [originY]) der Flaeche (darf ausserhalb der Flaeche liegen).
 *
 * Jede feine Zelle traegt eine Restschicht ([depthAt], Einheiten 0..[MAX_DEPTH]), entweder lose oder
 * hart ([isHard]); Dicke 0 heisst frei. Die Knochenzellen ([isBone]) sind der feine Umriss des
 * Knochens. Sie werden gespeichert und nicht aus den Daten neu berechnet, damit ein laufendes
 * Freilegen gueltig bleibt, wenn sich Umrisse oder Parameter aendern.
 *
 * [finished] wird gesetzt, sobald der Anteil freiliegender Knochenzellen die Schwelle
 * `preparation.finishedShare` erreicht (`Preparer`), und bleibt danach gesetzt. Die Qualitaet des
 * Knochens steht im [Piece] des Fundes; AP2-09 (Bergen) schliesst an [finished] an.
 *
 * Unveraenderlich; Aktionen erzeugen einen neuen Zustand. Gespeichert wird kompakt: die Restschicht
 * als Base64 von einem Byte je Zelle (Bit 7 = hart, Bits 0..6 = Dicke), die Knochenzellen als Bitfeld.
 *
 * @property findIndex Fund in `ExcavationSite.finds`
 * @property boneId Knochen des Fundes
 */
@Serializable(with = BonePreparationSerializer::class)
class BonePreparation(
    val findIndex: Int,
    val boneId: String,
    val originX: Int,
    val originY: Int,
    val subdivision: Int,
    val width: Int,
    val height: Int,
    depths: IntArray,
    hard: BooleanArray,
    bone: BooleanArray,
    val finished: Boolean = false,
) {
    private val depthArray: IntArray = depths.copyOf()
    private val hardArray: BooleanArray = BooleanArray(depths.size) { i -> hard[i] && depths[i] > 0 }
    private val boneArray: BooleanArray = bone.copyOf()

    init {
        require(findIndex >= 0) { "findIndex muss >= 0 sein" }
        require(subdivision >= 1) { "subdivision muss >= 1 sein" }
        require(width >= 1 && height >= 1) { "Nahansicht muss mindestens 1x1 sein: ${width}x$height" }
        require(depths.size == width * height && hard.size == depths.size && bone.size == depths.size) {
            "Restschicht/Knochen passen nicht zu ${width}x$height"
        }
        require(depthArray.all { it in 0..MAX_DEPTH }) { "Restschicht muss in 0..$MAX_DEPTH liegen" }
        require(boneArray.any { it }) { "Nahansicht ohne Knochenzelle" }
    }

    /** Knochen dieser Nahansicht. */
    val ref: BoneRef get() = BoneRef(findIndex, boneId)

    /** Anzahl feiner Zellen. */
    val cellCount: Int get() = width * height

    /** Zellindex von ([x], [y]). */
    fun cellIndex(x: Int, y: Int): Int {
        require(contains(x, y)) { "Zelle ($x, $y) liegt ausserhalb von ${width}x$height" }
        return y * width + x
    }

    /** `true`, wenn ([x], [y]) im feinen Raster liegt. */
    fun contains(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    /** Spalte einer Zelle. */
    fun xOf(cell: Int): Int = cell % width

    /** Zeile einer Zelle. */
    fun yOf(cell: Int): Int = cell / width

    /** Dicke der Restschicht in Zelle [cell] (0 = frei). */
    fun depthAt(cell: Int): Int = depthArray[cell]

    /** `true`, wenn in Zelle [cell] eine harte Stelle liegt (nur bei Dicke > 0). */
    fun isHard(cell: Int): Boolean = hardArray[cell]

    /** `true`, wenn Zelle [cell] zum Knochen gehoert. */
    fun isBone(cell: Int): Boolean = boneArray[cell]

    /** Kopie der Dicken (Zellindex `y * width + x`). */
    fun depthsCopy(): IntArray = depthArray.copyOf()

    /** Kopie der harten Stellen. */
    fun hardCopy(): BooleanArray = hardArray.copyOf()

    /** Kopie der Knochenzellen. */
    fun boneCopy(): BooleanArray = boneArray.copyOf()

    /** Anzahl Knochenzellen. */
    val boneCellCount: Int = boneArray.count { it }

    /** Anzahl Knochenzellen ohne Restschicht. */
    val exposedBoneCellCount: Int = boneArray.indices.count { boneArray[it] && depthArray[it] == 0 }

    /** Fortschritt: Anteil der Knochenzellen ohne Restschicht (0..1). */
    val progress: Double get() = exposedBoneCellCount.toDouble() / boneCellCount

    /** Neuer Zustand mit gleichem Raster und gleichen Knochenzellen (fuer Aktionen in `engine`). */
    fun with(depths: IntArray, hard: BooleanArray, finished: Boolean): BonePreparation =
        BonePreparation(findIndex, boneId, originX, originY, subdivision, width, height, depths, hard, boneArray, finished)

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is BonePreparation &&
                findIndex == other.findIndex && boneId == other.boneId &&
                originX == other.originX && originY == other.originY &&
                subdivision == other.subdivision && width == other.width && height == other.height &&
                finished == other.finished &&
                depthArray.contentEquals(other.depthArray) &&
                hardArray.contentEquals(other.hardArray) &&
                boneArray.contentEquals(other.boneArray)
            )

    override fun hashCode(): Int {
        var result = findIndex
        result = 31 * result + boneId.hashCode()
        result = 31 * result + originX
        result = 31 * result + originY
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + depthArray.contentHashCode()
        result = 31 * result + hardArray.contentHashCode()
        result = 31 * result + finished.hashCode()
        return result
    }

    override fun toString(): String =
        "BonePreparation($findIndex/$boneId, ${width}x$height, progress=$progress, finished=$finished)"

    companion object {
        /** Groesste speicherbare Dicke der Restschicht (7 Bit im Speicherformat), keine Balancing-Grenze. */
        const val MAX_DEPTH: Int = 0x7F

        private const val HARD_BIT: Int = 0x80
        private const val BITS_PER_BYTE: Int = 8

        /** Restschicht als Base64, ein Byte je Zelle: Bit 7 hart, Bits 0..6 Dicke. */
        internal fun encodeResidue(depths: IntArray, hard: BooleanArray): String =
            Base64.getEncoder().encodeToString(
                ByteArray(depths.size) { i -> (depths[i] or if (hard[i]) HARD_BIT else 0).toByte() },
            )

        /** Gegenstueck zu [encodeResidue]. */
        internal fun decodeResidue(text: String): Pair<IntArray, BooleanArray> {
            val bytes = decodeBase64(text, "Restschicht")
            return IntArray(bytes.size) { bytes[it].toInt() and MAX_DEPTH } to
                BooleanArray(bytes.size) { bytes[it].toInt() and HARD_BIT != 0 }
        }

        /** Bitfeld als Base64, Bit `i % 8` von Byte `i / 8` (niederwertigstes Bit zuerst). */
        internal fun encodeBits(bits: BooleanArray): String {
            val bytes = ByteArray((bits.size + BITS_PER_BYTE - 1) / BITS_PER_BYTE)
            bits.forEachIndexed { i, set ->
                if (set) bytes[i / BITS_PER_BYTE] = (bytes[i / BITS_PER_BYTE].toInt() or (1 shl (i % BITS_PER_BYTE))).toByte()
            }
            return Base64.getEncoder().encodeToString(bytes)
        }

        /** Gegenstueck zu [encodeBits] fuer [size] Bits. */
        internal fun decodeBits(text: String, size: Int): BooleanArray {
            val bytes = decodeBase64(text, "Knochenzellen")
            if (bytes.size != (size + BITS_PER_BYTE - 1) / BITS_PER_BYTE) {
                throw SerializationException("Knochenzellen: ${bytes.size} Bytes passen nicht zu $size Zellen")
            }
            return BooleanArray(size) { i -> (bytes[i / BITS_PER_BYTE].toInt() shr (i % BITS_PER_BYTE)) and 1 == 1 }
        }

        private fun decodeBase64(text: String, what: String): ByteArray =
            try {
                Base64.getDecoder().decode(text)
            } catch (e: IllegalArgumentException) {
                throw SerializationException("$what sind kein gueltiges Base64: ${e.message}")
            }
    }
}

/** Speicherform von [BonePreparation] mit kompakt kodierter Restschicht und Knochenzellen. */
@Serializable
@SerialName("BonePreparation")
private class BonePreparationSurrogate(
    val findIndex: Int,
    val boneId: String,
    val originX: Int,
    val originY: Int,
    val subdivision: Int,
    val width: Int,
    val height: Int,
    /** Base64, ein Byte je feiner Zelle (Bit 7 hart, Bits 0..6 Dicke), Zellindex `y * width + x`. */
    val residue: String,
    /** Base64-Bitfeld der Knochenzellen. */
    val bone: String,
    val finished: Boolean,
)

/** Serialisiert [BonePreparation] ueber [BonePreparationSurrogate]. */
internal object BonePreparationSerializer : KSerializer<BonePreparation> {
    override val descriptor = BonePreparationSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: BonePreparation) {
        val surrogate = BonePreparationSurrogate(
            findIndex = value.findIndex,
            boneId = value.boneId,
            originX = value.originX,
            originY = value.originY,
            subdivision = value.subdivision,
            width = value.width,
            height = value.height,
            residue = BonePreparation.encodeResidue(value.depthsCopy(), value.hardCopy()),
            bone = BonePreparation.encodeBits(value.boneCopy()),
            finished = value.finished,
        )
        encoder.encodeSerializableValue(BonePreparationSurrogate.serializer(), surrogate)
    }

    override fun deserialize(decoder: Decoder): BonePreparation {
        val s = decoder.decodeSerializableValue(BonePreparationSurrogate.serializer())
        if (s.width < 1 || s.height < 1) throw SerializationException("Nahansicht ${s.width}x${s.height} ist ungueltig")
        val size = s.width * s.height
        val (depths, hard) = BonePreparation.decodeResidue(s.residue)
        if (depths.size != size) throw SerializationException("Restschicht: ${depths.size} Werte fuer $size Zellen")
        val bone = BonePreparation.decodeBits(s.bone, size)
        return BonePreparation(s.findIndex, s.boneId, s.originX, s.originY, s.subdivision, s.width, s.height, depths, hard, bone, s.finished)
    }
}

package de.noelkoeppen.fossilcollector.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Schluessel eines Fundes: Art, Knochen sowie Alter und Geschlecht (Kapitel 4.4 und 4.8).
 *
 * Text-Form: `speciesId:boneId:age:sex`, ein fehlender Wert (`null`) wird als `_` geschrieben,
 * z. B. `parasaurolophus:dorsal:_:_`. Im MVP sind [age] und [sex] immer `null`; die Felder
 * bleiben trotzdem erhalten (Kapitel 0, Regel 8).
 *
 * Im JSON wird der Schluessel als String gespeichert, auch als Schluessel von Maps
 * (Kapitel 11, "Spielstand (Auszug)").
 */
@Serializable(with = PieceKeySerializer::class)
data class PieceKey(
    val speciesId: String,
    val boneId: String,
    val age: String? = null,
    val sex: String? = null,
) {
    init {
        requireValidPart(speciesId, "speciesId")
        requireValidPart(boneId, "boneId")
        age?.let { requireValidPart(it, "age") }
        sex?.let { requireValidPart(it, "sex") }
    }

    /** Text-Form `speciesId:boneId:age:sex` mit `_` fuer `null`. */
    override fun toString(): String =
        listOf(speciesId, boneId, age ?: NULL_TOKEN, sex ?: NULL_TOKEN).joinToString(SEPARATOR.toString())

    companion object {
        /** Trennzeichen der Text-Form. */
        const val SEPARATOR: Char = ':'

        /** Platzhalter fuer `null` in der Text-Form. */
        const val NULL_TOKEN: String = "_"

        private const val PART_COUNT = 4

        /**
         * Liest die Text-Form. Wirft [IllegalArgumentException], wenn der Text nicht genau
         * vier Teile hat, ein Teil leer ist oder Art bzw. Knochen `_` sind.
         */
        fun parse(text: String): PieceKey {
            val parts = text.split(SEPARATOR)
            require(parts.size == PART_COUNT) { "PieceKey braucht $PART_COUNT Teile: '$text'" }
            return fromParts(parts)
        }

        /** Wie [parse], liefert aber `null` statt einer Exception. */
        fun parseOrNull(text: String): PieceKey? = runCatching { parse(text) }.getOrNull()

        /** Baut einen Schluessel aus genau vier Text-Teilen (gemeinsam genutzt mit [SpareZoneKey]). */
        internal fun fromParts(parts: List<String>): PieceKey {
            require(parts.size == PART_COUNT) { "PieceKey braucht $PART_COUNT Teile: $parts" }
            val (species, bone, age, sex) = parts
            require(species != NULL_TOKEN) { "speciesId darf nicht '$NULL_TOKEN' sein" }
            require(bone != NULL_TOKEN) { "boneId darf nicht '$NULL_TOKEN' sein" }
            return PieceKey(
                speciesId = species,
                boneId = bone,
                age = age.takeUnless { it == NULL_TOKEN },
                sex = sex.takeUnless { it == NULL_TOKEN },
            )
        }

        /** Ein Teil darf nicht leer sein, nicht `_` sein und kein Trennzeichen enthalten. */
        internal fun requireValidPart(value: String, name: String) {
            require(value.isNotEmpty()) { "$name darf nicht leer sein" }
            require(value != NULL_TOKEN) { "$name darf nicht '$NULL_TOKEN' sein (reserviert fuer null)" }
            require(SEPARATOR !in value) { "$name darf kein '$SEPARATOR' enthalten: '$value'" }
        }
    }
}

/**
 * Schluessel eines Ersatzteils: Fund-Schluessel plus Zone (Kapitel 4.5,
 * `spareZone[speciesId:boneId:age:sex:zoneId]`). Ersatzteile sind an Alter und Geschlecht gebunden.
 */
@Serializable(with = SpareZoneKeySerializer::class)
data class SpareZoneKey(
    val piece: PieceKey,
    val zoneId: String,
) {
    init {
        PieceKey.requireValidPart(zoneId, "zoneId")
    }

    /** Text-Form `speciesId:boneId:age:sex:zoneId`. */
    override fun toString(): String = "$piece${PieceKey.SEPARATOR}$zoneId"

    companion object {
        /** Liest die Text-Form. Wirft [IllegalArgumentException] bei ungueltigem Text. */
        fun parse(text: String): SpareZoneKey {
            val parts = text.split(PieceKey.SEPARATOR)
            require(parts.size == PART_COUNT) { "SpareZoneKey braucht $PART_COUNT Teile: '$text'" }
            return SpareZoneKey(PieceKey.fromParts(parts.subList(0, PART_COUNT - 1)), parts.last())
        }

        /** Wie [parse], liefert aber `null` statt einer Exception. */
        fun parseOrNull(text: String): SpareZoneKey? = runCatching { parse(text) }.getOrNull()

        private const val PART_COUNT = 5
    }
}

/** Serialisiert [PieceKey] als String in Text-Form (auch als Map-Schluessel nutzbar). */
object PieceKeySerializer : KSerializer<PieceKey> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("de.noelkoeppen.fossilcollector.core.model.PieceKey", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: PieceKey) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): PieceKey {
        val text = decoder.decodeString()
        return try {
            PieceKey.parse(text)
        } catch (e: IllegalArgumentException) {
            throw SerializationException("Ungueltiger PieceKey '$text': ${e.message}", e)
        }
    }
}

/** Serialisiert [SpareZoneKey] als String in Text-Form (auch als Map-Schluessel nutzbar). */
object SpareZoneKeySerializer : KSerializer<SpareZoneKey> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("de.noelkoeppen.fossilcollector.core.model.SpareZoneKey", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: SpareZoneKey) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): SpareZoneKey {
        val text = decoder.decodeString()
        return try {
            SpareZoneKey.parse(text)
        } catch (e: IllegalArgumentException) {
            throw SerializationException("Ungueltiger SpareZoneKey '$text': ${e.message}", e)
        }
    }
}

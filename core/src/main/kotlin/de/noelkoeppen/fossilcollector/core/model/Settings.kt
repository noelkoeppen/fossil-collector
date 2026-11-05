package de.noelkoeppen.fossilcollector.core.model

import de.noelkoeppen.fossilcollector.core.number.NumberNotation
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Einstellungen (Kapitel 8.3). Bleiben beim Prestige erhalten (Kapitel 5.3).
 * Die Standardwerte sind Bedienvorgaben aus dem Design, keine Balancing-Werte.
 */
@Serializable
data class Settings(
    val language: LanguageSetting = LanguageSetting.SYSTEM,
    @Serializable(with = NumberNotationSettingSerializer::class)
    val numberFormat: NumberNotation = NumberNotation.SHORT,
)

/** Spracheinstellung (Kapitel 12): Systemsprache oder fest Deutsch bzw. Englisch. */
@Serializable
enum class LanguageSetting {
    @SerialName("system")
    SYSTEM,

    @SerialName("de")
    DE,

    @SerialName("en")
    EN,
}

/**
 * Speichert [NumberNotation] im Spielstand als `"short"` bzw. `"scientific"` (Kapitel 11),
 * ohne die Enum aus `core.number` an das Speicherformat zu koppeln.
 */
object NumberNotationSettingSerializer : KSerializer<NumberNotation> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("de.noelkoeppen.fossilcollector.core.model.NumberFormatSetting", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: NumberNotation) {
        encoder.encodeString(
            when (value) {
                NumberNotation.SHORT -> SHORT
                NumberNotation.SCIENTIFIC -> SCIENTIFIC
            },
        )
    }

    override fun deserialize(decoder: Decoder): NumberNotation =
        when (val text = decoder.decodeString()) {
            SHORT -> NumberNotation.SHORT
            SCIENTIFIC -> NumberNotation.SCIENTIFIC
            else -> throw SerializationException("Unbekanntes Zahlenformat '$text'")
        }

    private const val SHORT = "short"
    private const val SCIENTIFIC = "scientific"
}

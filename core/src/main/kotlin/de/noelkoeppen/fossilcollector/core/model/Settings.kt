package de.noelkoeppen.fossilcollector.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Einstellungen. Die Standardwerte sind Bedienvorgaben, keine Balancing-Werte.
 *
 * Das fruehere Zahlenformat (kurz/wissenschaftlich) ist mit den grossen Zahlen entfallen (AP2-02);
 * ein Feld `numberFormat` in aelteren Spielstaenden wird beim Laden ignoriert.
 */
@Serializable
data class Settings(
    val language: LanguageSetting = LanguageSetting.SYSTEM,
)

/** Spracheinstellung: Systemsprache oder fest Deutsch bzw. Englisch. */
@Serializable
enum class LanguageSetting {
    @SerialName("system")
    SYSTEM,

    @SerialName("de")
    DE,

    @SerialName("en")
    EN,
}

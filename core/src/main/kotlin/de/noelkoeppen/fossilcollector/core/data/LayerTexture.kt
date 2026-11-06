package de.noelkoeppen.fossilcollector.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Gesteinstextur eines Schichtbands im Grabungs-Screen (Kapitel 8.5, AP-26). Reine Gestaltung ohne
 * Spielwirkung; die App erzeugt daraus deterministisch ein Muster (Seed aus der Schicht-ID).
 *
 * @property serialName Name im JSON (`layers.json: texture`)
 */
@Serializable
enum class LayerTexture(val serialName: String) {
    /** Ohne Muster, nur die Bandfarbe. */
    @SerialName("plain") PLAIN("plain"),

    /** Lockerer Kies und Sand: Koernung und einzelne Kiesel. */
    @SerialName("gravel") GRAVEL("gravel"),

    /** Feine, leicht wellige Baenderung (Schichtungslinien) mit feiner Koernung. */
    @SerialName("banded") BANDED("banded"),

    /** Kreide: helle Sprenkel, Feuersteinknollen und angedeutete Muscheln bzw. Ammoniten. */
    @SerialName("chalk") CHALK("chalk"),
}

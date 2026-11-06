package de.noelkoeppen.fossilcollector.core.model

import kotlinx.serialization.Serializable

/**
 * Ein konkretes Stueck (bester Run-Fund eines Knochens, Kapitel 4.4).
 *
 * [zones] enthaelt die Qualitaet (0.0 bis 1.0) je Zone in der Reihenfolge der Zonen aus
 * `bones.json`. Die Anzahl ist pro Knochen konfigurierbar (Kapitel 4.3) und wird hier nicht
 * festgelegt. Der zugehoerige [PieceKey] ist der Schluessel der Map, in der das Stueck liegt.
 *
 * Qualitaeten sind auf 0..1 begrenzte Anteile und deshalb `Double`, keine `GameNumber`
 * (Regel 6 verbietet `Int`/`Long`, die Ueberlaufgefahr besteht hier nicht).
 */
@Serializable
data class Piece(
    val zones: List<Double>,
) {
    /** Qualitaet des Stuecks = Mittelwert der Zonen (Kapitel 4.2, Schritt 5); ohne Zonen 0. */
    val quality: Double get() = if (zones.isEmpty()) 0.0 else zones.average()
}

/**
 * Eintrag im Sammelbuch (permanent, Kapitel 4.4 und 4.7).
 *
 * @property quality beste Qualitaet dieses Knochens (0.0 bis 1.0)
 * @property reconstructed `true`, wenn der Eintrag aus einer Rekonstruktion stammt (Kapitel 4.5)
 * @property zones Zonen-Qualitaeten des Buch-Fundes in der Reihenfolge aus `bones.json` (fuer die
 *   Zonen-Balken im Art-Detail, Kapitel 8.3). Standardwert leer, damit aeltere Spielstaende ohne
 *   Migration laden; leer heisst "unbekannt", massgeblich ist immer [quality] (AP-09).
 */
@Serializable
data class BookEntry(
    val quality: Double,
    val reconstructed: Boolean = false,
    val zones: List<Double> = emptyList(),
)

/**
 * Duplikat, das in der Laborwarteschlange auf einen freien Forschungsplatz wartet (Kapitel 4.4).
 * Die Zonen werden mitgespeichert, weil das Labor daraus Ersatzteile gewinnt (Kapitel 4.5).
 */
@Serializable
data class QueuedPiece(
    val piece: PieceKey,
    val zones: List<Double>,
)

/**
 * Laufende Forschung auf einem Laborplatz (Kapitel 4.5).
 *
 * @property piece Schluessel des erforschten Stuecks
 * @property zones Zonen-Qualitaeten des Stuecks (Quelle fuer Ersatzteile)
 * @property remaining verbleibende Dauer in Sekunden
 * @property duration Gesamtdauer der Forschung in Sekunden, festgelegt beim Start (AP-25, fuer den
 *   Fortschrittsbalken). Standardwert `null` = unbekannt, damit aeltere Spielstaende ohne Migration
 *   laden; dann rechnet `LabSystem.progress` mit der aktuellen Dauer.
 */
@Serializable
data class LabJob(
    val piece: PieceKey,
    val zones: List<Double>,
    val remaining: Double,
    val duration: Double? = null,
)

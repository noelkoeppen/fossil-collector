package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Ereignis aus [GameEngine.advance] (Kapitel 10.3). Die UI nutzt Ereignisse fuer Popups,
 * Animationen und Badges; der Spielzustand selbst steckt immer vollstaendig im [AdvanceResult].
 *
 * **Erweiterung:** Jedes System, das neue Ereignisse braucht (Fund in AP-08, Forschung fertig in
 * AP-10, Meilenstein in AP-11 ...), ergaenzt hier seine eigene Implementierung. Ereignisse werden
 * erst angelegt, wenn ein System sie erzeugt.
 */
sealed interface GameEvent {

    /**
     * Grabungsteams haben in diesem `advance`-Aufruf Bloecke in einer Schicht erzeugt.
     * Hoechstens ein Ereignis je Schicht und Aufruf, nur wenn [amount] > 0.
     *
     * @property layerId Schicht-ID
     * @property amount erzeugte Bloecke in diesem Aufruf
     */
    data class BlocksDug(val layerId: String, val amount: GameNumber) : GameEvent

    /**
     * Die Praeparation hat ganze Bloecke einer Schicht freigelegt (Kapitel 4.2). Hoechstens ein
     * Ereignis je Schicht und Aufruf (bzw. Tipp), nur wenn [blocks] > 0. Wird nach den
     * [FossilFound]-Ereignissen dieser Schicht gemeldet.
     *
     * @property layerId Schicht-ID
     * @property blocks freigelegte Bloecke (Gestein und Fossilien zusammen)
     * @property fossils davon Fossilien
     * @property rockMoney Geld aus dem Gestein, bereits gutgeschrieben
     */
    data class BlocksPrepared(
        val layerId: String,
        val blocks: GameNumber,
        val fossils: GameNumber,
        val rockMoney: GameNumber,
    ) : GameEvent

    /**
     * Ein Fossil wurde freigelegt (Kapitel 4.2). Ein Ereignis je Fund, in der Reihenfolge der
     * Wuerfe. Das Einordnen (Buch, Museum, Labor, Verkauf) erfolgt im [FindHandler] (AP-09), der
     * eigene Ereignisse melden kann.
     *
     * @property find der Fund mit Zonen-Qualitaeten
     * @property tierId ID der Qualitaetsstufe des Stuecks (`balance.qualityTiers`)
     */
    data class FossilFound(val find: Find, val tierId: String) : GameEvent

    /**
     * Der Fund hat einen neuen oder besseren Eintrag im Sammelbuch erzeugt (Kapitel 4.4, Schritt 1).
     * Grundlage fuer das Badge "neuer Bucheintrag" (Kapitel 8.1).
     *
     * @property pieceKey Schluessel des Eintrags
     * @property quality neue Qualitaet im Buch
     * @property previousQuality bisherige Qualitaet oder `null`, wenn es noch keinen Eintrag gab
     */
    data class BookUpdated(val pieceKey: PieceKey, val quality: Double, val previousQuality: Double?) : GameEvent

    /**
     * Erster Fund dieses Schluessels im aktuellen Durchlauf (Kapitel 4.4, Schritt 2); das Stueck
     * wird in den Run-Bestand aufgenommen.
     *
     * @property pieceKey Schluessel des Stuecks
     * @property quality Qualitaet des Stuecks
     */
    data class NewPiece(val pieceKey: PieceKey, val quality: Double) : GameEvent

    /**
     * Der Fund ersetzt einen schlechteren Run-Fund desselben Schluessels (Kapitel 4.4, Schritt 2).
     * Das ersetzte Stueck wird danach als Duplikat behandelt ([DuplicateQueued] bzw. [DuplicateSold]).
     *
     * @property pieceKey Schluessel des Stuecks
     * @property quality Qualitaet des neuen Stuecks
     * @property previousQuality Qualitaet des ersetzten Stuecks
     */
    data class PieceImproved(val pieceKey: PieceKey, val quality: Double, val previousQuality: Double) : GameEvent

    /**
     * Ein Duplikat wurde in die Laborwarteschlange gestellt (Kapitel 4.4, Schritt 3).
     *
     * @property pieceKey Schluessel des Duplikats
     * @property quality Qualitaet des Duplikats
     */
    data class DuplicateQueued(val pieceKey: PieceKey, val quality: Double) : GameEvent

    /**
     * Ein Duplikat wurde sofort verkauft, weil die Laborwarteschlange voll ist (Kapitel 4.4,
     * Schritt 3). Das Geld ist bereits gutgeschrieben.
     *
     * @property pieceKey Schluessel des Duplikats
     * @property quality Qualitaet des Duplikats
     * @property money Erloes `layer.value * (0.5 + q) * duplicateSellFactor * sell_price`
     */
    data class DuplicateSold(val pieceKey: PieceKey, val quality: Double, val money: GameNumber) : GameEvent

    /**
     * Offline-Aggregation (Kapitel 7.1, AP-14): [count] Fossilien aus [layerId] wurden nicht einzeln
     * gewuerfelt, sondern als Duplikate zum Erwartungswert verkauft. Hoechstens ein Ereignis je
     * Schicht und `advance`-Aufruf; entsteht nur im Offline-Fortschritt.
     *
     * @property layerId Schicht-ID
     * @property count Anzahl der aggregierten Funde
     * @property expectedQuality angenommene Qualitaet je Fund ([PrepSystem.expectedQuality])
     * @property money gutgeschriebener Erloes
     */
    data class FindsAggregated(
        val layerId: String,
        val count: GameNumber,
        val expectedQuality: Double,
        val money: GameNumber,
    ) : GameEvent

    /**
     * Mit diesem Fund sind im aktuellen Durchlauf erstmals alle Knochen der Art vorhanden
     * (Skelett vollstaendig, Kapitel 4.6). Wird nach [NewPiece] gemeldet.
     *
     * @property speciesId Art-ID
     */
    data class SkeletonCompleted(val speciesId: String) : GameEvent

    /**
     * Ein Stueck aus der Laborwarteschlange belegt einen freien Forschungsplatz (Kapitel 4.5).
     *
     * @property pieceKey Schluessel des Stuecks
     * @property duration Dauer der Forschung in Sekunden (`labDuration * lab_speed`)
     */
    data class LabJobStarted(val pieceKey: PieceKey, val duration: Double) : GameEvent

    /**
     * Eine Forschung ist abgeschlossen (Kapitel 4.5, Badge "Forschung fertig"). FP und Ersatzteile
     * sind bereits gutgeschrieben.
     *
     * @property pieceKey Schluessel des erforschten Stuecks
     * @property researchPoints gutgeschriebene FP `(layer.order + 1) * research_points`
     */
    data class LabJobCompleted(val pieceKey: PieceKey, val researchPoints: GameNumber) : GameEvent

    /**
     * Eine Art wurde erstmals im Labor abgeschlossen und gilt als erforscht (Completion-Stufe 1,
     * Kapitel 4.7). Wird nach dem zugehoerigen [LabJobCompleted] gemeldet.
     *
     * @property speciesId Art-ID
     */
    data class SpeciesResearched(val speciesId: String) : GameEvent

    /**
     * Der Spieler hat einen Knochen aus Ersatzteilen rekonstruiert (Kapitel 4.5). Wird vor den
     * Folge-Ereignissen ([BookUpdated], [NewPiece] bzw. [PieceImproved], [SkeletonCompleted],
     * [DuplicateQueued] bzw. [DuplicateSold]) gemeldet.
     *
     * @property pieceKey Schluessel des rekonstruierten Knochens
     * @property quality Qualitaet der Rekonstruktion (hoechstens `balance.reconstructionCap`)
     * @property improvedRun `true`, wenn der Run-Fund ersetzt bzw. neu angelegt wurde
     * @property improvedBook `true`, wenn der Bucheintrag ersetzt bzw. neu angelegt wurde
     */
    data class Reconstructed(
        val pieceKey: PieceKey,
        val quality: Double,
        val improvedRun: Boolean,
        val improvedBook: Boolean,
    ) : GameEvent

    /**
     * Ein Meilenstein wurde erreicht (Kapitel 5.2, Toast). Wird genau einmal je Meilenstein gemeldet;
     * Geld und FP der Belohnung sind bereits gutgeschrieben, dauerhafte Effekte wirken ab dem
     * naechsten Abgleich der Modifier-Quellen.
     *
     * @property milestoneId ID aus `milestones.json` (Texte `milestone.<id>.name|desc`)
     * @property money gutgeschriebenes Geld
     * @property researchPoints gutgeschriebene FP
     */
    data class MilestoneReached(
        val milestoneId: String,
        val money: GameNumber,
        val researchPoints: GameNumber,
    ) : GameEvent

    /**
     * Prestige ausgefuehrt (Kapitel 5.3): neuer Durchlauf, Ruhm ist bereits gutgeschrieben. Wird vor
     * den Meilenstein-Ereignissen (z. B. `first_prestige`) gemeldet.
     *
     * @property fameGained gutgeschriebener Ruhm
     */
    data class Prestiged(val fameGained: GameNumber) : GameEvent
}

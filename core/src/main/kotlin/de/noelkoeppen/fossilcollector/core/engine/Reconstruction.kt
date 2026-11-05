package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.BookEntry
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.Piece
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.SpareZoneKey
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import kotlin.math.floor
import kotlin.math.max

/**
 * Vorschau einer moeglichen Rekonstruktion (Abfrage fuer die UI, Liste "rekonstruierbare Knochen").
 *
 * @property pieceKey Schluessel des Knochens
 * @property zones Zonen-Qualitaeten der Rekonstruktion in der Reihenfolge aus `bones.json`, bereits
 *   auf [quality] skaliert (ihr Mittelwert ist [quality])
 * @property quality erwartete Qualitaet `min(reconstructionCap, mean(Zonen))`
 * @property missingZones Zonen-IDs ohne Ersatzteil, die mit `reconstructionMissingZoneQuality`
 *   ergaenzt werden (nur mit Capstone `lab_5`)
 * @property improvesRun `true`, wenn es keinen Run-Fund gibt oder [quality] echt hoeher ist
 * @property improvesBook `true`, wenn es keinen Bucheintrag gibt oder [quality] echt hoeher ist
 */
data class ReconstructionPreview(
    val pieceKey: PieceKey,
    val zones: List<Double>,
    val quality: Double,
    val missingZones: List<String>,
    val improvesRun: Boolean,
    val improvesBook: Boolean,
) {
    /** `true`, wenn die Rekonstruktion Run-Fund oder Buch verbessern wuerde (Button aktiv). */
    val isImprovement: Boolean get() = improvesRun || improvesBook
}

/** Ergebnis der Spieleraktion Rekonstruieren. Der urspruengliche Zustand wird nie veraendert. */
sealed interface ReconstructionResult {
    /**
     * Rekonstruktion ausgefuehrt.
     *
     * @property state neuer Spielstand
     * @property events Ereignisse (zuerst [GameEvent.Reconstructed], dann die Folge-Ereignisse)
     * @property preview die ausgefuehrte Rekonstruktion
     */
    data class Success(
        val state: GameState,
        val events: List<GameEvent>,
        val preview: ReconstructionPreview,
    ) : ReconstructionResult

    /** Rekonstruktion abgelehnt; der Spielstand bleibt unveraendert. */
    data class Failure(val reason: ReconstructionFailure) : ReconstructionResult
}

/** Gruende fuer eine abgelehnte Rekonstruktion. */
enum class ReconstructionFailure {
    /** Art oder Knochen sind unbekannt bzw. der Knochen gehoert nicht zur Art. */
    UNKNOWN_PIECE,

    /** Es fehlen mehr Ersatzteile, als `reconstruction_missing_zones` erlaubt (oder es gibt keins). */
    MISSING_SPARE_ZONES,

    /** Die Rekonstruktion waere weder fuer den Run-Fund noch fuer das Buch eine Verbesserung. */
    NO_IMPROVEMENT,

    /** Das Ergebnis enthielte `NaN`/`Infinity` (siehe [NumericErrorHandler]). */
    NON_FINITE_RESULT,
}

/**
 * Rekonstruktion aus Ersatzteilen (GAME_DESIGN.md, Kapitel 4.5). Spieleraktion, kein
 * [AdvanceSystem]; Aufruf ueber [GameEngine.reconstruct].
 *
 * - **Moeglich**, wenn es fuer jede Zone des Knochens (`bones.json`) ein Ersatzteil
 *   `run.spareZones[SpareZoneKey(pieceKey, zoneId)]` gibt. Mit `reconstruction_missing_zones`
 *   (additiv, Basis 0, Capstone `lab_5`: 1) duerfen `floor(Wert)` Zonen fehlen; sie werden mit
 *   `balance.reconstructionMissingZoneQuality` ergaenzt. Mindestens ein Ersatzteil ist immer noetig.
 * - **Qualitaet:** `reconQ = min(balance.reconstructionCap, mean(Zonen))`. Liegt der Mittelwert ueber
 *   dem Cap, werden alle Zonen mit `cap / mean` skaliert, damit Run-Stueck (dessen Qualitaet der
 *   Zonen-Mittelwert ist) und Bucheintrag dieselbe Qualitaet haben. Weil `DataValidator` verlangt,
 *   dass der Cap unter der hoechsten Qualitaetsstufe liegt, ist **Museumsreif nie erreichbar**.
 * - **Wirkung:** Run-Fund und Bucheintrag werden jeweils nur ersetzt, wenn `reconQ` **echt hoeher**
 *   ist (oder es keinen gibt). Der Bucheintrag bekommt `reconstructed = true` und die Zonen der
 *   Rekonstruktion. Ein ersetzter Run-Fund zaehlt wie in Kapitel 4.4 als Duplikat (Warteschlange
 *   oder Verkauf). Ist keins von beiden eine Verbesserung, wird die Aktion abgelehnt.
 * - **Ersatzteile werden nicht verbraucht:** Sie sind das "beste gesicherte Zonen-Stueck"
 *   (Glossar) und bleiben bis zum Prestige erhalten.
 * - `run.pieces` wird bei jeder Aenderung als neue Map-Instanz gesetzt (Cache des [MuseumSystem]).
 *
 * Die Modifier-Quellen muessen aktuell sein ([GameEngine.reconstruct] gleicht sie vorher ab).
 */
class Reconstruction(
    private val data: GameData,
    private val modifiers: ModifierEngine,
    private val classifier: FindClassifier = FindClassifier(data, modifiers),
) {

    /** Anzahl Zonen, die ohne Ersatzteil ergaenzt werden duerfen: `floor(reconstruction_missing_zones)`. */
    fun allowedMissingZones(): Int =
        max(0.0, floor(modifiers.additive(StatId.RECONSTRUCTION_MISSING_ZONES))).toInt()

    /**
     * Vorschau fuer [key] oder `null`, wenn die Rekonstruktion nicht moeglich ist (unbekannter
     * Knochen, zu wenige Ersatzteile). Die Vorschau wird auch geliefert, wenn sie keine Verbesserung
     * waere ([ReconstructionPreview.isImprovement]).
     */
    fun preview(state: GameState, key: PieceKey): ReconstructionPreview? = evaluate(state, key).first

    /**
     * Alle Knochen, die mit den aktuellen Ersatzteilen rekonstruiert werden koennen, sortiert nach
     * Schluessel-Text. Kandidaten sind alle Schluessel mit mindestens einem Ersatzteil.
     */
    fun reconstructableBones(state: GameState): List<ReconstructionPreview> =
        state.run.spareZones.keys
            .mapTo(HashSet()) { it.piece }
            .mapNotNull { preview(state, it) }
            .sortedBy { it.pieceKey.toString() }

    /** Rekonstruiert [key] (Spieleraktion). Ohne Wirkung auf [state]. */
    fun reconstruct(state: GameState, key: PieceKey): ReconstructionResult {
        val (preview, failure) = evaluate(state, key)
        if (preview == null) return ReconstructionResult.Failure(failure ?: ReconstructionFailure.UNKNOWN_PIECE)
        if (!preview.isImprovement) return ReconstructionResult.Failure(ReconstructionFailure.NO_IMPROVEMENT)

        val events = ArrayList<GameEvent>()
        events += GameEvent.Reconstructed(key, preview.quality, preview.improvesRun, preview.improvesBook)

        // Zaehler fuer den Meilenstein `reconstructions` (AP-11).
        var meta = state.meta.let { it.copy(stats = it.stats.copy(reconstructions = it.stats.reconstructions + 1.0)) }
        if (preview.improvesBook) {
            val previous = meta.book[key]
            meta = meta.copy(
                book = meta.book + (key to BookEntry(preview.quality, reconstructed = true, zones = preview.zones)),
            )
            events += GameEvent.BookUpdated(key, preview.quality, previous?.quality)
        }

        var run = state.run
        if (preview.improvesRun) {
            val existing = run.pieces[key]
            val newRun = run.copy(pieces = run.pieces + (key to Piece(preview.zones)))
            if (existing == null) {
                events += GameEvent.NewPiece(key, preview.quality)
                val species = data.species(key.speciesId)
                if (!Skeletons.isCompleteInRun(run, species) && Skeletons.isCompleteInRun(newRun, species)) {
                    events += GameEvent.SkeletonCompleted(species.id)
                }
                run = newRun
            } else {
                events += GameEvent.PieceImproved(key, preview.quality, existing.quality)
                run = classifier.handleDuplicate(newRun, key, existing) { events += it }
            }
        }
        return ReconstructionResult.Success(state.copy(meta = meta, run = run), events, preview)
    }

    private fun evaluate(state: GameState, key: PieceKey): Pair<ReconstructionPreview?, ReconstructionFailure?> {
        val species = data.speciesById[key.speciesId]
        val bone = data.bonesById[key.boneId]
        if (species == null || bone == null || key.boneId !in species.bones || bone.zones.isEmpty()) {
            return null to ReconstructionFailure.UNKNOWN_PIECE
        }

        val spare = state.run.spareZones
        val missing = ArrayList<String>()
        val raw = bone.zones.map { zoneId ->
            val stored = spare[SpareZoneKey(key, zoneId)]
            if (stored == null) missing += zoneId
            stored ?: data.balance.reconstructionMissingZoneQuality
        }
        if (missing.size > allowedMissingZones() || missing.size == bone.zones.size) {
            return null to ReconstructionFailure.MISSING_SPARE_ZONES
        }

        val mean = raw.average()
        val cap = data.balance.reconstructionCap
        val zones = if (mean > cap) raw.map { it * (cap / mean) } else raw
        // Qualitaet = Mittelwert der gespeicherten Zonen (wie Piece.quality), damit eine erneute
        // Rekonstruktion mit denselben Ersatzteilen nicht als Verbesserung gilt; bis auf Rundung
        // ist das min(cap, mean).
        val quality = Piece(zones).quality

        val runPiece = state.run.pieces[key]
        val bookEntry = state.meta.book[key]
        val preview = ReconstructionPreview(
            pieceKey = key,
            zones = zones,
            quality = quality,
            missingZones = missing,
            improvesRun = runPiece == null || quality > runPiece.quality,
            improvesBook = bookEntry == null || quality > bookEntry.quality,
        )
        return preview to null
    }
}

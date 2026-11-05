package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.LayerDefinition
import de.noelkoeppen.fossilcollector.core.data.SpeciesDefinition
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.PieceKey
import de.noelkoeppen.fossilcollector.core.model.RunState
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import de.noelkoeppen.fossilcollector.core.random.GameRandom
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Anschlussstelle fuer das Einordnen von Funden (Kapitel 4.4, AP-09).
 *
 * Die Praeparation ruft den Handler fuer **jeden** Fund sofort auf, mit dem aktuellen
 * Zwischenzustand (Bloecke der Schicht sind bereits abgezogen, `meta.stats.piecesFound` ist schon
 * erhoeht). Der Handler gibt den neuen Zustand zurueck (Buch, Run-Bestand, Laborwarteschlange,
 * Verkauf) und kann ueber [AdvanceContext.emit] eigene Ereignisse melden. Weil der naechste Wurf
 * den vom Handler aktualisierten `run.pieces` sieht, wirkt der Pech-Schutz (Kapitel 4.9) auch
 * innerhalb eines grossen `dt` korrekt.
 *
 * Der Handler darf den Zufall aus [AdvanceContext.rng] nutzen, muss aber rein bezogen auf den
 * Spielstand sein (keine Seiteneffekte ausserhalb des Rueckgabewerts).
 */
fun interface FindHandler {
    fun onFind(state: GameState, find: Find, context: AdvanceContext): GameState

    companion object {
        /** Stand AP-08: Funde werden nur gemeldet, nicht eingeordnet. */
        val NONE: FindHandler = FindHandler { state, _, _ -> state }
    }
}

/**
 * Praeparation und Qualitaet (GAME_DESIGN.md, Kapitel 4.2, 4.3, 4.9).
 *
 * **Verarbeitung:** Praeparatoren legen `prep_rate` Bloecke pro Sekunde frei (Stat `prep_rate`,
 * additiv mit Basis 0; die Basis liefert die Quelle `upgrade:preparator` mit 0.2 je Praeparator,
 * `mult`-Modifier wie Skill `prep_2` wirken darauf). Tippen ([tapPrep]) legt
 * `balance.tapPrepAmount` Bloecke frei; `prep_rate` wirkt nicht auf das Tippen (analog zur Grabung).
 *
 * **Bruchteile:** Es werden nur **ganze** Bloecke gewuerfelt. Die Verarbeitungsmenge eines Aufrufs
 * wird auf den globalen Fortschritt `run.prepProgress` addiert; daraus werden so viele ganze
 * Bloecke freigelegt, wie es das Budget und das Lager erlauben. Eine Schicht stellt nur ganze
 * Bloecke bereit (`floor(blocks)`), Bruchteile im Lager warten auf weitere Grabung. Danach bleibt
 * nur der angefangene Bruchteil (`[0, 1)`) im Fortschritt; Kapazitaet ohne verfuegbare Bloecke
 * verfaellt (kein Aufsparen fuer spaeter).
 *
 * **Reihenfolge:** immer zuerst die tiefste Schicht (hoechste `order`) mit mindestens einem ganzen
 * Block, dann die naechsthoehere.
 *
 * **Pro Block** (Zufall in genau dieser Reihenfolge):
 * 1. Fossil mit `p = 1 - (1 - find_chance) * (1 - guaranteed_fossil_chance)`, sonst Gestein
 *    (`layer.value * balance.rockSellFactor` Geld, sofort gutgeschrieben). `find_chance` hat die
 *    Basis `balance.findChance`, `guaranteed_fossil_chance` (Capstone `dig_5`) die Basis 0. Die
 *    Kombination entspricht zwei unabhaengigen Chancen ("2 % der Bloecke enthalten garantiert ein
 *    Fossil", sonst normaler Fundwurf) und wird in einem einzigen Wurf ausgewertet.
 * 2. Art per `rarityWeight` aus der Schicht.
 * 3. Knochen aus `species.bones`, Gewicht `balance.missingBoneWeight` fuer fehlende Knochen, sonst 1.
 *    Fehlend = kein Eintrag in `run.pieces` fuer den `pieceKey` des neuen Fundes
 *    (`speciesId:boneId:_:_` im MVP).
 * 4. Nur wenn `no_damage_chance > 0`: ein Wurf, ob der ganze Fund ohne Schaden bleibt (Capstone
 *    `prep_5`, gilt fuer alle Zonen).
 * 5. Je Zone (Anzahl aus `bones.json`): `base = uniform(quality_base_min, qualityBaseMax)`, dann
 *    (ausser bei Schadensfreiheit) `damage = max(0, fragility - damage_reduction) *
 *    uniform(damageRollMin, damageRollMax)`, `zoneQ = clamp(base - damage, 0, 1)`.
 *    `quality_base_min` hat die Basis `balance.qualityBaseMin` und wird auf `qualityBaseMax`
 *    begrenzt.
 *
 * **Ergebnis eines Fossils:** ein [Find]. Die Praeparation zaehlt `meta.stats.piecesFound` hoch,
 * meldet [GameEvent.FossilFound] und uebergibt den Fund an den [findHandler] (AP-09).
 *
 * Die Modifier-Quellen muessen aktuell sein; [GameEngine.advance] und [GameEngine.tapPrep] gleichen
 * sie vorher ab.
 */
class PrepSystem(
    private val data: GameData,
    private val modifiers: ModifierEngine,
    private val findHandler: FindHandler = FindHandler.NONE,
) : AdvanceSystem {

    /** Qualitaetsstufen aus `balance.qualityTiers`. */
    val qualityTiers: QualityTiers = QualityTiers.fromGameData(data)

    /** Schichten von der tiefsten zur obersten. */
    private val layersDeepestFirst: List<LayerDefinition> = data.layers.sortedByDescending { it.order }

    /** Verarbeitete Bloecke pro Sekunde durch Praeparatoren (Stat `prep_rate`, nie negativ). */
    fun prepRate(): Double = max(0.0, modifiers.additive(StatId.PREP_RATE))

    /** Chance, dass ein Block ein Fossil enthaelt (`find_chance` kombiniert mit `guaranteed_fossil_chance`). */
    fun fossilChance(): Double {
        val find = modifiers.value(StatId.FIND_CHANCE, data.balance.findChance).coerceIn(0.0, 1.0)
        val guaranteed = modifiers.additive(StatId.GUARANTEED_FOSSIL_CHANCE).coerceIn(0.0, 1.0)
        return (1.0 - (1.0 - find) * (1.0 - guaranteed)).coerceIn(0.0, 1.0)
    }

    /** Geld fuer einen Gesteinsblock aus [layer]. */
    fun rockValue(layer: LayerDefinition): Double = layer.value * data.balance.rockSellFactor

    /**
     * Gewicht von [boneId] bei der Knochenwahl fuer [speciesId] (Pech-Schutz, Kapitel 4.9):
     * `balance.missingBoneWeight`, wenn es in [run] noch keinen Fund mit diesem Schluessel gibt,
     * sonst 1.
     */
    fun boneWeight(run: RunState, speciesId: String, boneId: String): Double =
        if (PieceKey(speciesId, boneId) in run.pieces) EXISTING_BONE_WEIGHT else data.balance.missingBoneWeight

    override fun advance(state: GameState, dt: Double, context: AdvanceContext): GameState =
        process(state, prepRate() * dt, context)

    /**
     * Spieleraktion "Tippen auf Freilegen": verarbeitet `balance.tapPrepAmount` Bloecke nach
     * denselben Regeln wie die Praeparatoren (inkl. Bruchteil-Fortschritt).
     *
     * @return neuer Zustand und Ereignisse (Funde fuer das Fund-Popup)
     */
    fun tapPrep(state: GameState, rng: GameRandom): AdvanceResult {
        val context = AdvanceContext(rng)
        val result = process(state, data.balance.tapPrepAmount, context)
        return AdvanceResult(result, context.events)
    }

    /**
     * Verarbeitet [amount] Bloecke (Bruchteile erlaubt) gemaess Klassenbeschreibung. Gibt die
     * Eingabe-Instanz zurueck, wenn sich nichts aendert.
     */
    fun process(state: GameState, amount: Double, context: AdvanceContext): GameState {
        val run = state.run
        val budget = run.prepProgress.toDouble() + amount
        // Nicht endliche Werte bewusst durchreichen, damit GameEngine sie erkennt (Kapitel 6.6).
        if (!budget.isFinite()) return state.copy(run = run.copy(prepProgress = GameNumber(budget)))
        if (amount <= 0.0 && budget < 1.0) return state

        var remaining = wholeCount(budget)
        var processed = 0.0
        var current = state
        for (layer in layersDeepestFirst) {
            if (remaining <= 0L) break
            val available = wholeCount(current.run.blocks[layer.id]?.toDouble() ?: 0.0)
            val count = min(remaining, available)
            if (count <= 0L) continue
            current = processLayer(current, layer, count, context)
            remaining -= count
            processed += count.toDouble()
        }

        val rest = budget - processed
        val progress = (rest - floor(rest + ROUNDING_TOLERANCE)).coerceIn(0.0, MAX_PROGRESS)
        if (processed == 0.0 && progress == run.prepProgress.toDouble()) return state
        return current.copy(run = current.run.copy(prepProgress = GameNumber(progress)))
    }

    /** Legt [count] ganze Bloecke aus [layer] frei. */
    private fun processLayer(state: GameState, layer: LayerDefinition, count: Long, context: AdvanceContext): GameState {
        val stock = state.run.blocks[layer.id] ?: GameNumber.ZERO
        val left = GameNumber(max(0.0, (stock - count.toDouble()).toDouble()))
        var current = state.copy(run = state.run.copy(blocks = state.run.blocks + (layer.id to left)))

        val chance = fossilChance()
        var rocks = 0L
        var fossils = 0L
        for (i in 0 until count) {
            val find = if (context.rng.nextDouble() < chance) rollFind(current.run, layer, context.rng) else null
            if (find == null) {
                rocks++
                continue
            }
            fossils++
            val meta = current.meta
            current = current.copy(
                meta = meta.copy(stats = meta.stats.copy(piecesFound = meta.stats.piecesFound + 1.0)),
            )
            context.emit(GameEvent.FossilFound(find, qualityTiers.tierOf(find.quality).id))
            current = findHandler.onFind(current, find, context)
        }

        val money = GameNumber(rocks.toDouble() * rockValue(layer))
        if (rocks > 0L) current = current.copy(run = current.run.copy(money = current.run.money + money))
        context.emit(
            GameEvent.BlocksPrepared(
                layerId = layer.id,
                blocks = GameNumber(count.toDouble()),
                fossils = GameNumber(fossils.toDouble()),
                rockMoney = money,
            ),
        )
        return current
    }

    /**
     * Wuerfelt Art, Knochen und Zonen eines Fossils aus [layer] (Schritte 2 bis 5). Liefert `null`,
     * wenn die Schicht keine Arten hat (der Block zaehlt dann als Gestein).
     */
    fun rollFind(run: RunState, layer: LayerDefinition, rng: GameRandom): Find? {
        val candidates = data.speciesByLayer[layer.id].orEmpty()
        if (candidates.isEmpty()) return null
        val species = candidates[weightedIndex(candidates.map { it.rarityWeight }, rng)]
        val boneId = rollBone(run, species, rng)
        val key = PieceKey(species.id, boneId)
        val noDamageChance = modifiers.additive(StatId.NO_DAMAGE_CHANCE).coerceIn(0.0, 1.0)
        val undamaged = noDamageChance > 0.0 && rng.nextDouble() < noDamageChance
        val zones = rollZones(layer, boneId, undamaged, rng)
        return Find(key, layer.id, zones, undamaged)
    }

    /** Knochenwahl mit Pech-Schutz (Kapitel 4.9). */
    fun rollBone(run: RunState, species: SpeciesDefinition, rng: GameRandom): String {
        val weights = species.bones.map { boneWeight(run, species.id, it) }
        return species.bones[weightedIndex(weights, rng)]
    }

    /**
     * Zonen-Qualitaeten fuer einen Knochen [boneId] aus [layer] nach Formel 4.2. Die Zonenzahl
     * kommt aus `bones.json`. Bei [undamaged] entfaellt der Schaden (und dessen Wurf).
     */
    fun rollZones(layer: LayerDefinition, boneId: String, undamaged: Boolean, rng: GameRandom): List<Double> {
        val balance = data.balance
        val baseMax = balance.qualityBaseMax
        val baseMin = min(modifiers.value(StatId.QUALITY_BASE_MIN, balance.qualityBaseMin), baseMax)
        val reduction = modifiers.additive(StatId.DAMAGE_REDUCTION)
        val effectiveFragility = max(0.0, layer.fragility - reduction)
        return data.bone(boneId).zones.map {
            val base = rng.nextDouble(baseMin, baseMax)
            val damage = if (undamaged) {
                0.0
            } else {
                effectiveFragility * rng.nextDouble(balance.damageRollMin, balance.damageRollMax)
            }
            (base - damage).coerceIn(0.0, 1.0)
        }
    }

    companion object {
        /** Gewicht vorhandener Knochen beim Pech-Schutz (Kapitel 4.9: "vorhandene das Gewicht 1"). */
        private const val EXISTING_BONE_WEIGHT: Double = 1.0

        /** Toleranz gegen Gleitkomma-Rundung bei ganzen Bloecken, kein Spielwert. */
        private const val ROUNDING_TOLERANCE: Double = 1e-9

        /** Groesster Wert fuer `prepProgress` (knapp unter 1). */
        private val MAX_PROGRESS: Double = Math.nextDown(1.0)

        /**
         * Anzahl ganzer Bloecke in [value] (kleine Rundungsfehler wie 0.9999999999 zaehlen voll).
         * Nur ein lokaler Schleifenzaehler, kein gespeicherter Spielwert.
         */
        internal fun wholeCount(value: Double): Long {
            if (value <= 0.0) return 0L
            val whole = floor(value + ROUNDING_TOLERANCE)
            return if (whole >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else whole.toLong()
        }

        /** Index gemaess [weights] (alle >= 0, Summe > 0); ein Wurf `nextDouble()`. */
        internal fun weightedIndex(weights: List<Double>, rng: GameRandom): Int {
            val total = weights.sum()
            require(total > 0.0) { "Summe der Gewichte muss positiv sein: $weights" }
            val roll = rng.nextDouble() * total
            var cumulative = 0.0
            for (i in weights.indices) {
                cumulative += weights[i]
                if (roll < cumulative) return i
            }
            return weights.indexOfLast { it > 0.0 }
        }
    }
}

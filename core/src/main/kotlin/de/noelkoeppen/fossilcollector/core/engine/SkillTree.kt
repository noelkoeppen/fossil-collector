package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.data.SkillBranch
import de.noelkoeppen.fossilcollector.core.data.SkillDefinition
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.MetaState
import de.noelkoeppen.fossilcollector.core.number.GameNumber
import kotlin.math.ceil
import kotlin.math.pow

/** Zustand eines Skill-Knotens aus Sicht der UI. */
enum class SkillNodeStatus {
    /** Gekauft. */
    OWNED,

    /** Alle Voraussetzungen erfuellt, kaufbar sobald genug Ruhm da ist (siehe [SkillNodeView.affordable]). */
    AVAILABLE,

    /** Mindestens eine Voraussetzung fehlt. */
    LOCKED,
}

/**
 * Ein Skill-Knoten fuer die UI (Ruhm-Screen, AP-22).
 *
 * @property definition Definition aus `skilltree.json`
 * @property status gekauft, verfuegbar oder gesperrt
 * @property price Preis bei Kauf jetzt; `null`, wenn der Knoten schon gekauft ist. Gesperrte Knoten
 *   zeigen den Preis, den sie jetzt kosten wuerden (er steigt mit jedem weiteren Kauf).
 * @property affordable `true`, wenn [status] [SkillNodeStatus.AVAILABLE] ist und der Ruhm reicht
 *   (d. h. [SkillTree.buy] wuerde gelingen)
 * @property missingRequirements fehlende Voraussetzungen in Datenreihenfolge (leer, wenn keine fehlt)
 */
data class SkillNodeView(
    val definition: SkillDefinition,
    val status: SkillNodeStatus,
    val price: GameNumber?,
    val affordable: Boolean,
    val missingRequirements: List<String>,
) {
    val id: String get() = definition.id
}

/**
 * Uebersicht eines Astes fuer die UI.
 *
 * @property branch Ast
 * @property nodes Knoten nach Tiefe (bei gleicher Tiefe in Datenreihenfolge)
 * @property ownedCount gekaufte Knoten dieses Astes
 */
data class SkillBranchView(
    val branch: SkillBranch,
    val nodes: List<SkillNodeView>,
    val ownedCount: Int,
) {
    /** Anzahl Knoten im Ast. */
    val total: Int get() = nodes.size

    /** `true`, wenn alle Knoten des Astes gekauft sind. */
    val complete: Boolean get() = ownedCount == total
}

/** Ergebnis von [GameEngine.buySkill]. Der Eingabe-Zustand wird nie veraendert. */
sealed interface SkillPurchaseResult {
    /**
     * Skill gekauft.
     *
     * @property state neuer Spielstand (`fame - cost`, `fameSpent + cost`, Skill in `meta.skills`)
     * @property skillId gekaufter Skill
     * @property cost bezahlter Ruhm
     */
    data class Success(val state: GameState, val skillId: String, val cost: GameNumber) : SkillPurchaseResult

    /** Kauf abgelehnt; der Spielstand bleibt unveraendert. */
    data class Failure(val reason: SkillPurchaseFailure) : SkillPurchaseResult
}

/** Gruende fuer einen abgelehnten Skillkauf (in dieser Reihenfolge geprueft). */
enum class SkillPurchaseFailure {
    /** Die Skill-ID steht nicht in `skilltree.json`. */
    UNKNOWN_SKILL,

    /** Der Skill ist bereits gekauft. */
    ALREADY_OWNED,

    /** Mindestens eine Voraussetzung (`requires`) fehlt. */
    REQUIREMENTS_MISSING,

    /** Zu wenig unverteilter Ruhm. */
    NOT_ENOUGH_FAME,
}

/**
 * Ergebnis eines Respec (alle Skills zurueckgeben).
 *
 * @property meta Metazustand danach: keine Skills, `fame + refundedFame`, `fameSpent = 0`
 * @property refundedSkills zurueckgegebene Skill-IDs (wie in `meta.skills`, auch unbekannte)
 * @property refundedFame erstatteter Ruhm (= bisheriges `meta.fameSpent`)
 */
data class RespecResult(
    val meta: MetaState,
    val refundedSkills: Set<String>,
    val refundedFame: GameNumber,
)

/**
 * Plan eines Respec mit anschliessendem Neukauf (fuer die Vorschau im Prestige-Dialog).
 *
 * @property meta Metazustand nach Erstattung und Neukauf
 * @property refundedFame erstatteter Ruhm
 * @property bought tatsaechlich neu gekaufte Skills in Kaufreihenfolge
 * @property rejected uebersprungene Wuensche mit Grund, in Wunschreihenfolge
 */
data class RespecPlan(
    val meta: MetaState,
    val refundedFame: GameNumber,
    val bought: List<String>,
    val rejected: List<Pair<String, SkillPurchaseFailure>>,
)

/**
 * Skilltree (GAME_DESIGN.md, Kapitel 5.4) und Respec (Kapitel 5.3). Reine Berechnung auf dem
 * [MetaState], ohne [de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine]: Die Wirkung
 * gekaufter Skills entsteht ausschliesslich ueber die Quellen `skill:<id>` ([SkillSources]), die
 * [GameEngine.buySkill] bzw. [GameEngine.prestige] danach abgleichen.
 *
 * **Preis:** `nodeCost = ceil( node.baseCost * balance.skillCostGrowth ^ ownedNodeCount )`.
 * `ownedNodeCount` zaehlt alle gekauften, in den Daten bekannten Skills ueber alle Aeste. Ruhm ist
 * immer ganzzahlig (Gewinn wird abgerundet), daher wird der Preis auf ganze Ruhm-Punkte
 * aufgerundet (Kapitel 5.4, Praezisierung AP-13).
 *
 * **Erstattung:** Jeder Kauf addiert den gezahlten Preis zu `meta.fameSpent`. Der Respec erstattet
 * genau diese Summe. So ist die Erstattung eindeutig (unabhaengig von der Kaufreihenfolge, die bei
 * globalem Kostenwachstum den Gesamtpreis beeinflusst) und entspricht exakt dem ausgegebenen Ruhm.
 */
class SkillTree(private val data: GameData) {

    /** Knoten je Ast (nur Aeste mit Knoten, in Reihenfolge von [SkillBranch]), je Ast nach Tiefe sortiert. */
    private val nodesByBranch: Map<SkillBranch, List<SkillDefinition>> =
        SkillBranch.entries
            .associateWith { branch -> data.skills.filter { it.branch == branch }.sortedBy { it.depth } }
            .filterValues { it.isNotEmpty() }

    /** Aeste mit Knoten (im MVP ohne `expedition`). */
    val branches: List<SkillBranch> get() = nodesByBranch.keys.toList()

    /** Anzahl gekaufter, in den Daten bekannter Skills (Exponent des Kostenwachstums). */
    fun ownedCount(meta: MetaState): Int = meta.skills.count { it in data.skillsById }

    /** Preis von [skill] bei Kauf jetzt (ohne Pruefung, ob er kaufbar ist). */
    fun price(meta: MetaState, skill: SkillDefinition): GameNumber {
        val raw = skill.baseCost * data.balance.skillCostGrowth.pow(ownedCount(meta))
        return GameNumber(ceil(raw - ROUNDING_TOLERANCE))
    }

    /** Preis des Skills [skillId] bei Kauf jetzt. */
    fun price(meta: MetaState, skillId: String): GameNumber = price(meta, data.skill(skillId))

    /** Fehlende Voraussetzungen von [skill] in Datenreihenfolge. */
    fun missingRequirements(meta: MetaState, skill: SkillDefinition): List<String> =
        skill.requires.filter { it !in meta.skills }

    /** Grund, warum [skillId] jetzt nicht gekauft werden kann, oder `null`, wenn der Kauf gelingt. */
    fun check(meta: MetaState, skillId: String): SkillPurchaseFailure? {
        val skill = data.skillsById[skillId] ?: return SkillPurchaseFailure.UNKNOWN_SKILL
        return when {
            skillId in meta.skills -> SkillPurchaseFailure.ALREADY_OWNED
            missingRequirements(meta, skill).isNotEmpty() -> SkillPurchaseFailure.REQUIREMENTS_MISSING
            !(meta.fame >= price(meta, skill)) -> SkillPurchaseFailure.NOT_ENOUGH_FAME
            else -> null
        }
    }

    /** `true`, wenn [skillId] jetzt gekauft werden kann. */
    fun canBuy(meta: MetaState, skillId: String): Boolean = check(meta, skillId) == null

    /**
     * Kauft [skillId]: `fame - Preis`, `fameSpent + Preis`, Skill in `skills`.
     *
     * @return neuer Metazustand und gezahlter Preis, oder `null`, wenn [check] einen Fehlgrund liefert
     */
    fun buy(meta: MetaState, skillId: String): Pair<MetaState, GameNumber>? {
        if (check(meta, skillId) != null) return null
        val cost = price(meta, skillId)
        return meta.copy(
            fame = meta.fame - cost,
            fameSpent = meta.fameSpent + cost,
            skills = meta.skills + skillId,
        ) to cost
    }

    /** Sicht auf einen Knoten fuer die UI. */
    fun node(meta: MetaState, skill: SkillDefinition): SkillNodeView {
        if (skill.id in meta.skills) {
            return SkillNodeView(skill, SkillNodeStatus.OWNED, price = null, affordable = false, emptyList())
        }
        val missing = missingRequirements(meta, skill)
        val price = price(meta, skill)
        val status = if (missing.isEmpty()) SkillNodeStatus.AVAILABLE else SkillNodeStatus.LOCKED
        val affordable = status == SkillNodeStatus.AVAILABLE && meta.fame >= price
        return SkillNodeView(skill, status, price, affordable, missing)
    }

    /** Sicht auf den Knoten [skillId]. */
    fun node(meta: MetaState, skillId: String): SkillNodeView = node(meta, data.skill(skillId))

    /** Uebersicht aller Aeste mit Knoten. */
    fun overview(meta: MetaState): List<SkillBranchView> = nodesByBranch.map { (branch, nodes) ->
        val views = nodes.map { node(meta, it) }
        SkillBranchView(branch, views, views.count { it.status == SkillNodeStatus.OWNED })
    }

    /** Gibt alle Skills zurueck und erstattet `meta.fameSpent` zu 100 %. */
    fun respec(meta: MetaState): RespecResult = RespecResult(
        meta = meta.copy(fame = meta.fame + meta.fameSpent, fameSpent = GameNumber.ZERO, skills = emptySet()),
        refundedSkills = meta.skills,
        refundedFame = meta.fameSpent,
    )

    /**
     * Respec und anschliessender Neukauf von [rebuy] in der angegebenen Reihenfolge ueber [buy].
     * Nicht kaufbare Wuensche (z. B. Voraussetzung fehlt, Ruhm reicht nicht, doppelt) werden
     * uebersprungen und in [RespecPlan.rejected] gemeldet.
     */
    fun planRespec(meta: MetaState, rebuy: List<String>): RespecPlan {
        val respec = respec(meta)
        var current = respec.meta
        val bought = ArrayList<String>()
        val rejected = ArrayList<Pair<String, SkillPurchaseFailure>>()
        for (id in rebuy) {
            val failure = check(current, id)
            if (failure != null) {
                rejected += id to failure
                continue
            }
            current = buy(current, id)!!.first
            bought += id
        }
        return RespecPlan(current, respec.refundedFame, bought, rejected)
    }

    private companion object {
        /** Toleranz gegen Gleitkomma-Rundung beim Aufrunden (z. B. 2.0000000001), kein Spielwert. */
        const val ROUNDING_TOLERANCE: Double = 1e-9
    }
}

/**
 * Respec beim Prestige (Kapitel 5.3) als [PrestigeMetaStep]: gibt alle Skills zurueck, erstattet den
 * ausgegebenen Ruhm zu 100 % und kauft danach [rebuy] in dieser Reihenfolge neu (nicht kaufbare
 * Wuensche werden uebersprungen, siehe [SkillTree.planRespec]). Laeuft in [GameEngine.prestige] vor
 * der Berechnung der Startwerte, damit zurueckgegebene Start-Skills (`dig_3`, `lab_3`) im neuen
 * Durchlauf nicht mehr und neu gekaufte sofort wirken.
 *
 * Ohne Respec (Skills behalten) wird [PrestigeMetaStep.NONE] uebergeben.
 */
class RespecStep(
    private val skillTree: SkillTree,
    val rebuy: List<String> = emptyList(),
) : PrestigeMetaStep {
    override fun apply(meta: MetaState): MetaState = skillTree.planRespec(meta, rebuy).meta
}

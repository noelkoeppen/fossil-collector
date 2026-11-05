package de.noelkoeppen.fossilcollector.core.engine

import de.noelkoeppen.fossilcollector.core.data.DefinitionIds
import de.noelkoeppen.fossilcollector.core.data.GameData
import de.noelkoeppen.fossilcollector.core.model.GameState
import de.noelkoeppen.fossilcollector.core.model.RunState
import de.noelkoeppen.fossilcollector.core.modifier.Modifier
import de.noelkoeppen.fossilcollector.core.modifier.ModifierEngine
import de.noelkoeppen.fossilcollector.core.number.GameNumber

/**
 * Liefert die Modifier-Quellen einer Quellen-Art aus dem Spielstand.
 *
 * Jeder Provider ist fuer alle Quellen-IDs mit seinem [prefix] zustaendig (z. B. `skill:`).
 * Beim Abgleich entfernt [ModifierSources.sync] Quellen dieses Prefixes, die der Provider nicht
 * mehr liefert. Quellen mit Prefixen, fuer die es keinen Provider gibt, bleiben unberuehrt.
 */
interface ModifierSourceProvider {
    /** Prefix inkl. Doppelpunkt, z. B. `skill:`. Muss unter allen Providern eindeutig sein. */
    val prefix: String

    /** Gewuenschte Quellen: ID (mit [prefix]) -> Modifier. */
    fun sources(state: GameState, data: GameData): Map<String, List<Modifier>>
}

/**
 * Zentraler Abgleich zwischen Spielstand und [ModifierEngine] (Kapitel 6.2).
 *
 * Aufruf nach dem Laden, nach jeder Aktion, die Quellen aendert (Kauf von Praeparatoren oder
 * Werkzeugstufen, Skillkauf, Prestige), oder einfach vor der Auswertung. Der Abgleich ist
 * idempotent: Unveraenderte Quellen werden nicht neu gesetzt, damit der Cache der Engine erhalten
 * bleibt.
 *
 * Standard-Provider ([DEFAULT_PROVIDERS]):
 * - [UpgradeSources]: `upgrade:<generatorId>` mit Effekten je Einheit mal Bestand
 *   (z. B. `upgrade:preparator`, `prep_rate` add 0.2 je Praeparator) und `upgrade:<upgradeId>` mit
 *   `effectsPerLevel` mal Stufe (z. B. `upgrade:tools`)
 * - [SkillSources]: `skill:<skillId>` fuer jeden gekauften Skill
 *
 * - [BookSources] (AP-11): `book:<speciesId>` (Buch-Bonus ab Completion-Stufe 2) und
 *   `book:researched:<speciesId>` (Pauschal-Bonus fuer erforschte Arten)
 * - [MilestoneSources] (AP-11): `milestone:<id>` mit den `reward.effects` erreichter Meilensteine
 *
 * Weitere Quellen-Arten werden als [ModifierSourceProvider] mit eigenem Prefix implementiert und in
 * [DEFAULT_PROVIDERS] aufgenommen. An [sync] selbst muss dafuer nichts geaendert werden.
 */
class ModifierSources(
    private val providers: List<ModifierSourceProvider> = DEFAULT_PROVIDERS,
) {
    init {
        val prefixes = providers.map { it.prefix }
        require(prefixes.size == prefixes.toSet().size) { "Provider-Prefixe muessen eindeutig sein: $prefixes" }
        require(prefixes.none { a -> prefixes.any { b -> a != b && b.startsWith(a) } }) {
            "Provider-Prefixe duerfen sich nicht ueberlappen: $prefixes"
        }
    }

    /** Gleicht die Quellen von [engine] mit [state] ab. */
    fun sync(engine: ModifierEngine, state: GameState, data: GameData) {
        for (provider in providers) {
            val wanted = provider.sources(state, data)
            require(wanted.keys.all { it.startsWith(provider.prefix) }) {
                "Provider ${provider.prefix} liefert fremde IDs: ${wanted.keys}"
            }
            for (id in engine.sourceIds) {
                if (id.startsWith(provider.prefix) && id !in wanted) engine.removeSource(id)
            }
            for ((id, modifiers) in wanted) {
                if (engine.source(id) != modifiers) engine.setSource(id, modifiers)
            }
        }
    }

    companion object {
        /** Standard-Provider: Upgrades und Skills (AP-06), Buch und Meilensteine (AP-11). */
        val DEFAULT_PROVIDERS: List<ModifierSourceProvider> =
            listOf(UpgradeSources, SkillSources, BookSources, MilestoneSources)

        /** Standard-Instanz mit [DEFAULT_PROVIDERS]. */
        val DEFAULT: ModifierSources = ModifierSources()

        /** Kurzform fuer `DEFAULT.sync(engine, state, data)`. */
        fun sync(engine: ModifierEngine, state: GameState, data: GameData) = DEFAULT.sync(engine, state, data)

        /** Modifier-Liste, bei der jeder Wert mit [count] multipliziert ist; leer bei `count <= 0`. */
        internal fun scaled(modifiers: List<Modifier>, count: GameNumber): List<Modifier> {
            val c = count.toDouble()
            if (c <= 0.0) return emptyList()
            return modifiers.map { it.copy(value = it.value * c) }
        }
    }
}

/**
 * Quellen der Kategorie Upgrade, Prefix `upgrade:`:
 * - Generatoren mit Effekten je Einheit: `upgrade:<generatorId>` = Effekte mal Bestand
 *   (z. B. `upgrade:preparator`)
 * - Stufen-Upgrades: `upgrade:<upgradeId>` = `effectsPerLevel` mal Stufe (z. B. `upgrade:tools`)
 *
 * Bei Bestand bzw. Stufe 0 wird keine Quelle gesetzt. Generator- und Upgrade-IDs muessen
 * disjunkt sein.
 */
object UpgradeSources : ModifierSourceProvider {
    override val prefix: String = "upgrade:"

    override fun sources(state: GameState, data: GameData): Map<String, List<Modifier>> {
        val result = LinkedHashMap<String, List<Modifier>>()
        for (generator in data.generators) {
            if (generator.effects.isEmpty()) continue
            val modifiers = ModifierSources.scaled(generator.effects, generatorCount(state.run, generator.id))
            if (modifiers.isNotEmpty()) result[prefix + generator.id] = modifiers
        }
        for (upgrade in data.upgrades) {
            require(upgrade.id !in data.generatorsById) { "Upgrade-ID kollidiert mit Generator-ID: ${upgrade.id}" }
            val modifiers = ModifierSources.scaled(upgrade.effectsPerLevel, upgradeLevel(state.run, upgrade.id))
            if (modifiers.isNotEmpty()) result[prefix + upgrade.id] = modifiers
        }
        return result
    }

    /** Bestand eines Generators im Spielstand. */
    fun generatorCount(run: RunState, generatorId: String): GameNumber = when (generatorId) {
        DefinitionIds.PREPARATOR -> run.preparators
        DefinitionIds.HALL -> run.halls
        DefinitionIds.LAB_SLOT -> run.labSlots
        else -> throw IllegalArgumentException("Generator ohne Feld im Spielstand: $generatorId")
    }

    /** Stufe eines Upgrades im Spielstand. */
    fun upgradeLevel(run: RunState, upgradeId: String): GameNumber = when (upgradeId) {
        DefinitionIds.TOOLS -> run.toolLevel
        else -> throw IllegalArgumentException("Upgrade ohne Feld im Spielstand: $upgradeId")
    }
}

/** Gekaufte Skills, Quelle `skill:<skillId>`. Unbekannte Skill-IDs im Spielstand werden ignoriert. */
object SkillSources : ModifierSourceProvider {
    override val prefix: String = "skill:"

    override fun sources(state: GameState, data: GameData): Map<String, List<Modifier>> =
        state.meta.skills
            .mapNotNull { id -> data.skillsById[id]?.let { prefix + id to it.effects } }
            .toMap()
}

package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.ModifierCategory
import de.noelkoeppen.fossilcollector.core.modifier.ModifierOp
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Laedt die echten Spieldaten und prueft Referenzen und Vollstaendigkeit
 * (GAME_DESIGN.md, Kapitel 10.4). Zusaetzlich: Negativtests, dass der Validator
 * typische Fehler erkennt.
 */
class DataValidationTest {

    private val data: GameData = DataRepository().load()

    @Test
    fun realDataLoadsWithoutErrors() {
        assertEquals(emptyList<String>(), DataValidator.validate(data))
    }

    @Test
    fun mvpContentIsComplete() {
        assertEquals(listOf("quaternary", "neogene", "cretaceous"), data.layersInOrder.map { it.id })
        assertEquals(18, data.species.size)
        data.layers.forEach { assertEquals(6, data.speciesInLayer(it.id).size, "Arten in ${it.id}") }
        assertEquals(8, data.bones.size)
        data.species.forEach { assertEquals(8, it.bones.size, "Knochen von ${it.id}") }
        assertEquals(20, data.skills.size)
        assertEquals(4, data.skills.count { it.capstone })
        assertTrue(data.milestones.isNotEmpty())
    }

    @Test
    fun startValuesMatchDesign() {
        val b = data.balance
        assertEquals(0.08, b.findChance)
        assertEquals(0.89, b.reconstructionCap)
        assertEquals(listOf(25, 50, 100, 200), b.teamMilestones)
        assertEquals(listOf(1.0, 2.0, 3.0, 5.0, 8.0), b.bookTierFactor)
        assertEquals(6000.0, data.layer("neogene").unlockCost)
        assertEquals(1.17, data.generator(GameData.GENERATOR_PREPARATOR).growth)
        assertEquals(1, data.generator(GameData.GENERATOR_HALL).free)
        assertEquals(null, data.upgrade(GameData.UPGRADE_TOOLS).maxLevel)
    }

    @Test
    fun bookBonusesAreSmall() {
        // Kapitel 6.4: Basiswerte 1 bis 3 Prozent.
        data.species.forEach {
            assertEquals(ModifierOp.MULT, it.bookBonus.op, it.id)
            assertTrue(it.bookBonus.value in 0.01..0.03, "Buch-Bonus von ${it.id}: ${it.bookBonus.value}")
        }
    }

    @Test
    fun skillCostsFollowDepth() {
        val expected = mapOf(1 to 1.0, 2 to 2.0, 3 to 3.0, 4 to 5.0, 5 to 8.0)
        data.skills.forEach { assertEquals(expected[it.depth], it.baseCost, it.id) }
    }

    @Test
    fun bothLanguagesHaveAllKeys() {
        val required = LocaleKeys.requiredKeys(data)
        GameData.SUPPORTED_LANGUAGES.forEach { lang ->
            val missing = required - data.locales.getValue(lang).keys
            assertTrue(missing.isEmpty(), "$lang fehlt: $missing")
        }
        assertEquals(data.locales.getValue("de").keys, data.locales.getValue("en").keys)
    }

    // ---- Negativtests ----

    @Test
    fun detectsUnknownLayerAndBone() {
        val s = data.species.first()
        val broken = copy(species = data.species.drop(1) + s.copy(layer = "jurassic", bones = s.bones + "tail"))
        val errors = DataValidator.validate(broken)
        assertHas(errors, "unbekannte Schicht 'jurassic'")
        assertHas(errors, "unbekannter Knochen 'tail'")
    }

    @Test
    fun detectsBrokenSkillPrerequisites() {
        val node = data.skill("dig_2")
        val broken = copy(skills = data.skills.map { if (it.id == "dig_2") node.copy(requires = listOf("dig_9")) else it })
        assertHas(DataValidator.validate(broken), "Voraussetzung 'dig_9' existiert nicht")

        val crossBranch = copy(skills = data.skills.map { if (it.id == "dig_2") node.copy(requires = listOf("lab_1")) else it })
        assertHas(DataValidator.validate(crossBranch), "anderen Ast")
    }

    @Test
    fun detectsWrongModifierCategory() {
        val node = data.skill("prep_1")
        val wrong = node.copy(effects = listOf(EffectDef(StatId.DAMAGE_REDUCTION, ModifierOp.ADD, 0.02, ModifierCategory.UPGRADE)))
        val broken = copy(skills = data.skills.map { if (it.id == "prep_1") wrong else it })
        assertHas(DataValidator.validate(broken), "Kategorie 'skill'")
    }

    @Test
    fun detectsReconstructionCapReachingPristine() {
        val broken = copy(balance = data.balance.copy(reconstructionCap = 0.95))
        assertHas(DataValidator.validate(broken), "reconstructionCap")
    }

    @Test
    fun detectsMissingTranslation() {
        val de = data.locales.getValue("de") - LocaleKeys.speciesName("tyrannosaurus")
        val broken = copy(locales = data.locales + ("de" to de))
        assertHas(DataValidator.validate(broken), "species.tyrannosaurus.name")
    }

    @Test
    fun detectsDuplicateIdsAndMissingGenerators() {
        val broken = copy(
            bones = data.bones + data.bones.first(),
            generators = data.generators.filterNot { it.id == GameData.GENERATOR_LAB_SLOT },
        )
        val errors = DataValidator.validate(broken)
        assertHas(errors, "doppelte ID 'skull'")
        assertHas(errors, "Pflicht-Generator 'lab_slot' fehlt")
    }

    @Test
    fun rejectsUnknownStatIdAndFieldsWhenLoading() {
        val files = realFiles()
        val badStat = files + (DataRepository.FILE_UPGRADES to files.getValue(DataRepository.FILE_UPGRADES)
            .replace("\"damage_reduction\"", "\"damage_reducton\""))
        assertThrows<DataValidationException> { DataRepository { badStat[it] }.load() }

        val extraField = files + (DataRepository.FILE_BONES to files.getValue(DataRepository.FILE_BONES)
            .replaceFirst("\"id\"", "\"color\": \"#000000\", \"id\""))
        assertThrows<DataValidationException> { DataRepository { extraField[it] }.load() }
    }

    @Test
    fun reportsMissingFile() {
        val files = realFiles() - DataRepository.FILE_MILESTONES
        val ex = assertThrows<DataValidationException> { DataRepository { files[it] }.load() }
        assertHas(ex.errors, "milestones.json: Datei fehlt")
    }

    private fun realFiles(): Map<String, String> {
        val names = listOf(
            DataRepository.FILE_LAYERS, DataRepository.FILE_SPECIES, DataRepository.FILE_BONES,
            DataRepository.FILE_GENERATORS, DataRepository.FILE_UPGRADES, DataRepository.FILE_SKILLTREE,
            DataRepository.FILE_MILESTONES, DataRepository.FILE_BALANCE,
        ) + GameData.SUPPORTED_LANGUAGES.map { DataRepository.localeFile(it) }
        return names.associateWith { name ->
            javaClass.classLoader.getResource("data/$name")!!.readText(Charsets.UTF_8)
        }
    }

    private fun copy(
        species: List<SpeciesDef> = data.species,
        bones: List<BoneDef> = data.bones,
        generators: List<GeneratorDef> = data.generators,
        skills: List<SkillNodeDef> = data.skills,
        balance: BalanceDef = data.balance,
        locales: Map<String, Map<String, String>> = data.locales,
    ) = GameData(data.layers, species, bones, generators, data.upgrades, skills, data.milestones, balance, locales)

    private fun assertHas(errors: List<String>, fragment: String) {
        assertTrue(errors.any { fragment in it }, "Erwarteter Fehler mit '$fragment' fehlt. Gefunden: $errors")
    }
}

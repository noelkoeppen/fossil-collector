package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.ModifierCategory
import de.noelkoeppen.fossilcollector.core.modifier.StatId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Datenvalidierung (GAME_DESIGN.md, Kapitel 10.4 und 12): Alle Dateien laden, Referenzen und
 * Locale-Schluessel pruefen sowie MVP-Umfang aus Kapitel 4.3, 5.4 und 6.4 sicherstellen.
 */
class DataValidationTest {

    private val data: GameData = DataRepository().load()

    private fun resource(path: String): String? =
        javaClass.classLoader.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun dataFile(file: String): String = checkNotNull(resource(DataRepository.RESOURCE_ROOT + file)) { "$file fehlt" }

    /** Repository, bei dem eine Datei durch [patch] veraendert wird. */
    private fun patchedRepository(file: String, patch: (String) -> String) = DataRepository { name ->
        resource(DataRepository.RESOURCE_ROOT + name)?.let { if (name == file) patch(it) else it }
    }

    private fun errorsOf(file: String, patch: (String) -> String): List<String> =
        DataValidator.validate(patchedRepository(file, patch).parse())

    // --- Laden und Referenzen der echten Daten ---

    @Test
    fun allFilesLoadWithoutValidationErrors() {
        assertEquals(emptyList<String>(), DataValidator.validate(data))
    }

    @Test
    fun everySpeciesReferencesExistingLayerAndBones() {
        data.species.forEach { species ->
            assertTrue(species.layer in data.layersById, "Schicht fehlt fuer ${species.id}")
            species.bones.forEach { assertTrue(it in data.bonesById, "Knochen $it fehlt fuer ${species.id}") }
        }
    }

    @Test
    fun everySkillRequirementExists() {
        data.skills.flatMap { it.requires }.forEach { assertTrue(it in data.skillsById, "Skill $it fehlt") }
    }

    @Test
    fun everyLocaleKeyExistsInBothLanguages() {
        val required = LocaleKeys.requiredKeys(data)
        val de = checkNotNull(data.locales["de"])
        val en = checkNotNull(data.locales["en"])
        assertEquals(de.keys, en.keys, "de und en haben unterschiedliche Schluessel")
        assertTrue(de.keys.containsAll(required), "Fehlend: ${required - de.keys}")
    }

    // --- MVP-Umfang ---

    @Test
    fun mvpLayersMatchStartValues() {
        assertEquals(listOf("quaternary", "neogene", "cretaceous"), data.layers.map { it.id })
        assertEquals(listOf(0.0, 6_000.0, 250_000.0), data.layers.map { it.unlockCost })
        assertEquals(listOf(10.0, 2_000.0, 60_000.0), data.layers.map { it.teamBaseCost })
        assertEquals(listOf(1.0, 8.0, 60.0), data.layers.map { it.value })
        assertEquals(listOf(0.15, 0.30, 0.45), data.layers.map { it.fragility })
        assertEquals(listOf(1.0, 3.0, 10.0), data.layers.map { it.fameWeight })
    }

    @Test
    fun eighteenSpeciesWithRarityWeightsPerLayer() {
        assertEquals(18, data.species.size)
        data.layers.forEach { layer ->
            val weights = data.speciesByLayer.getValue(layer.id).map { it.rarityWeight }
            assertEquals(listOf(40.0, 25.0, 15.0, 10.0, 5.0, 2.0), weights, "Gewichte in ${layer.id}")
        }
    }

    @Test
    fun bookBonusesAreSmallAndVaried() {
        data.species.forEach {
            assertTrue(it.bookBonus.value in 0.01..0.03, "Buch-Bonus von ${it.id} ausserhalb 1-3 %")
        }
        assertTrue(data.species.map { it.bookBonus.stat }.distinct().size >= 6, "Zu wenig verschiedene Stats")
        assertEquals(StatId.DIG_RATE, data.species("woolly_mammoth").bookBonus.stat)
        assertEquals(StatId.FIND_CHANCE, data.species("smilodon").bookBonus.stat)
        assertEquals(StatId.FAME_GAIN, data.species("tyrannosaurus").bookBonus.stat)
    }

    @Test
    fun eightBonesWithFourZonesEach() {
        assertEquals(
            listOf("skull", "jaw", "cervical", "dorsal", "ribs", "pelvis", "forelimb", "hindlimb"),
            data.bones.map { it.id },
        )
        data.bones.forEach { assertEquals(4, it.zones.size, "Zonen von ${it.id}") }
        assertEquals(listOf("centrum", "spinous", "transverse_l", "transverse_r"), data.bone("dorsal").zones)
    }

    @Test
    fun twentySkillsInFourBranchesWithoutExpedition() {
        assertEquals(20, data.skills.size)
        val branches = data.skills.groupBy { it.branch }
        assertEquals(
            setOf(SkillBranch.DIG, SkillBranch.PREP, SkillBranch.LAB, SkillBranch.CURATOR),
            branches.keys,
        )
        branches.values.forEach { nodes ->
            assertEquals(listOf(1, 2, 3, 4, 5), nodes.map { it.depth }.sorted())
            assertEquals(listOf(1.0, 2.0, 3.0, 5.0, 8.0), nodes.sortedBy { it.depth }.map { it.baseCost })
            assertEquals(listOf(5), nodes.filter { it.capstone }.map { it.depth })
        }
        data.skills.flatMap { it.effects }.forEach { assertEquals(ModifierCategory.SKILL, it.category) }
    }

    @Test
    fun balanceContainsGlobalValues() {
        val b = data.balance
        assertEquals(0.08, b.findChance)
        assertEquals(0.89, b.reconstructionCap)
        assertEquals(2.0, b.skeletonMult)
        assertEquals(8.0, b.slotsPerHall)
        assertEquals(listOf(25.0, 50.0, 100.0, 200.0), b.teamMilestones)
        assertEquals(listOf(1.0, 2.0, 3.0, 5.0, 8.0), b.bookTierFactor)
        assertEquals(listOf("fragment", "damaged", "good", "excellent", "pristine"), b.qualityTiers.map { it.id })
        assertEquals(ModifierCategory.BOOK, b.researchedBonus.category)
        assertTrue(b.reconstructionCap < b.qualityTiers.last().minQuality, "Rekonstruktion darf nie Museumsreif sein")
    }

    @Test
    fun generatorsAndToolUpgradeExist() {
        assertEquals(setOf("preparator", "hall", "lab_slot"), data.generatorsById.keys)
        assertEquals(1, data.generator("hall").free)
        assertEquals(1, data.generator("lab_slot").free)
        val tools = data.upgrade("tools")
        assertEquals(Currency.RESEARCH_POINTS, tools.currency)
        assertEquals(StatId.DAMAGE_REDUCTION, tools.effectsPerLevel.single().stat)
    }

    @Test
    fun milestonesHaveRewards() {
        assertTrue(data.milestones.size >= 7)
        assertNotNull(data.milestonesById["first_find"])
    }

    // --- Der Validator erkennt kaputte Daten ---

    @Test
    fun detectsUnknownLayerReference() {
        val errors = errorsOf(DataRepository.SPECIES) {
            it.replaceFirst("\"layer\": \"quaternary\"", "\"layer\": \"jurassic\"")
        }
        assertTrue(errors.any { "unbekannte Schicht 'jurassic'" in it }, errors.toString())
    }

    @Test
    fun detectsUnknownBoneReference() {
        val errors = errorsOf(DataRepository.SPECIES) { it.replaceFirst("\"skull\"", "\"tail\"") }
        assertTrue(errors.any { "unbekannter Knochen 'tail'" in it }, errors.toString())
    }

    @Test
    fun detectsUnknownSkillRequirement() {
        val errors = errorsOf(DataRepository.SKILLTREE) { it.replaceFirst("\"requires\": [\"dig_1\"]", "\"requires\": [\"dig_0\"]") }
        assertTrue(errors.any { "unbekannte Voraussetzung 'dig_0'" in it }, errors.toString())
    }

    @Test
    fun detectsMissingLocaleKey() {
        val errors = errorsOf(DataRepository.localeFile("en")) {
            it.replace(Regex("\\s*\"species\\.cave_bear\\.name\": \"[^\"]*\","), "")
        }
        assertTrue(errors.any { "en.json: Schluessel 'species.cave_bear.name' fehlt" in it }, errors.toString())
    }

    @Test
    fun rejectsUnknownStatId() {
        val repo = patchedRepository(DataRepository.SKILLTREE) { it.replaceFirst("find_chance", "luck") }
        val e = assertThrows<DataValidationException> { repo.load() }
        assertTrue(e.errors.single().startsWith(DataRepository.SKILLTREE), e.errors.toString())
    }

    @Test
    fun rejectsReconstructionCapReachingPristine() {
        val errors = errorsOf(DataRepository.BALANCE) {
            it.replace("\"reconstructionCap\": 0.89", "\"reconstructionCap\": 0.95")
        }
        assertTrue(errors.any { "reconstructionCap" in it }, errors.toString())
    }

    @Test
    fun rejectsMissingFile() {
        val repo = DataRepository { name -> if (name == DataRepository.BONES) null else resource(DataRepository.RESOURCE_ROOT + name) }
        val e = assertThrows<DataValidationException> { repo.load() }
        assertEquals(listOf("bones.json: Datei fehlt"), e.errors)
    }

    // --- Serialisierung und Schemas ---

    @Test
    fun statIdsSerializeAsSnakeCase() {
        StatId.entries.forEach {
            assertEquals("\"${it.serialName}\"", Json.encodeToString(StatId.serializer(), it))
            assertEquals(it, StatId.fromSerialName(it.serialName))
        }
        assertEquals("\"dig_rate\"", Json.encodeToString(StatId.serializer(), StatId.DIG_RATE))
    }

    @Test
    fun everyDataFileHasSchema() {
        val schemaNames = DataRepository.ALL_FILES.map { file ->
            if (file.startsWith("locale/")) "locale.schema.json" else file.removeSuffix(".json") + ".schema.json"
        }.distinct() + "common.schema.json"
        schemaNames.forEach { name ->
            val text = checkNotNull(resource("schema/$name")) { "Schema $name fehlt" }
            assertTrue(Json.parseToJsonElement(text) is JsonObject, "$name ist kein JSON-Objekt")
        }
        DataRepository.ALL_FILES.forEach { dataFile(it) }
    }

    @Test
    fun schemaStatIdsMatchEnum() {
        val common = Json.parseToJsonElement(checkNotNull(resource("schema/common.schema.json"))).jsonObject
        val stats = common.getValue("\$defs").jsonObject.getValue("statId").jsonObject.getValue("enum")
        val names = (stats as JsonArray).jsonArray.map { it.jsonPrimitive.content }
        assertEquals(StatId.entries.map { it.serialName }, names)
    }
}

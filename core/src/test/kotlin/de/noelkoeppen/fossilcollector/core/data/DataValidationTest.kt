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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Datenvalidierung: Alle Dateien laden, Referenzen und Locale-Schluessel pruefen sowie den
 * vorhandenen Datenumfang (Schichten, Arten, Knochen, Qualitaetsstufen) sicherstellen.
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
    fun everyLocaleKeyExistsInBothLanguages() {
        val required = LocaleKeys.requiredKeys(data)
        val de = checkNotNull(data.locales["de"])
        val en = checkNotNull(data.locales["en"])
        assertEquals(de.keys, en.keys, "de und en haben unterschiedliche Schluessel")
        assertTrue(de.keys.containsAll(required), "Fehlend: ${required - de.keys}")
    }

    // --- MVP-Umfang ---

    @Test
    fun mvpLayersInOrder() {
        assertEquals(listOf("quaternary", "neogene", "cretaceous"), data.layers.map { it.id })
        assertEquals(listOf(0, 1, 2), data.layers.map { it.order })
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
    fun eightBones() {
        assertEquals(
            listOf("skull", "jaw", "cervical", "dorsal", "ribs", "pelvis", "forelimb", "hindlimb"),
            data.bones.map { it.id },
        )
    }

    @Test
    fun balanceContainsQualityTiers() {
        val b = data.balance
        assertEquals(listOf("fragment", "damaged", "good", "excellent", "pristine"), b.qualityTiers.map { it.id })
        assertEquals(listOf(0.0, 0.25, 0.5, 0.7, 0.9), b.qualityTiers.map { it.minQuality })
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
    fun detectsMissingLocaleKey() {
        val errors = errorsOf(DataRepository.localeFile("en")) {
            it.replace(Regex("\\s*\"species\\.cave_bear\\.name\": \"[^\"]*\","), "")
        }
        assertTrue(errors.any { "en.json: Schluessel 'species.cave_bear.name' fehlt" in it }, errors.toString())
    }

    @Test
    fun rejectsUnknownStatId() {
        val repo = patchedRepository(DataRepository.BALANCE) { it.replaceFirst("damage_reduction", "luck") }
        val e = assertThrows<DataValidationException> { repo.load() }
        assertTrue(e.errors.single().startsWith(DataRepository.BALANCE), e.errors.toString())
    }

    @Test
    fun rejectsUnknownField() {
        val repo = patchedRepository(DataRepository.BONES) { it.replaceFirst("{\"id\": \"skull\"}", "{\"id\": \"skull\", \"size\": 1}") }
        val e = assertThrows<DataValidationException> { repo.load() }
        assertTrue(e.errors.single().startsWith(DataRepository.BONES), e.errors.toString())
    }

    @Test
    fun detectsUnsortedQualityTiers() {
        val errors = errorsOf(DataRepository.BALANCE) { it.replace("\"minQuality\": 0.7", "\"minQuality\": 0.4") }
        assertTrue(errors.any { "streng aufsteigend" in it }, errors.toString())
    }

    @Test
    fun detectsInvalidStatLimit() {
        val errors = errorsOf(DataRepository.BALANCE) {
            it.replace("\"quality_base_min\": {\"min\": 0, \"max\": 1}", "\"quality_base_min\": {\"min\": 1, \"max\": 0}")
        }
        assertTrue(errors.any { "statLimits.quality_base_min min > max" in it }, errors.toString())
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
        assertEquals("\"damage_reduction\"", Json.encodeToString(StatId.serializer(), StatId.DAMAGE_REDUCTION))
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

    // --- Schichttexturen (AP-26) ---

    @Test
    fun mvpLayersHaveDistinctTextures() {
        val textures = data.layers.map { it.texture }
        assertEquals(listOf(LayerTexture.GRAVEL, LayerTexture.BANDED, LayerTexture.CHALK), textures)
    }

    @Test
    fun missingTextureDefaultsToPlain() {
        val repo = patchedRepository(DataRepository.LAYERS) { it.replaceFirst(Regex(",\\s*\"texture\": \"gravel\""), "") }
        assertEquals(LayerTexture.PLAIN, repo.load().layers.first().texture)
    }

    @Test
    fun rejectsUnknownTexture() {
        val repo = patchedRepository(DataRepository.LAYERS) { it.replaceFirst("\"gravel\"", "\"marble\"") }
        val e = assertThrows<DataValidationException> { repo.load() }
        assertTrue(e.errors.single().startsWith(DataRepository.LAYERS), e.errors.toString())
    }

    @Test
    fun schemaTexturesMatchEnum() {
        val schema = Json.parseToJsonElement(checkNotNull(resource("schema/layers.schema.json"))).jsonObject
        val texture = schema.getValue("items").jsonObject.getValue("properties").jsonObject.getValue("texture").jsonObject
        val names = texture.getValue("enum").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(LayerTexture.entries.map { it.serialName }, names)
        LayerTexture.entries.forEach {
            assertEquals("\"${it.serialName}\"", Json.encodeToString(LayerTexture.serializer(), it))
        }
    }

    @Test
    fun schemaStatIdsMatchEnum() {
        val common = Json.parseToJsonElement(checkNotNull(resource("schema/common.schema.json"))).jsonObject
        val stats = common.getValue("\$defs").jsonObject.getValue("statId").jsonObject.getValue("enum")
        val names = (stats as JsonArray).jsonArray.map { it.jsonPrimitive.content }
        assertEquals(StatId.entries.map { it.serialName }, names)
    }

    @Test
    fun schemaModifierCategoriesMatchEnum() {
        val common = Json.parseToJsonElement(checkNotNull(resource("schema/common.schema.json"))).jsonObject
        val categories = common.getValue("\$defs").jsonObject.getValue("modifierCategory").jsonObject.getValue("enum")
        val names = categories.jsonArray.map { it.jsonPrimitive.content }
        val expected = ModifierCategory.entries.map { Json.encodeToString(ModifierCategory.serializer(), it).trim('"') }
        assertEquals(expected, names)
    }
}

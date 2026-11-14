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
 * Datenvalidierung: Alle Dateien laden, Referenzen, Nachbarschaftsgraph und Locale-Schluessel pruefen
 * sowie den vorhandenen Datenumfang (Schichten, Arten, Knochen, Qualitaetsstufen) sicherstellen.
 * Die Pruefung gegen die 3D-Modelle steht in [SpeciesModelFilesTest].
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
    fun everySpeciesReferencesExistingLayerAndHasConnectedGraph() {
        data.species.forEach { species ->
            assertTrue(species.layer in data.layersById, "Schicht fehlt fuer ${species.id}")
            assertTrue(species.isConnected(species.bones), "Graph von ${species.id} nicht zusammenhaengend")
            species.neighbors.flatten().forEach { assertTrue(it in species.bones, "Kante mit unbekanntem Knochen $it") }
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
    fun mvpHasOnlyQuaternary() {
        assertEquals(listOf("quaternary"), data.layers.map { it.id })
        assertEquals(listOf(0), data.layers.map { it.order })
    }

    @Test
    fun mvpHasOnlyCaveBear() {
        assertEquals(listOf("cave_bear"), data.species.map { it.id })
        assertEquals(40.0, data.species("cave_bear").rarityWeight)
        assertEquals("quaternary", data.species("cave_bear").layer)
    }

    @Test
    fun caveBearHasAllSingleBonesOfModel() {
        val bones = data.species("cave_bear").bones
        assertEquals(105, bones.size)
        assertTrue(listOf("occipital", "mandible_l", "mandible_r", "sternum", "forepaw_l", "caudal_8").all { it in bones })
    }

    @Test
    fun balanceContainsQualityTiers() {
        val b = data.balance
        assertEquals(listOf("fragment", "damaged", "good", "excellent", "pristine"), b.qualityTiers.map { it.id })
        assertEquals(listOf(0.0, 0.25, 0.5, 0.7, 0.9), b.qualityTiers.map { it.minQuality })
    }

    @Test
    fun balanceContainsFindParameters() {
        val finds = data.balance.finds
        assertTrue(finds.sizeWeights.isNotEmpty())
        assertEquals(1, finds.sizeWeights.minOf { it.size }, "Einzelknochen-Funde muessen moeglich sein")
        assertTrue(finds.baseQualityMin < finds.baseQualityMax)
        assertTrue(finds.boneQualityDeviation > 0.0)
    }

    @Test
    fun balanceContainsExcavationParameters() {
        val ex = data.balance.excavation
        assertTrue(ex.width >= 1 && ex.height >= 1)
        assertTrue(ex.depthMin in 1..ex.depthMax)
        assertTrue(ex.hintDepth in 1 until ex.depthMin)
        assertTrue(ex.findCountWeights.isNotEmpty())
        assertTrue(ex.placementAttempts >= 1)
    }

    @Test
    fun toolsContainShovelAndTrowel() {
        assertEquals(listOf("shovel", "trowel"), data.tools.map { it.id })
        assertTrue(data.tool("shovel").rough)
        assertTrue(data.tool("shovel").boneDamage > 0.0)
        assertEquals(false, data.tool("trowel").rough)
        assertEquals(0.0, data.tool("trowel").boneDamage)
        data.tools.forEach { assertTrue(it.removal <= data.balance.excavation.hintDepth, it.id) }
        assertEquals("Schaufel", TextProvider.fromGameData(data, "de").toolName("shovel"))
        assertEquals("Trowel", TextProvider.fromGameData(data, "en").toolName("trowel"))
    }

    // --- Der Validator erkennt kaputte Daten ---

    @Test
    fun detectsInvalidExcavation() {
        val errors = errorsOf(DataRepository.BALANCE) {
            it.replace("\"depthMin\": 60", "\"depthMin\": 120")
                .replace("\"placementAttempts\": 20", "\"placementAttempts\": 0")
                .replace("\"findSpacing\": 2", "\"findSpacing\": -1")
                .replace("{\"count\": 3, \"weight\": 40}", "{\"count\": 2, \"weight\": 0}")
                .replace("\"placeholderFootprint\": {\"width\": 4", "\"placeholderFootprint\": {\"width\": 40")
        }
        listOf(
            "excavation: depthMin > depthMax",
            "excavation: placementAttempts muss >= 1 sein",
            "excavation: findSpacing muss >= 0 sein",
            "excavation: findCountWeights.weight fuer Anzahl 2 muss > 0 sein",
            "excavation: findCountWeights.count 2 doppelt",
            "excavation: placeholderFootprint ist groesser als die Flaeche",
        ).forEach { expected -> assertTrue(errors.any { expected in it }, "$expected fehlt in $errors") }
    }

    @Test
    fun detectsDepthAboveStorageLimit() {
        val errors = errorsOf(DataRepository.BALANCE) { it.replace("\"depthMax\": 100", "\"depthMax\": 70000") }
        assertEquals(listOf("balance.json/excavation: depthMax muss <= 65535 sein"), errors)
    }

    @Test
    fun detectsHintDepthNotBelowMinimumDepth() {
        val errors = errorsOf(DataRepository.BALANCE) { it.replace("\"hintDepth\": 20", "\"hintDepth\": 60") }
        assertTrue(errors.any { "excavation: hintDepth muss kleiner als depthMin sein" in it }, errors.toString())
    }

    @Test
    fun detectsInvalidTools() {
        val errors = errorsOf(DataRepository.TOOLS) {
            it.replace("\"removal\": 20", "\"removal\": 25")
                .replace("\"boneDamage\": 0.08", "\"boneDamage\": 0.0")
                .replace("\"radius\": 1.5, \"rough\": false, \"boneDamage\": 0.0", "\"radius\": 0, \"rough\": false, \"boneDamage\": 0.1")
        }
        listOf(
            "tools.json/shovel: removal 25 ist groesser als hintDepth 20",
            "tools.json/shovel: grobes Werkzeug braucht boneDamage > 0",
            "tools.json/trowel: radius muss > 0 sein",
            "tools.json/trowel: nicht grobes Werkzeug muss boneDamage 0 haben",
        ).forEach { expected -> assertTrue(errors.any { expected in it }, "$expected fehlt in $errors") }
    }

    @Test
    fun detectsMissingToolName() {
        val errors = errorsOf(DataRepository.localeFile("en")) { it.replace(Regex("\\s*\"tool\\.trowel\\.name\": \"[^\"]*\","), "") }
        assertTrue(errors.any { "en.json: Schluessel 'tool.trowel.name' fehlt" in it }, errors.toString())
    }

    @Test
    fun rejectsMissingExcavationParameters() {
        val repo = patchedRepository(DataRepository.BALANCE) { it.replace(Regex(",\\s*\"placementAttempts\": [0-9]+"), "") }
        val e = assertThrows<DataValidationException> { repo.load() }
        assertTrue(e.errors.single().startsWith(DataRepository.BALANCE), e.errors.toString())
    }

    @Test
    fun detectsInvalidFindSizeWeights() {
        val errors = errorsOf(DataRepository.BALANCE) {
            it.replace("{\"size\": 1, \"weight\": 35}", "{\"size\": 0, \"weight\": 35}")
                .replace("{\"size\": 3, \"weight\": 17}", "{\"size\": 2, \"weight\": 0}")
        }
        assertTrue(errors.any { "finds: sizeWeights.size 0 muss >= 1 sein" in it }, errors.toString())
        assertTrue(errors.any { "finds: sizeWeights.weight fuer Groesse 2 muss > 0 sein" in it }, errors.toString())
        assertTrue(errors.any { "finds: sizeWeights.size 2 doppelt" in it }, errors.toString())
    }

    @Test
    fun detectsEmptyFindSizeWeights() {
        val errors = errorsOf(DataRepository.BALANCE) { it.replace(Regex("\"sizeWeights\": \\[[^\\]]*\\]"), "\"sizeWeights\": []") }
        assertTrue(errors.any { "finds: sizeWeights leer" in it }, errors.toString())
    }

    @Test
    fun detectsInvalidFindQualities() {
        val errors = errorsOf(DataRepository.BALANCE) {
            it.replace("\"baseQualityMin\": 0.4", "\"baseQualityMin\": 1.2")
                .replace("\"boneQualityDeviation\": 0.05", "\"boneQualityDeviation\": -0.1")
        }
        assertTrue(errors.any { "finds: baseQualityMin 1.2 liegt nicht in 0..1" in it }, errors.toString())
        assertTrue(errors.any { "finds: boneQualityDeviation -0.1 liegt nicht in 0..1" in it }, errors.toString())
        assertTrue(errors.any { "finds: baseQualityMin > baseQualityMax" in it }, errors.toString())
    }

    @Test
    fun rejectsMissingFindParameters() {
        val repo = patchedRepository(DataRepository.BALANCE) { it.replace(Regex(",\\s*\"boneQualityDeviation\": [0-9.]+"), "") }
        val e = assertThrows<DataValidationException> { repo.load() }
        assertTrue(e.errors.single().startsWith(DataRepository.BALANCE), e.errors.toString())
    }

    @Test
    fun detectsUnknownLayerReference() {
        val errors = errorsOf(DataRepository.SPECIES) {
            it.replaceFirst("\"layer\": \"quaternary\"", "\"layer\": \"jurassic\"")
        }
        assertTrue(errors.any { "unbekannte Schicht 'jurassic'" in it }, errors.toString())
    }

    @Test
    fun detectsEdgeWithUnknownBone() {
        val errors = errorsOf(DataRepository.SPECIES) { it.replaceFirst("[\"occipital\", \"sphenoid\"]", "[\"occipital\", \"tail\"]") }
        assertTrue(errors.any { "Kante [occipital, tail] mit unbekanntem Knochen 'tail'" in it }, errors.toString())
    }

    @Test
    fun detectsDisconnectedGraph() {
        // Brustbein nur ueber die Rippen 1 bis 9 angebunden: alle Kanten entfernen.
        val errors = errorsOf(DataRepository.SPECIES) { text ->
            text.replace(Regex(",?\\s*\\[\"sternum\", \"rib_\\d_[lr]\"\\]|\\[\"rib_\\d_[lr]\", \"sternum\"\\],?"), "")
        }
        assertEquals(listOf("species.json/cave_bear: Nachbarschaftsgraph ist nicht zusammenhaengend"), errors)
    }

    @Test
    fun detectsInvalidEdges() {
        val errors = errorsOf(DataRepository.SPECIES) {
            it.replaceFirst("\"neighbors\": [", "\"neighbors\": [[\"sternum\"], [\"sternum\", \"sternum\"], [\"sphenoid\", \"occipital\"],")
        }
        assertTrue(errors.any { "Kante [sternum] hat nicht genau zwei Knochen" in it }, errors.toString())
        assertTrue(errors.any { "Kante [sternum, sternum] verbindet einen Knochen mit sich selbst" in it }, errors.toString())
        assertTrue(errors.any { "Kante [occipital, sphenoid] doppelt" in it }, errors.toString())
    }

    @Test
    fun detectsDuplicateBone() {
        val errors = errorsOf(DataRepository.SPECIES) { it.replaceFirst("\"sphenoid\",", "\"sphenoid\", \"sphenoid\",") }
        assertTrue(errors.any { "Knochen 'sphenoid' doppelt" in it }, errors.toString())
    }

    @Test
    fun detectsMissingBoneName() {
        val errors = errorsOf(DataRepository.localeFile("de")) {
            it.replace(Regex("\\s*\"bone\\.cave_bear\\.sternum\\.name\": \"[^\"]*\","), "")
        }
        assertTrue(errors.any { "de.json: Schluessel 'bone.cave_bear.sternum.name' fehlt" in it }, errors.toString())
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
        val repo = patchedRepository(DataRepository.SPECIES) { it.replaceFirst("\"rarityWeight\"", "\"size\": 1, \"rarityWeight\"") }
        val e = assertThrows<DataValidationException> { repo.load() }
        assertTrue(e.errors.single().startsWith(DataRepository.SPECIES), e.errors.toString())
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
        val repo = DataRepository { name -> if (name == DataRepository.SPECIES) null else resource(DataRepository.RESOURCE_ROOT + name) }
        val e = assertThrows<DataValidationException> { repo.load() }
        assertEquals(listOf("species.json: Datei fehlt"), e.errors)
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
    fun mvpLayerHasGravelTexture() {
        assertEquals(listOf(LayerTexture.GRAVEL), data.layers.map { it.texture })
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

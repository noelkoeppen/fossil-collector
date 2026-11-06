package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.StatId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class TextProviderTest {

    private val locales = mapOf(
        "de" to mapOf("species.cave_bear.name" to "Höhlenbär", "bone.skull.name" to "Schädel", "only.de" to "nur DE"),
        "en" to mapOf("species.cave_bear.name" to "Cave bear", "bone.skull.name" to "Skull"),
    )

    @Test
    fun resolvesRequestedLanguage() {
        val en = TextProvider(locales, "en")
        assertEquals("Cave bear", en.speciesName("cave_bear"))
        assertEquals("Skull", en.boneName("skull"))
        assertEquals("Höhlenbär", TextProvider(locales, "de").speciesName("cave_bear"))
    }

    @Test
    fun missingKeyFallsBackToFallbackLanguageThenKey() {
        val en = TextProvider(locales, "en")
        assertEquals("nur DE", en.text("only.de"))
        assertEquals("missing.key", en.text("missing.key"))
    }

    @Test
    fun unknownLanguageUsesFallback() {
        assertEquals("Höhlenbär", TextProvider(locales, "fr").speciesName("cave_bear"))
    }

    @Test
    fun withLanguageSwitchesAndReusesSameLanguage() {
        val de = TextProvider(locales)
        assertEquals("de", de.language)
        assertSame(de, de.withLanguage("de"))
        assertEquals("Skull", de.withLanguage("en").boneName("skull"))
    }

    @Test
    fun milestoneNamesExistInBothLanguages() {
        // AP-25: Meilenstein-Toasts zeigen den Namen ueber TextProvider.milestoneName.
        val data = DataRepository().load()
        for (language in DataRepository.LANGUAGES) {
            val texts = TextProvider.fromGameData(data, language)
            data.milestones.forEach {
                assertEquals(data.locales.getValue(language).getValue(LocaleKeys.milestoneName(it.id)), texts.milestoneName(it.id))
            }
        }
    }

    @Test
    fun emptyProviderReturnsKeys() {
        assertEquals(LocaleKeys.layerName("neogene"), TextProvider.EMPTY.layerName("neogene"))
    }

    @Test
    fun realDataHasNamesForAllLayersSpeciesBonesAndTiersInBothLanguages() {
        val data = DataRepository().load()
        for (language in DataRepository.LANGUAGES) {
            val texts = TextProvider.fromGameData(data, language)
            data.layers.forEach { assertEquals(data.locales.getValue(language)[LocaleKeys.layerName(it.id)], texts.layerName(it.id)) }
            data.species.forEach { assertEquals(data.locales.getValue(language)[LocaleKeys.speciesName(it.id)], texts.speciesName(it.id)) }
            data.bones.forEach { assertEquals(data.locales.getValue(language)[LocaleKeys.boneName(it.id)], texts.boneName(it.id)) }
            data.balance.qualityTiers.forEach {
                assertEquals(data.locales.getValue(language)[LocaleKeys.qualityTierName(it.id)], texts.qualityTierName(it.id))
            }
        }
        assertEquals("Neogen", TextProvider.fromGameData(data, "de").layerName("neogene"))
        assertEquals("Neogene", TextProvider.fromGameData(data, "en").layerName("neogene"))
    }

    @Test
    fun statNamesExistForAllStatsInBothLanguages() {
        val data = DataRepository().load()
        for (language in DataRepository.LANGUAGES) {
            val texts = TextProvider.fromGameData(data, language)
            for (stat in StatId.entries) {
                val key = LocaleKeys.statName(stat)
                assertTrue(key in LocaleKeys.requiredKeys(data), "Pflichtschluessel $key")
                assertNotEquals(key, texts.statName(stat), "Text fuer $key in $language")
            }
        }
        assertEquals("stat.museum_income.name", LocaleKeys.statName(StatId.MUSEUM_INCOME))
        assertEquals("Museumseinnahmen", TextProvider.fromGameData(data, "de").statName(StatId.MUSEUM_INCOME))
        assertEquals("Museum income", TextProvider.fromGameData(data, "en").statName(StatId.MUSEUM_INCOME))
    }
}

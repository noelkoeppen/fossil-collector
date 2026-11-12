package de.noelkoeppen.fossilcollector.core.data

import de.noelkoeppen.fossilcollector.core.modifier.StatId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class TextProviderTest {

    private val locales = mapOf(
        "de" to mapOf("species.cave_bear.name" to "Höhlenbär", "bone.cave_bear.femur_l.name" to "Oberschenkel links", "only.de" to "nur DE"),
        "en" to mapOf("species.cave_bear.name" to "Cave bear", "bone.cave_bear.femur_l.name" to "Left femur"),
    )

    @Test
    fun resolvesRequestedLanguage() {
        val en = TextProvider(locales, "en")
        assertEquals("Cave bear", en.speciesName("cave_bear"))
        assertEquals("Left femur", en.boneName("cave_bear", "femur_l"))
        assertEquals("bone.giant_deer.femur_l.name", en.boneName("giant_deer", "femur_l"))
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
        assertEquals("Left femur", de.withLanguage("en").boneName("cave_bear", "femur_l"))
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
            data.species.forEach { species ->
                species.bones.forEach {
                    val expected = data.locales.getValue(language)[LocaleKeys.boneName(species.id, it)]
                    assertEquals(expected, texts.boneName(species.id, it))
                }
            }
            data.balance.qualityTiers.forEach {
                assertEquals(data.locales.getValue(language)[LocaleKeys.qualityTierName(it.id)], texts.qualityTierName(it.id))
            }
        }
        assertEquals("Quartär", TextProvider.fromGameData(data, "de").layerName("quaternary"))
        assertEquals("Oberschenkelknochen links", TextProvider.fromGameData(data, "de").boneName("cave_bear", "femur_l"))
        assertEquals("Left Femur", TextProvider.fromGameData(data, "en").boneName("cave_bear", "femur_l"))
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
        assertEquals("stat.sell_price.name", LocaleKeys.statName(StatId.SELL_PRICE))
        assertEquals("Verkaufspreis", TextProvider.fromGameData(data, "de").statName(StatId.SELL_PRICE))
        assertEquals("Sell price", TextProvider.fromGameData(data, "en").statName(StatId.SELL_PRICE))
    }
}

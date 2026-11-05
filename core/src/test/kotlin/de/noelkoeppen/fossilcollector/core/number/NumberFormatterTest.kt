package de.noelkoeppen.fossilcollector.core.number

import de.noelkoeppen.fossilcollector.core.number.NumberLanguage.DE
import de.noelkoeppen.fossilcollector.core.number.NumberLanguage.EN
import de.noelkoeppen.fossilcollector.core.number.NumberNotation.SCIENTIFIC
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class NumberFormatterTest {

    private fun de(value: Double) = NumberFormatter.format(GameNumber(value), DE)
    private fun en(value: Double) = NumberFormatter.format(GameNumber(value), EN)
    private fun deSci(value: Double) = NumberFormatter.format(GameNumber(value), DE, SCIENTIFIC)
    private fun enSci(value: Double) = NumberFormatter.format(GameNumber(value), EN, SCIENTIFIC)

    @ParameterizedTest(name = "{0} -> DE \"{1}\", EN \"{2}\"")
    @CsvSource(
        delimiter = '|',
        value = [
            "0.0               | 0          | 0",
            "-0.0              | 0          | 0",
            "0.4               | 0          | 0",
            "0.5               | 1          | 1",
            "7.0               | 7          | 7",
            "42.49             | 42         | 42",
            "999.0             | 999        | 999",
            "999.4             | 999        | 999",
            "999.6             | 1 K        | 1K",
            "1000.0            | 1 K        | 1K",
            "1500.0            | 1,5 K      | 1.5K",
            "1234.0            | 1,23 K     | 1.23K",
            "1235.0            | 1,24 K     | 1.24K",
            "12345.0           | 12,35 K    | 12.35K",
            "100000.0          | 100 K      | 100K",
            "999994.0          | 999,99 K   | 999.99K",
            "999995.0          | 1 Mio.     | 1M",
            "999999.0          | 1 Mio.     | 1M",
            "1000000.0         | 1 Mio.     | 1M",
            "1005000.0         | 1,01 Mio.  | 1.01M",
            "2300000.0         | 2,3 Mio.   | 2.3M",
            "4100000000.0      | 4,1 Mrd.   | 4.1B",
            "999999999.0       | 1 Mrd.     | 1B",
            "1200000000000.0   | 1,2 Bio.   | 1.2T",
            "999990000000000.0 | 999,99 Bio. | 999.99T",
            "999999999999999.0 | 1e15       | 1e15",
            "1.0E15            | 1e15       | 1e15",
            "1.5E15            | 1,5e15     | 1.5e15",
            "1.234E20          | 1,23e20    | 1.23e20",
            "9.999E20          | 1e21       | 1e21",
            "1.7976931348623157E308 | 1,8e308 | 1.8e308",
        ],
    )
    fun shortNotation(value: Double, expectedDe: String, expectedEn: String) {
        assertEquals(expectedDe, de(value))
        assertEquals(expectedEn, en(value))
    }

    @ParameterizedTest(name = "{0} -> DE \"{1}\", EN \"{2}\"")
    @CsvSource(
        delimiter = '|',
        value = [
            "-0.4          | 0         | 0",
            "-0.6          | -1        | -1",
            "-999.4        | -999      | -999",
            "-999.6        | -1 K      | -1K",
            "-1500.0       | -1,5 K    | -1.5K",
            "-999999.0     | -1 Mio.   | -1M",
            "-2.5E18       | -2,5e18   | -2.5e18",
        ],
    )
    fun negativeNumbers(value: Double, expectedDe: String, expectedEn: String) {
        assertEquals(expectedDe, de(value))
        assertEquals(expectedEn, en(value))
    }

    @ParameterizedTest(name = "{0} -> DE \"{1}\", EN \"{2}\"")
    @CsvSource(
        delimiter = '|',
        value = [
            "0.0           | 0        | 0",
            "999.4         | 999      | 999",
            "999.6         | 1e3      | 1e3",
            "1000.0        | 1e3      | 1e3",
            "1500.0        | 1,5e3    | 1.5e3",
            "999999.0      | 1e6      | 1e6",
            "1.5E9         | 1,5e9    | 1.5e9",
            "1.234E9       | 1,23e9   | 1.23e9",
            "9.996E9       | 1e10     | 1e10",
            "1.0E15        | 1e15     | 1e15",
            "-1.5E9        | -1,5e9   | -1.5e9",
        ],
    )
    fun scientificNotation(value: Double, expectedDe: String, expectedEn: String) {
        assertEquals(expectedDe, deSci(value))
        assertEquals(expectedEn, enSci(value))
    }

    @Test
    fun nonFiniteValues() {
        for (language in NumberLanguage.entries) {
            for (notation in NumberNotation.entries) {
                assertEquals("NaN", NumberFormatter.format(GameNumber(Double.NaN), language, notation))
                assertEquals("∞", NumberFormatter.format(GameNumber(Double.POSITIVE_INFINITY), language, notation))
                assertEquals("-∞", NumberFormatter.format(GameNumber(Double.NEGATIVE_INFINITY), language, notation))
            }
        }
    }

    @Test
    fun doubleOverloadMatchesGameNumber() {
        assertEquals(NumberFormatter.format(GameNumber(2.3e6), DE), NumberFormatter.format(2.3e6, DE))
    }

    @Test
    fun defaultNotationIsShort() {
        assertEquals("1,5 K", NumberFormatter.format(GameNumber(1500.0), DE))
    }
}

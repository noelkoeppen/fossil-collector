package de.noelkoeppen.fossilcollector.core.save

import de.noelkoeppen.fossilcollector.core.number.GameNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

/** Fehlerfaelle beim Laden, Speichern und Import (Kapitel 7.2). */
class SaveCodecErrorTest {

    private val codec = SaveCodec.DEFAULT

    private fun fixture(name: String) = SaveFixtures.read("errors/$name")

    @Test
    fun truncatedJsonIsInvalidJson() {
        assertThrows<SaveException.InvalidJson> { codec.decode(fixture("truncated.json")) }
    }

    @Test
    fun emptyOrNonObjectTextIsInvalidJson() {
        for (text in listOf("", "   ", "[]", "42", "\"save\"", "null", "kein json")) {
            assertThrows<SaveException.InvalidJson>("Text: '$text'") { codec.decode(text) }
        }
    }

    @Test
    fun tooNewSaveIsRejected() {
        val error = assertThrows<SaveException.UnsupportedVersion> { codec.decode(fixture("too_new.json")) }
        assertEquals(99, error.foundVersion)
        assertTrue(error.isTooNew)
    }

    @Test
    fun missingVersionIsRejected() {
        assertThrows<SaveException.MissingVersion> { codec.decode(fixture("missing_version.json")) }
    }

    @Test
    fun missingRequiredFieldIsInvalidContent() {
        assertThrows<SaveException.InvalidContent> { codec.decode(fixture("missing_run.json")) }
    }

    @Test
    fun invalidPieceKeyIsInvalidContent() {
        assertThrows<SaveException.InvalidContent> { codec.decode(fixture("invalid_piece_key.json")) }
    }

    @Test
    fun wrongTypeIsInvalidContent() {
        assertThrows<SaveException.InvalidContent> { codec.decode(fixture("wrong_type.json")) }
    }

    @Test
    fun unknownSettingValueIsInvalidContent() {
        val text = SaveFixtures.read("v1_minimal.json")
            .replace("\"run\"", "\"settings\": { \"language\": \"fr\" }, \"run\"")
        assertThrows<SaveException.InvalidContent> { codec.decode(text) }
    }

    @Test
    fun nonFiniteNumbersAreRejectedWhenLoading() {
        val text = SaveFixtures.read("v1_minimal.json").replace("\"money\": 0", "\"money\": NaN")
        assertThrows<SaveException> { codec.decode(text) }
    }

    @Test
    fun nonFiniteValuesCannotBeSaved() {
        val nan = SaveFixtures.fullV1.copy(run = SaveFixtures.fullV1.run.copy(money = GameNumber(Double.NaN)))
        assertThrows<SaveException.NotEncodable> { codec.encode(nan) }
        val inf = SaveFixtures.fullV1.copy(
            meta = SaveFixtures.fullV1.meta.copy(fame = GameNumber(Double.POSITIVE_INFINITY)),
        )
        assertThrows<SaveException.NotEncodable> { codec.exportBase64(inf) }
    }

    @Test
    fun invalidBase64IsInvalidEncoding() {
        for (text in listOf("", "  \n ", "!!!nicht base64!!!", "a", "ab=c")) {
            assertThrows<SaveException.InvalidEncoding>("Text: '$text'") { codec.importBase64(text) }
        }
    }

    @Test
    fun base64WithInvalidUtf8IsInvalidEncoding() {
        val bytes = byteArrayOf(0xC3.toByte(), 0x28, 0xFF.toByte())
        val text = Base64.getEncoder().encodeToString(bytes)
        assertThrows<SaveException.InvalidEncoding> { codec.importBase64(text) }
    }

    @Test
    fun base64OfBrokenSaveReportsUnderlyingError() {
        val broken = Base64.getEncoder().encodeToString(fixture("truncated.json").toByteArray())
        assertThrows<SaveException.InvalidJson> { codec.importBase64(broken) }
        val tooNew = Base64.getEncoder().encodeToString(fixture("too_new.json").toByteArray())
        assertThrows<SaveException.UnsupportedVersion> { codec.importBase64(tooNew) }
    }
}

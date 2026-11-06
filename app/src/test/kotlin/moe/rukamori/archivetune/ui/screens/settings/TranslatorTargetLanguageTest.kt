package moe.rukamori.archivetune.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslatorTargetLanguageTest {
    @Test
    fun `live target wins when both keys are stored`() {
        assertEquals("FRENCH", translatorTargetLanguage("FRENCH", "GERMAN"))
    }

    @Test
    fun `legacy AI target survives the merge until the live key is set`() {
        assertEquals("GERMAN", translatorTargetLanguage(null, "GERMAN"))
        assertEquals("ja", translatorTargetLanguage("", "ja"))
    }

    @Test
    fun `unset keys fall back to the historical default`() {
        assertEquals("ENGLISH", translatorTargetLanguage(null, null))
        assertEquals("ENGLISH", translatorTargetLanguage("", " "))
    }
}

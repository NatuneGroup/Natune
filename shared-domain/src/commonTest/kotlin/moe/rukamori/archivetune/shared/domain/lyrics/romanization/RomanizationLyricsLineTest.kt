package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RomanizationLyricsLineTest {
    private fun preferences(
        japanese: Boolean = false,
        korean: Boolean = false,
        chinese: Boolean = false,
        hindi: Boolean = false,
        other: Boolean = false,
        ai: Boolean = false,
    ) = LyricsRomanizationPreferences(japanese, korean, chinese, hindi, other, ai)

    @Test fun japaneseSelectionNeedsMetadataOrKanaNotInstalledData() {
        val jp = preferences(japanese = true)
        assertFalse(shouldRomanizeLyricsLine("東京", jp))
        assertTrue(shouldRomanizeLyricsLine("東京", jp, "ja"))
        assertTrue(shouldRomanizeLyricsLine("東京", jp, contextText = "東京の空"))
        assertFalse(shouldRomanizeLyricsLine("東京", jp, "zh-CN", "東京の空"))
        assertTrue(shouldRomanizeLyricsLine("中文\uD840\uDC00", preferences(chinese = true), "zh"))
        assertFalse(shouldRomanizeLyricsLine("日本語", preferences()))
    }

    @Test fun koreanNfcAndNfdGiveTheSameLineAndTheSourceIsUntouched() {
        val nfc = "\uD55C\uAE00"
        val nfd = "\u1112\u1161\u11AB\u1100\u1173\u11AF"
        assertEquals(romanizeKorean(nfc), romanizeKorean(nfd))
        assertEquals("hangeul", romanizeKorean(nfd))
        val line = SyncedLine(time = 1234L, text = nfd)
        assertEquals("hangeul", LyricsRomanizer().phoneticsForLine(line, preferences(korean = true)).line)
        assertEquals(nfd, line.text)
        assertEquals(1234L, line.time)
    }

    @Test fun hindiLineUsesTheHindiEngineAndKeepsTheSource() {
        val romanizer = LyricsRomanizer()
        assertEquals("namaste", romanizer.romanizeLyricsLine("नमस्ते", preferences(hindi = true)))
        assertNull(romanizer.romanizeLyricsLine("नमस्ते", preferences()))
    }

    @Test fun builtInEnginesRunOnlyWhenAiIsNotHandlingTheLine() {
        val line = SyncedLine(time = 1000L, text = "\uD55C")
        val romanizer = LyricsRomanizer()
        assertEquals("han", romanizer.phoneticsForLine(line, preferences(korean = true)).line)
        assertEquals(LyricsPhonetics(), romanizer.phoneticsForLine(line, preferences(korean = true, ai = true)))
    }

    @Test fun unavailableThrowingOrUnchangedGenericEngineKeepsOriginalWithoutDuplicatePhonetics() {
        val original = "العربية می\u200Cروم"
        val romanizer = LyricsRomanizer()
        assertEquals(original, romanizer.romanizeWithIcu(original) { it })
        assertEquals(original, romanizer.romanizeWithIcu(original) { throw IllegalStateException("unavailable") })
        assertNull(normalizeRomanizedText(original, original))
        assertNull(normalizeRomanizedText(original, " "))
        assertNull(normalizeRomanizedText(original, romanizer.romanizeWithIcu(original) { it }))
    }

    @Test fun commonCodeHasNoGenericTransliteratorSoOtherScriptsGetNoPhonetics() {
        val other = preferences(other = true)
        assertNull(LyricsRomanizer().romanizeWithIcu("Россия"))
        assertNull(LyricsRomanizer().romanizeLyricsLine("Россия", other))
        assertTrue(hasOtherRomanizableScript("Россия"))
        assertFalse(hasOtherRomanizableScript("Hello 中文"))
    }

    @Test fun providedRomanizationReplacesBuiltInOnlyForAMatchingEnabledLanguage() {
        val provided = SyncedLine(time = 0L, text = "한", providerRomanizedText = "han", providerRomanizedLanguage = "ko-KR")
        assertEquals("han", LyricsRomanizer().phoneticsForLine(provided, preferences(korean = true)).line)
        assertEquals(
            LyricsPhonetics(),
            LyricsRomanizer().phoneticsForLine(provided, preferences(japanese = true)),
        )
    }

    @Test fun hasRomanizableScriptIgnoresLatinOnlyLines() {
        assertTrue(hasRomanizableScript("한"))
        assertTrue(hasRomanizableScript("東京"))
        assertFalse(hasRomanizableScript("Hello world"))
        assertFalse(hasRomanizableScript("   "))
    }
}

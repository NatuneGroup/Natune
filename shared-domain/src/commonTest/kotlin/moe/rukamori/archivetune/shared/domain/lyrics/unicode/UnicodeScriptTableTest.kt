package moe.rukamori.archivetune.shared.domain.lyrics.unicode

import kotlin.test.Test
import kotlin.test.assertEquals

/** Spot checks against Unicode 15.0.0 Scripts.txt. The full JDK comparison lives in the jvmTest parity test. */
class UnicodeScriptTableTest {
    private fun assertScript(
        expected: UnicodeScript,
        codePoint: Int,
    ) {
        assertEquals(expected, UnicodeScripts.scriptOf(codePoint), "U+${codePoint.toString(16).uppercase()}")
    }

    @Test
    fun latinIncludesAsciiLettersAndAccentedLetters() {
        assertScript(UnicodeScript.LATIN, 'A'.code)
        assertScript(UnicodeScript.LATIN, 'z'.code)
        assertScript(UnicodeScript.LATIN, 0x00E9)
        assertScript(UnicodeScript.LATIN, 0x0250)
    }

    @Test
    fun greekAndCyrillicResolveToTheirOwnScripts() {
        assertScript(UnicodeScript.GREEK, 0x0391)
        assertScript(UnicodeScript.GREEK, 0x03B1)
        assertScript(UnicodeScript.CYRILLIC, 0x0416)
        assertScript(UnicodeScript.CYRILLIC, 0x0456)
    }

    @Test
    fun armenianHebrewAndArabicResolveToTheirOwnScripts() {
        assertScript(UnicodeScript.ARMENIAN, 0x0531)
        assertScript(UnicodeScript.HEBREW, 0x05D0)
        assertScript(UnicodeScript.HEBREW, 0x05EA)
        assertScript(UnicodeScript.ARABIC, 0x0627)
        assertScript(UnicodeScript.ARABIC, 0x0645)
    }

    @Test
    fun indicAndThaiResolveToTheirOwnScripts() {
        assertScript(UnicodeScript.DEVANAGARI, 0x0915)
        assertScript(UnicodeScript.DEVANAGARI, 0x093F)
        assertScript(UnicodeScript.THAI, 0x0E01)
        assertScript(UnicodeScript.THAI, 0x0E33)
    }

    @Test
    fun hangulHiraganaKatakanaAndHanResolveToTheirOwnScripts() {
        assertScript(UnicodeScript.HANGUL, 0x1100)
        assertScript(UnicodeScript.HANGUL, 0xAC00)
        assertScript(UnicodeScript.HANGUL, 0xD7A3)
        assertScript(UnicodeScript.HIRAGANA, 0x3042)
        assertScript(UnicodeScript.KATAKANA, 0x30A2)
        assertScript(UnicodeScript.KATAKANA, 0x30F3)
        assertScript(UnicodeScript.HAN, 0x4E00)
        assertScript(UnicodeScript.HAN, 0x5B57)
        assertScript(UnicodeScript.HAN, 0x20000)
    }

    @Test
    fun digitsPunctuationSymbolsAndEmojiAreCommon() {
        assertScript(UnicodeScript.COMMON, '0'.code)
        assertScript(UnicodeScript.COMMON, '9'.code)
        assertScript(UnicodeScript.COMMON, ' '.code)
        assertScript(UnicodeScript.COMMON, '!'.code)
        assertScript(UnicodeScript.COMMON, 0x20AC)
        assertScript(UnicodeScript.COMMON, 0x1F600)
        assertScript(UnicodeScript.COMMON, 0x1F3FB)
        // Katakana-Hiragana prolonged sound mark: shared by both kana scripts, so Common.
        assertScript(UnicodeScript.COMMON, 0x30FC)
    }

    @Test
    fun combiningMarksAndJoinersAreInherited() {
        assertScript(UnicodeScript.INHERITED, 0x0301)
        assertScript(UnicodeScript.INHERITED, 0x200C)
        assertScript(UnicodeScript.INHERITED, 0x200D)
        assertScript(UnicodeScript.INHERITED, 0xFE0F)
        assertScript(UnicodeScript.INHERITED, 0x20E3)
    }

    @Test
    fun unassignedAndNonCharacterCodePointsAreUnknown() {
        assertScript(UnicodeScript.UNKNOWN, 0x0378)
        assertScript(UnicodeScript.UNKNOWN, 0x05FF)
        assertScript(UnicodeScript.UNKNOWN, 0xFFFF)
        assertScript(UnicodeScript.UNKNOWN, 0x10FFFF)
    }

    @Test
    fun surrogateHalvesAreUnknownBecauseScriptsTxtDoesNotListThem() {
        assertScript(UnicodeScript.UNKNOWN, 0xD800)
        assertScript(UnicodeScript.UNKNOWN, 0xDFFF)
    }

    @Test
    fun outOfRangeValuesAreUnknownRatherThanThrowing() {
        assertScript(UnicodeScript.UNKNOWN, -1)
        assertScript(UnicodeScript.UNKNOWN, 0x110000)
    }

    @Test
    fun enumOrdinalsStayAtTheHeadOfTheSpecialValues() {
        assertEquals(0, UnicodeScript.UNKNOWN.ordinal)
        assertEquals(1, UnicodeScript.COMMON.ordinal)
        assertEquals(2, UnicodeScript.INHERITED.ordinal)
    }
}

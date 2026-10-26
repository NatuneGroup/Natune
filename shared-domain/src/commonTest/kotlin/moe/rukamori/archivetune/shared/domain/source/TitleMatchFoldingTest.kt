package moe.rukamori.archivetune.shared.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the title folding that [TitleMatch] compares with. The Android folding runs on `java.text.Normalizer` and the JDK
 * regex word boundary, which is ASCII-only, so these cases hold the common Kotlin replacements to the same output.
 */
class TitleMatchFoldingTest {
    @Test
    fun dropsDiacriticsAndFeatureAndVersionWords() {
        assertEquals("deja vu", TitleMatch.normalize("Déjà Vu (feat. Guest)"))
        assertEquals("stay 2009", TitleMatch.normalize("Stay (Remastered 2009)"))
        assertEquals("song", TitleMatch.normalize("Song featuring X"))
        assertEquals("song", TitleMatch.normalize("Song ft. X"))
    }

    @Test
    fun decomposedAndPrecomposedFormsFoldToTheSameTitle() {
        assertEquals(TitleMatch.normalize("Café"), TitleMatch.normalize("Cafe\u0301"))
        assertEquals("cafe", TitleMatch.normalize("Cafe\u0301"))
    }

    @Test
    fun hangulSyllablesFoldToTheirJamo() {
        assertEquals("\u1112\u1161\u11AB\u1100\u116E\u11A8", TitleMatch.normalize("한국"))
    }

    @Test
    fun supplementaryPlaneCharactersFollowTheirUnicodeClasses() {
        // CJK compatibility ideograph U+2F800 has the canonical decomposition U+4E3D.
        assertEquals("\u4E3D", TitleMatch.normalize("\uD87E\uDC00"))
        // CJK Unified Ideograph Extension B (U+20000) is a letter and stays.
        assertEquals("a\uD840\uDC00b", TitleMatch.normalize("a\uD840\uDC00b"))
        // Variation selector-17 (U+E0100) is a non-spacing mark and is dropped.
        assertEquals("ab", TitleMatch.normalize("a\uDB40\uDD00b"))
    }

    @Test
    fun everyCharacterThatIsNotALetterOrDigitSeparatesWords() {
        assertEquals("song name", TitleMatch.normalize("Song \uD83C\uDFB5 Name"))
        assertEquals("hello world", TitleMatch.normalize("Hello\uFF1AWorld"))
        assertEquals("track", TitleMatch.normalize("Track_feat_guest"))
        assertEquals("", TitleMatch.normalize("!!! ???"))
    }

    @Test
    fun featureWordsNeedAsciiWordBoundaries() {
        assertEquals("夜", TitleMatch.normalize("夜feat Guest"))
        assertEquals("1feat", TitleMatch.normalize("1feat"))
        assertEquals("xfeat", TitleMatch.normalize("xfeat"))
    }

    @Test
    fun missingTitleFoldsToEmpty() {
        assertEquals("", TitleMatch.normalize(null))
        assertEquals("", TitleMatch.normalize("   "))
    }
}

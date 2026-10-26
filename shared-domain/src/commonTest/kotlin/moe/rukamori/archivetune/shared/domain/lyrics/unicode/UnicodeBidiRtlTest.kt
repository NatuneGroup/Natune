/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Truth table for the Android `LyricsUtils.isRtlText` first-strong-character rule. */
class UnicodeBidiRtlTest {
    private val hebrew = "\u05E9\u05DC\u05D5\u05DD" // שלום
    private val arabic = "\u0645\u0631\u062D\u0628\u0627" // مرحبا
    private val japanese = "\u65E5\u672C\u8A9E" // 日本語
    private val kana = "\u304B\u306A" // かな

    @Test
    fun rightToLeftScriptsAreRtl() {
        assertTrue(UnicodeBidi.isRtlText(hebrew))
        assertTrue(UnicodeBidi.isRtlText(arabic))
    }

    @Test
    fun leadingNeutralsDoNotDecideDirection() {
        assertTrue(UnicodeBidi.isRtlText("123 $hebrew"))
        assertTrue(UnicodeBidi.isRtlText("\"$hebrew\""))
        assertTrue(UnicodeBidi.isRtlText("!? $arabic"))
    }

    @Test
    fun firstStrongCharacterWinsInMixedLines() {
        assertTrue(UnicodeBidi.isRtlText("$hebrew hello"))
        assertFalse(UnicodeBidi.isRtlText("hello $hebrew"))
        assertFalse(UnicodeBidi.isRtlText("$japanese $arabic"))
        assertTrue(UnicodeBidi.isRtlText("$arabic 日本"))
    }

    @Test
    fun latinJapaneseAndEmptyTextAreLtr() {
        assertFalse(UnicodeBidi.isRtlText("hello"))
        assertFalse(UnicodeBidi.isRtlText(japanese))
        assertFalse(UnicodeBidi.isRtlText(kana))
        assertFalse(UnicodeBidi.isRtlText(""))
    }

    @Test
    fun textWithNoStrongCharacterIsLtr() {
        assertFalse(UnicodeBidi.isRtlText("123 !?"))
        assertFalse(UnicodeBidi.isRtlText("\u0661\u0662\u0663")) // Arabic-Indic digits: AN, not strong
        assertFalse(UnicodeBidi.isRtlText("\u064B")) // Arabic fathatan alone: NSM
        assertFalse(UnicodeBidi.isRtlText("\uD83D\uDE00")) // emoji: ON
    }

    @Test
    fun embeddingControlsCountAsTheirStrongDirection() {
        assertTrue(UnicodeBidi.isRtlText("\u202B"))
        assertFalse(UnicodeBidi.isRtlText("\u202A"))
        assertTrue(UnicodeBidi.isRtlText("\uD83D\uDE00$hebrew"))
    }

    @Test
    fun unassignedHebrewBlockCodePointIsSkippedNotRtl() {
        // U+05FF lies in the Hebrew block where @missing defaults say R, but it is unassigned, so
        // Character.getDirectionality reports undefined and Android never treated it as strong.
        assertFalse(UnicodeBidi.isRtlText("\u05FF"))
        assertFalse(UnicodeBidi.isRtlText("\u05FFa"))
        assertTrue(UnicodeBidi.isRtlText("\u05FF$hebrew"))
    }

    @Test
    fun strongClassPredicatesMatchTheTruthTable() {
        assertTrue(UnicodeBidi.isStrongRightToLeft(0x05D0))
        assertTrue(UnicodeBidi.isStrongRightToLeft(0x0627))
        assertTrue(UnicodeBidi.isStrongRightToLeft(0x202B))
        assertFalse(UnicodeBidi.isStrongRightToLeft(0x0041))
        assertFalse(UnicodeBidi.isStrongRightToLeft(0x0661))
        assertTrue(UnicodeBidi.isStrongLeftToRight(0x0041))
        assertTrue(UnicodeBidi.isStrongLeftToRight(0x202D))
        assertFalse(UnicodeBidi.isStrongLeftToRight(0x0661))
    }
}

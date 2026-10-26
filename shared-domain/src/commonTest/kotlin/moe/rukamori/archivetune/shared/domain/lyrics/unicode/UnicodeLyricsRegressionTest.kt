/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.unicode

import moe.rukamori.archivetune.shared.domain.lyrics.LyricsDetection
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsNormalizer
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port of the Android `LyricsUnicodeRegressionTest` assertions that do not need romanisation. The
 * Android-only pieces (the AI TTML document rebuild and the wrapping-unit helper) are not ported here:
 * the TTML reader is covered through [LyricsParser.parseTtml] and the wrapping unit is the grapheme cluster.
 */
class UnicodeLyricsRegressionTest {
    private val samples = listOf(
        "café e\u0301", "日本語かな", "中文\uD840\uDC00", "한글 한",
        "می\u200Cروم اردو العربية", "Українська", "क्\u200Dष", "ไทย\u200Bเพลง",
        "👩\u200D🎤 🎤", "123 العربية Latin ٤٥",
    )

    @Test
    fun plainAndLrcPreserveSemanticCodePointsAndTimestamps() {
        samples.forEach { text ->
            assertEquals(text, LyricsNormalizer.normalize("\uFEFF$text"))
            assertEquals(text, LyricsDetection.displayText(text))
            val parsed = LyricsParser.parse("[01:02.345]$text").single()
            assertEquals(text, parsed.text)
            assertEquals(62_345L, parsed.time)
            assertNull(parsed.words)
            assertEquals(text, LyricsDetection.displayText("[01:02.345]$text"))
        }
    }

    @Test
    fun everySampleSegmentsIntoClustersThatReassembleExactly() {
        samples.forEach { text ->
            assertEquals(text, GraphemeClusterSegmenter.segment(text).joinToString(""))
        }
    }

    @Test
    fun sampleDirectionFollowsTheFirstStrongCharacter() {
        val rtl = setOf(
            "می\u200Cروم اردو العربية",
            "123 العربية Latin ٤٥",
        )
        samples.forEach { text ->
            assertEquals(text in rtl, UnicodeBidi.isRtlText(text), text)
        }
    }

    @Test
    fun enhancedLrcKeepsJoinersGraphemesAndSourceWordTimes() {
        val first = "ک\u200Cک क्\u200Dष e\u0301 "
        val second = "👩\u200D🎤\u200B\uD840\uDC00"
        val entry = LyricsParser.parse("[00:01.000]<00:01.000>$first<00:02.250>$second").single()
        assertEquals(first + second, entry.text)
        val words = requireNotNull(entry.words)
        assertEquals(listOf(first, second), words.map { it.text })
        assertEquals(listOf(1.0, 2.25), words.map { it.startTime })
        assertEquals(listOf(2.25, 2.85), words.map { it.endTime })
        assertEquals(1_000L, entry.time)
    }

    @Test
    fun millisecondYrcFramingPreservesTextAndLineTime() {
        val text = "می\u200Cروم👩\u200D🎤\u200B\uD840\uDC00"
        val entry = LyricsParser.parse("[1200,900]${text}e\u0301").single()
        assertEquals(1_200L, entry.time)
        assertEquals(text + "e\u0301", entry.text)
    }

    @Test
    fun qrcFramingKeepsOriginalTimedFragments() {
        val text = "ک\u200Cک👩\u200D🎤\u200B\uD840\uDC00"
        val raw = "[1000,2000]$text(1000,800)e\u0301(1800,1200)"
        val entry = LyricsParser.parse(raw).single()
        assertEquals(1_000L, entry.time)
        assertEquals(text + "e\u0301", entry.text)
        val words = requireNotNull(entry.words)
        assertEquals(listOf(text, "e\u0301"), words.map { it.text })
        assertEquals(listOf(1.0, 1.8), words.map { it.startTime })
        assertEquals(listOf(1.8, 3.0), words.map { it.endTime })
    }

    @Test
    fun ttmlFramingKeepsUnicodeAndRealTimingAttributes() {
        val text = "می\u200Cروم क्\u200Dष 👩\u200D🎤\u200B\uD840\uDC00 e\u0301"
        val raw = """<tt xmlns="http://www.w3.org/ns/ttml" xml:lang="es"><body><div>""" +
            """<p begin="1.25s" end="3.5s"><span begin="1.25s" end="2.5s">$text</span></p>""" +
            """</div></body></tt>"""
        val line = LyricsParser.parseTtml(raw).single()
        assertEquals(text, line.text)
        assertEquals(1_250L, line.time)
        val words = requireNotNull(line.words)
        assertEquals(listOf(text), words.map { it.text })
        assertEquals(listOf(1.25, 2.5), listOf(words.single().startTime, words.single().endTime))
    }

    @Test
    fun unicodeSampleWithoutTimestampsIsNotMistakenForSyncedLyrics() {
        val text = "👩\u200D🎤 और सुनो"
        assertFalse(LyricsDetection.isLineSyncedLrc(text))
        assertTrue(LyricsDetection.displayText(text).contains("\u200D"))
    }
}

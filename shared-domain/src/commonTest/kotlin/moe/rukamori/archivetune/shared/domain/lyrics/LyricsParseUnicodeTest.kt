/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ported parse-side parts of the Android Unicode regression suite. */
class LyricsParseUnicodeTest {
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
    fun payloadDecoderHandlesCompleteDecimalHexScalarsAndInvalidLiterals() {
        val values = listOf(0, 0xD7FF, 0xE000, 0xFFFF, 0x10000, 127908, 131072, 1000000, 0x10FFFF)
        values.forEach { cp ->
            val expected = codePointToString(cp)
            assertEquals(expected, LyricsPayload.decodeEntities("&#$cp;"))
            assertEquals(expected, LyricsPayload.decodeEntities("&#x${cp.toString(16)};"))
        }
        assertEquals(
            codePointToString(127908) + codePointToString(131072) + codePointToString(0x10FFFF),
            LyricsPayload.decodeEntities("&#127908;&#131072;&#x10FFFF;"),
        )
        listOf(
            "&#55296;", "&#57343;", "&#xD800;", "&#xDFFF;", "&#1114112;", "&#x110000;",
            "&#999999999999999999999;", "&#xFFFFFFFFFFFFFFFF;", "&#-1;", "&#xZZ;", "&#;", "&#127908",
        ).forEach {
            assertEquals(it, LyricsPayload.decodeEntities(it))
        }
    }

    @Test
    fun namedEntitiesDecodeInTheDocumentedOrder() {
        assertEquals("<a & \"b\" 'c' d>", LyricsPayload.decodeEntities("&lt;a &amp; &quot;b&quot; &apos;c&apos; d&gt;"))
        assertEquals("a b", LyricsPayload.decodeEntities("a&nbsp;b"))
    }

    @Test
    fun actualPayloadIngestionDecodesSupplementaryTextWithoutChangingLrcTiming() {
        val text = "🎤\uD840\uDC00👩\u200D🎤می\u200Cروم"
        val raw = "[01:02.345]&#127908;&#131072;👩\u200D🎤می\u200Cروم"

        val decoded = requireNotNull(LyricsPayload.toLyrics(raw))
        val entry = LyricsParser.parse(decoded).single()
        assertEquals(62_345L, entry.time)
        assertEquals(text, entry.text)
        assertEquals(text, LyricsPayload.toLyrics(raw.substringAfter(']')))
        assertEquals(text, LyricsPayload.toLyrics(text))
        assertEquals(text, LyricsPayload.toLyrics("""{"lyrics":"${raw.substringAfter(']')}"}"""))
    }
}

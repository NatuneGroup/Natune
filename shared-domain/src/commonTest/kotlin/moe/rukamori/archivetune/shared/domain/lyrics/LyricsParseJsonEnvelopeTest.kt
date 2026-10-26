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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cached provider bodies can still be JSON envelopes around TTML; reads must unwrap them. */
class LyricsParseJsonEnvelopeTest {
    private val ttml =
        """<tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="0.0" end="1.0">Hello</p></div></body></tt>"""

    private val envelope =
        """{"lyrics":"<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div><p begin=\"0.0\" end=\"1.0\">Hello</p></div></body></tt>"}"""

    @Test
    fun jsonEnvelopeUnwrapsToTtml() {
        assertEquals(ttml, LyricsNormalizer.normalize(envelope))
    }

    @Test
    fun htmlEscapedTtmlInsideEnvelopeUnwrapsThenUnescapes() {
        val escaped =
            """{"lyrics":"&lt;tt xmlns=&quot;http://www.w3.org/ns/ttml&quot;&gt;&lt;body&gt;&lt;div&gt;&lt;p begin=&quot;0.0&quot; end=&quot;1.0&quot;&gt;Hello&lt;/p&gt;&lt;/div&gt;&lt;/body&gt;&lt;/tt&gt;"}"""

        assertEquals(ttml, LyricsNormalizer.normalize(escaped))
    }

    @Test
    fun plainTtmlIsUnchanged() {
        assertEquals(ttml, LyricsNormalizer.normalize(ttml))
    }

    @Test
    fun lrcStartingWithBracketIsUnchanged() {
        val lrc = "[00:12.00]First line\n[00:15.50]Second line"

        assertEquals(lrc, LyricsNormalizer.normalize(lrc))
    }

    @Test
    fun malformedJsonReturnsOriginal() {
        val malformed = """{"lyrics":"""

        assertEquals(malformed, LyricsNormalizer.normalize(malformed))
    }

    @Test
    fun errorEnvelopeReturnsOriginalWithoutThrowing() {
        val error = """{"error":"not found"}"""

        assertEquals(error, LyricsNormalizer.normalize(error))
    }

    @Test
    fun jsonQuotingTheTtmlNamespaceIsNotATtmlDocument() {
        assertFalse(LyricsPayload.isTtmlDocument(envelope))
        assertFalse(LyricsPayload.isTtmlDocument("""{"ns":"http://www.w3.org/ns/ttml"}"""))
    }

    @Test
    fun xmlLeadingTtmlIsATtmlDocument() {
        assertTrue(LyricsPayload.isTtmlDocument(ttml))
        assertTrue(LyricsPayload.isTtmlDocument("""<?xml version="1.0"?><tt xmlns="http://www.w3.org/ns/ttml"></tt>"""))
    }

    @Test
    fun toLyricsReturnsInnerTtmlForEnvelopeQuotingNamespace() {
        assertEquals(ttml, LyricsPayload.toLyrics(envelope))
    }

    @Test
    fun unwrapRejectsNullTrueFalseNumbersAndErrorJson() {
        listOf("null", "true", "false", "42", """{"error":"not found"}""").forEach { raw ->
            assertNull(LyricsPayload.unwrap(raw), raw)
            assertNull(LyricsPayload.toLyrics(raw), raw)
        }
    }

    @Test
    fun unwrapReadsTheLyricsFieldOfAnEnvelope() {
        assertEquals("[00:01.00]Hi", LyricsPayload.unwrap("""{"lyrics":"[00:01.00]Hi"}"""))
    }

    @Test
    fun parseJsonReturnsNullForBrokenJson() {
        assertNull(LyricsPayload.parseJson("""{"lyrics":"""))
        assertTrue(LyricsPayload.parseJson("""{"lyrics":"x"}""") != null)
    }
}

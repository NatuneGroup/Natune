package moe.rukamori.archivetune.shared.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Lyrics bodies come from third-party providers, so the XML reader must fail closed. */
class LyricsParseSecurityTest {
    private val ttmlNamespace = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>"
    private val ttmlTail = "</div></body></tt>"

    @Test
    fun doctypeDeclarationYieldsNoLinesAndNeverResolvesTheEntity() {
        val xxe =
            """
            <?xml version="1.0"?>
            <!DOCTYPE tt [<!ENTITY xxe "SECRET-PAYLOAD">]>
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="0.000" end="1.000">&xxe;</p></div></body></tt>
            """.trimIndent()

        assertTrue(LyricsParser.parseTtml(xxe).isEmpty())
        assertTrue(LyricsParser.parse(xxe).none { it.text.contains("SECRET-PAYLOAD") })
    }

    @Test
    fun undeclaredEntityIsRejectedWithoutBeingResolved() {
        val lines = LyricsParser.parseTtml("$ttmlNamespace<p begin=\"0.000\" end=\"1.000\">&xxe;</p>$ttmlTail")

        assertTrue(lines.isEmpty())
    }

    @Test
    fun deeplyNestedTtmlIsRejected() {
        val deep = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body>" + "<div>".repeat(200) +
            "<p begin=\"0.000\" end=\"1.000\">Deep</p>" + "</div>".repeat(200) + "</body></tt>"

        assertTrue(LyricsParser.parseTtml(deep).isEmpty())
    }

    @Test
    fun oversizedTtmlIsRejected() {
        val oversized = ttmlNamespace + "<p begin=\"0.000\" end=\"1.000\">" + "a".repeat(2_000_000) + "</p>" + ttmlTail

        assertTrue(LyricsParser.parseTtml(oversized).isEmpty())
    }

    @Test
    fun angleBracketsInPlainTextStayLiteral() {
        val lrc = "[00:01.00]I <3 you"

        assertEquals("I <3 you", LyricsParser.parse(lrc).single().text)
        assertEquals("I <3 you", LyricsDetection.displayText("I <3 you"))
        assertFalse(LyricsDetection.hasWordSyncedLyrics("I <3 you"))
        assertFalse(LyricsDetection.isTtml("I <3 you"))
    }
}

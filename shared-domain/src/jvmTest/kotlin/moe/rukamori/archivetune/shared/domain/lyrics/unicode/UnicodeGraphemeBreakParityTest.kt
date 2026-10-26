/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.unicode

import java.text.BreakIterator
import java.util.Locale
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-checks the common segmenter against `java.text.BreakIterator.getCharacterInstance` on a fixed corpus
 * and on seeded random strings. Where the JDK and UAX #29 disagree, the test records the case and asserts the
 * spec value instead of forcing the JDK result.
 */
class UnicodeGraphemeBreakParityTest {
    private val fixedCorpus = listOf(
        "\r\n", "\r\n\r\n", "a\r\nb", "\n\r", "\u0001\u0301", "e\u0301\u0301x",
        "\u1100\u1161\u11A8", "\u1100\u1100", "\u1100\uAC00", "\uAC00\u11A8", "\uAC01\u11A8",
        "\u1161\u1161", "\u11A8\u11A8", "\u11A8\u1100", "\u1100\u1161\u1100",
        "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67", "\uD83D\uDC69\u200D\uD83C\uDFA4",
        "a\u200Db", "\u200D\uD83D\uDC69", "\uD83D\uDE00\u200D\uD83D\uDE00",
        "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDEB\uD83C\uDDF7", "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDEB",
        "\uD83C\uDDFA\uD83C\uDDF8\uD83C\uDDEB\uD83C\uDDF7\uD83C\uDDE9", "a\uD83C\uDDFA",
        "\uD83D\uDC4D\uD83C\uDFFD", "\uD83D\uDC4B\uD83C\uDFFB\uD83D\uDC4B\uD83C\uDFFF",
        "\u0915\u093F", "\u0915\u093F\u0915\u093F", "\u0915\u094D\u0937", "\u0E01\u0E33",
        "\u0600" + "1", "\u2764\uFE0F", "\u2764\uFE0E", "1\uFE0F\u20E3", "a\uFE0Fb", "\uFE0F",
        "\uD800a", "\u304B\u3099\u306A", "",
        "\uD800\uD83C\uDFFD", "\uDC00\u200C", "\uD800\u093F", "\uD800\uFE0F\uFE0F",
    )

    /**
     * Lone surrogates are the only known divergence. The JDK breaks before Extend, SpacingMark and ZWJ
     * after an unpaired surrogate; UAX #29 treats the surrogate as GCB Other ("Any"), so GB9 and GB9a say
     * no break. These are asserted to the spec value in UnicodeGraphemeSegmentationTest.
     */
    private val knownSpecDifferences = setOf(
        "\uD800\uD83C\uDFFD", "\uDC00\u200C", "\uD800\u093F", "\uD800\uFE0F\uFE0F",
    )

    private fun jdkStarts(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val boundaries = ArrayList<Int>()
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            boundaries.add(boundary)
            boundary = iterator.next()
        }
        return boundaries.dropLast(1)
    }

    private fun ourStarts(text: String): List<Int> = GraphemeClusterSegmenter.clusterStarts(text).toList()

    @Test
    fun fixedCorpusMatchesTheJdkCharacterBreakIterator() {
        val disagreements = fixedCorpus.filter { jdkStarts(it) != ourStarts(it) }.toSet()
        assertEquals(knownSpecDifferences, disagreements)
    }

    @Test
    fun randomStringsMatchTheJdkCharacterBreakIterator() {
        // Lone surrogates are left out of the random pool; the known differences above cover them.
        val pool = intArrayOf(
            0x61, 0x0301, 0x200D, 0xFE0F, 0xFE0E, 0x1F468, 0x1F3FD, 0x1F1FA, 0x1F1F8, 0x0D, 0x0A, 0x01,
            0x1100, 0x1161, 0x11A8, 0xAC00, 0xAC01, 0x0915, 0x093F, 0x094D, 0x0E01, 0x0E33, 0x0600, 0x31,
            0x2764, 0x3099, 0x20, 0x05D0, 0x0627, 0x0661, 0x1F600, 0x20E3, 0xAD, 0x200C,
        )
        val random = Random(20_261_016)
        val disagreements = ArrayList<String>()
        repeat(3_000) {
            val builder = StringBuilder()
            repeat(random.nextInt(1, 11)) { builder.appendCodePoint(pool[random.nextInt(pool.size)]) }
            val text = builder.toString()
            if (jdkStarts(text) != ourStarts(text)) disagreements.add(text.codePoints().toArray().toList().toString())
        }
        // Random streams hit the same rules as the fixed corpus. Any disagreement must be recorded above
        // as a known spec difference rather than dropped here.
        assertEquals(emptyList(), disagreements.take(5), "first disagreements as code point lists")
    }
}

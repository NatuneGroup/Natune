package moe.rukamori.archivetune.ui.component

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Enhanced LRC / TTML can split ONE word into several syllable-level tokens ("a" + "gain", "mo" +
 * "ment"). Inventing a space between every pair of Latin syllables rendered "burst a gain" and "mo
 * ment"; only the whitespace the source itself carries may survive. The karaoke sweep clips against
 * the ranges from the same join, so adjacent syllables of one word must be contiguous slices.
 */
class LyricsSyllableJoinTest {
    private fun syllable(text: String, start: Int, end: Int) =
        KaraokeSyllable(content = text, start = start, end = end, phonetic = null)

    @Test
    fun syllablesOfOneWordStayOneWord() {
        val joined =
            listOf(
                    syllable("a", 1_000, 1_120),
                    syllable("gain", 1_120, 1_400),
                )
                .joinSyllables()

        assertEquals("again", joined.text)
        // No gap was inserted: the sweep crosses "a" -> "gain" without a hole.
        assertEquals(listOf(0..0, 1..4), joined.ranges)
        // The second range begins exactly where the first ends: no gap for the sweep to jump.
        assertEquals(joined.ranges[1]!!.first, joined.ranges[0]!!.last + 1)
    }

    @Test
    fun anotherSplitWordKeepsItsOwnTimingButNoVisualGap() {
        val syllables =
            listOf(
                syllable("mo", 2_000, 2_150),
                syllable("ment", 2_150, 2_600),
            )
        val joined = syllables.joinSyllables()

        assertEquals("moment", joined.text)
        assertEquals("moment", syllables.joinSyllableContents())
        // Per-syllable timing is kept: two ranges, two distinct (start, end) spans on the source.
        assertEquals(2, joined.ranges.size)
        assertEquals(listOf(2_000, 2_150), syllables.map { it.start })
        assertEquals(listOf(2_150, 2_600), syllables.map { it.end })
    }

    @Test
    fun sourceWhitespaceIsPreservedVerbatim() {
        // Word-stamped providers put the separator on the word that carries it.
        val joined =
            listOf(
                    syllable("burst ", 1_000, 1_300),
                    syllable("a", 1_300, 1_380),
                    syllable("gain", 1_380, 1_700),
                    syllable(" into ", 1_700, 1_900),
                    syllable("view", 1_900, 2_400),
                )
                .joinSyllables()

        assertEquals("burst again into view", joined.text)
        // The literal spaces are part of the ranges, so they render where the source put them.
        assertEquals("burst ", joined.text.substring(joined.ranges[0]!!))
        assertEquals(' ', joined.text[5])
        assertEquals(' ', joined.text[11])
        // "a" + "gain" stayed one word: the second range begins right after the first ends, no gap.
        assertEquals(joined.ranges[1]!!.first, "burst ".length)
        assertEquals(joined.ranges[2]!!.first, joined.ranges[1]!!.last + 1)
    }

    @Test
    fun compactCjkSyllablesAreNotGivenSpaces() {
        // No separator in the source means no separator in the output.
        val joined =
            listOf(
                    syllable("東", 1_000, 1_200),
                    syllable("京", 1_200, 1_400),
                )
                .joinSyllables()

        assertEquals("東京", joined.text)
        assertEquals(listOf(0..0, 1..1), joined.ranges)
    }

    @Test
    fun emptySyllablesHaveNoRange() {
        val joined =
            listOf(
                    syllable("", 1_000, 1_100),
                    syllable("word", 1_100, 1_500),
                )
                .joinSyllables()

        assertEquals("word", joined.text)
        assertNull(joined.ranges[0])
        assertEquals(0..3, joined.ranges[1])
    }

    @Test
    fun plainTextAndSweptLineShareOneJoin() {
        // Both renderers must lay out the identical string; they only ever call the one helper.
        val syllables =
            listOf(
                syllable("gimme ", 1_000, 1_400),
                syllable("some ", 1_400, 1_700),
                syllable("day", 1_700, 2_300),
            )
        assertEquals(syllables.joinSyllables().text, syllables.joinSyllableContents())
    }
}

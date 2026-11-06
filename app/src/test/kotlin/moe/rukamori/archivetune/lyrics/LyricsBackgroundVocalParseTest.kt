package moe.rukamori.archivetune.lyrics

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import moe.rukamori.archivetune.ui.component.buildSyncedLyrics
import moe.rukamori.archivetune.ui.component.hasWordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Background vocals at the source. Two places state them and neither used to survive the trip:
 *
 * - TTML says it explicitly, with `ttm:role="x-bg"` on the span that holds the backing words.
 * - The enhanced-LRC providers (YouLyPlus's word endpoint, BetterLyrics' LRC fallback, the enhanced-LRC
 *   files they mirror) have no attribute for it and write the backing vocal inline in round brackets.
 *
 * Both end up as [WordTimestamp.isBackground], which is what makes the model publish the backing voice as
 * its own smaller row under the lead line instead of running it as lead text.
 */
class LyricsBackgroundVocalParseTest {
    /**
     * An enhanced-LRC line whose backing voice is inline: the shape YouLyPlus's word endpoint and
     * BetterLyrics' LRC fallback emit.
     */
    private val lrcWithBackground =
        "[00:10.000]<00:10.000>hold <00:10.500>on <00:11.000>(gimme <00:11.400>some <00:11.800>day)"

    private val lrcUnbalanced =
        "[00:10.000]<00:10.000>hold <00:10.500>(gimme <00:11.000>some <00:11.500>day"

    /** A bracket that opens inside a word is text, not a voice. */
    private val lrcBracketInsideWord = "[00:10.000]<00:10.000>a <00:10.500>b(c <00:11.000>d"

    private val lrcLeadOnly = "[00:10.000]<00:10.000>hold <00:10.500>on"

    /**
     * A whole line in brackets: the source's own phrasing of a sung lead line, not a backing voice. The
     * bracket convention only marks a run with lead words outside it, so this stays lead.
     */
    private val lrcWholeLineBracketed = "[00:10.000]<00:10.000>(I <00:10.500>know)"

    private val ttmlWithBackgroundVoice =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:lang="en">
          <body>
            <div>
              <p begin="00:00:10.000" end="00:00:13.000" ttm:agent="v1">
                <span begin="00:00:10.000" end="00:00:10.500">hold </span>
                <span begin="00:00:10.500" end="00:00:11.000">on </span>
                <span ttm:role="x-bg">
                  <span begin="00:00:11.000" end="00:00:11.400">gimme </span>
                  <span begin="00:00:11.400" end="00:00:11.700">some </span>
                  <span begin="00:00:11.700" end="00:00:12.300">day</span>
                </span>
              </p>
            </div>
          </body>
        </tt>
        """.trimIndent()

    @Test
    fun ttmlBackgroundSpansKeepTheirRole() {
        val entry = LyricsUtils.parseTtml(ttmlWithBackgroundVoice).single()
        val words = entry.words.orEmpty()
        assertEquals(5, words.size)
        assertEquals(listOf(false, false, true, true, true), words.map { it.isBackground })
        // The role does not change the words' own timing, which is what lets the backing row sweep.
        assertEquals(11.0, words[2].startTime, 0.01)
        assertEquals(12.3, words[4].endTime, 0.01)
    }

    @Test
    fun ttmlWithoutABackgroundSpanIsAllLead() {
        val ttml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata" xml:lang="en">
              <body>
                <div>
                  <p begin="00:00:01.000" end="00:00:03.000" ttm:agent="v1">
                    <span begin="00:00:01.000" end="00:00:01.600">hold </span>
                    <span begin="00:00:01.600" end="00:00:02.400">on</span>
                  </p>
                </div>
              </body>
            </tt>
            """.trimIndent()
        val words = LyricsUtils.parseTtml(ttml).single().words.orEmpty()
        assertEquals(listOf("hold ", "on"), words.map { it.text })
        assertTrue(words.none { it.isBackground })
    }

    @Test
    fun anInlineBracketedRunIsABackgroundVoice() {
        val words = LyricsUtils.parseLyrics(lrcWithBackground).single().words.orEmpty()
        assertEquals(5, words.size)
        assertEquals(listOf(false, false, true, true, true), words.map { it.isBackground })
        // The brackets stay in the words: the row's text is the source text, never a rewritten one.
        assertEquals("hold on (gimme some day)", words.joinToString("") { it.text })
    }

    @Test
    fun aBracketInsideAWordIsTextAndNotAVoice() {
        val words = LyricsUtils.parseLyrics(lrcBracketInsideWord).single().words.orEmpty()
        assertTrue(words.none { it.isBackground })
    }

    @Test
    fun aLineWithNoBracketsKeepsEveryLeadWord() {
        val words = LyricsUtils.parseLyrics(lrcLeadOnly).single().words.orEmpty()
        assertTrue(words.none { it.isBackground })
    }

    @Test
    fun aWholeLineInBracketsStaysLead() {
        // "(I know)" is a sung lead phrase, not a backing voice: marking it background shrank the row
        // and moved it under a lead line that does not exist.
        val words = LyricsUtils.parseLyrics(lrcWholeLineBracketed).single().words.orEmpty()
        assertEquals(2, words.size)
        assertTrue(words.none { it.isBackground })
        assertEquals("(I know)", words.joinToString("") { it.text })
    }

    @Test
    fun aBracketedRunWithLeadWordsOutsideStaysBackground() {
        // The same brackets at the end of a line that has lead words in front are the convention.
        val lrc = "[00:10.000]<00:10.000>I <00:10.500>know <00:11.000>(I <00:11.400>know)"
        val words = LyricsUtils.parseLyrics(lrc).single().words.orEmpty()
        assertEquals(listOf(false, false, true, true), words.map { it.isBackground })
    }

    @Test
    fun anUnbalancedOpenBracketMarksTheRestOfTheLine() {
        val words = LyricsUtils.parseLyrics(lrcUnbalanced).single().words.orEmpty()
        assertEquals(listOf(false, true, true, true), words.map { it.isBackground })
    }

    @Test
    fun theParsedBackingVoiceReachesTheModelAsItsOwnRow() {
        val entries = LyricsUtils.parseLyrics(lrcWithBackground)
        val row = buildSyncedLyrics(entries, emptyMap()).lines.single()
        assertTrue("row was $row", row is KaraokeLine.MainKaraokeLine)
        val accompaniment = (row as KaraokeLine.MainKaraokeLine).accompanimentLines.orEmpty()
        assertEquals(1, accompaniment.size)
        val backing = accompaniment.single()
        assertEquals("(gimme some day)", backing.syllables.joinToString("") { it.content })
        assertTrue(backing.hasWordTiming())
        // The lead line keeps the lead words only.
        assertEquals("hold on", row.syllables.joinToString("") { it.content }.trim())
        assertFalse(row.syllables.any { it.content.contains("gimme") })
    }
}

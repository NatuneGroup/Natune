package moe.rukamori.archivetune.ui.component

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.WordTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Background vocals in the Enhanced model. The lead voice keeps the line, and the background vocal that
 * answers it keeps its own row: its own word timings, drawn smaller under the lead line — including the
 * whole line that is only background ("gimme some day") and the document's second voice, which used to be
 * promoted to a lead line at full size.
 */
class LyricsBackgroundVocalsTest {
    private fun words(vararg words: WordTimestamp): List<WordTimestamp> = words.toList()

    private fun word(
        text: String,
        start: Double,
        end: Double,
        background: Boolean = false,
    ) = WordTimestamp(text = text, startTime = start, endTime = end, isBackground = background)

    @Test
    fun aMixedLineKeepsItsBackgroundVocalAsItsOwnTimedRow() {
        val entry =
            LyricsEntry(
                time = 1_000L,
                text = "hold on (gimme some day)",
                words =
                    words(
                        word("hold ", 1.0, 1.3),
                        word("on ", 1.3, 1.8),
                        word("(gimme ", 1.8, 2.1, background = true),
                        word("some ", 2.1, 2.4, background = true),
                        word("day)", 2.4, 3.1, background = true),
                    ),
            )
        val row = buildSyncedLyrics(listOf(entry), emptyMap()).lines.single()
        assertTrue(row is KaraokeLine.MainKaraokeLine)
        val accompaniment = (row as KaraokeLine.MainKaraokeLine).accompanimentLines.orEmpty()
        assertEquals(1, accompaniment.size)
        val backing = accompaniment.single()
        assertEquals("(gimme some day)", backing.syllables.joinToString("") { it.content })
        // Its own timing, which is what lets it sweep with the voice it answers.
        assertEquals(1_800, backing.start)
        assertEquals(3_100, backing.end)
        assertTrue(backing.hasWordTiming())
    }

    @Test
    fun aLineThatIsOnlyBackgroundVocalsIsASecondaryRowOfItsOwn() {
        val entry =
            LyricsEntry(
                time = 9_000L,
                text = "gimme some day",
                words =
                    words(
                        word("gimme ", 9.0, 9.4, background = true),
                        word("some ", 9.4, 9.7, background = true),
                        word("day", 9.7, 10.3, background = true),
                    ),
            )
        val row = buildSyncedLyrics(listOf(entry), emptyMap()).lines.single()
        assertTrue("row was $row", row is KaraokeLine.AccompanimentKaraokeLine)
        assertEquals(9_000, row.start)
        assertEquals(10_300, row.end)
        assertEquals(KaraokeAlignment.Start, (row as KaraokeLine).alignment)
        assertTrue(row.hasWordTiming())
    }

    @Test
    fun theSecondVoiceIsDrawnAsAnAnswerToTheLeadLine() {
        val entry =
            LyricsEntry(
                time = 5_000L,
                text = "gimme some day",
                agent = "v2",
                words =
                    words(
                        word("gimme ", 5.0, 5.4),
                        word("some ", 5.4, 5.7),
                        word("day", 5.7, 6.3),
                    ),
            )
        val row = buildSyncedLyrics(listOf(entry), emptyMap()).lines.single()
        assertTrue("row was $row", row is KaraokeLine.AccompanimentKaraokeLine)
        assertEquals(KaraokeAlignment.End, (row as KaraokeLine).alignment)
        assertTrue(row.hasWordTiming())
    }

    @Test
    fun theLeadVoiceNeverLosesItsWordsToTheBackground() {
        val entry =
            LyricsEntry(
                time = 1_000L,
                text = "hold on",
                words = words(word("hold ", 1.0, 1.3), word("on ", 1.3, 1.8)),
            )
        val row = buildSyncedLyrics(listOf(entry), emptyMap()).lines.single()
        assertEquals("hold on", (row as KaraokeLine).syllables.joinToString("") { it.content }.trim())
        assertEquals(1_000, row.start)
        assertEquals(1_800, row.end)
    }

    @Test
    fun anEntirelySyntheticWordListStillFallsBackToTheLine() {
        // Identical starts and identical durations are what a provider fakes; hasTrueWordSync rejects
        // them, so the row stays line-synced instead of sweeping evenly spaced nonsense.
        val entry =
            LyricsEntry(
                time = 2_000L,
                text = "four words here",
                words =
                    words(
                        word("four ", 2.0, 2.5),
                        word("words ", 2.0, 2.5),
                        word("here", 2.0, 2.5),
                    ),
            )
        val row = buildSyncedLyrics(listOf(entry), emptyMap()).lines.single()
        assertFalse(row.hasWordTiming())
    }
}

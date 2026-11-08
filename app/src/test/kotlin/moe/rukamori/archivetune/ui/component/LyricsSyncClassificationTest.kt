package moe.rukamori.archivetune.ui.component

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import moe.rukamori.archivetune.lyrics.LyricsEntry
import moe.rukamori.archivetune.lyrics.LyricsPhonetics
import moe.rukamori.archivetune.lyrics.WordTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sync-type classification for the Enhanced renderer: only a row with real per-word spans may sweep.
 * A line-synced LRC row is published as a [KaraokeLine] when it carries a reading, and those syllables
 * are pegs at the line's own start — sweeping them lights the whole row at one instant and lifts it
 * through a completion it never earned, which is exactly the complaint the maintainer raised.
 */
class LyricsSyncClassificationTest {
    private fun syllable(
        text: String,
        start: Int,
        end: Int,
    ) = KaraokeSyllable(content = text, start = start, end = end, phonetic = null)

    private fun karaokeLine(
        syllables: List<KaraokeSyllable>,
        alignment: KaraokeAlignment = KaraokeAlignment.Start,
    ) = KaraokeLine.MainKaraokeLine(
        syllables = syllables,
        translation = null,
        alignment = alignment,
        start = syllables.first().start,
        end = syllables.last().end,
        phonetic = null,
    )

    @Test
    fun aLineSyncedRowNeverHasWordTiming() {
        // Every syllable is pegged at the line's start by buildAlternateLineSyncedLrcLine.
        val line = karaokeLine(listOf(syllable("Hold ", 5_000, 5_001), syllable("on", 5_000, 5_001)))
        assertFalse(line.hasWordTiming())
    }

    @Test
    fun aSingleSyllableIsNotWordTiming() {
        // One word spanning the line is what a provider's one-word line looks like; there is nothing to
        // sweep between, so it keeps the line highlight.
        val line = karaokeLine(listOf(syllable("Whole line", 1_000, 4_000)))
        assertFalse(line.hasWordTiming())
    }

    @Test
    fun realWordSpansAreWordTiming() {
        val line =
            karaokeLine(
                listOf(
                    syllable("gimme ", 1_000, 1_400),
                    syllable("some ", 1_400, 1_700),
                    syllable("day", 1_700, 2_300),
                ),
            )
        assertTrue(line.hasWordTiming())
    }

    @Test
    fun anAccompanimentRowIsClassifiedLikeAnyOther() {
        val accompaniment =
            KaraokeLine.AccompanimentKaraokeLine(
                syllables = listOf(syllable("(ooh ", 2_000, 2_400), syllable("ooh)", 2_400, 2_900)),
                translation = null,
                alignment = KaraokeAlignment.Start,
                start = 2_000,
                end = 2_900,
                phonetic = null,
            )
        assertTrue(accompaniment.hasWordTiming())
    }

    @Test
    fun aPlainSyncedLineIsNotWordTiming() {
        assertFalse(SyncedLine(content = "text", translation = null, start = 0, end = 1_000).hasWordTiming())
    }

    @Test
    fun aLineSyncedRowWithAReadingStaysLineSyncedWhenBuilt() {
        // The parser produces a line-synced entry, the renderer resolves a reading for it, and the row
        // that comes out carries the reading without claiming word timing.
        val entry = LyricsEntry(time = 12_000L, text = "Hold on")
        val built = buildSyncedLyrics(listOf(entry), mapOf(0 to LyricsPhonetics(line = "hōru on")))
        assertEquals(1, built.lines.size)
        val row = built.lines.first()
        assertTrue("row was $row", row is KaraokeLine)
        assertEquals("hōru on", (row as KaraokeLine.MainKaraokeLine).phonetic)
        assertFalse("a line-synced row must not sweep: $row", row.hasWordTiming())
    }

    @Test
    fun aWordSyncedRowKeepsItsSweep() {
        val entry =
            LyricsEntry(
                time = 1_000L,
                text = "gimme some day",
                words =
                    listOf(
                        WordTimestamp("gimme ", 1.0, 1.4),
                        WordTimestamp("some ", 1.4, 1.7),
                        WordTimestamp("day", 1.7, 2.3),
                    ),
            )
        val built = buildSyncedLyrics(listOf(entry), emptyMap())
        val row = built.lines.single()
        assertTrue(row.hasWordTiming())
    }
}

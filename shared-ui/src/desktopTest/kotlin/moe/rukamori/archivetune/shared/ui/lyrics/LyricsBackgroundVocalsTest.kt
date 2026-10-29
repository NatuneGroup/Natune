package moe.rukamori.archivetune.shared.ui.lyrics

import moe.rukamori.archivetune.shared.domain.lyrics.KaraokeLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsWord
import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LyricsBackgroundVocalsTest {
    private val trailing = KaraokeLine(
        time = 1_000L,
        text = "Hold on (hold on)",
        words = listOf(
            LyricsWord("Hold", 1.0, 1.4),
            LyricsWord("on", 1.4, 1.8),
            LyricsWord("(hold", 1.9, 2.2, isBackground = true),
            LyricsWord("on)", 2.2, 2.6, isBackground = true),
        ),
    )

    @Test
    fun theDefaultKeepsBackgroundVocalsInline() {
        val row = LyricsRenderRows.build(listOf(trailing)).single()
        assertEquals("Hold on (hold on)", row.text)
        assertNull(row.background)
        assertNull(row.backgroundWords)
    }

    @Test
    fun trailingBackgroundVocalsMoveToTheirOwnLine() {
        val row = LyricsRenderRows.build(listOf(trailing), splitBackground = true).single()
        assertEquals("Hold on", row.text)
        assertEquals("(hold on)", row.background)
        assertEquals(listOf(0..4, 6..8), row.backgroundWords?.map { it.range })
        assertEquals(listOf(1_900L, 2_200L), row.backgroundWords?.map { it.startMs })
        // The lead ranges still point into the shortened text.
        assertEquals(listOf(0..3, 5..6), row.words?.map { it.range })
        // The row stays lit until the background vocals end.
        assertEquals(2_600L, row.scanEndMs)
    }

    @Test
    fun backgroundVocalsBeforeTheLeadStayInline() {
        val leading = KaraokeLine(
            time = 1_000L,
            text = "(ooh) Hold on",
            words = listOf(
                LyricsWord("(ooh)", 1.0, 1.3, isBackground = true),
                LyricsWord("Hold", 1.4, 1.8),
                LyricsWord("on", 1.8, 2.2),
            ),
        )
        val row = LyricsRenderRows.build(listOf(leading), splitBackground = true).single()
        assertEquals("(ooh) Hold on", row.text)
        assertNull(row.background)
    }

    @Test
    fun lineOnlyRowsAreUnchanged() {
        val row = LyricsRenderRows.build(listOf(SyncedLine(1_000L, "Plain line")), splitBackground = true).single()
        assertEquals("Plain line", row.text)
        assertNull(row.background)
    }
}

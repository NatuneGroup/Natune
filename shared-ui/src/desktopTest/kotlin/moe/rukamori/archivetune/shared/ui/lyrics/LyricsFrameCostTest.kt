package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine

/**
 * Regressions for the frame-cost follow-up: the "passed rows" state must be a cheap Int count, not
 * a Set rebuilt over every row per frame, and the line-change scroll must start at once (the quick,
 * front-loaded feel ArchiveTune has) rather than holding still through a slow symmetric
 * ease-in-out.
 */
class LyricsFrameCostTest {
    @Test
    fun passedCountMatchesPerRowRuleForMonotonicRows() {
        val rows =
            LyricsRenderRows.build(
                listOf(
                    SyncedLine(time = 1_000, text = "one"),
                    SyncedLine(time = 2_000, text = "two"),
                    SyncedLine(time = 3_000, text = "three"),
                ),
            )
        val counter = PassedRowCounter(rows)

        // Before the first end nothing has passed; each row passes exactly as the feed clears its
        // end;
        // every finished row stays passed through the outro.
        for (feed in listOf(0L, 1_000L, 2_000L, 3_000L, 9_000L)) {
            val expected = rows.count { isPassedLyricRow(it, feed) }
            assertEquals(expected, counter.countAt(feed), "feed=$feed")
            rows.indices.forEach { index ->
                assertEquals(
                    isPassedLyricRow(rows[index], feed),
                    index < counter.countAt(feed),
                    "feed=$feed row=$index",
                )
            }
        }
    }

    @Test
    fun passedCountIsZeroForUnsyncedText() {
        // A static document has no timing to follow, so no row can ever be passed.
        val rows = LyricsRenderRows.build(listOf(SyncedLine(time = 0, text = "plain text")))
        assertEquals(0, PassedRowCounter(rows).countAt(Long.MAX_VALUE))
    }

    @Test
    fun overlappingRowsAreNotPassedUntilTheFurthestEarlierEndClears() {
        // A duet/second voice whose earlier row reaches past the feed: the later row must not be
        // declared passed while its overlapping predecessor is still singing (prefix-max rule).
        val rows =
            listOf(
                LyricsRenderRow(
                    index = 0,
                    timeMs = 1_000,
                    scanStartMs = 1_000,
                    scanEndMs = 5_000,
                    text = "lead",
                    phonetic = null,
                    translation = null,
                    words = null,
                    agent = null,
                    isInstrumental = false,
                    isSynced = true,
                    isSecondVoice = false,
                    isRtl = false,
                ),
                LyricsRenderRow(
                    index = 1,
                    timeMs = 1_000,
                    scanStartMs = 1_000,
                    scanEndMs = 3_000,
                    text = "answer",
                    phonetic = null,
                    translation = null,
                    words = null,
                    agent = "v2",
                    isInstrumental = false,
                    isSynced = true,
                    isSecondVoice = true,
                    isRtl = false,
                ),
            )
        val counter = PassedRowCounter(rows)
        // feed 4s clears the answer's end but NOT the lead's 5s end: count stays below both
        // indices.
        assertEquals(0, counter.countAt(4_000))
        // Once the furthest end clears, both leading rows are passed.
        assertEquals(2, counter.countAt(5_000))
    }

    @Test
    fun autoScrollRespondsImmediately() {
        assertEquals(280, AUTO_SCROLL_MS)
        // A slow symmetric ease-in-out barely moves in its first tenth; a front-loaded decelerate
        // curve is already well past that, which is the quick response the follow needs.
        assertTrue(
            AUTO_SCROLL_EASE.transform(0.1f) > 0.1f,
            "scroll must start faster than linear; got ${AUTO_SCROLL_EASE.transform(0.1f)}",
        )
    }
}

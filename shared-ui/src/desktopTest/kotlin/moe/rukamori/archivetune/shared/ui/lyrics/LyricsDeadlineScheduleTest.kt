package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The idle-frame deadline schedule: live spans gate frames, edges bound the sleeps. */
class LyricsDeadlineScheduleTest {
    private fun row(index: Int, start: Long, end: Long, words: List<LyricsWordTiming>? = null) =
        LyricsRenderRow(
            index = index, timeMs = start, scanStartMs = start, scanEndMs = end,
            text = "line $index", phonetic = null, translation = null, words = words,
            agent = null, isInstrumental = false, isSynced = true, isSecondVoice = false, isRtl = false,
        )

    private val rows = listOf(
        row(0, 1_000, 4_000, listOf(LyricsWordTiming(1_000, 1_800, null), LyricsWordTiming(2_000, 3_000, null))),
        row(1, 10_000, 14_000, listOf(LyricsWordTiming(10_000, 11_000, null))),
    )

    @Test
    fun insideAWordSpanIsLive() {
        val schedule = LyricsDeadlineSchedule.of(rows, minSweepMs = 150, includeInterludes = false)
        assertTrue(schedule.isLive(1_500))
        assertFalse(schedule.isLive(1_900)) // between the two words nothing animates
        assertTrue(schedule.isLive(2_500))
    }

    @Test
    fun minSweepExtendsTheLiveSpan() {
        val schedule = LyricsDeadlineSchedule.of(rows, minSweepMs = 600, includeInterludes = false)
        assertTrue(schedule.isLive(2_400)) // word ends at 3_000 either way
        val short = LyricsDeadlineSchedule.of(
            listOf(row(0, 0, 1_000, listOf(LyricsWordTiming(100, 200, null)))),
            minSweepMs = 600, includeInterludes = false,
        )
        assertTrue(short.isLive(650)) // 100 + 600 > 200
    }

    @Test
    fun nextChangeSkipsToTheNearestBoundary() {
        val schedule = LyricsDeadlineSchedule.of(rows, minSweepMs = 150, includeInterludes = false)
        assertEquals(1_800, schedule.nextChangeAfter(1_500)) // first word's live end is an edge
        assertEquals(2_000, schedule.nextChangeAfter(1_900))
        // After the line end the next boundary is the row's sink, SINK_MS ahead of its first word.
        assertEquals(10_000 - LyricsSweepGeometry.SINK_MS, schedule.nextChangeAfter(4_000))
        assertEquals(10_000, schedule.nextChangeAfter(9_999))
        assertNull(schedule.nextChangeAfter(14_000))
    }

    @Test
    fun interludesBecomeLiveSpansWhenRequested() {
        // A >=9s silence between rows owns a breathing interlude.
        val gapped = listOf(row(0, 0, 1_000), row(1, 12_000, 13_000))
        val withInterludes = LyricsDeadlineSchedule.of(gapped, minSweepMs = 150, includeInterludes = true)
        val without = LyricsDeadlineSchedule.of(gapped, minSweepMs = 150, includeInterludes = false)
        assertTrue(withInterludes.isLive(5_000))
        assertFalse(without.isLive(5_000))
    }

    @Test
    fun anActivationLeadMovesTheRowEdgeEarly() {
        val led = listOf(
            row(0, 0, 1_000),
            row(1, 5_000, 6_000).copy(activationLeadMs = 500L),
        )
        val schedule = LyricsDeadlineSchedule.of(led, minSweepMs = 150, includeInterludes = false)
        assertEquals(4_500L, schedule.nextChangeAfter(1_000)) // the activation edge lands before the timed start
        assertEquals(4_500L, schedule.nextChangeAfter(4_400))
        assertEquals(6_000L, schedule.nextChangeAfter(4_500))
    }
}

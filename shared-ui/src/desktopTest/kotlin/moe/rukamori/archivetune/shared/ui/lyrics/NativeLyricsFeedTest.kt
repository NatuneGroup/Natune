package moe.rukamori.archivetune.shared.ui.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.lyrics.KaraokeLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsWord
import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import moe.rukamori.archivetune.shared.domain.playback.MonotonicClock

class NativeLyricsFeedTest {
    private var now = 0L
    private val clock = MonotonicClock { now }

    @Test fun spansJoinBackToTheRowTextWithGapsOnTheWordBefore() {
        val rows = LyricsRenderRows.build(listOf(karaoke(1_000L, "hello", "world")))
        val spans = nativeLyricsSpans(rows[0].text, rows[0].words)
        assertEquals(listOf("hello ", "world"), spans.map { it.text })
        assertEquals(rows[0].text, spans.joinToString("") { it.text })
        assertEquals(1_000L, spans[0].startMs)
        assertEquals(1_500L, spans[0].endMs)
        assertEquals(1_500L, spans[1].startMs)
    }

    @Test fun unusableWordRangesLeaveTheRowLineOnly() {
        assertTrue(nativeLyricsSpans("text", null).isEmpty())
        assertTrue(nativeLyricsSpans("text", listOf(LyricsWordTiming(0L, 10L, null))).isEmpty())
        val outOfOrder = listOf(LyricsWordTiming(0L, 10L, 5..8), LyricsWordTiming(10L, 20L, 0..3), LyricsWordTiming(20L, 30L, 40..44))
        val spans = nativeLyricsSpans("abcd efgh", outOfOrder)
        assertEquals(listOf("abcd efgh"), spans.map { it.text })
        assertEquals(0L, spans.single().startMs)
    }

    @Test fun documentCarriesBackgroundVocalsReadingsAndTheSharedTapPolicy() {
        val line = KaraokeLine(
            time = 2_000L,
            text = "lead (echo)",
            words = listOf(
                LyricsWord("lead", 2.0, 2.5),
                LyricsWord("(echo)", 2.5, 3.0, isBackground = true),
            ),
        )
        val rows = LyricsRenderRows.build(listOf(line, SyncedLine(time = 0L, text = "intro")), splitBackground = true)
        val document = NativeLyricsDocument.from(TEST_SESSION, rows, LyricsSurfaceSettings(tapToSeek = true), 0x7FFFFFFF)
        val first = document.lines[0]
        assertEquals("lead", first.text)
        assertEquals("(echo)", first.background)
        assertEquals("(echo)", first.backgroundSpans.joinToString("") { it.text })
        assertTrue(first.seekable)
        assertFalse(document.lines[1].seekable)
        assertTrue(document.synced)
        assertEquals(LyricsModeProfile.MANUAL_SCROLL_HOLD_MS, document.manualScrollHoldMs)
        assertEquals(0x7FFFFFFF, document.colorArgb)

        val noSeek = NativeLyricsDocument.from(TEST_SESSION, rows, LyricsSurfaceSettings(tapToSeek = false), 0)
        assertTrue(noSeek.lines.none { it.seekable })
    }

    @Test fun timelineFollowsTheSnapshotClockWithTheSharedFeedOffsets() {
        val timeline = NativeLyricsTimeline(clock)
        val rows = LyricsRenderRows.build(timedLines(4, stepMs = 1_000L))
        timeline.setRows(rows)
        timeline.setSyncOffset(-200L)
        timeline.accept(testSnapshot(positionMs = 1_000L, sampleMonotonicNanos = 0L, advancing = false))
        val held = timeline.frame()
        val expected = LyricsFeedTiming.feedMs(1_000L, -200L, LyricsFeedTiming.LRC_LEAD_MS)
        assertEquals(expected, held.positionMs)
        assertEquals(1, held.primaryLine)
        assertTrue(held.isLineActive(1))
        assertFalse(held.advancing)
        assertEquals(1, held.discontinuity)

        timeline.accept(testSnapshot(positionMs = 1_000L, sampleMonotonicNanos = 0L, advancing = true))
        now = nanos(1_000L)
        val moving = timeline.frame()
        assertTrue(moving.advancing)
        assertEquals(2, moving.primaryLine)
        assertEquals(1, moving.discontinuity)

        timeline.accept(testSnapshot(positionMs = 3_000L, sampleMonotonicNanos = now, discontinuityGeneration = 1L))
        assertEquals(2, timeline.frame().discontinuity)
        assertEquals(3, timeline.frame().primaryLine)
    }

    @Test fun depthMatchesTheSharedRendererSteps() {
        val timeline = NativeLyricsTimeline(clock)
        timeline.setRows(LyricsRenderRows.build(timedLines(8, stepMs = 1_000L)))
        timeline.accept(testSnapshot(positionMs = 0L))
        val frame = timeline.frame()
        val sung = frame.depth(frame.primaryLine, reduceMotion = false)
        assertEquals(NativeLyricsDepth(1f, 0f, 1f), sung)
        val far = frame.depth(frame.primaryLine + 6, reduceMotion = false)
        // The platform view stands in for the refined Enhanced look: the veil is the uniform blur
        // and the far row waits the full stagger before it follows a line change.
        val shared = LyricsDepth.of(
            LyricsDepth.MAX_STEPS,
            reduceMotion = false,
            uniformBlurDp = LyricsDepth.UNIFORM_BLUR_DP,
        )
        assertEquals(
            NativeLyricsDepth(shared.scale, shared.blurDp, shared.alpha, LyricsDepth.staggerMs(LyricsDepth.MAX_STEPS)),
            far,
        )
        assertEquals(1f, frame.depth(frame.primaryLine + 1, reduceMotion = true).scale)
        assertEquals(0f, frame.depth(frame.primaryLine + 1, reduceMotion = true).blurPoints)
    }
}

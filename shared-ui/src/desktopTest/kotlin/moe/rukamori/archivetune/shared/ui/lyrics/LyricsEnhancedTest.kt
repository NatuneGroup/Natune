/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.lyrics

import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.lyrics.KaraokeLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsWord
import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import moe.rukamori.archivetune.shared.domain.lyrics.romanization.LyricsPhonetics
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Engine behaviour of the Enhanced renderer: the clock, active-line scan, sweep geometry, glow and shaping. */
class LyricsEnhancedTest {
    @Test
    fun smoothPositionAdvancesWithThePlaybackSpeed() {
        val smooth = LyricsSmoothPosition()
        smooth.accept(testSnapshot(positionMs = 0, sampleMonotonicNanos = 0, advancing = true, playbackSpeed = 2f), 0)

        assertEquals(200L, smooth.advance(nanos(100)))
    }

    @Test
    fun smoothPositionHoldsWhilePausedOrBuffering() {
        val smooth = LyricsSmoothPosition()
        smooth.accept(testSnapshot(positionMs = 10_000, sampleMonotonicNanos = 0, advancing = true), 0)
        assertEquals(10_100L, smooth.advance(nanos(100)))

        val stalled = testSnapshot(positionMs = 10_150, sampleMonotonicNanos = nanos(120), buffering = true, advancing = true)
        assertEquals(LyricsAnchorChange.ANCHORED, smooth.accept(stalled, nanos(120)))

        assertEquals(10_150L, smooth.advance(nanos(300)))
        assertEquals(10_150L, smooth.advance(nanos(900)))
    }

    @Test
    fun smallDriftIsClosedByHalfAndLargeDriftSnapsBack() {
        val behind = LyricsSmoothPosition()
        behind.accept(testSnapshot(positionMs = 1_000, sampleMonotonicNanos = 0, advancing = true), 0)
        assertEquals(1_000L, behind.advance(0))
        behind.accept(testSnapshot(positionMs = 1_100, sampleMonotonicNanos = 0, advancing = true), 0)
        // The projection is 100 ms behind the anchor, inside the 180 ms band, so 55% of the gap closes.
        assertEquals(1_055L, behind.advance(0))

        val ahead = LyricsSmoothPosition()
        ahead.accept(testSnapshot(positionMs = 1_000, sampleMonotonicNanos = 0, advancing = true), 0)
        ahead.accept(testSnapshot(positionMs = 800, sampleMonotonicNanos = 0, advancing = true), 0)
        // The projection is 200 ms ahead of the anchor, past the 80 ms band, so it snaps back.
        assertEquals(800L, ahead.advance(0))

        val lagging = LyricsSmoothPosition()
        lagging.accept(testSnapshot(positionMs = 1_000, sampleMonotonicNanos = 0, advancing = true), 0)
        lagging.accept(testSnapshot(positionMs = 1_300, sampleMonotonicNanos = 0, advancing = true), 0)
        // The projection is 300 ms behind the anchor, past the 180 ms band, so it snaps forward.
        assertEquals(1_300L, lagging.advance(0))
    }

    @Test
    fun discontinuityResetsTheClockAndStaleSnapshotsAreIgnored() {
        val smooth = LyricsSmoothPosition()
        assertEquals(
            LyricsAnchorChange.RESET,
            smooth.accept(testSnapshot(positionMs = 10_000, sampleMonotonicNanos = 0, advancing = true), 0),
        )
        val seek = testSnapshot(
            positionMs = 50_000,
            sampleMonotonicNanos = nanos(1_000),
            advancing = true,
            discontinuityGeneration = 1,
        )
        assertEquals(LyricsAnchorChange.RESET, smooth.accept(seek, nanos(1_000)))
        assertEquals(50_000L, smooth.advance(nanos(1_000)))

        val older = testSnapshot(positionMs = 12_000, sampleMonotonicNanos = nanos(500), advancing = true)
        assertEquals(LyricsAnchorChange.STALE, smooth.accept(older, nanos(1_100)))
        // The clock keeps running from the newer anchor, and the stale observation does not move it.
        assertEquals(50_100L, smooth.advance(nanos(1_100)))
    }

    @Test
    fun frameLoopRunsOnlyWhileVisibleResumedMovingAndNotReduced() {
        val moving = testSnapshot(positionMs = 1_000, advancing = true)
        assertTrue(LyricsFrameGate.runsFrameLoop(visible = true, resumed = true, reduceMotion = false, snapshot = moving))
        assertFalse(LyricsFrameGate.runsFrameLoop(visible = false, resumed = true, reduceMotion = false, snapshot = moving))
        assertFalse(LyricsFrameGate.runsFrameLoop(visible = true, resumed = false, reduceMotion = false, snapshot = moving))
        assertFalse(LyricsFrameGate.runsFrameLoop(visible = true, resumed = true, reduceMotion = true, snapshot = moving))
        assertFalse(LyricsFrameGate.runsFrameLoop(visible = true, resumed = true, reduceMotion = false, snapshot = null))
        val buffering = testSnapshot(positionMs = 1_000, advancing = true, buffering = true)
        assertFalse(LyricsFrameGate.runsFrameLoop(visible = true, resumed = true, reduceMotion = false, snapshot = buffering))
        val remote = testSnapshot(positionMs = 1_000, advancing = true, outputRoute = PlaybackOutputRoute.REMOTE)
        assertFalse(LyricsFrameGate.runsFrameLoop(visible = true, resumed = true, reduceMotion = false, snapshot = remote))
    }

    @Test
    fun scannerReturnsSimultaneousOverlappingDuetAndBackingLines() {
        val scanner = ActiveLineScanner(
            starts = longArrayOf(0, 1_000, 2_000),
            ends = longArrayOf(5_000, 3_000, 2_500),
        )

        assertEquals(listOf(0, 1, 2), scanner.activeAt(2_200))
        assertEquals(2, scanner.primaryAt(2_200))
        assertEquals(listOf(0, 1), scanner.activeAt(2_500))
        assertEquals(listOf(0), scanner.activeAt(500))
        assertEquals(emptyList(), scanner.activeAt(6_000))
        assertEquals(-1, scanner.primaryAt(6_000))
    }

    @Test
    fun scannerKeepsDisplayOrderAndReusesAnUnchangedResult() {
        val scanner = ActiveLineScanner(starts = longArrayOf(2_000, 0), ends = longArrayOf(2_500, 5_000))

        val first = scanner.activeAt(2_200)
        assertEquals(listOf(0, 1), first)
        assertSame(first, scanner.activeAt(2_300))
        assertEquals(listOf(1), scanner.activeAt(4_000))
    }

    @Test
    fun wordDoesNotLightBeforeItsStartAfterABackwardSeek() {
        val bands = LyricsLineBands(widthPx = 200f, tops = floatArrayOf(0f), bottoms = floatArrayOf(40f))
        val boxes = listOf(LyricsWordBox(startMs = 1_000, endMs = 2_000, leftPx = 10f, rightPx = 110f, line = 0))

        assertEquals(0, LyricsSweepGeometry.activeIndex(boxes, 1_500))
        assertEquals(-1, LyricsSweepGeometry.activeIndex(boxes, 900))
        assertEquals(emptyList(), LyricsSweepGeometry.clipRects(boxes, bands, 900, rtl = false, reduceMotion = false))

        // The sweep is a function of the position, so a seek back leaves nothing lit from the earlier pass.
        assertEquals(1, LyricsSweepGeometry.clipRects(boxes, bands, 1_500, rtl = false, reduceMotion = false).size)
        assertEquals(emptyList(), LyricsSweepGeometry.clipRects(boxes, bands, 500, rtl = false, reduceMotion = false))
    }

    @Test
    fun sweepEdgeFollowsTheWordAndRightToLeftTextSweepsFromTheRight() {
        val box = LyricsWordBox(startMs = 1_000, endMs = 2_000, leftPx = 10f, rightPx = 110f, line = 0)
        val bands = LyricsLineBands(widthPx = 200f, tops = floatArrayOf(0f), bottoms = floatArrayOf(40f))
        val boxes = listOf(box)

        assertEquals(0.5f, LyricsSweepGeometry.progressAt(box, 1_500, reduceMotion = false))
        assertEquals(LyricsClipRect(0f, 0f, 60f, 40f), LyricsSweepGeometry.clipRects(boxes, bands, 1_500, rtl = false, reduceMotion = false).single())
        assertEquals(LyricsClipRect(60f, 0f, 200f, 40f), LyricsSweepGeometry.clipRects(boxes, bands, 1_500, rtl = true, reduceMotion = false).single())
    }

    @Test
    fun earlierLinesStayFullyBrightWhileTheActiveWordSweeps() {
        val bands = LyricsLineBands(widthPx = 200f, tops = floatArrayOf(0f, 40f), bottoms = floatArrayOf(40f, 80f))
        val boxes = listOf(LyricsWordBox(startMs = 1_000, endMs = 2_000, leftPx = 10f, rightPx = 110f, line = 1))

        val rects = LyricsSweepGeometry.clipRects(boxes, bands, 1_500, rtl = false, reduceMotion = false)

        assertEquals(LyricsClipRect(0f, 0f, 200f, 40f), rects[0])
        assertEquals(LyricsClipRect(0f, 40f, 60f, 80f), rects[1])
    }

    @Test
    fun veryShortWordsStillTravelAcrossTheirGlyphs() {
        val box = LyricsWordBox(startMs = 1_000, endMs = 1_050, leftPx = 0f, rightPx = 100f, line = 0)

        assertEquals(0.5f, LyricsSweepGeometry.progressAt(box, 1_075, reduceMotion = false), 1e-6f)
        assertEquals(1f, LyricsSweepGeometry.progressAt(box, 1_150, reduceMotion = false), 1e-6f)
        assertEquals(1f, LyricsSweepGeometry.progressAt(box, 1_150, reduceMotion = false, minSweepMs = 50), 1e-6f)
    }

    @Test
    fun reducedMotionJumpsBetweenUnsweptAndSweptWithoutIntermediateStates() {
        val box = LyricsWordBox(startMs = 1_000, endMs = 2_000, leftPx = 0f, rightPx = 100f, line = 0)

        assertEquals(0f, LyricsSweepGeometry.progressAt(box, 999, reduceMotion = true))
        assertEquals(1f, LyricsSweepGeometry.progressAt(box, 1_001, reduceMotion = true))
    }

    @Test
    fun glowFollowsTheAndroidWindowEnvelope() {
        val held = LyricsWordBox(startMs = 0, endMs = 1_000, leftPx = 0f, rightPx = 100f, line = 0)
        assertEquals(0.5f, LyricsSweepGeometry.glowIntensity(held, 90), 1e-4f)
        assertEquals(1f, LyricsSweepGeometry.glowIntensity(held, 500), 1e-4f)
        assertEquals(0.26316f, LyricsSweepGeometry.glowIntensity(held, 900), 1e-4f)
        assertEquals(0f, LyricsSweepGeometry.glowIntensity(held, 1_000))
        assertEquals(0f, LyricsSweepGeometry.glowIntensity(held, -1))

        // A quick word takes the floor, so quick patter keeps a faint bloom.
        val quick = LyricsWordBox(startMs = 0, endMs = 100, leftPx = 0f, rightPx = 100f, line = 0)
        assertEquals(LyricsSweepGeometry.GLOW_FLOOR, LyricsSweepGeometry.glowIntensity(quick, 50), 1e-4f)
    }

    @Test
    fun shapeCacheReusesShapedRowsAndEvictsTheLeastRecentlyUsed() {
        val shaped = mutableListOf<Int>()
        val cache = LyricsShapeCache<Int, String>(maxEntries = 2) { key ->
            shaped += key
            "shape-$key"
        }

        cache.get(1)
        cache.get(2)
        assertEquals("shape-1", cache.get(1))
        assertEquals(listOf(1, 2), shaped)

        cache.get(3)
        assertEquals(2, cache.size)
        assertEquals("shape-1", cache.get(1))
        assertEquals(listOf(1, 2, 3), shaped)

        cache.get(2)
        assertEquals(listOf(1, 2, 3, 2), shaped)
    }

    @Test
    fun prewarmShapesEveryKeyOnceAndYieldsWithinItsBudget() = runBlocking {
        val shaped = mutableListOf<Int>()
        val cache = LyricsShapeCache<Int, String>(maxEntries = 16) { key ->
            shaped += key
            "shape-$key"
        }
        cache.get(3)

        cache.prewarm(listOf(1, 2, 3, 4, 5), budgetNanos = 0L)

        assertEquals(5, cache.size)
        assertEquals(listOf(3, 1, 2, 4, 5), shaped)
        assertEquals(LYRICS_PREWARM_BUDGET_NANOS, 3_000_000L)
    }

    @Test
    fun lineSpanIncludesWordsButNotTheLeadAndHoldsAFinalLineForItsDefault() {
        val rows = LyricsRenderRows.build(
            listOf(
                karaoke(startMs = 1_000, first = "hello", second = "world"),
                SyncedLine(time = 4_000, text = "last line"),
            ),
        )

        assertEquals(1_000L, rows[0].scanStartMs)
        assertEquals(2_000L, rows[0].scanEndMs)
        assertEquals(1_000L, rows[0].words!![0].startMs)
        assertEquals(1_500L, rows[0].words!![0].endMs)
        assertEquals(4_000L, rows[1].scanStartMs)
        assertEquals(4_000L + LyricsRenderRows.DEFAULT_LINE_HOLD_MS, rows[1].scanEndMs)
    }

    @Test
    fun readingsSitBeneathTheSourceAndWordRangesStayOnTheSourceText() {
        val line = karaoke(startMs = 0, first = "hello", second = "world")

        val lineReading = LyricsRenderRows.build(listOf(line), listOf(LyricsPhonetics(line = "heh wurld")))
        assertEquals("hello world", lineReading[0].text)
        assertEquals("heh wurld", lineReading[0].phonetic)

        val wordReading = LyricsRenderRows.build(listOf(line), listOf(LyricsPhonetics(words = listOf("heh", "wurld"))))
        assertEquals("hello world", wordReading[0].text)
        assertEquals("heh wurld", wordReading[0].phonetic)
        assertEquals(listOf(0..4, 6..10), wordReading[0].words!!.map { it.range })
    }

    @Test
    fun unsyncedTextNeverBecomesActiveOrSeeks() {
        val rows = LyricsRenderRows.build(listOf(SyncedLine(time = 0, text = "plain text")))

        assertFalse(rows[0].isSynced)
        assertEquals(emptyList(), LyricsRenderRows.scanner(rows).activeAt(0))
    }

    @Test
    fun feedAddsTheLeadAndVisualTuningButRowTimesStayCanonical() {
        assertEquals(300L, LyricsFeedTiming.LRC_LEAD_MS)
        assertEquals(10_250L, LyricsFeedTiming.feedMs(positionMs = 10_000, syncOffsetMs = -200, leadMs = 300))

        val lineRows = LyricsRenderRows.build(listOf(SyncedLine(time = 5_000, text = "a")))
        assertEquals(LyricsFeedTiming.LRC_LEAD_MS, LyricsFeedTiming.leadMsFor(lineRows))
        val karaokeRows = LyricsRenderRows.build(listOf(karaoke(startMs = 5_000, first = "a", second = "b")))
        assertEquals(LyricsFeedTiming.TTML_LEAD_MS, LyricsFeedTiming.leadMsFor(karaokeRows))
        assertEquals(5_000L, karaokeRows[0].timeMs)
    }

    @Test
    fun overlappingLinesFromTheSameStartAreBothActive() {
        val rows = LyricsRenderRows.build(
            listOf(
                karaoke(startMs = 2_000, first = "lead", second = "voice", agent = "v1"),
                karaoke(startMs = 2_000, first = "back", second = "up", agent = "v2"),
            ),
        )

        assertTrue(rows[1].isSecondVoice)
        assertFalse(rows[0].isSecondVoice)
        assertContentEquals(listOf(0, 1), LyricsRenderRows.scanner(rows).activeAt(2_200))
    }

    @Test
    fun shapedRowsKeepWordBoxesSortedAndInsideTheLine() = runBlocking {
        val row = LyricsRenderRows.build(
            listOf(karaoke(startMs = 0, first = "wide", second = "words")),
        ).single()
        val measurer = testTextMeasurer()

        val shaped = shapeLyricRow(measurer, row, LyricsShapeKey(0, 400, lyricsMainStyleForTests()))

        assertEquals(2, shaped.boxes.size)
        assertTrue(shaped.boxes[0].startMs <= shaped.boxes[1].startMs)
        shaped.boxes.forEach { box ->
            assertTrue(box.leftPx >= 0f && box.leftPx <= box.rightPx, "word box out of order: $box")
            assertEquals(0, box.line)
        }
        assertEquals(1, shaped.bands.lineCount)
        assertNull(LyricsRenderRows.build(listOf(SyncedLine(time = 0, text = "x")))[0].words)
    }
}

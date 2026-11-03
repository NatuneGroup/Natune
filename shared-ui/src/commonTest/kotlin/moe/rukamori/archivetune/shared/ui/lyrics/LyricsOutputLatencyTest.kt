package moe.rukamori.archivetune.shared.ui.lyrics

import moe.rukamori.archivetune.shared.domain.lyrics.SyncedLine
import moe.rukamori.archivetune.shared.domain.playback.MonotonicClock
import moe.rukamori.archivetune.shared.domain.playback.PlaybackClock
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsOutputLatencyTest {
    private val snapshot = PlaybackSnapshot(
        occurrenceId = "track@1",
        mediaId = "track",
        playbackGeneration = 1,
        discontinuityGeneration = 0,
        positionMs = 10_000,
        sampleMonotonicNanos = 0,
        durationMs = 180_000,
        isSeekable = true,
        playbackSpeed = 1f,
        playWhenReady = true,
        isAudioAdvancing = true,
        isBuffering = false,
        outputRoute = PlaybackOutputRoute.LOCAL,
        outputLatencyMs = 2_000,
    )

    @Test
    fun outputDelayAddsToNeitherTheUserOffsetNorCanonicalSeekPositions() {
        val clock = LyricsClock()
        clock.retime(syncOffsetMs = 100, leadMs = 300)
        clock.accept(snapshot, 0)
        assertEquals(8_550L, clock.feedMs.longValue)
        assertEquals(10_000L, PlaybackClock.positionMs(snapshot, 0))
        clock.advance(100_000_000)
        assertEquals(8_650L, clock.feedMs.longValue)
    }

    @Test
    fun outputDelayIsConvertedFromWallTimeAtThePlaybackSpeed() {
        val clock = LyricsClock()
        clock.accept(snapshot.copy(playbackSpeed = 1.5f), 0)
        assertEquals(7_150L, clock.feedMs.longValue)
    }

    @Test
    fun unknownLatencyKeepsTheExistingTimingAndCanReplaceAKnownRoute() {
        val clock = LyricsClock()
        clock.accept(snapshot, 0)
        clock.accept(snapshot.copy(sampleMonotonicNanos = 1, outputLatencyMs = null), 1)
        assertEquals(10_150L, clock.feedMs.longValue)
        assertEquals(1, clock.discontinuities.intValue)
    }

    @Test
    fun theIdleParkFollowsTheSpeedOfTheLatestFreshSnapshot() {
        val clock = LyricsClock()
        assertEquals(1f, clock.playbackSpeed)
        clock.accept(snapshot.copy(sampleMonotonicNanos = 100, playbackSpeed = 2f), 100)
        assertEquals(2f, clock.playbackSpeed)
        clock.accept(snapshot.copy(sampleMonotonicNanos = 0, playbackSpeed = 0.5f), 100)
        assertEquals(2f, clock.playbackSpeed)
    }

    @Test
    fun aStaleSnapshotCannotReplaceTheOutputDelay() {
        val clock = LyricsClock()
        clock.accept(snapshot.copy(sampleMonotonicNanos = 100), 100)
        clock.accept(snapshot.copy(sampleMonotonicNanos = 0, outputLatencyMs = 0), 100)
        assertEquals(8_150L, clock.feedMs.longValue)
    }

    @Test
    fun seeksKeepCanonicalTimestampsAndCanHoldLyricsBeforeTheFirstAudibleSample() {
        val clock = LyricsClock()
        clock.accept(snapshot, 0)
        val seek = snapshot.copy(positionMs = 0, discontinuityGeneration = 1)
        clock.accept(seek, 0)
        assertEquals(-1_850L, clock.feedMs.longValue)
        assertEquals(0L, PlaybackClock.positionMs(seek, 0))
        assertEquals(2, clock.discontinuities.intValue)
    }

    @Test
    fun pausedOrBufferingOutputKeepsTheSameLatencyOffsetWithoutAdvancing() {
        for (state in listOf(
            snapshot.copy(playWhenReady = false, isAudioAdvancing = false),
            snapshot.copy(isBuffering = true),
        )) {
            val clock = LyricsClock()
            clock.accept(state, 0)
            clock.advance(1_000_000_000)
            assertEquals(8_150L, clock.feedMs.longValue)
        }
    }

    @Test
    fun anExtremeOutputEstimateCannotWrapTheFeedToTheEndOfTheSong() {
        val clock = LyricsClock()
        clock.retime(syncOffsetMs = -1_000, leadMs = 0)
        clock.accept(snapshot.copy(positionMs = 0, outputLatencyMs = Long.MAX_VALUE, playbackSpeed = Float.MAX_VALUE), 0)
        assertEquals(Long.MIN_VALUE, clock.feedMs.longValue)
    }

    @Test
    fun theOffsetHoldsLyricsBackByTheRouteLatencyAndBuffer() {
        assertEquals(-25L, LyricsFeedTiming.outputLatencyOffsetMs(0.020, 0.005))
    }

    @Test
    fun missingOrNonsensicalReadingsCountAsNone() {
        assertEquals(0L, LyricsFeedTiming.outputLatencyOffsetMs(Double.NaN, -1.0))
        assertEquals(-5L, LyricsFeedTiming.outputLatencyOffsetMs(Double.POSITIVE_INFINITY, 0.005))
    }

    @Test
    fun theOffsetIsBounded() {
        assertEquals(-LyricsFeedTiming.MAX_OUTPUT_LATENCY_MS, LyricsFeedTiming.outputLatencyOffsetMs(9.0, 0.0))
    }

    @Test
    fun nativeAndComposeClocksApplyTheOutputDelayExactlyOnceAtThePlaybackRate() {
        var now = 0L
        val native = NativeLyricsTimeline(MonotonicClock { now })
        val rows = LyricsRenderRows.build(listOf(
            SyncedLine(time = 7_000, text = "first"),
            SyncedLine(time = 8_000, text = "second"),
        ))
        native.setRows(rows)
        native.setSyncOffset(100)
        val compose = LyricsClock().apply { retime(100, LyricsFeedTiming.LRC_LEAD_MS) }
        val playing = snapshot.copy(playbackSpeed = 1.5f)
        native.accept(playing)
        compose.accept(playing, now)

        assertEquals(7_550L, native.frame().positionMs)
        assertEquals(compose.feedMs.longValue, native.frame().positionMs)
        assertEquals(0, native.frame().primaryLine)

        now = 100_000_000
        compose.advance(now)
        assertEquals(7_700L, native.frame().positionMs)
        assertEquals(compose.feedMs.longValue, native.frame().positionMs)

        val seek = playing.copy(positionMs = 0, sampleMonotonicNanos = now, discontinuityGeneration = 1)
        native.accept(seek)
        compose.accept(seek, now)
        assertEquals(-2_450L, native.frame().positionMs)
        assertEquals(compose.feedMs.longValue, native.frame().positionMs)
        assertEquals(2, native.frame().discontinuity)
        assertEquals(0L, PlaybackClock.positionMs(seek, now))
    }

    @Test
    fun nativeTimelineRejectsStaleOutputAndAdvancementChanges() {
        val native = NativeLyricsTimeline(MonotonicClock { 100 })
        native.accept(snapshot.copy(sampleMonotonicNanos = 100))
        native.accept(snapshot.copy(outputLatencyMs = 0, playWhenReady = false, isAudioAdvancing = false))

        assertEquals(8_150L, native.frame().positionMs)
        assertTrue(native.frame().advancing)
        assertEquals(1, native.frame().discontinuity)
    }
}

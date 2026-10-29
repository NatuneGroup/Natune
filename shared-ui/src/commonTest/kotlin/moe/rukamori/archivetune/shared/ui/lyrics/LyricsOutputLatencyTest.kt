package moe.rukamori.archivetune.shared.ui.lyrics

import moe.rukamori.archivetune.shared.domain.playback.PlaybackClock
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals

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
}

package moe.rukamori.archivetune.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PlaybackClockTest {
    private val oneSecond = 1_000_000_000L

    private fun snapshot(
        positionMs: Long = 10_000L,
        sampleMonotonicNanos: Long = 0L,
        durationMs: Long? = 200_000L,
        playbackSpeed: Float = 1f,
        playWhenReady: Boolean = true,
        isAudioAdvancing: Boolean = true,
        isBuffering: Boolean = false,
        outputRoute: PlaybackOutputRoute = PlaybackOutputRoute.LOCAL,
        discontinuityGeneration: Long = 0L,
    ) = PlaybackSnapshot(
        occurrenceId = "occurrence-1",
        mediaId = "media-1",
        playbackGeneration = 1L,
        discontinuityGeneration = discontinuityGeneration,
        positionMs = positionMs,
        sampleMonotonicNanos = sampleMonotonicNanos,
        durationMs = durationMs,
        isSeekable = true,
        playbackSpeed = playbackSpeed,
        playWhenReady = playWhenReady,
        isAudioAdvancing = isAudioAdvancing,
        isBuffering = isBuffering,
        outputRoute = outputRoute,
    )

    @Test
    fun positionAdvancesOnlyWhileAudioIsAdvancingAndNotBuffering() {
        for (advancing in listOf(true, false)) {
            for (buffering in listOf(true, false)) {
                val snapshot = snapshot(isAudioAdvancing = advancing, isBuffering = buffering)
                val expected = if (advancing && !buffering) 15_000L else 10_000L
                assertEquals(
                    expected,
                    PlaybackClock.positionMs(snapshot, 5 * oneSecond),
                    "isAudioAdvancing=$advancing isBuffering=$buffering",
                )
                assertEquals(advancing && !buffering, PlaybackClock.isInterpolating(snapshot))
            }
        }
    }

    @Test
    fun playIntentWithoutAudioEvidenceHoldsThePosition() {
        val snapshot = snapshot(playWhenReady = true, isAudioAdvancing = false)

        assertEquals(10_000L, PlaybackClock.positionMs(snapshot, oneSecond))
    }

    @Test
    fun remoteRouteHoldsThePositionEvenWhileAudioReportsAdvancing() {
        val snapshot = snapshot(outputRoute = PlaybackOutputRoute.REMOTE)

        assertEquals(10_000L, PlaybackClock.positionMs(snapshot, oneSecond))
        assertFalse(PlaybackClock.isInterpolating(snapshot))
    }

    @Test
    fun localAndUnknownRoutesInterpolate() {
        for (route in listOf(PlaybackOutputRoute.LOCAL, PlaybackOutputRoute.UNKNOWN)) {
            val snapshot = snapshot(outputRoute = route)

            assertEquals(11_000L, PlaybackClock.positionMs(snapshot, oneSecond), "route=$route")
        }
    }

    @Test
    fun positionScalesWithPlaybackSpeed() {
        assertEquals(12_000L, PlaybackClock.positionMs(snapshot(playbackSpeed = 2f), oneSecond))
        assertEquals(10_500L, PlaybackClock.positionMs(snapshot(playbackSpeed = 0.5f), oneSecond))
        assertEquals(11_250L, PlaybackClock.positionMs(snapshot(playbackSpeed = 1.25f), oneSecond))
    }

    @Test
    fun scaledElapsedTimeRoundsToTheNearestMillisecond() {
        // Five milliseconds at 1.25x is 6.25 milliseconds of media time.
        val fiveMilliseconds = 5_000_000L

        assertEquals(
            10_006L,
            PlaybackClock.positionMs(snapshot(playbackSpeed = 1.25f), fiveMilliseconds),
        )
    }

    @Test
    fun positionClampsToTheDuration() {
        val nearEnd = snapshot(positionMs = 199_000L, durationMs = 200_000L)

        assertEquals(200_000L, PlaybackClock.positionMs(nearEnd, 10 * oneSecond))
    }

    @Test
    fun positionClampsToZeroWhenTheSnapshotReportsANegativePosition() {
        val negative = snapshot(positionMs = -250L)

        assertEquals(0L, PlaybackClock.positionMs(negative, 0L))
        assertEquals(0L, PlaybackClock.positionMs(negative, 100_000_000L))
    }

    @Test
    fun unknownDurationLeavesTheUpperBoundOpen() {
        val unknown = snapshot(durationMs = null)

        assertEquals(10_000L + 3_600_000L, PlaybackClock.positionMs(unknown, 3600 * oneSecond))
    }

    @Test
    fun readingBeforeTheSampleTimeHoldsTheObservedPosition() {
        val sampled = snapshot(sampleMonotonicNanos = 5 * oneSecond)

        assertEquals(10_000L, PlaybackClock.positionMs(sampled, 4 * oneSecond))
    }

    @Test
    fun discontinuityResetsInterpolationToTheNewObservation() {
        val before = snapshot(positionMs = 10_000L, sampleMonotonicNanos = 0L)
        val seek = snapshot(
            positionMs = 60_000L,
            sampleMonotonicNanos = 2 * oneSecond,
            discontinuityGeneration = 1L,
        )

        // Without the reset, the old anchor would have run on to 12 s at the seek instant.
        assertEquals(12_000L, PlaybackClock.positionMs(before, 2 * oneSecond))

        val anchor = PlaybackClock.accept(before, seek)

        assertSame(seek, anchor)
        assertEquals(60_000L, PlaybackClock.positionMs(anchor, 2 * oneSecond))
        assertEquals(60_500L, PlaybackClock.positionMs(anchor, 2 * oneSecond + 500_000_000L))
    }

    @Test
    fun staleSnapshotFromBeforeTheDiscontinuityCannotMoveTheClock() {
        val seek = snapshot(
            positionMs = 60_000L,
            sampleMonotonicNanos = 2 * oneSecond,
            discontinuityGeneration = 1L,
        )
        val lateOld = snapshot(
            positionMs = 13_000L,
            sampleMonotonicNanos = 3 * oneSecond,
            discontinuityGeneration = 0L,
        )

        val anchor = PlaybackClock.accept(seek, lateOld)

        assertSame(seek, anchor)
        assertEquals(61_000L, PlaybackClock.positionMs(anchor, 3 * oneSecond))
    }

    @Test
    fun firstObservationBecomesTheAnchor() {
        val first = snapshot()

        assertSame(first, PlaybackClock.accept(null, first))
    }

    @Test
    fun laterObservationInTheSameGenerationReplacesTheAnchorAndEarlierOneIsIgnored() {
        val earlier = snapshot(positionMs = 10_000L, sampleMonotonicNanos = oneSecond)
        val later = snapshot(positionMs = 11_000L, sampleMonotonicNanos = 2 * oneSecond)

        assertSame(later, PlaybackClock.accept(earlier, later))
        assertSame(later, PlaybackClock.accept(later, earlier))
        assertTrue(later.supersedes(earlier))
    }
}

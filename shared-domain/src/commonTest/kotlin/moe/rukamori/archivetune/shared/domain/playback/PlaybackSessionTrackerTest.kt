/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSessionTrackerTest {
    @Test
    fun loadStartsAPlaybackGenerationAndSeeksCountAsDiscontinuities() {
        val tracker = PlaybackSessionTracker()
        tracker.beginSession("occ-1", "media-1")
        val beforeSeek = tracker.observe(observation(positionMs = 0L), sampleMonotonicNanos = 1L)
        assertEquals(1L, beforeSeek.playbackGeneration)
        assertEquals(0L, beforeSeek.discontinuityGeneration)

        tracker.recordDiscontinuity()
        tracker.recordDiscontinuity()
        val afterSeeks = tracker.observe(observation(positionMs = 3_500L), sampleMonotonicNanos = 2L)
        assertEquals(2L, afterSeeks.discontinuityGeneration)

        tracker.beginSession("occ-2", "media-2")
        val nextOccurrence = tracker.observe(observation(positionMs = 0L), sampleMonotonicNanos = 3L)
        assertEquals("occ-2", nextOccurrence.occurrenceId)
        assertEquals(2L, nextOccurrence.playbackGeneration)
        assertEquals(0L, nextOccurrence.discontinuityGeneration)
    }

    @Test
    fun audioIsNeverReportedAsAdvancingWhileBufferingOrEnded() {
        val tracker = PlaybackSessionTracker()
        tracker.beginSession("occ-1", "media-1")

        val buffering = tracker.observe(
            observation(isPlaying = true, isBuffering = true),
            sampleMonotonicNanos = 1L,
        )
        assertFalse(buffering.isAudioAdvancing)
        assertTrue(buffering.isBuffering)

        val ended = tracker.observe(
            observation(isPlaying = true, isEnded = true),
            sampleMonotonicNanos = 2L,
        )
        assertFalse(ended.isAudioAdvancing)
        assertTrue(ended.isEnded)

        val playing = tracker.observe(observation(isPlaying = true), sampleMonotonicNanos = 3L)
        assertTrue(playing.isAudioAdvancing)
    }

    @Test
    fun unknownDurationStaysNullAndBufferedPositionIsCarried() {
        val tracker = PlaybackSessionTracker()
        tracker.beginSession("occ-1", "media-1")

        val unknown = tracker.observe(
            observation(durationMs = null, bufferedPositionMs = 1_250L),
            sampleMonotonicNanos = 1L,
        )
        assertNull(unknown.durationMs)
        assertEquals(1_250L, unknown.bufferedPositionMs)
    }

    @Test
    fun remoteRouteKeepsSpeedButHoldsInterpolation() {
        val tracker = PlaybackSessionTracker()
        tracker.beginSession("occ-1", "media-1")

        val remote = tracker.observe(
            observation(isPlaying = true, speed = 1.5f, route = PlaybackOutputRoute.REMOTE),
            sampleMonotonicNanos = 1L,
        )

        assertEquals(1.5f, remote.playbackSpeed)
        assertFalse(PlaybackClock.isInterpolating(remote))
    }

    @Test
    fun observingOrDiscontinuingBeforeASessionIsRejected() {
        val tracker = PlaybackSessionTracker()

        assertFailsWith<IllegalStateException> { tracker.recordDiscontinuity() }
        assertFailsWith<IllegalStateException> {
            tracker.observe(observation(positionMs = 0L), sampleMonotonicNanos = 1L)
        }
    }

    private fun observation(
        positionMs: Long = 0L,
        durationMs: Long? = 4_000L,
        bufferedPositionMs: Long? = 4_000L,
        speed: Float = 1f,
        isPlaying: Boolean = false,
        isBuffering: Boolean = false,
        isEnded: Boolean = false,
        route: PlaybackOutputRoute = PlaybackOutputRoute.LOCAL,
    ) = PlaybackObservation(
        positionMs = positionMs,
        durationMs = durationMs,
        bufferedPositionMs = bufferedPositionMs,
        isSeekable = true,
        playbackSpeed = speed,
        playWhenReady = true,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        isEnded = isEnded,
        outputRoute = route,
    )
}

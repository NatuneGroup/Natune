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

class PlaybackSnapshotTest {
    private fun snapshot(
        playbackGeneration: Long = 1L,
        discontinuityGeneration: Long = 0L,
        sampleMonotonicNanos: Long = 0L,
        positionMs: Long = 0L,
        durationMs: Long? = 180_000L,
        playbackSpeed: Float = 1f,
    ) = PlaybackSnapshot(
        occurrenceId = "occurrence-1",
        mediaId = "media-1",
        playbackGeneration = playbackGeneration,
        discontinuityGeneration = discontinuityGeneration,
        positionMs = positionMs,
        sampleMonotonicNanos = sampleMonotonicNanos,
        durationMs = durationMs,
        isSeekable = true,
        playbackSpeed = playbackSpeed,
        playWhenReady = true,
        isAudioAdvancing = true,
        isBuffering = false,
        outputRoute = PlaybackOutputRoute.LOCAL,
    )

    @Test
    fun playbackSpeedMustBePositiveAndFinite() {
        for (speed in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("speed=$speed") {
                snapshot(playbackSpeed = speed)
            }
        }
    }

    @Test
    fun durationMayBeUnknownOrZeroButNeverNegative() {
        assertFailsWith<IllegalArgumentException> { snapshot(durationMs = -1L) }
        assertNull(snapshot(durationMs = null).durationMs)
        assertEquals(0L, snapshot(durationMs = 0L).durationMs)
    }

    @Test
    fun playbackGenerationOrdersBeforeDiscontinuityAndTime() {
        val newerSession = snapshot(playbackGeneration = 2L, discontinuityGeneration = 0L, sampleMonotonicNanos = 1L)
        val olderSession = snapshot(playbackGeneration = 1L, discontinuityGeneration = 9L, sampleMonotonicNanos = 9L)

        assertTrue(newerSession.supersedes(olderSession))
        assertFalse(olderSession.supersedes(newerSession))
    }

    @Test
    fun discontinuityGenerationOrdersBeforeObservationTime() {
        val seek = snapshot(discontinuityGeneration = 1L, sampleMonotonicNanos = 1L)
        val preSeekLate = snapshot(discontinuityGeneration = 0L, sampleMonotonicNanos = 9L)

        assertTrue(seek.supersedes(preSeekLate))
        assertFalse(preSeekLate.supersedes(seek))
    }

    @Test
    fun withinOneGenerationTheLaterObservationWins() {
        val earlier = snapshot(sampleMonotonicNanos = 1L)
        val later = snapshot(sampleMonotonicNanos = 2L)

        assertTrue(later.supersedes(earlier))
        assertFalse(earlier.supersedes(later))
    }

    @Test
    fun anEqualTimestampCountsAsNewerSoACorrectionReplacesItsPredecessor() {
        val first = snapshot(sampleMonotonicNanos = 7L, positionMs = 1_000L)
        val correction = snapshot(sampleMonotonicNanos = 7L, positionMs = 1_010L)

        assertTrue(correction.supersedes(first))
        assertTrue(first.supersedes(correction))
    }
}

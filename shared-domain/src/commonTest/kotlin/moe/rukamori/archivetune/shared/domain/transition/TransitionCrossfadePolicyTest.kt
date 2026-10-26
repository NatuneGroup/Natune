/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.domain.transition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Values from the shipped Android CrossfadePolicyTest, kept so the shared port cannot drift from them. */
class TransitionCrossfadePolicyTest {
    @Test
    fun effectiveDurationIsClampedToTheTrackAndMinimum() {
        assertEquals(5_000L, CrossfadePolicy.effectiveDurationMs(5_000L, 200_000L, 150L, 500L, -1L))
        assertEquals(9_850L, CrossfadePolicy.effectiveDurationMs(20_000L, 10_000L, 150L, 500L, -1L))
        assertEquals(500L, CrossfadePolicy.effectiveDurationMs(100L, 200_000L, 150L, 500L, -1L))
        assertNull(CrossfadePolicy.effectiveDurationMs(5_000L, -1L, 150L, 500L, -1L))
        assertNull(CrossfadePolicy.effectiveDurationMs(5_000L, 600L, 150L, 500L, -1L))
    }

    @Test
    fun repeatOneTargetsTheCurrentItemAndRepeatAllWraps() {
        assertEquals(2, CrossfadePolicy.resolveTargetIndex(repeatOne = true, currentIndex = 2, nextIndex = 3, itemCount = 10, unsetIndex = -1))
        assertEquals(3, CrossfadePolicy.resolveTargetIndex(repeatOne = false, currentIndex = 2, nextIndex = 3, itemCount = 10, unsetIndex = -1))
        assertEquals(0, CrossfadePolicy.resolveTargetIndex(repeatOne = false, currentIndex = 9, nextIndex = 0, itemCount = 10, unsetIndex = -1))
    }

    @Test
    fun invalidTargetsResolveToNoCrossfade() {
        assertNull(CrossfadePolicy.resolveTargetIndex(false, 9, -1, 10, -1))
        assertNull(CrossfadePolicy.resolveTargetIndex(false, 2, 2, 10, -1))
        assertNull(CrossfadePolicy.resolveTargetIndex(false, 2, 10, 10, -1))
        assertNull(CrossfadePolicy.resolveTargetIndex(true, 0, 0, 0, -1))
    }

    @Test
    fun readinessNeedsAReadyAndBufferedIncomingPlayer() {
        fun snapshot(ready: Boolean, idle: Boolean, ended: Boolean, error: Boolean, buffered: Boolean) =
            CrossfadePolicy.ReadinessSnapshot(ready, idle, ended, error, buffered)

        assertTrue(CrossfadePolicy.isReadyForFadeStart(snapshot(true, false, false, false, true)))
        assertFalse(CrossfadePolicy.isReadyForFadeStart(snapshot(true, false, false, false, false)))
        assertFalse(CrossfadePolicy.isReadyForFadeStart(snapshot(false, false, false, false, true)))
        assertTrue(CrossfadePolicy.mustAbortReadiness(snapshot(false, false, true, false, true)))
        assertTrue(CrossfadePolicy.mustAbortReadiness(snapshot(false, false, false, true, true)))
        assertFalse(CrossfadePolicy.mustAbortReadiness(snapshot(false, true, false, false, false)))
    }

    @Test
    fun audibleAdvancementRequiresPositionMovement() {
        fun snapshot(ready: Boolean, playing: Boolean, error: Boolean, position: Long, previous: Long) =
            CrossfadePolicy.AudioAdvancementSnapshot(ready, playing, error, position, previous)

        assertTrue(CrossfadePolicy.hasAudioAdvanced(snapshot(true, true, false, 1_250L, 1_100L)))
        assertFalse(CrossfadePolicy.hasAudioAdvanced(snapshot(true, true, false, 1_100L, 1_100L)))
        assertFalse(CrossfadePolicy.hasAudioAdvanced(snapshot(true, true, false, 0L, 0L)))
        assertFalse(CrossfadePolicy.hasAudioAdvanced(snapshot(true, false, false, 1_250L, 1_100L)))
        assertFalse(CrossfadePolicy.hasAudioAdvanced(snapshot(true, true, true, 1_250L, 1_100L)))
    }

    @Test
    fun outgoingElapsedTimeStopsAtTheFadeDuration() {
        assertEquals(300L, CrossfadePolicy.outgoingElapsedMs(startPositionMs = 500L, currentPositionMs = 800L, durationMs = 5_000L))
        assertEquals(1_000L, CrossfadePolicy.outgoingElapsedMs(startPositionMs = 500L, currentPositionMs = 9_000L, durationMs = 1_000L))
        assertEquals(0L, CrossfadePolicy.outgoingElapsedMs(startPositionMs = -1L, currentPositionMs = 800L, durationMs = 5_000L))
    }

    @Test
    fun fadeProgressOnlyCountsWhileTheIncomingDeckIsAdvancing() {
        assertEquals(
            1_000L,
            CrossfadePolicy.advanceFadeElapsedMs(1_000L, previousPositionMs = 500L, currentPositionMs = 800L, durationMs = 5_000L, incomingAdvancing = false),
        )
        assertEquals(
            1_300L,
            CrossfadePolicy.advanceFadeElapsedMs(1_000L, previousPositionMs = 500L, currentPositionMs = 800L, durationMs = 5_000L, incomingAdvancing = true),
        )
    }

    @Test
    fun aStalledIncomingDeckAbortsOnlyWhilePlaybackIsRequested() {
        assertTrue(CrossfadePolicy.shouldAbortForIncomingStall(playbackRequested = true, incomingAdvancing = false, stallElapsedMs = 3_000L, maximumStallMs = 3_000L))
        assertFalse(CrossfadePolicy.shouldAbortForIncomingStall(playbackRequested = true, incomingAdvancing = true, stallElapsedMs = 3_000L, maximumStallMs = 3_000L))
        assertFalse(CrossfadePolicy.shouldAbortForIncomingStall(playbackRequested = false, incomingAdvancing = false, stallElapsedMs = 9_999L, maximumStallMs = 3_000L))
        assertFalse(CrossfadePolicy.shouldAbortForIncomingStall(playbackRequested = true, incomingAdvancing = false, stallElapsedMs = 2_999L, maximumStallMs = 3_000L))
    }

    @Test
    fun promotionRequiresTheCurrentGenerationAndAHealthyIncomingPlayer() {
        fun snapshot(
            generationMatches: Boolean,
            targetIndex: Int,
            incomingItemMatchesTarget: Boolean = true,
            error: Boolean = false,
            idle: Boolean = false,
            ended: Boolean = false,
            ready: Boolean = true,
            playing: Boolean = true,
            playbackRequested: Boolean = true,
        ) = CrossfadePolicy.PromotionSnapshot(
            generationMatches = generationMatches,
            targetIndex = targetIndex,
            incomingItemMatchesTarget = incomingItemMatchesTarget,
            hasError = error,
            isIdle = idle,
            isEnded = ended,
            isReady = ready,
            isPlaying = playing,
            playbackRequested = playbackRequested,
            unsetIndex = -1,
        )

        assertTrue(CrossfadePolicy.mayPromote(snapshot(true, 3)))
        assertFalse(CrossfadePolicy.mayPromote(snapshot(false, 3)))
        assertFalse(CrossfadePolicy.mayPromote(snapshot(true, 3, error = true)))
        assertFalse(CrossfadePolicy.mayPromote(snapshot(true, 3, idle = true)))
        assertFalse(CrossfadePolicy.mayPromote(snapshot(true, 3, ended = true)))
        assertFalse(CrossfadePolicy.mayPromote(snapshot(true, 3, incomingItemMatchesTarget = false)))
        assertFalse(CrossfadePolicy.mayPromote(snapshot(true, 3, ready = false)))
        assertFalse(CrossfadePolicy.mayPromote(snapshot(true, 3, playing = false)))
        assertTrue(CrossfadePolicy.mayPromote(snapshot(true, 3, playing = false, playbackRequested = false)))
        assertFalse(CrossfadePolicy.mayPromote(snapshot(true, -1)))
    }

    @Test
    fun outgoingRanOutPromotesWhileTheIncomingDeckOnlyBuffers() {
        val buffering = CrossfadePolicy.PromotionSnapshot(
            generationMatches = true,
            targetIndex = 4,
            incomingItemMatchesTarget = true,
            hasError = false,
            isIdle = false,
            isEnded = false,
            isReady = false,
            isPlaying = false,
            playbackRequested = true,
            unsetIndex = -1,
            outgoingRanOut = true,
        )
        assertTrue(CrossfadePolicy.mayPromote(buffering))
        assertFalse(CrossfadePolicy.mayPromote(buffering.copy(outgoingRanOut = false)))
    }

    @Test
    fun endedPlaybackResumesOnlyWithANextItemOrRepeatOne() {
        assertTrue(CrossfadePolicy.shouldResumeAfterEnded(playbackRequested = true, hasNextItem = true, repeatOne = false, suppressAutoPlayback = false))
        assertTrue(CrossfadePolicy.shouldResumeAfterEnded(playbackRequested = true, hasNextItem = false, repeatOne = true, suppressAutoPlayback = false))
        assertFalse(CrossfadePolicy.shouldResumeAfterEnded(playbackRequested = true, hasNextItem = true, repeatOne = false, suppressAutoPlayback = true))
        assertFalse(CrossfadePolicy.shouldResumeAfterEnded(playbackRequested = true, hasNextItem = false, repeatOne = false, suppressAutoPlayback = false))
    }
}

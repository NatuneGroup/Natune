package moe.rukamori.archivetune.shared.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamRecoveryTrackerTest {
    @Test
    fun readyDoesNotRefillTheSingleFreshAttempt() {
        val tracker = StreamRecoveryTracker<AudioSourceType>()
        assertTrue(tracker.onFailure("song", AudioSourceType.TIDAL))
        assertEquals(AudioSourceType.TIDAL, tracker.refreshSource("song"))
        tracker.resolved("song")
        assertNull(tracker.refreshSource("song"))
        assertFalse(tracker.onFailure("song", AudioSourceType.TIDAL))
        assertEquals(setOf(AudioSourceType.TIDAL), tracker.rejectedSources("song"))
        assertTrue(tracker.onFailure("song", AudioSourceType.QOBUZ))
    }

    @Test
    fun anUnresolvedFreshAttemptRejectsOnlyItsOwnSource() {
        val tracker = StreamRecoveryTracker<AudioSourceType>()
        tracker.onFailure("song", AudioSourceType.DEEZER)
        tracker.reject("song", AudioSourceType.DEEZER)
        assertNull(tracker.refreshSource("song"))
        assertFalse(tracker.onFailure("song", AudioSourceType.DEEZER))
        assertTrue(tracker.onFailure("other", AudioSourceType.DEEZER))
    }

    @Test
    fun explicitReplayAndQueueTrimmingReleaseOldBudgets() {
        val tracker = StreamRecoveryTracker<AudioSourceType>()
        tracker.onFailure("song", AudioSourceType.SPOTIFY)
        tracker.onFailure("other", AudioSourceType.SPOTIFY)
        tracker.clear("song")
        assertTrue(tracker.onFailure("song", AudioSourceType.SPOTIFY))
        tracker.retainSongs { it == "song" }
        assertTrue(tracker.onFailure("other", AudioSourceType.SPOTIFY))
    }
}

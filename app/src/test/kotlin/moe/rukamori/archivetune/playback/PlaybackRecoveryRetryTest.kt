package moe.rukamori.archivetune.playback

import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.constants.AudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRecoveryRetryTest {
    private data class Fire(val mediaId: String, val resumePlaying: Boolean, val seekIndex: Int, val seekPositionMs: Long)

    private var now = 0L
    private val monitor = RebufferMonitor { now }
    private val tracker = PlaybackStreamRecoveryTracker()
    private val fired = mutableListOf<Fire>()

    /** Tell the monitor which item the player currently holds; the gate rejects prepares for anything else. */
    private fun track(mediaId: String) =
        monitor.onPlaybackStateChanged(mediaId, buffering = false, ready = true, playWhenReady = true)

    private fun stall(mediaId: String, durationMs: Long = 2_000L) {
        monitor.onPlaybackStateChanged(mediaId, buffering = true, ready = false, playWhenReady = true)
        now += durationMs
        monitor.onPlaybackStateChanged(mediaId, buffering = false, ready = true, playWhenReady = true)
    }

    private fun newGate(scope: kotlinx.coroutines.CoroutineScope) =
        PlaybackRecoveryRetry(
            scope = { scope },
            player = { throw AssertionError("the default fire never runs in scheduler tests") },
            tracker = tracker,
            rebuffer = monitor,
            backoff = PlaybackRetryBackoff(baseDelayMs = 1_000L, maxDelayMs = 60_000L, random = Random(3)),
            nowMs = { now },
            fire = { mediaId, resumePlaying, seekIndex, seekPositionMs ->
                fired += Fire(mediaId, resumePlaying, seekIndex, seekPositionMs)
            },
        )

    private fun freeze(mediaId: String) {
        monitor.onPlaybackStateChanged(mediaId, buffering = true, ready = false, playWhenReady = true)
        now += 1_000L
        monitor.onPlaybackStateChanged(mediaId, buffering = false, ready = true, playWhenReady = true)
        repeat(5) { stall(mediaId) }
    }

    @Test
    fun `the first retry fires immediately`() = runTest {
        track("song")
        val gate = newGate(this)
        assertTrue(gate.allow("song"))
        gate.prepare("song", resumePlaying = true)
        assertEquals(listOf(Fire("song", true, androidx.media3.common.C.INDEX_UNSET, 0L)), fired)
    }

    @Test
    fun `a later retry waits out the backoff and then fires once`() = runTest {
        track("song")
        val gate = newGate(this)
        gate.allow("song")
        gate.allow("song")
        gate.prepare("song", resumePlaying = false)
        assertTrue(fired.isEmpty())
        advanceUntilIdle()
        assertEquals(1, fired.size)
        assertEquals("song", fired[0].mediaId)
    }

    @Test
    fun `a stop command cancels a pending retry`() = runTest {
        track("song")
        val gate = newGate(this)
        gate.allow("song")
        gate.allow("song")
        gate.prepare("song", resumePlaying = true)
        gate.cancelPending()
        advanceUntilIdle()
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `a seek cancels a pending retry`() = runTest {
        track("song")
        val gate = newGate(this)
        gate.allow("song")
        gate.allow("song")
        gate.prepare("song", resumePlaying = true)
        gate.onSeek()
        advanceUntilIdle()
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `a pause cancels a pending retry`() = runTest {
        track("song")
        val gate = newGate(this)
        gate.allow("song")
        gate.allow("song")
        gate.prepare("song", resumePlaying = true)
        gate.onPlayWhenReadyChanged(false)
        advanceUntilIdle()
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `a media transition cancels a pending retry`() = runTest {
        track("song")
        val gate = newGate(this)
        gate.allow("song")
        gate.allow("song")
        gate.prepare("song", resumePlaying = true)
        gate.onMediaItemTransition("next-song")
        advanceUntilIdle()
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `a newer prepare supersedes the pending one`() = runTest {
        track("song")
        val gate = newGate(this)
        gate.allow("song")
        gate.allow("song")
        gate.prepare("song", resumePlaying = true)
        gate.prepare("song", resumePlaying = false, seekIndex = 1)
        advanceUntilIdle()
        assertEquals(listOf(Fire("song", false, 1, 0L)), fired)
    }

    @Test
    fun `a generation invalidated retry never fires even if it runs late`() = runTest {
        track("song")
        val gate = newGate(this)
        gate.allow("song")
        gate.allow("song")
        gate.prepare("song", resumePlaying = true)
        gate.cancelPending()
        gate.prepare("song", resumePlaying = false, seekIndex = 2)
        advanceUntilIdle()
        assertEquals(listOf(Fire("song", false, 2, 0L)), fired)
    }

    @Test
    fun `a stale prepare never fires`() = runTest {
        track("current")
        val gate = newGate(this)
        gate.prepare("gone", resumePlaying = true)
        advanceUntilIdle()
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `a stale prepare does not cancel the pending retry of the current item`() = runTest {
        track("next")
        val gate = newGate(this)
        gate.allow("next")
        gate.allow("next")
        gate.prepare("next", resumePlaying = true)
        // The bad-response refresh for the previous item resolves only now; it must not
        // supersede the retry the current item already owns.
        gate.prepare("previous", resumePlaying = true)
        advanceUntilIdle()
        assertEquals(listOf(Fire("next", true, androidx.media3.common.C.INDEX_UNSET, 0L)), fired)
    }

    @Test
    fun `a recovery seek is not consumed as a user seek`() = runTest {
        track("song")
        val gate = newGate(this)
        gate.prepare("song", resumePlaying = false, seekIndex = 1, seekPositionMs = 5_000L)
        // The discontinuity the recovery seek produced: consumed by the gate, never tagging
        // the monitor's next stall as seek-originated.
        gate.onSeek()
        repeat(3) { stall("song") }
        assertTrue(monitor.shouldStepDownQuality("song"))
    }

    @Test
    fun `an ignored recovery seek does not swallow a later user seek`() = runTest {
        track("song")
        val gate = newGate(this)
        gate.prepare("song", resumePlaying = false, seekIndex = 1, seekPositionMs = 5_000L)
        // The player ignored it (its index left the timeline during the backoff): no
        // discontinuity ever arrives, so the mark must expire and the next user seek behave
        // normally instead of being consumed.
        now += 5_000L
        gate.onSeek()
        repeat(3) { stall("song") }
        assertFalse(monitor.shouldStepDownQuality("song"))
    }

    @Test
    fun `a user seek after the recovery seek still tags stalls`() = runTest {
        track("song")
        val gate = newGate(this)
        gate.prepare("song", resumePlaying = false, seekIndex = 1, seekPositionMs = 5_000L)
        gate.onSeek() // recovery discontinuity — consumed
        gate.onSeek() // a real user seek
        repeat(3) { stall("song") }
        assertFalse(monitor.shouldStepDownQuality("song"))
    }

    @Test
    fun `minDelayMs floors the delay even on a first attempt`() = runTest {
        track("song")
        val gate = newGate(this)
        gate.prepare("song", resumePlaying = false, minDelayMs = 400L)
        assertTrue(fired.isEmpty())
        advanceUntilIdle()
        assertEquals(1, fired.size)
    }

    @Test
    fun `fire arguments carry the seek target`() = runTest {
        track("song")
        val gate = newGate(this)
        gate.prepare("song", resumePlaying = false, seekIndex = 2, seekPositionMs = 45_000L)
        assertEquals(listOf(Fire("song", false, 2, 45_000L)), fired)
    }

    @Test
    fun `a fire failure is routed to onFailure`() = runTest {
        track("song")
        var failed: Throwable? = null
        val boom = IllegalStateException("prepare blew up")
        val gate =
            PlaybackRecoveryRetry(
                scope = { this },
                player = { throw AssertionError() },
                tracker = tracker,
                rebuffer = monitor,
                fire = { _, _, _, _ -> throw boom },
            )
        gate.prepare("song", resumePlaying = true, onFailure = { failed = it })
        assertEquals(boom, failed)
    }

    @Test
    fun `a frozen track may not retry and spends no attempt`() {
        freeze("song")
        val gate = newGate(kotlinx.coroutines.test.TestScope())
        assertFalse(gate.allow("song"))
        assertEquals(0, tracker.attemptCount("song"))
    }

    @Test
    fun `audioQuality steps down only for a degraded track`() {
        freeze("song")
        val gate = newGate(kotlinx.coroutines.test.TestScope())
        assertEquals(AudioQuality.LOW, gate.audioQuality("song", lowData = false, configured = AudioQuality.AUTO))
        assertEquals(AudioQuality.AUTO, gate.audioQuality("other", lowData = false, configured = AudioQuality.AUTO))
        assertEquals(AudioQuality.LOW, gate.audioQuality("other", lowData = true, configured = AudioQuality.AUTO))
    }
}

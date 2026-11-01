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

    private fun newGate(scope: kotlinx.coroutines.CoroutineScope) =
        PlaybackRecoveryRetry(
            scope = { scope },
            player = { throw AssertionError("the default fire never runs in scheduler tests") },
            tracker = tracker,
            rebuffer = monitor,
            playerCache = { throw AssertionError("the cache is not read unless degraded") },
            backoff = PlaybackRetryBackoff(baseDelayMs = 1_000L, maxDelayMs = 60_000L, random = Random(3)),
            fire = { mediaId, resumePlaying, seekIndex, seekPositionMs ->
                fired += Fire(mediaId, resumePlaying, seekIndex, seekPositionMs)
            },
        )

    private fun freeze(mediaId: String) {
        monitor.onPlaybackStateChanged(mediaId, buffering = true, ready = false, playWhenReady = true)
        now += 1_000L
        monitor.onPlaybackStateChanged(mediaId, buffering = false, ready = true, playWhenReady = true)
        repeat(5) {
            monitor.onPlaybackStateChanged(mediaId, buffering = true, ready = false, playWhenReady = true)
            now += 2_000L
            monitor.onPlaybackStateChanged(mediaId, buffering = false, ready = true, playWhenReady = true)
        }
    }

    @Test
    fun `the first retry fires immediately`() = runTest {
        val retry = newGate(this)
        assertTrue(retry.allow("song"))
        retry.prepare("song", resumePlaying = true)
        assertEquals(listOf(Fire("song", true, androidx.media3.common.C.INDEX_UNSET, 0L)), fired)
    }

    @Test
    fun `a later retry waits out the backoff and then fires once`() = runTest {
        val retry = newGate(this)
        retry.allow("song")
        retry.allow("song")
        retry.prepare("song", resumePlaying = false)
        assertTrue(fired.isEmpty())
        advanceUntilIdle()
        assertEquals(1, fired.size)
        assertEquals("song", fired[0].mediaId)
    }

    @Test
    fun `stop cancels a pending retry`() = runTest {
        val retry = newGate(this)
        retry.allow("song")
        retry.allow("song")
        retry.prepare("song", resumePlaying = true)
        retry.cancelPending()
        advanceUntilIdle()
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `a seek cancels a pending retry`() = runTest {
        val retry = newGate(this)
        retry.allow("song")
        retry.allow("song")
        retry.prepare("song", resumePlaying = true)
        retry.onSeek()
        advanceUntilIdle()
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `a media transition cancels a pending retry`() = runTest {
        val retry = newGate(this)
        retry.allow("song")
        retry.allow("song")
        retry.prepare("song", resumePlaying = true)
        retry.onMediaItemTransition("next-song")
        advanceUntilIdle()
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `a newer prepare supersedes the pending one`() = runTest {
        val retry = newGate(this)
        retry.allow("song")
        retry.allow("song")
        retry.prepare("song", resumePlaying = true)
        retry.prepare("other", resumePlaying = true)
        advanceUntilIdle()
        assertEquals(listOf("other"), fired.map { it.mediaId })
    }

    @Test
    fun `a generation invalidated retry never fires even if it runs late`() = runTest {
        val retry = newGate(this)
        retry.allow("song")
        retry.allow("song")
        retry.prepare("song", resumePlaying = true)
        retry.cancelPending()
        retry.prepare("other", resumePlaying = true)
        advanceUntilIdle()
        assertEquals(listOf("other"), fired.map { it.mediaId })
    }

    @Test
    fun `minDelayMs floors the delay even on a first attempt`() = runTest {
        val retry = newGate(this)
        retry.prepare("song", resumePlaying = false, minDelayMs = 400L)
        assertTrue(fired.isEmpty())
        advanceUntilIdle()
        assertEquals(1, fired.size)
    }

    @Test
    fun `fire arguments carry the seek target`() = runTest {
        val retry = newGate(this)
        retry.prepare("song", resumePlaying = false, seekIndex = 2, seekPositionMs = 45_000L)
        assertEquals(listOf(Fire("song", false, 2, 45_000L)), fired)
    }

    @Test
    fun `a fire failure is routed to onFailure`() = runTest {
        var failed: Throwable? = null
        val boom = IllegalStateException("prepare blew up")
        val retry =
            PlaybackRecoveryRetry(
                scope = { this },
                player = { throw AssertionError() },
                tracker = tracker,
                rebuffer = monitor,
                playerCache = { throw AssertionError() },
                fire = { _, _, _, _ -> throw boom },
            )
        retry.prepare("song", resumePlaying = true, onFailure = { failed = it })
        assertEquals(boom, failed)
    }

    @Test
    fun `a frozen track may not retry and spends no attempt`() {
        freeze("song")
        val retry = newGate(kotlinx.coroutines.test.TestScope())
        assertFalse(retry.allow("song"))
        assertEquals(0, tracker.attemptCount("song"))
    }

    @Test
    fun `audioQuality steps down only for a degraded track`() {
        freeze("song")
        val retry = newGate(kotlinx.coroutines.test.TestScope())
        assertEquals(AudioQuality.LOW, retry.audioQuality("song", lowData = false, configured = AudioQuality.AUTO))
        assertEquals(AudioQuality.AUTO, retry.audioQuality("other", lowData = false, configured = AudioQuality.AUTO))
        assertEquals(AudioQuality.LOW, retry.audioQuality("other", lowData = true, configured = AudioQuality.AUTO))
    }
}

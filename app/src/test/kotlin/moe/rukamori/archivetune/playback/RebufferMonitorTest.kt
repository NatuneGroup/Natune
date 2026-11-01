package moe.rukamori.archivetune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RebufferMonitorTest {
    private var now = 0L
    private val monitor = RebufferMonitor { now }

    private fun buffering(mediaId: String, playWhenReady: Boolean = true) {
        monitor.onPlaybackStateChanged(mediaId, buffering = true, ready = false, playWhenReady = playWhenReady)
    }

    private fun ready(mediaId: String) {
        monitor.onPlaybackStateChanged(mediaId, buffering = false, ready = true, playWhenReady = true)
    }

    private fun idle(mediaId: String) {
        monitor.onPlaybackStateChanged(mediaId, buffering = false, ready = false, playWhenReady = true)
    }

    private fun stall(mediaId: String, durationMs: Long = 2_000L) {
        buffering(mediaId)
        now += durationMs
        ready(mediaId)
    }

    @Test
    fun `initial buffering before the first ready is not a rebuffer`() {
        buffering("song")
        now += 30_000L
        ready("song")
        repeat(2) { stall("song") }
        assertFalse(monitor.shouldStepDownQuality("song"))
        assertFalse(monitor.shouldSuppressRetries("song"))
    }

    @Test
    fun `a stall shorter than the minimum is a seek flicker and does not count`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(5) { stall("song", durationMs = 400L) }
        assertFalse(monitor.shouldStepDownQuality("song"))
    }

    @Test
    fun `buffering while playback is not wanted does not count`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(5) {
            buffering("song", playWhenReady = false)
            now += 5_000L
            ready("song")
        }
        assertFalse(monitor.shouldStepDownQuality("song"))
    }

    @Test
    fun `three real stalls inside the window step the quality down`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(3) {
            stall("song")
            now += 10_000L
        }
        assertTrue(monitor.shouldStepDownQuality("song"))
        assertFalse(monitor.shouldSuppressRetries("song"))
        assertFalse(monitor.shouldStepDownQuality("other-song"))
    }

    @Test
    fun `five real stalls inside the window freeze retries`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(5) {
            stall("song")
            now += 5_000L
        }
        assertTrue(monitor.shouldStepDownQuality("song"))
        assertTrue(monitor.shouldSuppressRetries("song"))
        assertFalse(monitor.shouldSuppressRetries("other-song"))
    }

    @Test
    fun `a stall that ends in an error state still counts`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(3) {
            buffering("song")
            now += 2_000L
            idle("song")
            now += 1_000L
        }
        assertTrue(monitor.shouldStepDownQuality("song"))
    }

    @Test
    fun `the freeze fades once the oldest stalls leave the window`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(5) { stall("song") }
        assertTrue(monitor.shouldSuppressRetries("song"))
        // Play cleanly past the window; each state change prunes the expired stalls.
        now += 91_000L
        ready("song")
        assertFalse(monitor.shouldSuppressRetries("song"))
        assertFalse(monitor.shouldStepDownQuality("song"))
    }

    @Test
    fun `a media transition starts a fresh count`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(5) { stall("song") }
        assertTrue(monitor.shouldSuppressRetries("song"))
        buffering("next-song")
        now += 1_000L
        ready("next-song")
        assertFalse(monitor.shouldSuppressRetries("next-song"))
        assertFalse(monitor.shouldStepDownQuality("next-song"))
    }

    @Test
    fun `an open stall counts provisionally and is dropped when it closes short`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(4) { stall("song") }
        buffering("song")
        assertTrue(monitor.shouldSuppressRetries("song"))
        // The fifth stall clears inside the seek-flicker bound: it never was a rebuffer.
        now += 500L
        ready("song")
        assertFalse(monitor.shouldSuppressRetries("song"))
        assertTrue(monitor.shouldStepDownQuality("song"))
    }
}

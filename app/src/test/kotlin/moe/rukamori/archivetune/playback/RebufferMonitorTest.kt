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
    fun `a transition onto the same id starts a fresh count too`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(5) { stall("song") }
        assertTrue(monitor.shouldSuppressRetries("song"))
        // Repeat-one and duplicate occurrences transition without leaving READY.
        monitor.onMediaItemChanged("song", ready = true, buffering = false)
        assertFalse(monitor.shouldSuppressRetries("song"))
        assertFalse(monitor.shouldStepDownQuality("song"))
        repeat(2) { stall("song") }
        assertFalse(monitor.shouldStepDownQuality("song"))
    }

    @Test
    fun `a gapless transition seeds ready so the new track's stalls count`() {
        // An AUTO transition can land without the player ever leaving READY; the seeded state
        // must stand in for the state event the monitor will never see for this track.
        monitor.onMediaItemChanged("song", ready = true, buffering = false)
        repeat(3) { stall("song") }
        assertTrue(monitor.shouldStepDownQuality("song"))
    }

    @Test
    fun `an unseeded transition still waits for ready before counting`() {
        monitor.onMediaItemChanged("song", ready = false, buffering = false)
        repeat(3) { stall("song") }
        assertFalse(monitor.shouldStepDownQuality("song"))
    }

    @Test
    fun `an open stall does not count until it reaches the minimum duration`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(4) { stall("song") }
        // The fifth stall has barely begun: it must not tip the count yet.
        buffering("song")
        now += 500L
        assertFalse(monitor.shouldSuppressRetries("song"))
        assertTrue(monitor.shouldStepDownQuality("song"))
    }

    @Test
    fun `an open stall counts once it reaches the minimum duration`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(4) { stall("song") }
        buffering("song")
        now += 1_600L
        assertTrue(monitor.shouldSuppressRetries("song"))
    }

    @Test
    fun `an open stall older than the window does not count`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(4) { stall("song") }
        buffering("song")
        now += 91_000L
        assertFalse(monitor.shouldSuppressRetries("song"))
    }

    @Test
    fun `a stall opened right after a seek is discarded`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(5) {
            monitor.onSeek()
            stall("song")
            now += 10_000L
        }
        assertFalse(monitor.shouldStepDownQuality("song"))
        assertFalse(monitor.shouldSuppressRetries("song"))
    }

    @Test
    fun `a stall opened well after a seek is real`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(3) {
            monitor.onSeek()
            now += 10_000L
            stall("song")
        }
        assertTrue(monitor.shouldStepDownQuality("song"))
    }

    @Test
    fun `a seek inside an open stall closes it instead of discarding it`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(4) { stall("song") }
        buffering("song")
        now += 5_000L
        monitor.onSeek()
        assertTrue(monitor.shouldSuppressRetries("song"))
    }

    @Test
    fun `a pause inside a qualified stall still counts its pre-pause length`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(4) { stall("song") }
        buffering("song")
        now += 2_000L
        monitor.onPlayWhenReadyChanged(false)
        // 2 s of genuine rebuffering already qualified: pausing must not erase it.
        assertTrue(monitor.shouldSuppressRetries("song"))
    }

    @Test
    fun `paused time is excluded and resuming while still buffering opens a fresh stall`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(3) { stall("song") }
        buffering("song")
        now += 2_000L
        monitor.onPlayWhenReadyChanged(false)
        // The paused stretch belongs to neither stall.
        now += 60_000L
        monitor.onPlayWhenReadyChanged(true)
        now += 1_600L
        ready("song")
        // 3 + pre-pause stall + resumed stall = 5; without the resume reopen it would stay 4.
        assertTrue(monitor.shouldSuppressRetries("song"))
    }

    @Test
    fun `a pause inside a short stall discards it`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(3) { stall("song") }
        buffering("song")
        now += 400L
        monitor.onPlayWhenReadyChanged(false)
        ready("song")
        assertTrue(monitor.shouldStepDownQuality("song"))
        assertFalse(monitor.shouldSuppressRetries("song"))
    }

    @Test
    fun `a resume while ready opens no stall`() {
        buffering("song")
        now += 1_000L
        ready("song")
        repeat(2) { stall("song") }
        monitor.onPlayWhenReadyChanged(false)
        monitor.onPlayWhenReadyChanged(true)
        now += 1_600L
        ready("song")
        // Still 2: had the resume opened a stall, it would already have qualified and made 3.
        assertFalse(monitor.shouldStepDownQuality("song"))
    }
}

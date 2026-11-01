package moe.rukamori.archivetune.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBufferPolicyTest {
    @Test
    fun aNormalLinkKeepsTheLongStandingDurations() {
        assertEquals(
            PlaybackBufferPolicy.Durations(
                minBufferMs = 20_000,
                maxBufferMs = 60_000,
                forPlaybackMs = 150,
                forPlaybackAfterRebufferMs = 750,
            ),
            PlaybackBufferPolicy.primary(lean = false, metered = false),
        )
    }

    @Test
    fun aMeteredLinkRaisesOnlyTheStartThresholds() {
        assertEquals(
            PlaybackBufferPolicy.Durations(
                // The watermarks stay put: a slow link must not grow the heap it holds.
                minBufferMs = 20_000,
                maxBufferMs = 60_000,
                forPlaybackMs = 1_000,
                forPlaybackAfterRebufferMs = 2_000,
            ),
            PlaybackBufferPolicy.primary(lean = false, metered = true),
        )
    }

    @Test
    fun leanDevicesKeepTheirConservativeProfileEvenOnAMeteredLink() {
        assertEquals(
            PlaybackBufferPolicy.Durations(
                minBufferMs = 8_000,
                maxBufferMs = 24_000,
                forPlaybackMs = 250,
                forPlaybackAfterRebufferMs = 1_000,
            ),
            PlaybackBufferPolicy.primary(lean = true, metered = false),
        )
        assertEquals(
            PlaybackBufferPolicy.primary(lean = true, metered = true),
            PlaybackBufferPolicy.primary(lean = true, metered = false),
        )
    }
}

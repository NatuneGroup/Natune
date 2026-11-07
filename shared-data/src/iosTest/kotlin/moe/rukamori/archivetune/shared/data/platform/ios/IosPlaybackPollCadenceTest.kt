package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosPlaybackPollCadenceTest {
    @Test
    fun theActiveRatesAreUnchanged() {
        val cadence = IosPlaybackPollCadence()
        assertEquals(50L, cadence.intervalMs(busy = true, blending = false))
        assertEquals(20L, cadence.intervalMs(busy = true, blending = true))
        assertEquals(250L, cadence.intervalMs(busy = false, blending = false))
    }

    @Test
    fun aPlayingAppInTheBackgroundReadsTenTimesLessOften() {
        val cadence = IosPlaybackPollCadence()
        val active = cadence.intervalMs(busy = true, blending = false)
        cadence.appActive = false
        val background = cadence.intervalMs(busy = true, blending = false)
        assertEquals(active * 10, background)
        assertTrue(background <= 1_000L, "a backgrounded read must still surface in under a second")
    }

    @Test
    fun anIdleAppUsesTheSlowRateInEitherState() {
        val cadence = IosPlaybackPollCadence()
        val idleActive = cadence.intervalMs(busy = false, blending = true)
        cadence.appActive = false
        val idleBackground = cadence.intervalMs(busy = false, blending = true)
        assertTrue(idleBackground >= idleActive)
    }

    @Test
    fun aPlayingBlendKeepsItsPumpRateInTheBackground() {
        val cadence = IosPlaybackPollCadence()
        val active = cadence.intervalMs(busy = true, blending = true)
        cadence.appActive = false
        assertEquals(active, cadence.intervalMs(busy = true, blending = true))
        assertTrue(
            active < IOS_BLEND_QUEUED_PCM_MS,
            "the read must refill the blend decks before they run dry",
        )
    }

    private companion object {
        /** Eight 1,024-frame buffers at 48 kHz, the PCM one blend deck holds between reads. */
        const val IOS_BLEND_QUEUED_PCM_MS = 8 * 1_024 * 1_000L / 48_000L
    }
}

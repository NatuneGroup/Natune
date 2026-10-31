package moe.rukamori.archivetune.shared.ui.marquee

import kotlin.test.Test
import kotlin.test.assertEquals

private class ManualClock {
    var nowMs = 0L
}

private fun passKey(index: Int) = MarqueePhaseKey(caller = "row", text = "text-$index")

private val passTiming = MarqueeTiming(initialDelayMs = 1_000L, iterations = 2, restMs = 500L)

class MarqueePhasesTest {
    @Test fun observedKeyKeepsItsPassStartWhileItIsObserved() {
        val clock = ManualClock()
        val store = MarqueePhaseStore(clockMs = { clock.nowMs })
        assertEquals(0L, store.begin(passKey(0)))
        clock.nowMs = 2 * MARQUEE_PHASE_EXPIRY_MS
        assertEquals(0L, store.begin(passKey(0)))
    }

    @Test fun unobservedKeyKeepsItsPassUntilTheExpiryLapses() {
        val clock = ManualClock()
        val store = MarqueePhaseStore(clockMs = { clock.nowMs })
        store.begin(passKey(0))
        store.end(passKey(0))
        clock.nowMs = MARQUEE_PHASE_EXPIRY_MS
        assertEquals(0L, store.begin(passKey(0)))
        store.end(passKey(0))
        clock.nowMs = 2 * MARQUEE_PHASE_EXPIRY_MS + 1
        assertEquals(clock.nowMs, store.begin(passKey(0)))
    }

    @Test fun capacityEvictsTheLeastRecentlyUsedUnobservedKey() {
        val clock = ManualClock()
        val store = MarqueePhaseStore(clockMs = { clock.nowMs })
        for (index in 0 until MARQUEE_PHASE_CAPACITY) {
            clock.nowMs = index.toLong()
            store.begin(passKey(index))
            store.end(passKey(index))
        }
        clock.nowMs = MARQUEE_PHASE_CAPACITY.toLong()
        store.begin(passKey(0))
        store.end(passKey(0))
        clock.nowMs = MARQUEE_PHASE_CAPACITY.toLong() + 1
        store.begin(passKey(MARQUEE_PHASE_CAPACITY))
        store.end(passKey(MARQUEE_PHASE_CAPACITY))
        clock.nowMs = MARQUEE_PHASE_CAPACITY.toLong() + 2
        assertEquals(0L, store.begin(passKey(0)))
        store.end(passKey(0))
        clock.nowMs = MARQUEE_PHASE_CAPACITY.toLong() + 3
        assertEquals(clock.nowMs, store.begin(passKey(1)))
    }

    @Test fun observedKeyIsNotEvictedWhileUnobservedKeysRemain() {
        val clock = ManualClock()
        val store = MarqueePhaseStore(clockMs = { clock.nowMs })
        assertEquals(0L, store.begin(passKey(0)))
        for (index in 1..MARQUEE_PHASE_CAPACITY) {
            clock.nowMs = index.toLong()
            store.begin(passKey(index))
            store.end(passKey(index))
        }
        clock.nowMs = MARQUEE_PHASE_CAPACITY.toLong() + 1
        assertEquals(0L, store.begin(passKey(0)))
        clock.nowMs = MARQUEE_PHASE_CAPACITY.toLong() + 2
        assertEquals(clock.nowMs, store.begin(passKey(1)))
    }

    @Test fun leftFadeRampsInAtTheStartAndOutAtTheEndOfThePass() {
        assertEquals(0f, marqueeLeftFadePx(offsetPx = 0f, travelPx = 100f, fadePx = 24f))
        assertEquals(10f, marqueeLeftFadePx(offsetPx = 10f, travelPx = 100f, fadePx = 24f))
        assertEquals(24f, marqueeLeftFadePx(offsetPx = 50f, travelPx = 100f, fadePx = 24f))
        assertEquals(5f, marqueeLeftFadePx(offsetPx = 95f, travelPx = 100f, fadePx = 24f))
        assertEquals(0f, marqueeLeftFadePx(offsetPx = 100f, travelPx = 100f, fadePx = 24f))
        assertEquals(0f, marqueeLeftFadePx(offsetPx = 120f, travelPx = 100f, fadePx = 24f))
        assertEquals(0f, marqueeLeftFadePx(offsetPx = 50f, travelPx = 100f, fadePx = 0f))
    }

    @Test fun offsetIsZeroBeforeTheInitialDelay() {
        assertEquals(0f, marqueeOffsetPx(elapsedMs = 0L, travelPx = 400f, passMs = 4_000L, timing = passTiming))
        assertEquals(0f, marqueeOffsetPx(elapsedMs = 999L, travelPx = 400f, passMs = 4_000L, timing = passTiming))
    }

    @Test fun offsetGrowsLinearlyAcrossEachPass() {
        val offsets = (0L until 4_000L step 500L).map { marqueeOffsetPx(1_000L + it, 400f, 4_000L, passTiming) }
        assertEquals(listOf(0f, 50f, 100f, 150f, 200f, 250f, 300f, 350f), offsets)
    }

    @Test fun offsetRestsBetweenPassesAndAfterTheLastPass() {
        assertEquals(0f, marqueeOffsetPx(elapsedMs = 5_000L, travelPx = 400f, passMs = 4_000L, timing = passTiming))
        assertEquals(0f, marqueeOffsetPx(elapsedMs = 5_499L, travelPx = 400f, passMs = 4_000L, timing = passTiming))
        assertEquals(0f, marqueeOffsetPx(elapsedMs = 5_500L, travelPx = 400f, passMs = 4_000L, timing = passTiming))
        assertEquals(100f, marqueeOffsetPx(elapsedMs = 6_500L, travelPx = 400f, passMs = 4_000L, timing = passTiming))
        assertEquals(0f, marqueeOffsetPx(elapsedMs = 9_500L, travelPx = 400f, passMs = 4_000L, timing = passTiming))
        assertEquals(0f, marqueeOffsetPx(elapsedMs = 10_000L, travelPx = 400f, passMs = 4_000L, timing = passTiming))
    }

    @Test fun offsetIsZeroWithoutTravelOrPassLength() {
        assertEquals(0f, marqueeOffsetPx(elapsedMs = 2_000L, travelPx = 0f, passMs = 4_000L, timing = passTiming))
        assertEquals(0f, marqueeOffsetPx(elapsedMs = 2_000L, travelPx = -10f, passMs = 4_000L, timing = passTiming))
        assertEquals(0f, marqueeOffsetPx(elapsedMs = 2_000L, travelPx = 400f, passMs = 0L, timing = passTiming))
    }

    @Test fun endIsTheStartOfTheLastPassPlusItsLength() {
        assertEquals(9_500L, marqueeEndMs(passMs = 4_000L, timing = passTiming))
    }
}

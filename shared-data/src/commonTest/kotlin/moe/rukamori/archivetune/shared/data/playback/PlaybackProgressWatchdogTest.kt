package moe.rukamori.archivetune.shared.data.playback

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackProgressWatchdogTest {
    private val watchdog = PlaybackProgressWatchdog(timeoutMs = TIMEOUT_MS, endToleranceMs = END_TOLERANCE_MS)

    @Test
    fun frozenPositionFiresOnceAtTheTimeout() {
        val start = 1_000_000_000L
        assertFalse(watchdog.observe(state(positionUs = 500_000L), expectProgress = true, nowNanos = start))
        assertFalse(watchdog.observe(state(positionUs = 500_000L), expectProgress = true, nowNanos = start + TIMEOUT_NANOS - 1))
        assertTrue(watchdog.observe(state(positionUs = 500_000L), expectProgress = true, nowNanos = start + TIMEOUT_NANOS))
        // The latch fires once per episode, not on every read afterwards.
        assertFalse(watchdog.observe(state(positionUs = 500_000L), expectProgress = true, nowNanos = start + 2 * TIMEOUT_NANOS))
    }

    @Test
    fun advancingPositionNeverFires() {
        var now = 1_000_000_000L
        var position = 0L
        repeat(40) {
            position += 20_000L
            now += 500_000_000L
            assertFalse(watchdog.observe(state(positionUs = position), expectProgress = true, nowNanos = now))
        }
    }

    @Test
    fun progressDuringTheWindowPostponesTheTrip() {
        val start = 1_000_000_000L
        assertFalse(watchdog.observe(state(positionUs = 0L), expectProgress = true, nowNanos = start))
        // Frozen for most of the window, then one step of progress restarts it.
        assertFalse(watchdog.observe(state(positionUs = 0L), expectProgress = true, nowNanos = start + TIMEOUT_NANOS - 1))
        assertFalse(watchdog.observe(state(positionUs = 10_000L), expectProgress = true, nowNanos = start + TIMEOUT_NANOS))
        assertFalse(watchdog.observe(state(positionUs = 10_000L), expectProgress = true, nowNanos = start + 2 * TIMEOUT_NANOS - 1))
        assertTrue(watchdog.observe(state(positionUs = 10_000L), expectProgress = true, nowNanos = start + 2 * TIMEOUT_NANOS))
    }

    @Test
    fun pausedReadDoesNotAccumulateStallTime() {
        val start = 1_000_000_000L
        assertFalse(watchdog.observe(state(positionUs = 500_000L), expectProgress = true, nowNanos = start))
        // Paused well past the timeout: no progress is expected, so the clock keeps resetting.
        repeat(10) { step ->
            assertFalse(
                watchdog.observe(
                    state(positionUs = 500_000L),
                    expectProgress = false,
                    nowNanos = start + (step + 1) * TIMEOUT_NANOS,
                ),
            )
        }
        // A host may stop polling during interruption/backgrounding. Resume starts a fresh window.
        val resumed = start + 20 * TIMEOUT_NANOS
        assertFalse(watchdog.observe(state(positionUs = 500_000L), true, resumed))
        assertFalse(watchdog.observe(state(positionUs = 500_000L), true, resumed + TIMEOUT_NANOS - 1))
        assertTrue(watchdog.observe(state(positionUs = 500_000L), true, resumed + TIMEOUT_NANOS))
    }

    @Test
    fun positionWithinEndToleranceNeverStalls() {
        val start = 1_000_000_000L
        val duration = 100_000_000L
        val tail = duration - END_TOLERANCE_MS * MICROS_PER_MILLI + 1L
        assertFalse(watchdog.observe(state(positionUs = tail, durationUs = duration), expectProgress = true, nowNanos = start))
        assertFalse(
            watchdog.observe(
                state(positionUs = tail, durationUs = duration),
                expectProgress = true,
                nowNanos = start + 3 * TIMEOUT_NANOS,
            ),
        )
    }

    @Test
    fun newLoadAndSeekRestartTheWindow() {
        val start = 1_000_000_000L
        assertFalse(watchdog.observe(state(positionUs = 0L), expectProgress = true, nowNanos = start))
        assertFalse(watchdog.observe(state(positionUs = 0L), expectProgress = true, nowNanos = start + TIMEOUT_NANOS - 1))
        // A seek restarts the clock even though the position did not move yet.
        assertFalse(watchdog.observe(state(seekGeneration = 1L, positionUs = 0L), expectProgress = true, nowNanos = start + TIMEOUT_NANOS))
        assertFalse(watchdog.observe(state(seekGeneration = 1L, positionUs = 0L), expectProgress = true, nowNanos = start + 2 * TIMEOUT_NANOS - 1))
        assertTrue(watchdog.observe(state(seekGeneration = 1L, positionUs = 0L), expectProgress = true, nowNanos = start + 2 * TIMEOUT_NANOS))
        // A new load restarts it again.
        assertFalse(watchdog.observe(state(load = 2L, positionUs = 0L), expectProgress = true, nowNanos = start + 3 * TIMEOUT_NANOS))
    }

    @Test
    fun progressAfterATripRearmsTheWatchdog() {
        val start = 1_000_000_000L
        assertFalse(watchdog.observe(state(positionUs = 0L), expectProgress = true, nowNanos = start))
        assertTrue(watchdog.observe(state(positionUs = 0L), expectProgress = true, nowNanos = start + TIMEOUT_NANOS))
        assertFalse(watchdog.observe(state(positionUs = 20_000L), expectProgress = true, nowNanos = start + TIMEOUT_NANOS + 1))
        assertFalse(watchdog.observe(state(positionUs = 20_000L), expectProgress = true, nowNanos = start + 2 * TIMEOUT_NANOS))
        assertTrue(watchdog.observe(state(positionUs = 20_000L), expectProgress = true, nowNanos = start + 2 * TIMEOUT_NANOS + 1))
    }

    @Test
    fun pauseAfterReportedStallRearmsAndUnknownDurationDoesNotSuppressStalls() {
        val read = state(durationUs = 0L)
        assertFalse(watchdog.observe(read, true, 0L))
        assertTrue(watchdog.observe(read, true, TIMEOUT_NANOS))
        assertFalse(watchdog.observe(read, false, 2 * TIMEOUT_NANOS))
        assertFalse(watchdog.observe(read, true, 5 * TIMEOUT_NANOS))
        assertTrue(watchdog.observe(read, true, 6 * TIMEOUT_NANOS))
    }

    private fun state(
        load: Long = 1L,
        seekGeneration: Long = 0L,
        positionUs: Long = 0L,
        durationUs: Long = 300_000_000L,
    ): EnginePlaybackState = EnginePlaybackState(
        flags = EnginePlaybackState.FLAG_HAS_SOURCE or EnginePlaybackState.FLAG_PLAY_WHEN_READY,
        errorCode = EnginePlaybackState.ERROR_NONE,
        loadGeneration = load,
        seekGeneration = seekGeneration,
        errorSequence = 0L,
        positionUs = positionUs,
        durationUs = durationUs,
        bufferedUs = durationUs,
        playbackSpeed = 1f,
    )

    private companion object {
        const val TIMEOUT_MS = 12_000L
        const val END_TOLERANCE_MS = 2_000L
        const val TIMEOUT_NANOS = TIMEOUT_MS * 1_000_000L
        const val MICROS_PER_MILLI = 1_000L
    }
}

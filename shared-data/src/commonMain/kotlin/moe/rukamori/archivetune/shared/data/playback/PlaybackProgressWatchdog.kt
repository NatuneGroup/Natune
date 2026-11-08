package moe.rukamori.archivetune.shared.data.playback

/**
 * Detects a live engine that renders no progress: the read position stays put while playback is
 * expected. The host feeds one call per engine read, and a return of true fires once per stall
 * episode, latching until progress, a new load or a seek resets the track.
 *
 * The window is the one the reference desktop player uses: twelve seconds of frozen position while
 * progress is expected, sampled on every read, with the last two seconds before the reported end
 * tolerated because several engines stop short of flagging the end at the tail. All times arrive
 * on the host's monotonic clock, so nothing here owns a thread.
 */
class PlaybackProgressWatchdog(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val endToleranceMs: Long = DEFAULT_END_TOLERANCE_MS,
) {
    private var loadGeneration = NO_LOAD
    private var seekGeneration = 0L
    private var lastPositionUs = Long.MIN_VALUE
    private var progressedAtNanos = 0L
    private var reported = false
    private var expectingProgress = false

    /**
     * One engine read. [expectProgress] is the host's "the listener should hear this now": a source
     * loaded, play requested and not ended. Returns true once, at the moment the read has made no
     * progress for [timeoutMs] while progress was expected; the latch releases as soon as the
     * position moves again, on a new load, on a seek, or on [reset].
     */
    fun observe(state: EnginePlaybackState, expectProgress: Boolean, nowNanos: Long): Boolean {
        val wasExpectingProgress = expectingProgress
        expectingProgress = expectProgress
        if (state.loadGeneration != loadGeneration || state.seekGeneration != seekGeneration) {
            // A load or a seek legitimately restarts the clock: opening and rebuffering take time.
            loadGeneration = state.loadGeneration
            seekGeneration = state.seekGeneration
            lastPositionUs = state.positionUs
            progressedAtNanos = nowNanos
            reported = false
            return false
        }
        if (!expectProgress || !wasExpectingProgress || nearEnd(state)) {
            // A paused, ended or tail-reaching read can hold its position forever without a stall.
            lastPositionUs = state.positionUs
            progressedAtNanos = nowNanos
            reported = false
            return false
        }
        if (state.positionUs != lastPositionUs) {
            lastPositionUs = state.positionUs
            progressedAtNanos = nowNanos
            reported = false
            return false
        }
        if (reported || nowNanos - progressedAtNanos < timeoutMs * NANOS_PER_MILLI) return false
        reported = true
        return true
    }

    /** The position counts as progress inside [endToleranceMs] of the reported end. */
    private fun nearEnd(state: EnginePlaybackState): Boolean =
        state.durationUs > 0L && state.positionUs >= state.durationUs - endToleranceMs * MICROS_PER_MILLI

    /** Forgets the tracked read entirely, for a deck whose session is retired or reloaded. */
    fun reset() {
        loadGeneration = NO_LOAD
        seekGeneration = 0L
        lastPositionUs = Long.MIN_VALUE
        progressedAtNanos = 0L
        reported = false
        expectingProgress = false
    }

    companion object {
        /** The stall window of the reference implementation: twelve seconds without progress. */
        const val DEFAULT_TIMEOUT_MS = 12_000L

        /** How close to the reported end a frozen position still counts as a normal finish. */
        const val DEFAULT_END_TOLERANCE_MS = 2_000L

        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MICROS_PER_MILLI = 1_000L
        private const val NO_LOAD = -1L
    }
}

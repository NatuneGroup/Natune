package moe.rukamori.archivetune.shared.data.playback

import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureReason
import moe.rukamori.archivetune.shared.domain.playback.PlaybackObservation
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSessionTracker
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot

/**
 * Turns native engine reads into the snapshots and failures that a playback port publishes.
 *
 * The engine restarts its counters at each load, while [PlaybackSnapshot] generations only move
 * forward. This mapper keeps that bookkeeping in one place: a read from an earlier load is dropped,
 * each completed seek is one discontinuity, and a load reports at most one failure. Output is
 * [PlaybackOutputRoute.LOCAL] because the engine renders to the local audio device.
 */
class EnginePlaybackMapper(
    private val tracker: PlaybackSessionTracker = PlaybackSessionTracker(),
) {
    private var request: PlaybackRequest? = null
    private var engineLoadGeneration = NO_LOAD
    private var seenSeekGeneration = 0L
    private var failureReported = false
    private var lastSnapshot: PlaybackSnapshot? = null

    /**
     * Starts the session for [request]. [engineLoadGeneration] is the value the engine returned from
     * its load call, and reads that carry any other load generation are ignored from now on.
     */
    fun beginRequest(request: PlaybackRequest, engineLoadGeneration: Long) {
        tracker.beginSession(request.occurrenceId, request.mediaId)
        this.request = request
        this.engineLoadGeneration = engineLoadGeneration
        seenSeekGeneration = 0L
        failureReported = false
        lastSnapshot = null
    }

    /**
     * Snapshot for [state] observed at [sampleMonotonicNanos], or null when no session is active or
     * the read belongs to another load.
     */
    fun observe(state: EnginePlaybackState, sampleMonotonicNanos: Long): PlaybackSnapshot? {
        if (request == null || state.loadGeneration != engineLoadGeneration) {
            return null
        }
        while (seenSeekGeneration < state.seekGeneration) {
            tracker.recordDiscontinuity()
            seenSeekGeneration++
        }
        val observation = PlaybackObservation(
            positionMs = state.positionUs.coerceAtLeast(0L) / MICROS_PER_MILLI,
            durationMs = if (state.durationUs >= 0L) state.durationUs / MICROS_PER_MILLI else null,
            bufferedPositionMs = if (state.bufferedUs >= 0L) state.bufferedUs / MICROS_PER_MILLI else null,
            isSeekable = state.isSeekable,
            playbackSpeed = state.playbackSpeed,
            playWhenReady = state.playWhenReady,
            isPlaying = state.isAdvancing,
            isBuffering = state.isBuffering,
            isEnded = state.isEnded,
            outputRoute = PlaybackOutputRoute.LOCAL,
        )
        return tracker.observe(observation, sampleMonotonicNanos).also { lastSnapshot = it }
    }

    /**
     * The failure for the current load, reported once. Returns null when the read shows no error,
     * belongs to another load, or the failure was already reported. [message] is only evaluated
     * when a failure is actually reported, so polling a healthy engine never reads error text.
     */
    fun failure(state: EnginePlaybackState, message: () -> String): PlaybackFailure? {
        val current = request ?: return null
        if (state.loadGeneration != engineLoadGeneration ||
            state.errorCode == EnginePlaybackState.ERROR_NONE ||
            failureReported
        ) {
            return null
        }
        failureReported = true
        return PlaybackFailure(current.occurrenceId, reasonFor(state.errorCode), message())
    }

    /**
     * The last position after the engine stopped, with playback no longer advancing or waiting for
     * data. Returns null when no observation was published for the session.
     */
    fun stopped(sampleMonotonicNanos: Long): PlaybackSnapshot? {
        val last = lastSnapshot
        request = null
        engineLoadGeneration = NO_LOAD
        lastSnapshot = null
        if (last == null) {
            return null
        }
        return last.copy(
            playWhenReady = false,
            isAudioAdvancing = false,
            isBuffering = false,
            sampleMonotonicNanos = sampleMonotonicNanos,
        )
    }

    private fun reasonFor(errorCode: Int): PlaybackFailureReason = when (errorCode) {
        EnginePlaybackState.ERROR_OPEN -> PlaybackFailureReason.OPEN_FAILED
        EnginePlaybackState.ERROR_OUTPUT -> PlaybackFailureReason.OUTPUT_FAILED
        else -> PlaybackFailureReason.DECODE_FAILED
    }

    private companion object {
        const val NO_LOAD = -1L
        const val MICROS_PER_MILLI = 1_000L
    }
}

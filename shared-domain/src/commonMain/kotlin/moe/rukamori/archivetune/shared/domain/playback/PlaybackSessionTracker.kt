package moe.rukamori.archivetune.shared.domain.playback

/**
 * What an engine can observe at one instant, without any generation bookkeeping.
 *
 * @property isPlaying Whether the engine has audio ready and is consuming it right now.
 * @property isBuffering Whether playback waits for data, so the position must hold.
 */
data class PlaybackObservation(
    val positionMs: Long,
    val durationMs: Long?,
    val bufferedPositionMs: Long?,
    val isSeekable: Boolean,
    val playbackSpeed: Float,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val isEnded: Boolean,
    val outputRoute: PlaybackOutputRoute,
)

/**
 * Turns engine observations into [PlaybackSnapshot] values that carry the right generations.
 *
 * Platform adapters call [beginSession] when a new occurrence starts, [recordDiscontinuity] when a
 * seek or repeat moves the position, and [observe] for every state change or periodic tick. The
 * tracker never reads an engine, so the same rules hold on Media3, the native engine and tests.
 */
class PlaybackSessionTracker {
    private var occurrenceId: String? = null
    private var mediaId: String? = null
    private var playbackGeneration = 0L
    private var discontinuityGeneration = 0L

    /** Starts a new playback generation. Discontinuities count from zero within it. */
    fun beginSession(occurrenceId: String, mediaId: String) {
        require(occurrenceId.isNotBlank()) { "occurrenceId must not be blank" }
        require(mediaId.isNotBlank()) { "mediaId must not be blank" }
        this.occurrenceId = occurrenceId
        this.mediaId = mediaId
        playbackGeneration++
        discontinuityGeneration = 0L
    }

    /** Marks a non-continuous position jump, such as a completed seek or a repeat. */
    fun recordDiscontinuity() {
        check(occurrenceId != null) { "recordDiscontinuity() before beginSession()" }
        discontinuityGeneration++
    }

    /**
     * Builds the snapshot for [observation] at [sampleMonotonicNanos].
     *
     * A snapshot never claims audio is advancing while the engine is buffering or has ended, even
     * if the raw engine flag disagrees, because interpolating a stalled clock would show progress
     * that did not happen.
     */
    fun observe(observation: PlaybackObservation, sampleMonotonicNanos: Long): PlaybackSnapshot {
        val id = checkNotNull(occurrenceId) { "observe() before beginSession()" }
        val media = checkNotNull(mediaId) { "observe() before beginSession()" }
        return PlaybackSnapshot(
            occurrenceId = id,
            mediaId = media,
            playbackGeneration = playbackGeneration,
            discontinuityGeneration = discontinuityGeneration,
            positionMs = observation.positionMs.coerceAtLeast(0L),
            sampleMonotonicNanos = sampleMonotonicNanos,
            durationMs = observation.durationMs?.coerceAtLeast(0L),
            isSeekable = observation.isSeekable,
            playbackSpeed = observation.playbackSpeed,
            playWhenReady = observation.playWhenReady,
            isAudioAdvancing = observation.isPlaying && !observation.isBuffering && !observation.isEnded,
            isBuffering = observation.isBuffering,
            outputRoute = observation.outputRoute,
            bufferedPositionMs = observation.bufferedPositionMs?.coerceAtLeast(0L),
            isEnded = observation.isEnded,
        )
    }
}

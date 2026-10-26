/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.playback

/**
 * Where the audio leaves the device. A [REMOTE] route owns its own clock, so local
 * interpolation must hold while it is active.
 */
enum class PlaybackOutputRoute {
    LOCAL,
    REMOTE,
    UNKNOWN,
}

/**
 * One observation of what playback is doing, published by a platform engine.
 *
 * Consumers derive progress, lyrics timing and queue state from this value alone, so every
 * engine must report audible reality rather than its own intent. [playWhenReady] is the user's
 * intent. [isAudioAdvancing] is the evidence that audio is being consumed. A new observation is a
 * new value; the engine never mutates one it has already published.
 *
 * @property occurrenceId Identity of this queue occurrence. The same media can appear twice.
 * @property mediaId Identity of the media item, shared by all of its occurrences.
 * @property playbackGeneration Changes when a new playback session or queue occurrence starts.
 * @property discontinuityGeneration Changes at every non-continuous position jump, such as a seek
 *   or a repeat.
 * @property positionMs Media position observed at [sampleMonotonicNanos].
 * @property sampleMonotonicNanos Observation time on the [MonotonicClock] base.
 * @property durationMs Media duration in milliseconds, or null while the engine does not know it.
 * @property playbackSpeed Media-time rate. 1.0 is real time. Must be positive and finite.
 * @property bufferedPositionMs Media position up to which audio is decoded and ready, or null while
 *   the engine does not know. Buffering and progress UI read it; it never moves the clock.
 * @property isEnded True once the engine has output the final sample of the current occurrence.
 */
data class PlaybackSnapshot(
    val occurrenceId: String,
    val mediaId: String,
    val playbackGeneration: Long,
    val discontinuityGeneration: Long,
    val positionMs: Long,
    val sampleMonotonicNanos: Long,
    val durationMs: Long?,
    val isSeekable: Boolean,
    val playbackSpeed: Float,
    val playWhenReady: Boolean,
    val isAudioAdvancing: Boolean,
    val isBuffering: Boolean,
    val outputRoute: PlaybackOutputRoute,
    val bufferedPositionMs: Long? = null,
    val isEnded: Boolean = false,
) {
    init {
        require(playbackSpeed.isFinite() && playbackSpeed > 0f) {
            "playbackSpeed must be a positive finite number, was $playbackSpeed"
        }
        require(durationMs == null || durationMs >= 0L) {
            "durationMs must be null or non-negative, was $durationMs"
        }
        require(bufferedPositionMs == null || bufferedPositionMs >= 0L) {
            "bufferedPositionMs must be null or non-negative, was $bufferedPositionMs"
        }
    }
}

/**
 * Whether this snapshot replaces [other] as the clock anchor.
 *
 * Generations order first, so a new session or a seek always wins over anything still in flight
 * from before it, even when that older snapshot carries a later timestamp. Within one generation
 * the observation time decides, so a late snapshot cannot move the clock backwards. An equal
 * timestamp counts as newer, so a corrected snapshot at the same instant still replaces its
 * predecessor.
 */
fun PlaybackSnapshot.supersedes(other: PlaybackSnapshot): Boolean = when {
    playbackGeneration != other.playbackGeneration -> playbackGeneration > other.playbackGeneration
    discontinuityGeneration != other.discontinuityGeneration ->
        discontinuityGeneration > other.discontinuityGeneration
    else -> sampleMonotonicNanos >= other.sampleMonotonicNanos
}

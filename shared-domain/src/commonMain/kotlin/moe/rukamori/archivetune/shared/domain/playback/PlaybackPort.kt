package moe.rukamori.archivetune.shared.domain.playback

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The boundary between the shared playback policy and one platform engine: Media3 on Android, the
 * native FFmpeg/miniaudio engine on desktop, AVAudioEngine on iOS.
 *
 * An engine publishes what the audio output is doing, not what the app asked for. Every engine
 * owns its own threads, so [submit] must return quickly and [snapshots] may conflate updates.
 * Consumers judge snapshots with [PlaybackClock.accept], which keeps the newest anchor.
 */
interface PlaybackPort {
    /** The latest observation, or null before the first load. Each emitted value is a new observation. */
    val snapshots: StateFlow<PlaybackSnapshot?>

    /** Failures for loads and background decode work. Replayed to nobody who was not collecting. */
    val failures: SharedFlow<PlaybackFailure>

    /** Queues [command] for the engine. Never blocks on decoding, network, or audio output. */
    fun submit(command: PlaybackCommand)

    /** Stops output and releases every engine resource. Commands after this are ignored. */
    fun close()
}

/**
 * A playback failure tied to the occurrence that was current when it happened. [httpStatus] is the status a source
 * answered with when one did, and [causes] is the cause chain, outermost first, as `Name: message` lines; the error
 * dialog copies both into its details.
 */
data class PlaybackFailure(
    val occurrenceId: String,
    val reason: PlaybackFailureReason,
    val message: String,
    val httpStatus: Int? = null,
    val causes: List<String> = emptyList(),
)

/** Why playback failed, in terms the app can act on without parsing [PlaybackFailure.message]. */
enum class PlaybackFailureReason {
    /** The locator is not a source this engine may open, such as an app-internal scheme. */
    UNSUPPORTED_LOCATOR,

    /** The source could not be opened or probed. */
    OPEN_FAILED,

    /** The source opened but its audio could not be decoded or read. */
    DECODE_FAILED,

    /** The platform audio output could not be opened or kept running. */
    OUTPUT_FAILED,

    /** No connected source resolved the song to a stream, so nothing reached the engine. */
    SOURCE_UNAVAILABLE,
}

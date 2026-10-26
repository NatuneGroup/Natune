/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.playback

/**
 * A request from the app to a playback engine. Engines apply commands in arrival order and never
 * block the caller; a command that cannot be applied is reported through [PlaybackPort.failures].
 */
sealed interface PlaybackCommand {
    /** Replaces the current occurrence. A load starts a new playback generation. */
    data class Load(
        val request: PlaybackRequest,
        val startPositionMs: Long,
        val playWhenReady: Boolean,
    ) : PlaybackCommand {
        init {
            require(startPositionMs >= 0L) { "startPositionMs must be non-negative, was $startPositionMs" }
        }
    }

    /** Sets the play intent to true. Audio starts only once the engine has data to output. */
    data object Play : PlaybackCommand

    /** Sets the play intent to false. The position holds at the last audible sample. */
    data object Pause : PlaybackCommand

    /** Moves to [positionMs]. Completion is a new discontinuity generation, not this command. */
    data class SeekTo(val positionMs: Long) : PlaybackCommand {
        init {
            require(positionMs >= 0L) { "positionMs must be non-negative, was $positionMs" }
        }
    }

    /** Changes the media-time rate. 1.0 is real time. */
    data class SetSpeed(val speed: Float) : PlaybackCommand {
        init {
            require(speed.isFinite() && speed > 0f) { "speed must be a positive finite number, was $speed" }
        }
    }

    /** Ends the current occurrence and releases its decoder. The port stays usable for a new load. */
    data object Stop : PlaybackCommand
}

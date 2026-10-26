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

    /** Changes the pitch multiplier: 1.0 is unshifted, 2.0 is one octave up. */
    data class SetPitch(val pitch: Float) : PlaybackCommand {
        init {
            require(pitch.isFinite() && pitch > 0f) { "pitch must be a positive finite number, was $pitch" }
        }
    }

    /** Ends the current occurrence and releases its decoder. The port stays usable for a new load. */
    data object Stop : PlaybackCommand
}

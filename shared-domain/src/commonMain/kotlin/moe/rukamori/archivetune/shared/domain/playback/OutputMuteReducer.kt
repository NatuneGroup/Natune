package moe.rukamori.archivetune.shared.domain.playback

/**
 * What a host-side output-mute policy knows between events: whether a pause the policy itself made
 * still awaits the unmute that resumes it ([pending]), and whether the muted output still needs
 * sampling ([polling]). A pause a policy causes and playback the listener wants are exclusive, so
 * [polling] alone encodes the session's playing bit, which [OutputMuteState.playing] reads back.
 */
data class OutputMuteState(val pending: Boolean, val polling: Boolean) {
    /** Whether the session wants audio now: sampling for a reason other than the mute wait. */
    val playing: Boolean get() = polling && !pending
}

/** One thing the mute policy observed, already classified by the host. */
sealed interface OutputMuteEvent {
    /** A fresh muted sample, from the volume watch or a stored app-level change. */
    data class VolumeSample(val muted: Boolean) : OutputMuteEvent

    /** The listener paused by hand: the pause is theirs, whatever the policy was waiting on. */
    data object ManualPause : OutputMuteEvent

    /** The listener stopped by hand, or playback ended without a media end. */
    data object ManualStop : OutputMuteEvent

    /**
     * The listener pressed play. [freshMuted] is the mute sampled on the caller's live queue at that
     * moment, never the stale watch value, and [volumeRestoreAvailable] says the stored app level
     * can be lifted to a recovery volume instead of pausing.
     */
    data class ManualPlay(val freshMuted: Boolean, val volumeRestoreAvailable: Boolean) : OutputMuteEvent

    /** A media end arrived: nothing remains that a resume could play. */
    data object Ended : OutputMuteEvent

    /** The session closed; the watch releases with it. */
    data object Closed : OutputMuteEvent
}

/** What the host applies after a reduction. */
enum class OutputMuteAction {
    NONE,
    PAUSE,
    RESUME,
    RESTORE_VOLUME,
}

/**
 * One transition of the pause-on-mute watch: reduces (state, event) to (next state, action). The
 * listener's own pause, stop, play-while-muted, media end and close all clear the pending wait, so
 * an unmute after any of them never resumes playback. Every host decision lives here so each
 * lifecycle case is covered by commonTest instead of living in flags on the host.
 */
fun reduceOutputMute(
    state: OutputMuteState,
    event: OutputMuteEvent,
): Pair<OutputMuteState, OutputMuteAction> = when (event) {
    is OutputMuteEvent.VolumeSample -> when {
        // The mute arrived while playback was wanted: pause it and wait for the unmute.
        state.playing && event.muted -> OutputMuteState(pending = true, polling = true) to OutputMuteAction.PAUSE
        // The waited-for unmute: resume and keep watching while it plays.
        state.pending && !event.muted -> OutputMuteState(pending = false, polling = true) to OutputMuteAction.RESUME
        else -> state to OutputMuteAction.NONE
    }

    is OutputMuteEvent.ManualPlay -> when {
        // A play request on a muted output the app level owns restores the stored level and plays.
        event.freshMuted && event.volumeRestoreAvailable ->
            OutputMuteState(pending = false, polling = true) to OutputMuteAction.RESTORE_VOLUME
        // Muted and nothing to restore: the play is paused and the unmute resumes it.
        event.freshMuted -> OutputMuteState(pending = true, polling = true) to OutputMuteAction.PAUSE
        else -> OutputMuteState(pending = false, polling = true) to OutputMuteAction.NONE
    }

    // Everything the listener or the media does that is not a play ends the watch entirely: a
    // pending mute pause is released silently, never resumed.
    OutputMuteEvent.ManualPause,
    OutputMuteEvent.ManualStop,
    OutputMuteEvent.Ended,
    OutputMuteEvent.Closed,
    -> OutputMuteState(pending = false, polling = false) to OutputMuteAction.NONE
}

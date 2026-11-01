package moe.rukamori.archivetune.shared.domain.playback

/**
 * What a host-side output-mute policy knows between events: whether a pause the policy itself made
 * still awaits the unmute that resumes it ([pending]), whether the muted output still needs sampling
 * ([polling]), and whether the watch is done ([closed]). A policy-caused pause and playback the
 * listener wants are exclusive, so [polling] alone encodes the playing bit, which [playing] reads
 * back. [closed] is terminal: every event that follows it reduces to itself and NONE, so a signal
 * delivered after the session closed can never produce a command.
 */
data class OutputMuteState(val pending: Boolean, val polling: Boolean, val closed: Boolean = false) {
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

    /** The session closed; the watch releases with it and nothing reduces again. */
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
 * listener's own pause, stop, media end and close all release the pending wait, so an unmute after
 * any of them never resumes playback; a manual play resolves its mute decision against the fresh
 * sample the host supplies, and a closed watch is terminal. Every host decision lives here so each
 * lifecycle case is covered by commonTest instead of living in flags on the host.
 */
fun reduceOutputMute(
    state: OutputMuteState,
    event: OutputMuteEvent,
): Pair<OutputMuteState, OutputMuteAction> = when {
    state.closed -> state to OutputMuteAction.NONE
    else -> when (event) {
        is OutputMuteEvent.VolumeSample -> when {
            // The mute arrived while playback was wanted: pause it and wait for the unmute.
            state.playing && event.muted ->
                OutputMuteState(pending = true, polling = true) to OutputMuteAction.PAUSE
            // The waited-for unmute: resume and keep watching while it plays.
            state.pending && !event.muted ->
                OutputMuteState(pending = false, polling = true) to OutputMuteAction.RESUME
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

        // Everything the listener or the media does that is not a play ends the watch silently: a
        // pending mute pause is released, never resumed.
        OutputMuteEvent.ManualPause,
        OutputMuteEvent.ManualStop,
        OutputMuteEvent.Ended,
        -> OutputMuteState(pending = false, polling = false) to OutputMuteAction.NONE

        OutputMuteEvent.Closed -> OutputMuteState(pending = false, polling = false, closed = true) to OutputMuteAction.NONE
    }
}

/** What a submitted command means to the mute watch, once its origin is known. */
enum class OutputMuteCommandUse {
    MANUAL_PAUSE,
    MANUAL_STOP,
    RESOLVE_PLAY,
    IGNORED,
}

/**
 * Classifies a [PlaybackCommandEvent] for the mute watch. Only USER commands carry listener intent —
 * the policy's own pause and play arrive with origin POLICY and are IGNORED, so no bookkeeping has
 * to tell an echo apart from a command. A USER Play maps to [RESOLVE_PLAY] instead of an event
 * because its mute decision needs a volume read taken live on the host's queue.
 */
fun outputMuteCommandUse(command: PlaybackCommand, origin: PlaybackCommandOrigin): OutputMuteCommandUse = when {
    origin != PlaybackCommandOrigin.USER -> OutputMuteCommandUse.IGNORED
    command == PlaybackCommand.Pause -> OutputMuteCommandUse.MANUAL_PAUSE
    command == PlaybackCommand.Stop -> OutputMuteCommandUse.MANUAL_STOP
    command == PlaybackCommand.Play -> OutputMuteCommandUse.RESOLVE_PLAY
    else -> OutputMuteCommandUse.IGNORED
}

/** What an option change does to the watch, given the session's playing bit. */
enum class OutputMuteReconcile {
    RELEASE,
    RESOLVE_PLAY,
    NONE,
}

/**
 * Reconciles the watch with an option change. Turning pause-on-mute off releases any wait like a
 * manual pause; turning it on while playback is wanted engages like a fresh play edge the host
 * resolves against a live sample — including pausing a session that is already muted.
 */
fun outputMuteReconcile(wasEnabled: Boolean, enabled: Boolean, playing: Boolean): OutputMuteReconcile = when {
    wasEnabled && !enabled -> OutputMuteReconcile.RELEASE
    !wasEnabled && enabled && playing -> OutputMuteReconcile.RESOLVE_PLAY
    else -> OutputMuteReconcile.NONE
}

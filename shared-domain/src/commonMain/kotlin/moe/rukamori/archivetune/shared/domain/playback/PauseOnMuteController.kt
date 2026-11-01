package moe.rukamori.archivetune.shared.domain.playback

/** What the host applies after one [PauseOnMuteController.observe] sample. */
enum class MuteTransitionAction {
    /** No edge, or an edge the policy ignores. */
    NONE,

    /** The output muted while playing, or playback was asked for while muted with no way back. */
    PAUSE,

    /** The output unmuted and this controller had paused it: the playback intent was to play. */
    RESUME,

    /**
     * Playback was asked for while muted and the host stores a recovery volume: lift the mute to
     * that level instead of pausing, as Android restores its stream volume on a play request.
     */
    RESTORE_VOLUME,
}

/**
 * The pause-on-mute transition policy, host-agnostic: the host describes each output-mute sample
 * and applies the returned action. Android feeds it the MUSIC stream mute and volume, the desktop
 * host feeds it the app output level, which is the only reachable mute on that engine.
 *
 * Only real transitions act: the first sample and repeated samples are ignored. A playback the
 * listener resumes by hand voids the pending resume, so the controller never plays over a pause
 * the user chose after it fired.
 */
class PauseOnMuteController {
    private var sampled = false
    private var wasMuted = false
    private var wasPlaying = false
    private var resumeOnUnmute = false

    /**
     * One output sample. [enabled] is the stored pause-on-mute option, [muted] whether the output
     * is silent now, [playing] whether media is loaded and play was requested, and
     * [volumeRestoreAvailable] whether a stored recovery volume lets a play request lift the mute.
     */
    fun observe(
        enabled: Boolean,
        muted: Boolean,
        playing: Boolean,
        volumeRestoreAvailable: Boolean,
    ): MuteTransitionAction {
        if (!enabled) {
            sampled = false
            wasMuted = false
            wasPlaying = false
            resumeOnUnmute = false
            return MuteTransitionAction.NONE
        }
        if (!sampled) {
            // The first sample only establishes the baseline, so a session that opens muted is not
            // paused by a transition that never happened.
            sampled = true
            wasMuted = muted
            wasPlaying = playing
            return MuteTransitionAction.NONE
        }
        val action = when {
            // The mute arrived during playback: pause now and offer to resume on unmute.
            muted && !wasMuted && playing -> {
                resumeOnUnmute = true
                MuteTransitionAction.PAUSE
            }
            // Play was asked for while muted: lift the mute when the host can, else hold the
            // request paused until the mute lifts, as a muted play request stays wanted.
            playing && !wasPlaying && muted ->
                if (volumeRestoreAvailable) {
                    MuteTransitionAction.RESTORE_VOLUME
                } else {
                    resumeOnUnmute = true
                    MuteTransitionAction.PAUSE
                }
            // The mute lifted: resume only what this controller paused, and only when the
            // listener has not already resumed on their own.
            !muted && wasMuted && resumeOnUnmute && !playing -> {
                resumeOnUnmute = false
                MuteTransitionAction.RESUME
            }
            else -> MuteTransitionAction.NONE
        }
        // Playback running again by any other path voids the pending resume, except the pause this
        // call just asked for.
        if (playing && action != MuteTransitionAction.PAUSE) resumeOnUnmute = false
        wasMuted = muted
        wasPlaying = playing
        return action
    }
}

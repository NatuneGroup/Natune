package moe.rukamori.archivetune.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class PauseOnMuteControllerTest {
    private val controller = PauseOnMuteController()

    @Test
    fun firstSampleNeverActs() {
        assertEquals(MuteTransitionAction.NONE, controller.observe(enabled = true, muted = true, playing = true, volumeRestoreAvailable = false))
        assertEquals(MuteTransitionAction.NONE, controller.observe(enabled = true, muted = false, playing = false, volumeRestoreAvailable = false))
    }

    @Test
    fun muteDuringPlaybackPausesAndUnmuteResumes() {
        controller.observe(enabled = true, muted = false, playing = true, volumeRestoreAvailable = false)
        assertEquals(MuteTransitionAction.PAUSE, controller.observe(enabled = true, muted = true, playing = true, volumeRestoreAvailable = false))
        // Still muted and now paused: no repeated action.
        assertEquals(MuteTransitionAction.NONE, controller.observe(enabled = true, muted = true, playing = false, volumeRestoreAvailable = false))
        assertEquals(MuteTransitionAction.RESUME, controller.observe(enabled = true, muted = false, playing = false, volumeRestoreAvailable = false))
        // Resumed: no further action.
        assertEquals(MuteTransitionAction.NONE, controller.observe(enabled = true, muted = false, playing = true, volumeRestoreAvailable = false))
    }

    @Test
    fun muteWhilePausedDoesNothing() {
        controller.observe(enabled = true, muted = false, playing = false, volumeRestoreAvailable = false)
        assertEquals(MuteTransitionAction.NONE, controller.observe(enabled = true, muted = true, playing = false, volumeRestoreAvailable = false))
        assertEquals(MuteTransitionAction.NONE, controller.observe(enabled = true, muted = false, playing = false, volumeRestoreAvailable = false))
    }

    @Test
    fun playRequestWhileMutedRestoresVolumeWhenAvailable() {
        controller.observe(enabled = true, muted = true, playing = false, volumeRestoreAvailable = true)
        assertEquals(MuteTransitionAction.RESTORE_VOLUME, controller.observe(enabled = true, muted = true, playing = true, volumeRestoreAvailable = true))
        // The restored volume lifts the mute; nothing else follows.
        assertEquals(MuteTransitionAction.NONE, controller.observe(enabled = true, muted = false, playing = true, volumeRestoreAvailable = true))
    }

    @Test
    fun playRequestWhileMutedPausesWithoutRestoreAndResumesOnUnmute() {
        controller.observe(enabled = true, muted = true, playing = false, volumeRestoreAvailable = false)
        assertEquals(MuteTransitionAction.PAUSE, controller.observe(enabled = true, muted = true, playing = true, volumeRestoreAvailable = false))
        assertEquals(MuteTransitionAction.RESUME, controller.observe(enabled = true, muted = false, playing = false, volumeRestoreAvailable = false))
    }

    @Test
    fun manualResumeWhileMutedIsANewPlayRequest() {
        controller.observe(enabled = true, muted = false, playing = true, volumeRestoreAvailable = true)
        assertEquals(MuteTransitionAction.PAUSE, controller.observe(enabled = true, muted = true, playing = true, volumeRestoreAvailable = true))
        assertEquals(MuteTransitionAction.NONE, controller.observe(enabled = true, muted = true, playing = false, volumeRestoreAvailable = true))
        // The listener plays again while still muted: that is a play request while muted, so the
        // recovery volume lifts the mute instead of holding the pause.
        assertEquals(MuteTransitionAction.RESTORE_VOLUME, controller.observe(enabled = true, muted = true, playing = true, volumeRestoreAvailable = true))
        assertEquals(MuteTransitionAction.NONE, controller.observe(enabled = true, muted = false, playing = true, volumeRestoreAvailable = true))
    }

    @Test
    fun disablingClearsThePendingResume() {
        controller.observe(enabled = true, muted = false, playing = true, volumeRestoreAvailable = false)
        assertEquals(MuteTransitionAction.PAUSE, controller.observe(enabled = true, muted = true, playing = true, volumeRestoreAvailable = false))
        assertEquals(MuteTransitionAction.NONE, controller.observe(enabled = false, muted = true, playing = false, volumeRestoreAvailable = false))
        assertEquals(MuteTransitionAction.NONE, controller.observe(enabled = true, muted = false, playing = false, volumeRestoreAvailable = false))
        // The feature sees a fresh first sample, so unmuted playback alone does not resume either.
        assertEquals(MuteTransitionAction.NONE, controller.observe(enabled = true, muted = false, playing = true, volumeRestoreAvailable = false))
    }
}

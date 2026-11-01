package moe.rukamori.archivetune.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class OutputMuteReducerTest {
    private val idle = OutputMuteState(pending = false, polling = false)
    private val playing = OutputMuteState(pending = false, polling = true)
    private val mutePaused = OutputMuteState(pending = true, polling = true)

    @Test
    fun playMuteUnmuteRoundTrip() {
        val (started, startAction) = reduceOutputMute(idle, OutputMuteEvent.ManualPlay(freshMuted = false, volumeRestoreAvailable = false))
        assertEquals(OutputMuteState(pending = false, polling = true), started)
        assertEquals(OutputMuteAction.NONE, startAction)

        val (paused, pauseAction) = reduceOutputMute(started, OutputMuteEvent.VolumeSample(muted = true))
        assertEquals(OutputMuteState(pending = true, polling = true), paused)
        assertEquals(OutputMuteAction.PAUSE, pauseAction)

        val (resumed, resumeAction) = reduceOutputMute(paused, OutputMuteEvent.VolumeSample(muted = false))
        assertEquals(OutputMuteState(pending = false, polling = true), resumed)
        assertEquals(OutputMuteAction.RESUME, resumeAction)
    }

    @Test
    fun manualPauseAfterMutePauseCancelsTheResume() {
        val (afterPause, action) = reduceOutputMute(mutePaused, OutputMuteEvent.ManualPause)
        assertEquals(OutputMuteState(pending = false, polling = false), afterPause)
        assertEquals(OutputMuteAction.NONE, action)
        // The unmute that arrives afterwards resumes nothing.
        val (afterUnmute, unmuteAction) = reduceOutputMute(afterPause, OutputMuteEvent.VolumeSample(muted = false))
        assertEquals(afterPause, afterUnmute)
        assertEquals(OutputMuteAction.NONE, unmuteAction)
    }

    @Test
    fun manualStopEndedAndClosedCancelTheResume() {
        for (event in listOf(OutputMuteEvent.ManualStop, OutputMuteEvent.Ended, OutputMuteEvent.Closed)) {
            val (next, action) = reduceOutputMute(mutePaused, event)
            assertEquals(OutputMuteState(pending = false, polling = false), next, "state for $event")
            assertEquals(OutputMuteAction.NONE, action, "action for $event")
        }
    }

    @Test
    fun manualPlayUsesTheFreshSample() {
        // Fresh-muted play pauses and keeps waiting for the unmute.
        val (muted, mutedAction) = reduceOutputMute(idle, OutputMuteEvent.ManualPlay(freshMuted = true, volumeRestoreAvailable = false))
        assertEquals(OutputMuteState(pending = true, polling = true), muted)
        assertEquals(OutputMuteAction.PAUSE, mutedAction)
        // A stale muted watch value cannot hold back a play whose fresh sample is clear.
        val (clear, clearAction) = reduceOutputMute(idle, OutputMuteEvent.ManualPlay(freshMuted = false, volumeRestoreAvailable = false))
        assertEquals(OutputMuteState(pending = false, polling = true), clear)
        assertEquals(OutputMuteAction.NONE, clearAction)
    }

    @Test
    fun manualPlayWhileMutedRestoresTheAppLevelWhenPossible() {
        val (next, action) = reduceOutputMute(idle, OutputMuteEvent.ManualPlay(freshMuted = true, volumeRestoreAvailable = true))
        assertEquals(OutputMuteState(pending = false, polling = true), next)
        assertEquals(OutputMuteAction.RESTORE_VOLUME, action)
    }

    @Test
    fun samplesWithoutAWatchDoNothing() {
        assertEquals(idle to OutputMuteAction.NONE, reduceOutputMute(idle, OutputMuteEvent.VolumeSample(muted = true)))
        assertEquals(playing to OutputMuteAction.NONE, reduceOutputMute(playing, OutputMuteEvent.VolumeSample(muted = false)))
    }
}

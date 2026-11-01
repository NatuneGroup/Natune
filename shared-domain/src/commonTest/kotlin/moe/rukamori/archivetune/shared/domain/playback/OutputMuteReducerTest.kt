package moe.rukamori.archivetune.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class OutputMuteReducerTest {
    private val idle = OutputMuteState(pending = false, polling = false)
    private val playing = OutputMuteState(pending = false, polling = true)
    private val mutePaused = OutputMuteState(pending = true, polling = true)

    private fun loadRequest() = PlaybackRequest("occ-1", "media-1", PlaybackLocator.LocalFile("/queue/a.flac"))

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
    fun manualPauseWhilePolicyPausedCancelsPending() {
        // A USER Pause is the listener's, always: even while the policy holds the pause.
        assertEquals(OutputMuteCommandUse.MANUAL_PAUSE, outputMuteCommandUse(PlaybackCommand.Pause, PlaybackCommandOrigin.USER))
        val (afterPause, action) = reduceOutputMute(mutePaused, OutputMuteEvent.ManualPause)
        assertEquals(OutputMuteState(pending = false, polling = false), afterPause)
        assertEquals(OutputMuteAction.NONE, action)
        // The unmute that arrives afterwards resumes nothing.
        val (afterUnmute, unmuteAction) = reduceOutputMute(afterPause, OutputMuteEvent.VolumeSample(muted = false))
        assertEquals(afterPause, afterUnmute)
        assertEquals(OutputMuteAction.NONE, unmuteAction)
    }

    @Test
    fun coincidentUserAndPolicyPausesStayDistinct() {
        // The policy's own Pause is ignored while the listener's lands, whatever the order.
        assertEquals(OutputMuteCommandUse.IGNORED, outputMuteCommandUse(PlaybackCommand.Pause, PlaybackCommandOrigin.POLICY))
        val (afterUser, userAction) = reduceOutputMute(mutePaused, OutputMuteEvent.ManualPause)
        assertEquals(OutputMuteState(pending = false, polling = false), afterUser)
        assertEquals(OutputMuteAction.NONE, userAction)
        // A second manual pause arriving coincident with the policy's reduces harmlessly.
        val (again, againAction) = reduceOutputMute(afterUser, OutputMuteEvent.ManualPause)
        assertEquals(afterUser, again)
        assertEquals(OutputMuteAction.NONE, againAction)
        // A POLICY play cannot resume either.
        assertEquals(OutputMuteCommandUse.IGNORED, outputMuteCommandUse(PlaybackCommand.Play, PlaybackCommandOrigin.POLICY))
    }

    @Test
    fun enablingWhilePlayingReEngagesTheWatch() {
        assertEquals(OutputMuteReconcile.RESOLVE_PLAY, outputMuteReconcile(wasEnabled = false, enabled = true, playing = true))
        val (next, action) = reduceOutputMute(idle, OutputMuteEvent.ManualPlay(freshMuted = false, volumeRestoreAvailable = false))
        assertEquals(OutputMuteState(pending = false, polling = true), next)
        assertEquals(OutputMuteAction.NONE, action)
        // Disabling releases like a manual pause; toggling while idle changes nothing.
        assertEquals(OutputMuteReconcile.RELEASE, outputMuteReconcile(wasEnabled = true, enabled = false, playing = true))
        assertEquals(OutputMuteReconcile.NONE, outputMuteReconcile(wasEnabled = false, enabled = true, playing = false))
    }

    @Test
    fun signalsAfterCloseProduceNoCommands() {
        val (closed, closeAction) = reduceOutputMute(mutePaused, OutputMuteEvent.Closed)
        assertEquals(OutputMuteState(pending = false, polling = false, closed = true), closed)
        assertEquals(OutputMuteAction.NONE, closeAction)
        // Every event that follows — a queued unmute, a manual play, anything — is inert.
        for (event in listOf(
            OutputMuteEvent.VolumeSample(muted = true),
            OutputMuteEvent.VolumeSample(muted = false),
            OutputMuteEvent.ManualPlay(freshMuted = false, volumeRestoreAvailable = false),
            OutputMuteEvent.ManualPause,
            OutputMuteEvent.ManualStop,
            OutputMuteEvent.Ended,
        )) {
            assertEquals(closed to OutputMuteAction.NONE, reduceOutputMute(closed, event), "after close for $event")
        }
    }

    @Test
    fun manualStopAndEndedCancelTheResume() {
        for (event in listOf(OutputMuteEvent.ManualStop, OutputMuteEvent.Ended)) {
            val (next, action) = reduceOutputMute(mutePaused, event)
            assertEquals(OutputMuteState(pending = false, polling = false), next, "state for $event")
            assertEquals(OutputMuteAction.NONE, action, "action for $event")
        }
        assertEquals(OutputMuteCommandUse.MANUAL_STOP, outputMuteCommandUse(PlaybackCommand.Stop, PlaybackCommandOrigin.USER))
    }

    @Test
    fun manualPlayUsesTheFreshSample() {
        val (muted, mutedAction) = reduceOutputMute(idle, OutputMuteEvent.ManualPlay(freshMuted = true, volumeRestoreAvailable = false))
        assertEquals(OutputMuteState(pending = true, polling = true), muted)
        assertEquals(OutputMuteAction.PAUSE, mutedAction)
        val (clear, clearAction) = reduceOutputMute(idle, OutputMuteEvent.ManualPlay(freshMuted = false, volumeRestoreAvailable = false))
        assertEquals(OutputMuteState(pending = false, polling = true), clear)
        assertEquals(OutputMuteAction.NONE, clearAction)
        assertEquals(OutputMuteCommandUse.RESOLVE_PLAY, outputMuteCommandUse(PlaybackCommand.Play, PlaybackCommandOrigin.USER))
    }

    @Test
    fun userLoadWithPlayResolvesLikeAPlayRequest() {
        // A normal queue load is how playback actually starts: the watch begins polling with it.
        assertEquals(
            OutputMuteCommandUse.RESOLVE_PLAY,
            outputMuteCommandUse(PlaybackCommand.Load(loadRequest(), 0L, playWhenReady = true), PlaybackCommandOrigin.USER),
        )
        // A load that stays paused releases the wait the same as a manual pause.
        assertEquals(
            OutputMuteCommandUse.MANUAL_PAUSE,
            outputMuteCommandUse(PlaybackCommand.Load(loadRequest(), 0L, playWhenReady = false), PlaybackCommandOrigin.USER),
        )
        // A policy-side load is never the listener's intent.
        assertEquals(
            OutputMuteCommandUse.IGNORED,
            outputMuteCommandUse(PlaybackCommand.Load(loadRequest(), 0L, playWhenReady = true), PlaybackCommandOrigin.POLICY),
        )
    }

    @Test
    fun stalePolicyCommandDropsAfterUserPause() {
        // The policy reduced a RESUME while the intent count stood at zero, then a USER Pause was
        // queued ahead of the POLICY Play it submitted: the loop's count has moved, so the Play
        // drops instead of applying over the listener's pause.
        var userIntentGeneration = 0L
        val observedByPolicy = userIntentGeneration
        userIntentGeneration++
        assertEquals(true, outputMuteIntentIsStale(observedByPolicy, userIntentGeneration))
        // A POLICY command tagged at the current generation still applies.
        assertEquals(false, outputMuteIntentIsStale(userIntentGeneration, userIntentGeneration))
    }

    @Test
    fun policyCommandTaggedWithLastProcessedNoticeGenerationDrops() {
        // The port's intent count stands at zero and the policy's observed generation starts there.
        var portGeneration = 0L
        var observedGeneration = portGeneration
        // A USER pause dispatches: the loop bumps the count and the notice that carries it is still
        // in flight, unprocessed.
        portGeneration++
        val pauseNotice = PlaybackCommandEvent(PlaybackCommand.Pause, PlaybackCommandOrigin.USER, id = 1L, intentGeneration = portGeneration)
        // A queued unmute sample reduces RESUME and submits a POLICY Play tagged with the last
        // processed notice's generation — still zero — so the loop drops it over the in-flight pause.
        val resume = reduceOutputMute(mutePaused, OutputMuteEvent.VolumeSample(muted = false))
        assertEquals(OutputMuteAction.RESUME, resume.second)
        assertEquals(true, outputMuteIntentIsStale(observedGeneration, portGeneration))
        // After the notice is processed the observed generation catches up and a tag lands fresh.
        observedGeneration = pauseNotice.intentGeneration
        assertEquals(false, outputMuteIntentIsStale(observedGeneration, portGeneration))
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

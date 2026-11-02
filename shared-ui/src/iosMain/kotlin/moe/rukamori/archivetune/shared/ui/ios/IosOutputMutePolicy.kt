@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.platform.ios.IosPlaybackPort
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.OutputMuteAction
import moe.rukamori.archivetune.shared.domain.playback.OutputMuteCommandUse
import moe.rukamori.archivetune.shared.domain.playback.OutputMuteEvent
import moe.rukamori.archivetune.shared.domain.playback.OutputMuteReconcile
import moe.rukamori.archivetune.shared.domain.playback.OutputMuteState
import moe.rukamori.archivetune.shared.domain.playback.PlaybackCommand
import moe.rukamori.archivetune.shared.domain.playback.PlaybackCommandOrigin
import moe.rukamori.archivetune.shared.domain.playback.outputMuteCommandUse
import moe.rukamori.archivetune.shared.domain.playback.outputMuteReconcile
import moe.rukamori.archivetune.shared.domain.playback.reduceOutputMute
import moe.rukamori.archivetune.shared.domain.settings.DeviceMutePlaybackRecoveryVolumeKey
import moe.rukamori.archivetune.shared.domain.settings.PauseOnDeviceMuteKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerVolumeKey
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore
import platform.AVFAudio.AVAudioSession

/** How often the watched session samples the system output level. */
private const val VOLUME_POLL_MS = 1_000L

/**
 * What the producers hand the mute actor. Every [OutputMuteEvent] arrives pre-classified except the
 * listener's play edges: their fresh volume read must happen on the main queue, so a USER Play —
 * and an option turned on while playback is wanted, which engages the same way — travels as
 * [ManualPlayRequest] and the actor resolves it at reduce time.
 */
private sealed interface OutputMuteSignal {
    data class Event(val event: OutputMuteEvent) : OutputMuteSignal
    /** A USER command notice and the intent generation it carried; the actor adopts it at process
     * time so a queued signal cannot see a generation that has not reduced yet. */
    data class UserCommand(val use: OutputMuteCommandUse, val generation: Long) : OutputMuteSignal
    /** An option turned on while playback is wanted resolves like a play edge, without intent. */
    data object ManualPlayRequest : OutputMuteSignal
}

/**
 * The iOS binding of the "pause on device mute" and "device mute playback recovery volume"
 * settings, driven entirely by [reduceOutputMute]. One actor on the main queue owns the
 * [OutputMuteState] flow: command notices the port emits, media ends, stored-level changes, volume
 * samples and session close all reach it through one channel in order, and every reduction either
 * submits a PAUSE or PLAY back through the port tagged [PlaybackCommandOrigin.POLICY] — so the
 * watch's own commands never read as the listener's — or updates the poll the actor then
 * reconciles.
 *
 * Kotlin/Native has no key-value observing surface for `outputVolume`, so while
 * [OutputMuteState.polling] and the option hold, a child on the main queue samples the system level
 * once a second and feeds VolumeSample signals; a stored app level of zero silences the mixer
 * identically, so settings emissions feed them too. A play edge's mute decision reads
 * `outputVolume` synchronously inside the actor, so a stale sample never decides it. [close]
 * cancels every collector and the poll, closes the channel and reduces Closed, after which the
 * state itself is terminal and no signal can produce a command.
 */
internal class IosOutputMutePolicy(
    private val scope: CoroutineScope,
    settings: VaultSettingsStore,
    private val port: IosPlaybackPort,
    player: MusicPlayerController,
    /** Sets the app-level output level and saves it, the session's [IosSessionGraph.setVolume]. */
    private val restoreVolume: (Float) -> Unit,
    private val report: (String) -> Unit,
) {
    private val signals = Channel<OutputMuteSignal>(Channel.UNLIMITED)
    private val state = MutableStateFlow(OutputMuteState(pending = false, polling = false))
    private val systemVolume = MutableStateFlow(systemOutputVolume())
    private var volumePoll: Job? = null

    // The last stored picture; the settings collector maintains it on its own queue.
    private var enabled = false
    private var appVolume = 1f
    private var recoveryVolume = 0f

    // Snapshot-collector memory: the playing bit option changes reconcile against, and the end edge.
    private var lastPlaying = false
    private var lastEnded = false

    // The intent generation of the newest USER command notice the actor has processed; every
    // POLICY submit tags it, so a user command still in flight makes the submit stale, not fresh.
    // Actor-owned: it is read and written only inside the actor coroutine.
    private var observedGeneration = port.userIntentGeneration

    private var closed = false

    // The one mutating collector: every signal reduces here, Main-confined and in order.
    private val actorJob = scope.launch(Dispatchers.Main) {
        for (signal in signals) {
            if (closed) break
            val event = when (signal) {
                is OutputMuteSignal.Event -> signal.event
                is OutputMuteSignal.UserCommand -> {
                    observedGeneration = signal.generation
                    when (signal.use) {
                        OutputMuteCommandUse.MANUAL_PAUSE -> OutputMuteEvent.ManualPause
                        OutputMuteCommandUse.MANUAL_STOP -> OutputMuteEvent.ManualStop
                        OutputMuteCommandUse.RESOLVE_PLAY -> manualPlay()
                        OutputMuteCommandUse.IGNORED -> null
                    }
                }
                OutputMuteSignal.ManualPlayRequest -> manualPlay()
            }
            if (event == null) continue
            val (next, action) = reduceOutputMute(state.value, event)
            state.value = next
            when (action) {
                OutputMuteAction.NONE -> Unit
                OutputMuteAction.PAUSE -> {
                    port.submitPolicy(PlaybackCommand.Pause, observedGeneration)
                    report("playback paused: the output is muted")
                }
                OutputMuteAction.RESUME -> port.submitPolicy(PlaybackCommand.Play, observedGeneration)
                OutputMuteAction.RESTORE_VOLUME -> try {
                    restoreVolume(recoveryVolume)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    report("mute recovery volume was not applied: ${failure::class.simpleName}")
                }
            }
            reconcilePoll()
        }
    }

    // USER commands are the listener; the policy's own POLICY submissions never reduce.
    private val commandJob = scope.launch {
        port.commandEvents.collect { notice ->
            val use = outputMuteCommandUse(notice.command, notice.origin)
            if (use != OutputMuteCommandUse.IGNORED) {
                signals.send(OutputMuteSignal.UserCommand(use, notice.intentGeneration))
            }
        }
    }

    // Snapshots still report the media end no command exists for, and keep the playing bit the
    // option reconcile needs.
    private val snapshotJob = scope.launch {
        player.snapshot.collect { snapshot ->
            val playing = snapshot?.let { it.playWhenReady && !it.isEnded } == true
            val ended = snapshot?.isEnded == true
            if (ended && !lastEnded) signals.send(OutputMuteSignal.Event(OutputMuteEvent.Ended))
            lastPlaying = playing
            lastEnded = ended
        }
    }

    // A stored-level change is a volume sample too: muting the app level mutes the output. The
    // option edge itself reconciles through the same pure mapping the tests cover.
    private val settingsJob = scope.launch {
        settings.snapshots.collect { snapshot ->
            val wasEnabled = enabled
            enabled = snapshot?.get(PauseOnDeviceMuteKey) ?: false
            appVolume = snapshot?.get(PlayerVolumeKey)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
            recoveryVolume = (snapshot?.get(DeviceMutePlaybackRecoveryVolumeKey) ?: 0).coerceIn(0, 100) / 100f
            when (outputMuteReconcile(wasEnabled, enabled, lastPlaying)) {
                OutputMuteReconcile.RELEASE ->
                    signals.send(OutputMuteSignal.Event(OutputMuteEvent.ManualPause))
                OutputMuteReconcile.RESOLVE_PLAY -> signals.send(OutputMuteSignal.ManualPlayRequest)
                OutputMuteReconcile.NONE -> if (state.value.polling)
                    signals.send(OutputMuteSignal.Event(OutputMuteEvent.VolumeSample(mutedNow())))
            }
        }
    }

    /** Ends the watch: producers, poll, channel and actor all stop, and no command can follow. */
    fun close() {
        if (closed) return
        closed = true
        commandJob.cancel()
        snapshotJob.cancel()
        settingsJob.cancel()
        signals.trySend(OutputMuteSignal.Event(OutputMuteEvent.Closed))
        signals.close()
        actorJob.cancel()
        volumePoll?.cancel()
        volumePoll = null
    }

    /**
     * Starts or stops the volume poll to match the reducer's answer on every signal: the watch runs
     * while the session wants playback or a mute pause waits, only while the option is on.
     */
    private fun reconcilePoll() {
        if (state.value.polling && enabled && !closed) {
            if (volumePoll != null) return
            volumePoll = scope.launch(Dispatchers.Main) {
                while (true) {
                    systemVolume.value = systemOutputVolume()
                    signals.send(OutputMuteSignal.Event(OutputMuteEvent.VolumeSample(mutedNow())))
                    delay(VOLUME_POLL_MS)
                }
            }
        } else {
            volumePoll?.cancel()
            volumePoll = null
        }
    }

    /** The ManualPlay a play-resolution signal carries, muted on the actor's own fresh read. */
    private fun manualPlay(): OutputMuteEvent.ManualPlay {
        val freshSystemVolume = systemOutputVolume()
        systemVolume.value = freshSystemVolume
        return OutputMuteEvent.ManualPlay(
            freshMuted = enabled && (freshSystemVolume <= 0f || appVolume <= 0f),
            volumeRestoreAvailable = recoveryVolume > 0f && freshSystemVolume > 0f,
        )
    }

    /** Whether the output counts as muted under the option right now: either level at zero. */
    private fun mutedNow(): Boolean = enabled && (systemVolume.value <= 0f || appVolume <= 0f)

    /**
     * The system output level, read straight from the session's bound `outputVolume` property:
     * the platform.AVFAudio binding compiles the read directly, and a synchronous sample is what
     * both the poll and the play-edge mute decision need.
     */
    private fun systemOutputVolume(): Float = AVAudioSession.sharedInstance().outputVolume
}

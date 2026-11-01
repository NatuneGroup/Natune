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
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.OutputMuteAction
import moe.rukamori.archivetune.shared.domain.playback.OutputMuteEvent
import moe.rukamori.archivetune.shared.domain.playback.OutputMuteState
import moe.rukamori.archivetune.shared.domain.playback.reduceOutputMute
import moe.rukamori.archivetune.shared.domain.settings.DeviceMutePlaybackRecoveryVolumeKey
import moe.rukamori.archivetune.shared.domain.settings.PauseOnDeviceMuteKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerVolumeKey
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore
import platform.AVFAudio.AVAudioSession

/** How often the watched session samples the system output level. */
private const val VOLUME_POLL_MS = 1_000L

/**
 * What the snapshot and settings collectors hand the mute actor. Every [OutputMuteEvent] arrives
 * pre-classified except the listener's play edges: their fresh volume read must happen on the main
 * queue, so a play edge travels as [ManualPlayRequest] and the actor resolves it at reduce time.
 */
private sealed interface OutputMuteSignal {
    data class Event(val event: OutputMuteEvent) : OutputMuteSignal
    data object ManualPlayRequest : OutputMuteSignal
}

/**
 * The iOS binding of the "pause on device mute" and "device mute playback recovery volume"
 * settings, driven entirely by [reduceOutputMute]. One actor on the main queue owns the
 * [OutputMuteState]: snapshot edges, stored-level changes, volume samples and session close all
 * reach it through one channel in order, and every reduction either applies a pause, resume or
 * volume restore through the player or updates the state flow the volume watch gates on.
 *
 * Kotlin/Native has no key-value observing surface for `outputVolume`, so while [OutputMuteState.polling]
 * holds, a child on the main queue samples the system level once a second and feeds VolumeSample
 * events; a stored app level of zero silences the mixer identically, so settings emissions feed
 * them too. There is no command-level hook on the player: a pause or play the actor itself issues
 * also arrives as a snapshot edge, and [expectedPolicyPauses]/[expectedPolicyResumes] consume those
 * echoes so only the listener's own commands reduce. A play edge's mute decision reads
 * `outputVolume` synchronously inside the actor, so a stale sample never decides it.
 */
internal class IosOutputMutePolicy(
    private val scope: CoroutineScope,
    settings: VaultSettingsStore,
    private val player: MusicPlayerController,
    /** Sets the app-level output level and saves it, the session's [IosSessionGraph.setVolume]. */
    private val restoreVolume: (Float) -> Unit,
    private val report: (String) -> Unit,
) {
    private val signals = Channel<OutputMuteSignal>(Channel.UNLIMITED)
    private val state = MutableStateFlow(OutputMuteState(pending = false, polling = false))
    private val systemVolume = MutableStateFlow(AVAudioSession.sharedInstance().outputVolume)
    private var volumePoll: Job? = null

    // The last stored picture; the settings collector maintains it on its own queue.
    private var enabled = false
    private var appVolume = 1f
    private var recoveryVolume = 0f

    // Actor-owned echo bookkeeping for the commands the policy itself submits.
    private var expectedPolicyPauses = 0
    private var expectedPolicyResumes = 0

    // Collector-owned snapshot edge memory.
    private var lastPlaying = false
    private var lastEnded = false
    private var lastExists = false

    private var closed = false

    init {
        // The one mutating collector: every signal reduces here, Main-confined and in order.
        scope.launch(Dispatchers.Main) {
            for (signal in signals) {
                val event = when (signal) {
                    is OutputMuteSignal.Event -> signal.event
                    OutputMuteSignal.ManualPlayRequest -> {
                        val freshSystemVolume = AVAudioSession.sharedInstance().outputVolume
                        systemVolume.value = freshSystemVolume
                        OutputMuteEvent.ManualPlay(
                            freshMuted = enabled && (freshSystemVolume <= 0f || appVolume <= 0f),
                            volumeRestoreAvailable = recoveryVolume > 0f && freshSystemVolume > 0f,
                        )
                    }
                }
                if (event == OutputMuteEvent.ManualPause && expectedPolicyPauses > 0) {
                    expectedPolicyPauses--
                    continue
                }
                if (event is OutputMuteEvent.ManualPlay && expectedPolicyResumes > 0) {
                    expectedPolicyResumes--
                    continue
                }
                val (next, action) = reduceOutputMute(state.value, event)
                state.value = next
                when (action) {
                    OutputMuteAction.NONE -> Unit
                    OutputMuteAction.PAUSE -> {
                        expectedPolicyPauses++
                        player.pause()
                        report("playback paused: the output is muted")
                    }
                    OutputMuteAction.RESUME -> {
                        expectedPolicyResumes++
                        player.play()
                    }
                    OutputMuteAction.RESTORE_VOLUME -> try {
                        restoreVolume(recoveryVolume)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        report("mute recovery volume was not applied: ${failure::class.simpleName}")
                    }
                }
            }
        }
        // Player edges become signals; a policy-issued pause or play echoes here but the actor's
        // expected counters consume it, so only the listener's own commands reduce.
        scope.launch {
            player.snapshot.collect { snapshot ->
                val playing = snapshot?.let { it.playWhenReady && !it.isEnded } == true
                val ended = snapshot?.isEnded == true
                val exists = snapshot != null
                when {
                    ended && !lastEnded -> signals.send(OutputMuteSignal.Event(OutputMuteEvent.Ended))
                    playing && !lastPlaying -> signals.send(OutputMuteSignal.ManualPlayRequest)
                    !playing && lastPlaying -> signals.send(OutputMuteSignal.Event(OutputMuteEvent.ManualPause))
                    lastExists && !exists -> signals.send(OutputMuteSignal.Event(OutputMuteEvent.ManualStop))
                }
                lastPlaying = playing
                lastEnded = ended
                lastExists = exists
            }
        }
        // A stored-level change is a volume sample too: muting the app level mutes the output, and
        // toggling the option clears or engages the watch like the matching manual command.
        scope.launch {
            settings.snapshots.collect { snapshot ->
                val wasEnabled = enabled
                enabled = snapshot?.get(PauseOnDeviceMuteKey) ?: false
                appVolume = snapshot?.get(PlayerVolumeKey)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
                recoveryVolume = (snapshot?.get(DeviceMutePlaybackRecoveryVolumeKey) ?: 0).coerceIn(0, 100) / 100f
                when {
                    wasEnabled && !enabled ->
                        signals.send(OutputMuteSignal.Event(OutputMuteEvent.ManualPause))
                    !wasEnabled && enabled && lastPlaying -> signals.send(OutputMuteSignal.ManualPlayRequest)
                    state.value.polling ->
                        signals.send(OutputMuteSignal.Event(OutputMuteEvent.VolumeSample(mutedNow())))
                }
            }
        }
        // The volume watch itself, gated by the reducer's polling bit on every state change.
        scope.launch {
            state.collect { current ->
                if (current.polling && enabled && !closed && volumePoll == null) {
                    volumePoll = launch(Dispatchers.Main) {
                        while (true) {
                            systemVolume.value = AVAudioSession.sharedInstance().outputVolume
                            signals.send(OutputMuteSignal.Event(OutputMuteEvent.VolumeSample(mutedNow())))
                            delay(VOLUME_POLL_MS)
                        }
                    }
                } else if (!current.polling) {
                    volumePoll?.cancel()
                    volumePoll = null
                }
            }
        }
    }

    /** Ends the watch at once; every collector still ends with the session's scope. */
    fun close() {
        if (closed) return
        closed = true
        signals.trySend(OutputMuteSignal.Event(OutputMuteEvent.Closed))
        volumePoll?.cancel()
        volumePoll = null
    }

    /** Whether the output counts as muted under the option right now: either level at zero. */
    private fun mutedNow(): Boolean = enabled && (systemVolume.value <= 0f || appVolume <= 0f)
}

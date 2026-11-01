@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.MuteTransitionAction
import moe.rukamori.archivetune.shared.domain.playback.PauseOnMuteController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.playback.shouldPollOutputMute
import moe.rukamori.archivetune.shared.domain.settings.DeviceMutePlaybackRecoveryVolumeKey
import moe.rukamori.archivetune.shared.domain.settings.PauseOnDeviceMuteKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerVolumeKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore
import platform.AVFAudio.AVAudioSession

/** How often the playing session samples the system output level. */
private const val VOLUME_POLL_MS = 1_000L

/**
 * One output-mute sample of the iOS session: everything [PauseOnMuteController.observe] asks for,
 * gathered from the stored settings, the player and the system output volume. The muted stream
 * Android watches is the system output volume here, and a stored app level of zero silences the
 * engine's mixer the same way, so both feed the sample.
 */
private data class IosMuteSample(
    val enabled: Boolean,
    val muted: Boolean,
    val playing: Boolean,
    val ended: Boolean,
    val volumeRestoreAvailable: Boolean,
    val recoveryVolume: Float,
)

/**
 * The iOS binding of the "pause on device mute" and "device mute playback recovery volume"
 * settings. It feeds [PauseOnMuteController] from the stored option, the stored app output level,
 * the audio session's system output volume — sampled on the main queue once a second while
 * playback is expected, because Kotlin/Native has no key-value observing surface — and the
 * player's snapshots, and applies the returned pause, resume or volume restore through the same
 * player and volume the listener drives.
 *
 * The policy is a collector, not a command hook: a play request while muted needs no interception,
 * because the resulting playing sample is the edge the controller acts on. The poll runs while
 * playback is expected and while a mute pause the policy itself made still awaits its unmute —
 * otherwise a paused session would stop sampling and never see the level lift. A pause or stop the
 * listener caused, a media end, or a play they asked for while still muted clears that wait and
 * stops the poll; each new play request polls once on its playing edge, so a stale muted sample
 * cannot hold a manual play back. The system output level cannot be lifted programmatically, so a
 * stored recovery volume only answers a mute that came from the app level.
 */
internal class IosOutputMutePolicy(
    scope: CoroutineScope,
    settings: VaultSettingsStore,
    private val player: MusicPlayerController,
    /** Sets the app-level output level and saves it, the session's [IosSessionGraph.setVolume]. */
    private val restoreVolume: (Float) -> Unit,
    private val report: (String) -> Unit,
) {
    private val controller = PauseOnMuteController()
    private val systemVolume = MutableStateFlow(AVAudioSession.sharedInstance().outputVolume)
    private var volumePoll: Job? = null
    // Whether a pause the policy itself made still waits for the unmute that resumes it.
    private var mutePausePending = false
    private var lastMuted = false
    private var lastPlaying = false
    private var closed = false

    init {
        scope.launch {
            player.snapshot.collect { snapshot ->
                val watching = !closed && shouldPollOutputMute(
                    playWhenReady = snapshot?.playWhenReady == true,
                    isEnded = snapshot == null || snapshot.isEnded,
                    mutePausePending = mutePausePending,
                )
                if (watching && volumePoll == null) {
                    volumePoll = launch(Dispatchers.Main) {
                        while (true) {
                            systemVolume.value = AVAudioSession.sharedInstance().outputVolume
                            delay(VOLUME_POLL_MS)
                        }
                    }
                } else if (!watching) {
                    volumePoll?.cancel()
                    volumePoll = null
                }
            }
        }
        scope.launch {
            combine(
                settings.snapshots,
                player.snapshot,
                systemVolume,
            ) { preferences, playbackNow, volume -> sampleOf(preferences, playbackNow, volume) }
                .distinctUntilChanged()
                .collect { sample ->
                    val action = controller.observe(
                        enabled = sample.enabled,
                        muted = sample.muted,
                        playing = sample.playing,
                        volumeRestoreAvailable = sample.volumeRestoreAvailable,
                    )
                    updateMutePausePending(sample, action)
                    lastMuted = sample.muted
                    lastPlaying = sample.playing
                    apply(action, sample)
                }
        }
    }

    /** Stops the volume poll at once; the collecting coroutines end with the session's scope. */
    fun close() {
        if (closed) return
        closed = true
        mutePausePending = false
        volumePoll?.cancel()
        volumePoll = null
    }

    /**
     * [mutePausePending] latches only behind a pause the mute itself caused, so the volume watch can
     * outlive the pause it made. Every way the listener takes the pause back clears it — pausing or
     * stopping by hand, reaching a media end, or pressing play while still muted — and so do the
     * resume and the volume restore the wait exists to serve.
     */
    private fun updateMutePausePending(sample: IosMuteSample, action: MuteTransitionAction) {
        mutePausePending = when {
            sample.ended -> false
            action == MuteTransitionAction.PAUSE -> sample.muted && !lastMuted
            action == MuteTransitionAction.RESUME || action == MuteTransitionAction.RESTORE_VOLUME -> false
            !sample.playing && lastPlaying && !sample.muted -> false
            else -> mutePausePending
        }
    }

    private fun sampleOf(
        preferences: PreferenceSnapshot?,
        playbackNow: PlaybackSnapshot?,
        systemVolume: Float,
    ): IosMuteSample {
        val appVolume = preferences?.get(PlayerVolumeKey)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        val recoveryPercent = (preferences?.get(DeviceMutePlaybackRecoveryVolumeKey) ?: 0).coerceIn(0, 100)
        return IosMuteSample(
            enabled = preferences?.get(PauseOnDeviceMuteKey) ?: false,
            muted = systemVolume <= 0f || appVolume <= 0f,
            playing = playbackNow?.let { it.playWhenReady && !it.isEnded } == true,
            ended = playbackNow == null || playbackNow.isEnded,
            // Restoring the app level cannot lift a mute the system volume itself owns.
            volumeRestoreAvailable = recoveryPercent > 0 && systemVolume > 0f,
            recoveryVolume = recoveryPercent / 100f,
        )
    }

    private fun apply(action: MuteTransitionAction, sample: IosMuteSample) {
        when (action) {
            MuteTransitionAction.NONE -> Unit
            MuteTransitionAction.PAUSE -> {
                player.pause()
                report("playback paused: the output is muted")
            }
            MuteTransitionAction.RESUME -> player.play()
            MuteTransitionAction.RESTORE_VOLUME -> try {
                restoreVolume(sample.recoveryVolume)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("mute recovery volume was not applied: ${failure::class.simpleName}")
            }
        }
    }
}

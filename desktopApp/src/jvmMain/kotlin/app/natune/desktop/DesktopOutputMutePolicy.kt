package app.natune.desktop

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import app.natune.desktop.playback.DesktopPlaybackPort
import app.natune.shared.domain.playback.MusicPlayerController
import app.natune.shared.domain.playback.MuteTransitionAction
import app.natune.shared.domain.playback.PauseOnMuteController
import app.natune.shared.domain.playback.PlaybackSnapshot
import app.natune.shared.domain.settings.DeviceMutePlaybackRecoveryVolumeKey
import app.natune.shared.domain.settings.PauseOnDeviceMuteKey
import app.natune.shared.domain.settings.PlayerVolumeKey
import app.natune.shared.domain.settings.PreferenceSnapshot
import app.natune.shared.ui.settings.VaultSettingsStore

/**
 * One output-mute sample of the desktop session: everything [PauseOnMuteController.observe] asks
 * for, gathered from the stored settings and the player. The desktop's mute is the app output
 * level, which is the only reachable "device volume" on this engine: a stored volume of zero, or
 * the port-level output mute, reads as the muted stream Android watches.
 */
private data class MuteSample(
    val enabled: Boolean,
    val muted: Boolean,
    val playing: Boolean,
    val volumeRestoreAvailable: Boolean,
    val recoveryVolume: Float,
)

/**
 * The desktop binding of the "pause on device mute" and "device mute playback recovery volume"
 * settings. It feeds [PauseOnMuteController] from the stored option, the stored output level and
 * the player's snapshots, and applies the returned pause, resume or volume-restore through the
 * same player and volume the listener drives.
 *
 * The policy is a collector, not a command hook: a play request while muted needs no interception,
 * because the resulting playing sample is the edge the controller acts on, matching the moment the
 * Android service reacts to a play request while its stream is muted.
 */
internal class DesktopOutputMutePolicy(
    scope: CoroutineScope,
    settings: VaultSettingsStore,
    private val player: MusicPlayerController,
    playback: DesktopPlaybackPort,
    /** Sets the app output level and saves it, the session's [DesktopSessionGraph.setVolume]. */
    private val restoreVolume: (Float) -> Unit,
) {
    private val controller = PauseOnMuteController()

    init {
        scope.launch {
            combine(
                settings.snapshots,
                player.snapshot,
                playback.audioOutputEnabled,
            ) { preferences, playbackNow, outputEnabled -> sampleOf(preferences, playbackNow, outputEnabled) }
                .distinctUntilChanged()
                .collect { sample ->
                    apply(
                        controller.observe(
                            enabled = sample.enabled,
                            muted = sample.muted,
                            playing = sample.playing,
                            volumeRestoreAvailable = sample.volumeRestoreAvailable,
                        ),
                        sample,
                    )
                }
        }
    }

    private fun sampleOf(
        preferences: PreferenceSnapshot?,
        playbackNow: PlaybackSnapshot?,
        outputEnabled: Boolean,
    ): MuteSample {
        val volume = preferences?.get(PlayerVolumeKey)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        val recoveryPercent = (preferences?.get(DeviceMutePlaybackRecoveryVolumeKey) ?: 0).coerceIn(0, 100)
        return MuteSample(
            enabled = preferences?.get(PauseOnDeviceMuteKey) ?: false,
            muted = !outputEnabled || volume <= 0f,
            playing = playbackNow?.let { it.playWhenReady && !it.isEnded } == true,
            volumeRestoreAvailable = recoveryPercent > 0,
            recoveryVolume = recoveryPercent / 100f,
        )
    }

    private fun apply(action: MuteTransitionAction, sample: MuteSample) {
        when (action) {
            MuteTransitionAction.NONE -> Unit
            MuteTransitionAction.PAUSE -> {
                player.pause()
                desktopDiagnostic("playback paused: the output is muted")
            }
            MuteTransitionAction.RESUME -> player.play()
            MuteTransitionAction.RESTORE_VOLUME -> try {
                restoreVolume(sample.recoveryVolume)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                desktopDiagnostic("mute recovery volume was not applied: ${failure.javaClass.simpleName}")
            }
        }
    }
}

@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.ui.ios

import kotlinx.cinterop.COpaquePointer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.MuteTransitionAction
import moe.rukamori.archivetune.shared.domain.playback.PauseOnMuteController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.settings.DeviceMutePlaybackRecoveryVolumeKey
import moe.rukamori.archivetune.shared.domain.settings.PauseOnDeviceMuteKey
import moe.rukamori.archivetune.shared.domain.settings.PlayerVolumeKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.ui.settings.VaultSettingsStore
import platform.AVFAudio.AVAudioSession
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.NSKeyValueObservingProtocol
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.darwin.NSObject

private const val OUTPUT_VOLUME_KEY_PATH = "outputVolume"

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
    val volumeRestoreAvailable: Boolean,
    val recoveryVolume: Float,
)

/**
 * The iOS binding of the "pause on device mute" and "device mute playback recovery volume"
 * settings. It feeds [PauseOnMuteController] from the stored option, the stored app output level,
 * the audio session's system output volume — observed through key-value observing, the documented
 * route for `outputVolume` — and the player's snapshots, and applies the returned pause, resume or
 * volume restore through the same player and volume the listener drives.
 *
 * The policy is a collector, not a command hook: a play request while muted needs no interception,
 * because the resulting playing sample is the edge the controller acts on. The system output level
 * cannot be lifted programmatically, so a stored recovery volume only answers a mute that came from
 * the app level; a system-volume mute pauses and waits for the listener's own unmute.
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
    private val observer = IosVolumeObserver { systemVolume.value = it }
    private var closed = false

    init {
        AVAudioSession.sharedInstance().addObserver(
            observer,
            forKeyPath = OUTPUT_VOLUME_KEY_PATH,
            options = NSKeyValueObservingOptionNew,
            context = null,
        )
        scope.launch {
            combine(
                settings.snapshots,
                player.snapshot,
                systemVolume,
            ) { preferences, playbackNow, volume -> sampleOf(preferences, playbackNow, volume) }
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

    /** Detaches the volume observer; the collecting coroutine ends with the session's scope. */
    fun close() {
        if (closed) return
        closed = true
        AVAudioSession.sharedInstance().removeObserver(observer, forKeyPath = OUTPUT_VOLUME_KEY_PATH)
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

/**
 * Reads the audio session's output volume on every change notification. Key-value observing calls
 * arrive on the queue that changed the property, so the observer only writes the state flow, which
 * is safe from any queue.
 */
private class IosVolumeObserver(private val onVolume: (Float) -> Unit) : NSObject(), NSKeyValueObservingProtocol {
    override fun observeValueForKeyPath(
        keyPath: String?,
        ofObject: Any?,
        change: Map<Any?, *>?,
        context: COpaquePointer?,
    ) {
        if (keyPath == OUTPUT_VOLUME_KEY_PATH) {
            (ofObject as? AVAudioSession)?.let { onVolume(it.outputVolume) }
        }
    }
}

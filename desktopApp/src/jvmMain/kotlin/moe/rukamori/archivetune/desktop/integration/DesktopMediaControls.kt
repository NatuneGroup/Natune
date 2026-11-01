package moe.rukamori.archivetune.desktop.integration

import java.security.MessageDigest
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackClock
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.playback.SystemMonotonicClock

/** Native transport messages, not a queue or a second playback state model. */
sealed interface DesktopTransportAction {
    data object Play : DesktopTransportAction
    data object Pause : DesktopTransportAction
    data object Toggle : DesktopTransportAction
    data object Stop : DesktopTransportAction
    data object Next : DesktopTransportAction
    data object Previous : DesktopTransportAction
    data class SeekTo(val occurrenceId: String, val positionMs: Long) : DesktopTransportAction
    data class Speed(val speed: Float) : DesktopTransportAction
    data class SetShuffle(val enabled: Boolean) : DesktopTransportAction
    data class SetRepeat(val repeat: QueueRepeatMode) : DesktopTransportAction
    data class SetVolume(val volume: Float) : DesktopTransportAction
}

/** All callbacks, including the occurrence check, execute on the existing queue owner's dispatcher. */
class DesktopMediaControls(
    private val snapshot: () -> PlaybackSnapshot?,
    private val dispatchToOwner: (() -> Unit) -> Unit,
    private val consume: (DesktopTransportAction) -> Unit,
) {
    fun submit(action: DesktopTransportAction) = dispatchToOwner { consume(action) }

    fun setPosition(path: String, positionMicros: Long) = dispatchToOwner {
        val current = snapshot() ?: return@dispatchToOwner
        if (path != trackPath(current.occurrenceId) || !current.isSeekable || current.isEnded || positionMicros < 0) {
            return@dispatchToOwner
        }
        consume(DesktopTransportAction.SeekTo(current.occurrenceId, clamp(current, positionMicros / 1000)))
    }

    fun seek(offsetMicros: Long) {
        val occurrence = snapshot()?.occurrenceId ?: return
        dispatchToOwner {
            val current = snapshot() ?: return@dispatchToOwner
            if (current.occurrenceId != occurrence || !current.isSeekable || current.isEnded) return@dispatchToOwner
            val position = PlaybackClock.positionMs(current, SystemMonotonicClock.nowNanos()).coerceAtLeast(0)
            val offset = offsetMicros / 1000
            val target = if (offset > 0 && position > Long.MAX_VALUE - offset) Long.MAX_VALUE else position + offset
            consume(DesktopTransportAction.SeekTo(occurrence, clamp(current, target)))
        }
    }

    private fun clamp(snapshot: PlaybackSnapshot, ms: Long) = ms.coerceIn(0, snapshot.durationMs ?: Long.MAX_VALUE)
}

/**
 * Called on the queue owner's dispatcher; next/previous remain that owner's decisions. Shuffle, repeat and volume are
 * not controller commands: they belong to the queue owner, the repeat preference and the output volume, so the host
 * hands them to those owners through the set* callbacks.
 */
fun MusicPlayerController.consumeDesktopTransport(
    action: DesktopTransportAction,
    next: () -> Unit,
    previous: () -> Unit,
    setShuffle: (Boolean) -> Unit = {},
    setRepeat: (QueueRepeatMode) -> Unit = {},
    setVolume: (Float) -> Unit = {},
) {
    when (action) {
        DesktopTransportAction.Play -> play()
        DesktopTransportAction.Pause -> pause()
        DesktopTransportAction.Toggle -> if (snapshot.value?.playWhenReady == true) pause() else play()
        DesktopTransportAction.Stop -> stop()
        DesktopTransportAction.Next -> next()
        DesktopTransportAction.Previous -> previous()
        is DesktopTransportAction.SeekTo -> if (snapshot.value?.occurrenceId == action.occurrenceId) seekTo(action.positionMs)
        is DesktopTransportAction.Speed -> if (action.speed.isFinite() && action.speed in 0.25f..4f) setPlaybackSpeed(action.speed)
        is DesktopTransportAction.SetShuffle -> setShuffle(action.enabled)
        is DesktopTransportAction.SetRepeat -> setRepeat(action.repeat)
        is DesktopTransportAction.SetVolume -> if (action.volume.isFinite()) setVolume(action.volume.coerceIn(0f, 1f))
    }
}

fun trackPath(occurrenceId: String): String = "/org/mpris/MediaPlayer2/track/" +
    MessageDigest.getInstance("SHA-256").digest(occurrenceId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

fun PlaybackSnapshot?.mprisStatus(): String = when {
    this == null || isEnded -> "Stopped"
    playWhenReady -> "Playing"
    else -> "Paused"
}

fun isSeekDiscontinuity(previous: PlaybackSnapshot?, current: PlaybackSnapshot?): Boolean =
    previous != null && current != null && previous.occurrenceId == current.occurrenceId &&
        previous.playbackGeneration == current.playbackGeneration &&
        previous.discontinuityGeneration != current.discontinuityGeneration

/** Display-only metadata must correspond to the accepted occurrence, never a provider URL or credential. */
data class DesktopMediaMetadata(
    val occurrenceId: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String = "",
    val artworkUri: String? = null,
    val canNext: Boolean = false,
    val canPrevious: Boolean = false,
)

/** Player-level controls state beside the per-track [DesktopMediaMetadata]; it is never track-scoped. */
data class DesktopTransportState(
    val shuffleEnabled: Boolean = false,
    val repeat: QueueRepeatMode = QueueRepeatMode.OFF,
    val volume: Float = 1f,
)

/** MPRIS LoopStatus accepts exactly "None", "Track" and "Playlist". */
fun QueueRepeatMode.mprisLoopStatus(): String = when (this) {
    QueueRepeatMode.OFF -> "None"
    QueueRepeatMode.ONE -> "Track"
    QueueRepeatMode.ALL -> "Playlist"
}

/** Null for any string outside the three MPRIS LoopStatus values, so the caller rejects the write. */
fun mprisLoopStatusRepeat(status: String): QueueRepeatMode? = when (status) {
    "None" -> QueueRepeatMode.OFF
    "Track" -> QueueRepeatMode.ONE
    "Playlist" -> QueueRepeatMode.ALL
    else -> null
}

/**
 * MPRIS volume writes above 1.0 ask for amplification the output has no stage for, and a negative write
 * is a mute request; both clamp into the engine's 0..1. A non-finite value is rejected, never applied.
 */
fun mprisVolumeLevel(value: Double): Float? =
    if (value.isFinite()) value.coerceIn(0.0, 1.0).toFloat() else null

interface DesktopSystemIntegration : AutoCloseable {
    val trayAvailable: Boolean
    fun update(snapshot: PlaybackSnapshot?, metadata: DesktopMediaMetadata?, transport: DesktopTransportState)
}

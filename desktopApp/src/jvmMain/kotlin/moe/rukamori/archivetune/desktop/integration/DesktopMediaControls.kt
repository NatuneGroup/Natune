/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration

import java.security.MessageDigest
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

/** Called on the queue owner's dispatcher; next/previous remain that owner's decisions. */
fun MusicPlayerController.consumeDesktopTransport(
    action: DesktopTransportAction,
    next: () -> Unit,
    previous: () -> Unit,
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

interface DesktopSystemIntegration : AutoCloseable {
    val trayAvailable: Boolean
    fun update(snapshot: PlaybackSnapshot?, metadata: DesktopMediaMetadata?)
}

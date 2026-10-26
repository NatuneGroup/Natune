/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.playback.port

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.playback.queues.finiteOccurrence
import moe.rukamori.archivetune.shared.domain.playback.MonotonicClock
import moe.rukamori.archivetune.shared.domain.playback.PlaybackCommand
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureReason
import moe.rukamori.archivetune.shared.domain.playback.PlaybackObservation
import moe.rukamori.archivetune.shared.domain.playback.PlaybackOutputRoute
import moe.rukamori.archivetune.shared.domain.playback.PlaybackPort
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSessionTracker
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.playback.SystemMonotonicClock

/**
 * Publishes what a Media3 [Player] is doing as [PlaybackSnapshot] values. It never starts a queue:
 * MusicService still resolves every source through resolveMultiSourceDataSpec, so a direct
 * [PlaybackCommand.Load] is refused instead of played around that chain.
 *
 * Media3 is touched only on the main thread. [submit] posts there, and listener callbacks already
 * arrive there. [attach] follows the active player, so a crossfade promotion, which swaps in a new
 * session player, starts a new session. [close] detaches the listener and leaves the player
 * running, because MusicService owns the player and outlives any one connection.
 *
 * @param occurrenceOf Reads the queue occurrence key a media item was queued with, or null when it has
 *   none. Ordinary items then fall back to their media id and position in the timeline.
 */
class Media3PlaybackPort(
    private val occurrenceOf: (MediaItem) -> String? = { item -> item.finiteOccurrence() },
    private val clock: MonotonicClock = SystemMonotonicClock,
) : PlaybackPort {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tracker = PlaybackSessionTracker()
    private val latest = MutableStateFlow<PlaybackSnapshot?>(null)
    private val publishedFailures = MutableSharedFlow<PlaybackFailure>(extraBufferCapacity = FAILURE_BUFFER)

    override val snapshots: StateFlow<PlaybackSnapshot?> = latest.asStateFlow()
    override val failures: SharedFlow<PlaybackFailure> = publishedFailures.asSharedFlow()

    private var attachedPlayer: Player? = null

    /** Occurrence and media id of the last session, so an empty player keeps reporting under them. */
    private var session: Pair<String, String>? = null
    private var closed = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = publish()

        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publish()

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = publish()

        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = publish()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val player = attachedPlayer ?: return
            startSession(player, repeatOfSameItem = reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val isSeek = reason == Player.DISCONTINUITY_REASON_SEEK ||
                reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
            if (isSeek && session != null) tracker.recordDiscontinuity()
            publish()
        }

        override fun onPlayerErrorChanged(playbackError: PlaybackException?) {
            val error = playbackError ?: return
            val occurrence = session?.first ?: return
            publishedFailures.tryEmit(
                PlaybackFailure(
                    occurrenceId = occurrence,
                    reason = error.failureReason(),
                    message = error.message ?: "Media3 error ${error.errorCode}",
                ),
            )
        }
    }

    /** Follows [newPlayer] as the active player. Attaching the player already followed does nothing. */
    fun attach(newPlayer: Player) {
        if (closed || newPlayer === attachedPlayer) return
        attachedPlayer?.removeListener(listener)
        attachedPlayer = newPlayer
        newPlayer.addListener(listener)
        // A different player instance is a different output, so it starts a new playback generation.
        startSession(newPlayer, repeatOfSameItem = false)
    }

    override fun submit(command: PlaybackCommand) {
        mainHandler.post { execute(command) }
    }

    override fun close() {
        if (closed) return
        closed = true
        attachedPlayer?.removeListener(listener)
        attachedPlayer = null
        session = null
    }

    /**
     * Starts a session for the player's current item. A repeat of the same occurrence is a
     * discontinuity rather than a new session, because the position jumps back inside it.
     */
    private fun startSession(player: Player, repeatOfSameItem: Boolean) {
        val item = player.currentMediaItem
        if (item != null) {
            val mediaId = item.mediaId.takeIf { it.isNotBlank() } ?: return
            val occurrence = occurrenceOf(item)?.takeIf { it.isNotBlank() }
                ?: "$mediaId@${player.currentMediaItemIndex}"
            if (repeatOfSameItem && session?.first == occurrence) {
                tracker.recordDiscontinuity()
            } else {
                beginSession(occurrence, mediaId)
            }
        } else {
            session?.let { (occurrence, mediaId) -> beginSession(occurrence, mediaId) }
        }
        publish()
    }

    private fun beginSession(occurrence: String, mediaId: String) {
        tracker.beginSession(occurrence, mediaId)
        session = occurrence to mediaId
    }

    /** Publishes one observation of the attached player. Nothing is published before a session. */
    private fun publish() {
        val player = attachedPlayer ?: return
        if (session == null) return
        val state = player.playbackState
        val observation = PlaybackObservation(
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it >= 0L },
            bufferedPositionMs = player.bufferedPosition.takeIf { it != C.TIME_UNSET && it >= 0L },
            isSeekable = player.isCurrentMediaItemSeekable,
            playbackSpeed = player.playbackParameters.speed.takeIf { it.isFinite() && it > 0f } ?: 1f,
            playWhenReady = player.playWhenReady,
            isPlaying = player.isPlaying,
            isBuffering = state == Player.STATE_BUFFERING,
            isEnded = state == Player.STATE_ENDED,
            outputRoute = player.deviceInfo.outputRoute(),
        )
        latest.value = tracker.observe(observation, clock.nowNanos())
    }

    private fun execute(command: PlaybackCommand) {
        if (closed) return
        val player = attachedPlayer
        when (command) {
            is PlaybackCommand.Load -> publishedFailures.tryEmit(
                PlaybackFailure(
                    occurrenceId = command.request.occurrenceId,
                    reason = PlaybackFailureReason.UNSUPPORTED_LOCATOR,
                    message = LOAD_THROUGH_QUEUE,
                ),
            )
            PlaybackCommand.Play -> player?.play()
            PlaybackCommand.Pause -> player?.pause()
            PlaybackCommand.Stop -> player?.stop()
            is PlaybackCommand.SeekTo -> player?.seekTo(command.positionMs)
            is PlaybackCommand.SetSpeed -> player?.setPlaybackSpeed(command.speed)
            is PlaybackCommand.SetPitch -> player?.let { it.playbackParameters = it.playbackParameters.withPitch(command.pitch) }
        }
    }

    private fun PlaybackException.failureReason(): PlaybackFailureReason = when (errorCode) {
        in IO_ERROR_CODES -> PlaybackFailureReason.OPEN_FAILED
        in AUDIO_OUTPUT_ERROR_CODES -> PlaybackFailureReason.OUTPUT_FAILED
        else -> PlaybackFailureReason.DECODE_FAILED
    }

    private fun DeviceInfo.outputRoute(): PlaybackOutputRoute = when (playbackType) {
        DeviceInfo.PLAYBACK_TYPE_LOCAL -> PlaybackOutputRoute.LOCAL
        DeviceInfo.PLAYBACK_TYPE_REMOTE -> PlaybackOutputRoute.REMOTE
        else -> PlaybackOutputRoute.UNKNOWN
    }

    private companion object {
        const val FAILURE_BUFFER = 8

        // Media3 error codes are grouped by thousands: 2xxx I/O, 5xxx audio renderer.
        val IO_ERROR_CODES = 2000..2999
        val AUDIO_OUTPUT_ERROR_CODES = 5000..5999

        const val LOAD_THROUGH_QUEUE =
            "Media3 plays queue items only; a direct load would bypass resolveMultiSourceDataSpec"
    }
}

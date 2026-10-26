/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.playback

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.C
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.models.ActiveOutputDevice
import moe.rukamori.archivetune.models.MediaMetadata
import javax.inject.Inject
import kotlin.math.roundToInt

data class ImmersivePlayerData(
    val metadata: MediaMetadata,
    val song: Song?,
    val format: FormatEntity?,
    val queueTitle: String?,
    val playbackState: Int,
    val isPlaying: Boolean,
    val canSkipPrevious: Boolean,
    val canSkipNext: Boolean,
    val outputDevice: ActiveOutputDevice,
    val hasPlaybackError: Boolean,
)

data class ImmersivePlaybackProgress(
    val positionMs: Long,
    val durationMs: Long,
    val volume: Float,
)

@OptIn(ExperimentalCoroutinesApi::class)
@ViewModelScoped
class ImmersivePlayerRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val connection = MutableStateFlow<PlayerConnection?>(null)

    // rukamori polls the position every 250ms for as long as a connection is attached, which is
    // the whole time the activity is started, sheet collapsed or not. The sheet flips this off
    // while it is collapsed; the last values stay on screen.
    private val progressActive = MutableStateFlow(true)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun attach(playerConnection: PlayerConnection) {
        connection.value = playerConnection
    }

    fun detach(playerConnection: PlayerConnection) {
        connection.compareAndSet(playerConnection, null)
    }

    fun observePlayer(): Flow<ImmersivePlayerData?> =
        connection.flatMapLatest { playerConnection ->
            if (playerConnection == null) {
                flowOf(null)
            } else {
                val media =
                    combine(
                        playerConnection.mediaMetadata,
                        playerConnection.currentSong,
                        playerConnection.currentFormat,
                    ) { metadata, song, format -> Triple(metadata, song, format) }
                val transport =
                    combine(
                        playerConnection.playbackState,
                        playerConnection.isPlaying,
                        playerConnection.canSkipPrevious,
                        playerConnection.canSkipNext,
                        playerConnection.queueTitle,
                    ) { playbackState, isPlaying, canSkipPrevious, canSkipNext, queueTitle ->
                        TransportData(playbackState, isPlaying, canSkipPrevious, canSkipNext, queueTitle)
                    }
                combine(
                    media,
                    transport,
                    playerConnection.service.activeAudioDevice,
                    playerConnection.error,
                ) { (metadata, song, format), transportData, outputDevice, error ->
                    metadata?.let {
                        ImmersivePlayerData(
                            metadata = it,
                            song = song,
                            format = format,
                            queueTitle = transportData.queueTitle,
                            playbackState = transportData.playbackState,
                            isPlaying = transportData.isPlaying,
                            canSkipPrevious = transportData.canSkipPrevious,
                            canSkipNext = transportData.canSkipNext,
                            outputDevice = outputDevice,
                            hasPlaybackError = error != null,
                        )
                    }
                }
            }
        }

    fun setProgressActive(active: Boolean) {
        progressActive.value = active
    }

    fun observeProgress(): Flow<ImmersivePlaybackProgress> =
        combine(connection, progressActive) { playerConnection, active -> playerConnection to active }
            .flatMapLatest { (playerConnection, active) ->
            if (playerConnection == null) {
                flowOf(ImmersivePlaybackProgress(0L, 0L, readVolumeFraction()))
            } else if (!active) {
                emptyFlow()
            } else {
                flow {
                    while (currentCoroutineContext().isActive) {
                        val player = playerConnection.player
                        val duration = player.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
                        emit(
                            ImmersivePlaybackProgress(
                                positionMs = player.currentPosition.coerceAtLeast(0L),
                                durationMs = duration,
                                volume = readVolumeFraction(),
                            ),
                        )
                        delay(250L)
                    }
                }
            }
        }

    fun togglePlayPause() {
        connection.value?.player?.togglePlayPause()
    }

    fun skipPrevious() {
        connection.value?.seekToPrevious()
    }

    fun skipNext() {
        connection.value?.seekToNext()
    }

    fun seekTo(positionMs: Long) {
        val playerConnection = connection.value ?: return
        val player = playerConnection.player
        val target = positionMs.coerceAtLeast(0L)
        // Mirrors the player sheet's own seek: mid-crossfade the screen already shows the incoming
        // song while the session player still sits on the outgoing one, so the seek has to land
        // in the song on screen rather than in the one fading out.
        if (player.currentMediaItem?.mediaId != playerConnection.mediaMetadata.value?.id) {
            player.seekToNext()
        }
        player.seekTo(target)
    }

    fun toggleLike() {
        connection.value?.toggleLike()
    }

    fun setVolume(fraction: Float) {
        val minVolume = readMinVolume()
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(minVolume + 1)
        val safeFraction = fraction.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: return
        val volume = (minVolume + ((maxVolume - minVolume) * safeFraction).roundToInt()).coerceIn(minVolume, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
    }

    private fun readVolumeFraction(): Float {
        val minVolume = readMinVolume()
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(minVolume + 1)
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return ((volume - minVolume).toFloat() / (maxVolume - minVolume).toFloat()).coerceIn(0f, 1f)
    }

    private fun readMinVolume(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        } else {
            0
        }

    private data class TransportData(
        val playbackState: Int,
        val isPlaying: Boolean,
        val canSkipPrevious: Boolean,
        val canSkipNext: Boolean,
        val queueTitle: String?,
    )
}

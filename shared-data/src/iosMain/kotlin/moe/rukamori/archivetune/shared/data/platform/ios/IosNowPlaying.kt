@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import moe.rukamori.archivetune.shared.data.playback.NowPlayingArtworkKey
import moe.rukamori.archivetune.shared.data.playback.NowPlayingMetadata
import moe.rukamori.archivetune.shared.data.playback.NowPlayingProjection
import moe.rukamori.archivetune.shared.data.playback.nowPlayingProjection
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackClock
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.playback.SystemMonotonicClock
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import platform.MediaPlayer.*

/**
 * Main-queue system controls dispatch to the existing controller/queue owner, not a new playlist.
 * [metadata] is keyed by occurrence, so repeated media do not inherit another occurrence's labels.
 */
class IosNowPlaying(
    private val player: MusicPlayerController,
    private val metadata: (occurrenceId: String) -> ContentItem?,
    private val next: () -> Unit,
    private val previous: () -> Unit,
    metadataChanges: Flow<*>? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val center = MPNowPlayingInfoCenter.defaultCenter()
    private val remote = MPRemoteCommandCenter.sharedCommandCenter()
    private val registrations = mutableListOf<Pair<MPRemoteCommand, Any>>()
    private val commandErrors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val failures = commandErrors.asSharedFlow()
    private var closed = false
    private val info = mutableMapOf<Any?, Any>()
    private var projection: NowPlayingProjection? = null
    private var artworkKey: NowPlayingArtworkKey? = null
    private var artwork: MPMediaItemArtwork? = null
    private var artworkLoad: Job? = null
    private var artworkGeneration = 0L
    private val artworkCache = LinkedHashMap<NowPlayingArtworkKey, MPMediaItemArtwork>()

    init {
        scope.launch {
            bind(remote.playCommand) { player.play() }
            bind(remote.pauseCommand) { player.pause() }
            bind(remote.togglePlayPauseCommand) {
                if (player.snapshot.value?.playWhenReady == true) player.pause() else player.play()
            }
            bind(remote.stopCommand) { player.stop() }
            bind(remote.nextTrackCommand) { next() }
            bind(remote.previousTrackCommand) { previous() }
            bind(remote.changePlaybackPositionCommand, accepts = { event ->
                val position = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime
                position != null && position.isFinite() && position >= 0 &&
                    player.snapshot.value?.isSeekable == true
            }) { event ->
                player.seekTo(((event as MPChangePlaybackPositionCommandEvent).positionTime * 1_000).toLong())
            }
            remote.changePlaybackPositionCommand.enabled = false
            center.nowPlayingInfo = null
            combine(player.snapshot, metadataChanges ?: flowOf(Unit)) { snapshot, _ -> snapshot }.collect { snapshot ->
                publish(snapshot)
            }
        }
    }

    private fun publish(snapshot: PlaybackSnapshot?, artworkChanged: Boolean = false) {
        if (closed) return
        if (snapshot == null) {
            selectArtwork(null)
            if (projection != null) center.nowPlayingInfo = null
            projection = null
            info.clear()
            remote.changePlaybackPositionCommand.enabled = false
            return
        }
        val next = nowPlayingProjection(snapshot, metadata(snapshot.occurrenceId))
        val changes = next.changesFrom(projection)
        selectArtwork(next.metadata.artworkKey)
        projection = next
        if (changes.seekability) remote.changePlaybackPositionCommand.enabled = next.seekable
        if (changes.metadata) updateMetadata(next.metadata)
        if (changes.metadata || artworkChanged) setInfo(MPMediaItemPropertyArtwork, artwork)
        if (!changes.publishInfo && !artworkChanged) return
        // A late cover/metadata write must reanchor to NOW, not replay the elapsed value from when its request began.
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] =
            PlaybackClock.positionMs(snapshot, SystemMonotonicClock.nowNanos()) / 1_000.0
        info[MPNowPlayingInfoPropertyPlaybackRate] = next.transport.rate
        info[MPNowPlayingInfoPropertyDefaultPlaybackRate] = next.transport.defaultRate
        center.nowPlayingInfo = info.toMap()
    }

    private fun updateMetadata(value: NowPlayingMetadata) {
        info[MPNowPlayingInfoPropertyExternalContentIdentifier] = value.mediaId
        info[MPNowPlayingInfoPropertyMediaType] = MPNowPlayingInfoMediaTypeAudio.toLong()
        setInfo(MPMediaItemPropertyTitle, value.title)
        setInfo(MPMediaItemPropertyArtist, value.artist)
        setInfo(MPMediaItemPropertyAlbumTitle, value.album)
        setInfo(MPMediaItemPropertyPlaybackDuration, value.durationMs?.div(1_000.0))
    }

    private fun setInfo(key: Any?, value: Any?) {
        if (value == null) info.remove(key) else info[key] = value
    }

    private fun selectArtwork(key: NowPlayingArtworkKey?) {
        if (key == artworkKey) return
        artworkGeneration++
        artworkLoad?.cancel()
        artworkLoad = null
        artworkKey = key
        artwork = key?.let { artworkCache.remove(it)?.also { held -> artworkCache[it] = held } }
        if (key == null || artwork != null) return
        val generation = artworkGeneration
        artworkLoad = scope.launch {
            val image = loadIosNowPlayingArtwork(key.uri) ?: return@launch
            ensureActive()
            if (closed || generation != artworkGeneration || key != artworkKey) return@launch
            val loaded = MPMediaItemArtwork(boundsSize = image.size, requestHandler = { image })
            artworkCache[key] = loaded
            while (artworkCache.size > ARTWORK_CACHE_ENTRIES) artworkCache.remove(artworkCache.keys.first())
            artwork = loaded
            publish(player.snapshot.value, artworkChanged = true)
        }
    }

    private fun bind(
        command: MPRemoteCommand,
        accepts: (MPRemoteCommandEvent) -> Boolean = { true },
        action: (MPRemoteCommandEvent) -> Unit,
    ) {
        command.enabled = true
        val token = command.addTargetWithHandler { event ->
            val expected = player.snapshot.value
            if (closed || event == null || expected == null) MPRemoteCommandHandlerStatusNoSuchContent
            else if (!accepts(event)) MPRemoteCommandHandlerStatusCommandFailed
            else {
                // Native callbacks are guarded; application failures never cross Objective-C.
                try {
                    // MPRemoteCommand callbacks can arrive off-main; actual command dispatch is confined.
                    scope.launch {
                        val current = player.snapshot.value
                        if (!closed && current != null && current.occurrenceId == expected.occurrenceId &&
                            current.playbackGeneration == expected.playbackGeneration) {
                            try { action(event) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { commandErrors.tryEmit("remote-command-failed") }
                        }
                    }
                    MPRemoteCommandHandlerStatusSuccess
                } catch (_: Exception) { MPRemoteCommandHandlerStatusCommandFailed }
            }
        }
        registrations += command to token
    }

    fun close() {
        scope.launch {
            if (closed) return@launch
            closed = true
            registrations.forEach { (command, token) -> command.removeTarget(token) }
            registrations.clear()
            artworkLoad?.cancel()
            artworkCache.clear()
            artwork = null
            info.clear()
            projection = null
            center.nowPlayingInfo = null
            scope.cancel()
        }
    }

    private companion object {
        const val ARTWORK_CACHE_ENTRIES = 4
    }
}

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
import platform.Foundation.NSNumber
import platform.MediaPlayer.*

/** Next/previous enablement for the system transport, from the queue successors the on-screen controls compute. */
data class IosTransportAvailability(val canPrevious: Boolean, val canNext: Boolean)

/**
 * Main-queue system controls dispatch to the existing controller/queue owner, not a new playlist.
 * [metadata] is keyed by occurrence, so repeated media do not inherit another occurrence's labels.
 *
 * [commandAvailability] drives the enabled flag of the next/previous remote commands from the queue's successors,
 * the way the Android media session advertises its transport actions. A host that supplies none keeps the commands
 * always enabled, as before; a host that supplies one sees them disabled while the queue reports no successor, and a
 * null snapshot disables them either way because there is nothing to skip from.
 */
class IosNowPlaying(
    private val player: MusicPlayerController,
    private val metadata: (occurrenceId: String) -> ContentItem?,
    private val next: () -> Unit,
    private val previous: () -> Unit,
    metadataChanges: Flow<*>? = null,
    commandAvailability: Flow<IosTransportAvailability>? = null,
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
    private var transportAvailability: IosTransportAvailability? = null
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
            // The system surfaces the same step the on-screen seek buttons use; no stored interval preference exists.
            bind(remote.skipForwardCommand, accepts = ::skippableIntervalEvent) { event ->
                skipBy((event as MPSkipIntervalCommandEvent).interval)
            }
            bind(remote.skipBackwardCommand, accepts = ::skippableIntervalEvent) { event ->
                skipBy(-(event as MPSkipIntervalCommandEvent).interval)
            }
            remote.skipForwardCommand.preferredIntervals = listOf(NSNumber(SKIP_INTERVAL_SECONDS))
            remote.skipBackwardCommand.preferredIntervals = listOf(NSNumber(SKIP_INTERVAL_SECONDS))
            remote.changePlaybackPositionCommand.enabled = false
            remote.skipForwardCommand.enabled = false
            remote.skipBackwardCommand.enabled = false
            center.nowPlayingInfo = null
            combine(
                player.snapshot,
                metadataChanges ?: flowOf(Unit),
                commandAvailability ?: flowOf(null),
            ) { snapshot, _, availability -> snapshot to availability }.collect { (snapshot, availability) ->
                transportAvailability = availability
                publish(snapshot)
            }
        }
    }

    private fun publish(snapshot: PlaybackSnapshot?, artworkChanged: Boolean = false) {
        if (closed) return
        // With no availability source the commands keep the enabled state `bind` gave them, as before.
        transportAvailability?.let { availability ->
            remote.nextTrackCommand.enabled = availability.canNext
            remote.previousTrackCommand.enabled = availability.canPrevious
        }
        if (snapshot == null) {
            selectArtwork(null)
            if (projection != null) center.nowPlayingInfo = null
            projection = null
            info.clear()
            remote.changePlaybackPositionCommand.enabled = false
            remote.skipForwardCommand.enabled = false
            remote.skipBackwardCommand.enabled = false
            return
        }
        val next = nowPlayingProjection(snapshot, metadata(snapshot.occurrenceId))
        val changes = next.changesFrom(projection)
        selectArtwork(next.metadata.artworkKey)
        projection = next
        if (changes.seekability) {
            remote.changePlaybackPositionCommand.enabled = next.seekable
            remote.skipForwardCommand.enabled = next.seekable
            remote.skipBackwardCommand.enabled = next.seekable
        }
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

    /** Interval commands carry a user-chosen step; a non-finite or non-positive one is refused like a bad seek. */
    private fun skippableIntervalEvent(event: MPRemoteCommandEvent): Boolean =
        (event as? MPSkipIntervalCommandEvent)?.interval?.let { it.isFinite() && it > 0.0 } == true &&
            player.snapshot.value?.isSeekable == true

    private fun skipBy(seconds: Double) {
        val snapshot = player.snapshot.value ?: return
        val position = PlaybackClock.positionMs(snapshot, SystemMonotonicClock.nowNanos()).coerceAtLeast(0L)
        val duration = snapshot.durationMs?.coerceAtLeast(0L) ?: Long.MAX_VALUE
        val target = (position + (seconds * 1_000).toLong()).coerceIn(0L, duration)
        player.seekTo(target)
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

        /** The interval the skip commands advertise, matching the on-screen seek step. */
        const val SKIP_INTERVAL_SECONDS = 5.0
    }
}
